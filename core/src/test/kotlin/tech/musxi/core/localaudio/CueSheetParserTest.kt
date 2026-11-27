package tech.musxi.core.localaudio

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CueSheetParserTest {
    @Test
    fun parsesOneTrackAndConvertsFrames() {
        val sheet = parse(
            """
            REM generated for tests
            TITLE "Album"
            PERFORMER "Artist"
            FILE "disc.flac" FLAC
              TRACK 01 AUDIO
                TITLE "Song"
                PERFORMER "Singer"
                INDEX 01 01:02:03
            """.trimIndent()
        )

        assertValid(sheet)
        assertEquals("UTF-8", sheet.encoding)
        assertEquals("Album", sheet.title)
        assertEquals("Artist", sheet.performer)
        assertEquals(listOf(CueFile(0, "disc.flac", "FLAC")), sheet.files)
        assertEquals(
            listOf(CueTrack(1, 0, "Song", "Singer", 4_653L, 5)),
            sheet.tracks
        )
    }

    @Test
    fun parsesTenAndTwentyFourTracksDynamically() {
        for (count in listOf(10, 24)) {
            val text = buildString {
                appendLine("TITLE \"Album $count\"")
                appendLine("PERFORMER \"Artist\"")
                appendLine("FILE \"album.flac\" FLAC")
                for (track in 1..count) {
                    appendLine("TRACK ${track.toString().padStart(2, '0')} AUDIO")
                    appendLine("TITLE \"Track $track\"")
                    appendLine("INDEX 01 ${track - 1}:00:00")
                }
            }

            val sheet = parse(text)
            assertValid(sheet)
            assertEquals(count, sheet.tracks.size)
            assertEquals((1..count).toList(), sheet.tracks.map { it.number })
            assertEquals((0 until count).map { it * 4_500L }, sheet.tracks.map { it.startFrame })
        }
    }

    @Test
    fun keepsAlbumAndTrackMetadataAsInputWithoutInheritance() {
        val sheet = parse(
            """
            TITLE "Album"
            PERFORMER "Album Artist"
            FILE "album.flac" FLAC
            TRACK 01 AUDIO
              TITLE "First"
              PERFORMER "Guest"
              INDEX 01 00:00:00
            TRACK 02 AUDIO
              INDEX 01 01:00:00
            """.trimIndent()
        )

        assertValid(sheet)
        assertEquals("Album", sheet.title)
        assertEquals("Album Artist", sheet.performer)
        assertEquals("First", sheet.tracks[0].title)
        assertEquals("Guest", sheet.tracks[0].performer)
        assertNull(sheet.tracks[1].title)
        assertNull(sheet.tracks[1].performer)
    }

    @Test
    fun decodesGb18030ChineseAndTypographicApostrophes() {
        val text = """
            TITLE "中文专辑"
            PERFORMER "歌手"
            FILE "中文歌曲.flac" FLAC
            TRACK 01 AUDIO
              TITLE "Don't Stop ’Til Tomorrow"
              PERFORMER "主唱"
              INDEX 01 00:00:00
        """.trimIndent()
        val sheet = parse(text.toByteArray(Charset.forName("GB18030")))

        assertValid(sheet)
        assertEquals("GB18030", sheet.encoding)
        assertEquals("中文专辑", sheet.title)
        assertEquals("歌手", sheet.performer)
        assertEquals("Don't Stop ’Til Tomorrow", sheet.tracks.single().title)
    }

    @Test
    fun acceptsUtf8AndUtf16BomEncodings() {
        val text = validSingleTrack(title = "标题")
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            text.toByteArray(Charsets.UTF_8)
        val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            text.toByteArray(Charsets.UTF_16LE)
        val utf16BeBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            text.toByteArray(Charsets.UTF_16BE)

        assertEquals("标题", parse(utf8Bom).title)
        assertEquals("UTF-8", parse(utf8Bom).encoding)
        assertEquals("标题", parse(utf16LeBom).title)
        assertEquals("UTF-16LE", parse(utf16LeBom).encoding)
        assertEquals("标题", parse(utf16BeBom).title)
        assertEquals("UTF-16BE", parse(utf16BeBom).encoding)
        assertValid(parse(utf8Bom))
        assertValid(parse(utf16LeBom))
        assertValid(parse(utf16BeBom))
    }

    @Test
    fun acceptsFlacAndWaveFileTypes() {
        val wave = parse(
            """
            FILE "disc.flac" WAVE
            TRACK 01 AUDIO
              INDEX 01 00:00:00
            """.trimIndent()
        )
        val flac = parse(
            """
            FILE "disc.FLAC" flac
            TRACK 01 AUDIO
              INDEX 01 00:00:00
            """.trimIndent()
        )

        assertValid(wave)
        assertValid(flac)
        assertEquals("WAVE", wave.files.single().type)
        assertEquals("FLAC", flac.files.single().type)
    }

    @Test
    fun rejectsBadAndOverflowingFrames() {
        for (timestamp in listOf(
            "00:60:00",
            "00:00:75",
            ":00:00",
            "00:0:00",
            "00:00",
            "00:00:00:00",
            "-1:00:00",
            "999999999999999999999999999999:00:00",
            "922337203685477580:00:00",
            "18446744073709551616:00:00",
        )) {
            assertIssue(parse(singleTrackWithIndex(timestamp)), CueIssueCode.INVALID_INDEX)
        }
    }

    @Test
    fun acceptsLargeButNonOverflowingFramesAndNonZeroFirstStart() {
        val sheet = parse(
            """
            FILE "disc.flac" FLAC
            TRACK 01 AUDIO
              INDEX 01 1000:00:00
            TRACK 02 AUDIO
              INDEX 01 1000:00:01
            """.trimIndent()
        )

        assertValid(sheet)
        assertEquals(listOf(4_500_000L, 4_500_001L), sheet.tracks.map { it.startFrame })
    }

    @Test
    fun rejectsMissingDuplicateAndNonIncreasingIndexes() {
        val firstTrackMetadata = parse(
            """
            TITLE "Album"
            PERFORMER "Album Artist"
            FILE "disc.flac" FLAC
            TRACK 01 AUDIO
              TITLE "First"
              PERFORMER "Guest"
              INDEX 01 00:00:00
            TRACK 02 AUDIO
              INDEX 01 00:01:00
            """.trimIndent()
        )
        assertValid(firstTrackMetadata)
        assertEquals("Album", firstTrackMetadata.title)
        assertEquals("First", firstTrackMetadata.tracks[0].title)
        assertEquals("Guest", firstTrackMetadata.tracks[0].performer)
        assertNull(firstTrackMetadata.tracks[1].title)
        assertNull(firstTrackMetadata.tracks[1].performer)

        assertIssue(
            parse(
                """
                FILE "disc.flac" FLAC
                TRACK 01 AUDIO
                  TITLE "No index"
                """.trimIndent()
            ),
            CueIssueCode.MISSING_INDEX
        )
        assertIssue(
            parse(
                """
                FILE "disc.flac" FLAC
                TRACK 01 AUDIO
                  INDEX 01 00:00:01
                  INDEX 01 00:00:02
                """.trimIndent()
            ),
            CueIssueCode.INVALID_INDEX
        )
        assertIssue(
            parse(
                """
                FILE "disc.flac" FLAC
                TRACK 01 AUDIO
                  INDEX 01 00:00:02
                TRACK 02 AUDIO
                  INDEX 01 00:00:02
                """.trimIndent()
            ),
            CueIssueCode.INVALID_INDEX
        )
        assertIssue(
            parse(
                """
                FILE "disc.flac" FLAC
                TRACK 01 AUDIO
                  INDEX 02 00:00:00
                  INDEX 01 00:00:01
                """.trimIndent()
            ),
            CueIssueCode.UNSUPPORTED_GAP
        )
        assertIssue(
            parse(
                """
                FILE "disc.flac" FLAC
                TRACK 01 AUDIO
                  INDEX 00 00:00:00
                  INDEX 01 00:00:01
                """.trimIndent()
            ),
            CueIssueCode.UNSUPPORTED_GAP
        )
        assertValid(
            parse(
                """
                FILE "disc.flac" FLAC
                TRACK 01 AUDIO
                  PREGAP 00:00:00
                  INDEX 01 00:00:00
                """.trimIndent()
            )
        )
    }

    @Test
    fun recordsMultipleFilesButRejectsTheSheet() {
        val sheet = parse(
            """
            FILE "one.flac" FLAC
            TRACK 01 AUDIO
              INDEX 01 00:00:00
            FILE "two.flac" FLAC
            TRACK 02 AUDIO
              INDEX 01 00:00:00
            """.trimIndent()
        )

        assertIssue(sheet, CueIssueCode.UNSUPPORTED_MULTI_FILE)
        assertEquals(listOf(0, 1), sheet.files.map { it.index })
        assertEquals(listOf(0, 1), sheet.tracks.map { it.fileIndex })
    }

    @Test
    fun rejectsDataTracksAndInvalidTrackOrder() {
        assertIssue(
            parse(
                """
                FILE "disc.bin" BINARY
                TRACK 01 MODE1/2352
                  INDEX 01 00:00:00
                """.trimIndent()
            ),
            CueIssueCode.UNSUPPORTED_TRACK
        )
        assertIssue(
            parse(
                """
                FILE "disc.flac" FLAC
                TRACK 02 AUDIO
                  INDEX 01 00:00:00
                TRACK 01 AUDIO
                  INDEX 01 00:01:00
                """.trimIndent()
            ),
            CueIssueCode.INVALID_ORDER
        )
        assertIssue(
            parse(
                """
                FILE "disc.flac" FLAC
                TRACK 00 AUDIO
                  INDEX 01 00:00:00
                """.trimIndent()
            ),
            CueIssueCode.INVALID_ORDER
        )
        assertIssue(
            parse(
                """
                FILE "disc.flac" FLAC
                TRACK 100 AUDIO
                  INDEX 01 00:00:00
                """.trimIndent()
            ),
            CueIssueCode.INVALID_ORDER
        )
    }

    @Test
    fun rejectsUnsafeReferencesAndUnsupportedFiles() {
        for (reference in listOf(
            "../outside.flac",
            "folder/inside.flac",
            "folder\\inside.flac",
            "/absolute.flac",
            "\\absolute.flac",
            "C:disc.flac",
            "https://example.invalid/disc.flac",
            "content://media/disc.flac",
            ".",
            "..",
        )) {
            assertIssue(
                parse(
                    """
                    FILE "$reference" FLAC
                    TRACK 01 AUDIO
                      INDEX 01 00:00:00
                    """.trimIndent()
                ),
                CueIssueCode.UNSAFE_REFERENCE
            )
        }

        for (line in listOf(
            "FILE \"disc.mp3\" MP3",
            "FILE \"disc.wav\" FLAC",
            "FILE \"disc.bin\" BINARY",
        )) {
            assertIssue(
                parse(
                    """
                    $line
                    TRACK 01 AUDIO
                      INDEX 01 00:00:00
                    """.trimIndent()
                ),
                CueIssueCode.UNSUPPORTED_FILE
            )
        }
        assertIssue(
            parse(
                """
                FILE "disc.flac"
                TRACK 01 AUDIO
                  INDEX 01 00:00:00
                """.trimIndent()
            ),
            CueIssueCode.INVALID_SYNTAX
        )

        assertValid(
            parse(
                """
                FILE "a..b.flac" FLAC
                TRACK 01 AUDIO
                  INDEX 01 00:00:00
                """.trimIndent()
            )
        )
    }

    @Test
    fun rejectsUnsupportedSemanticsInsteadOfIgnoringThem() {
        assertIssue(
            parse(
                """
                FILE "disc.flac" FLAC
                TRACK 01 AUDIO
                  FLAGS PRE
                  INDEX 01 00:00:00
                """.trimIndent()
            ),
            CueIssueCode.UNSUPPORTED_TRACK
        )
        assertIssue(
            parse(
                """
                FILE "disc.flac" FLAC
                TRACK 01 AUDIO
                  PREGAP 00:00:02
                  INDEX 01 00:00:00
                """.trimIndent()
            ),
            CueIssueCode.UNSUPPORTED_GAP
        )
        assertIssue(
            parse(
                """
                FILE "disc.flac" FLAC
                TRACK 01 AUDIO
                  POSTGAP 00:00:02
                  INDEX 01 00:00:00
                """.trimIndent()
            ),
            CueIssueCode.UNSUPPORTED_GAP
        )
        assertIssue(
            parse(
                """
                CATALOG 1234567890123
                FILE "disc.flac" FLAC
                TRACK 01 AUDIO
                  INDEX 01 00:00:00
                """.trimIndent()
            ),
            CueIssueCode.UNSUPPORTED_DIRECTIVE
        )
        assertIssue(
            parse(
                """
                CDTEXTFILE "disc.cdt"
                FILE "disc.flac" FLAC
                TRACK 01 AUDIO
                  INDEX 01 00:00:00
                """.trimIndent()
            ),
            CueIssueCode.UNSUPPORTED_DIRECTIVE
        )
        assertIssue(
            parse(
                """
                FILE "disc.flac" FLAC
                TRACK 01 AUDIO
                  ISRC USABC1234567
                  INDEX 01 00:00:00
                """.trimIndent()
            ),
            CueIssueCode.UNSUPPORTED_TRACK
        )
        assertIssue(
            parse(
                """
                FILE "disc.flac" FLAC
                TRACK 01 AUDIO
                  INDEX 01 00:00:00
                BOGUS value
                """.trimIndent()
            ),
            CueIssueCode.UNSUPPORTED_DIRECTIVE
        )
        assertValid(parse("REM this metadata is ignored\n" + validSingleTrack()))
    }

    @Test
    fun rejectsEmptyAndDuplicateMetadata() {
        for (line in listOf("TITLE", "TITLE \"\"", "PERFORMER", "PERFORMER \"\"")) {
            assertIssue(parse("$line\n" + validSingleTrack()), CueIssueCode.INVALID_SYNTAX)
        }
        assertIssue(
            parse(
                """
                TITLE "One"
                TITLE "Two"
                FILE "disc.flac" FLAC
                TRACK 01 AUDIO
                  INDEX 01 00:00:00
                """.trimIndent()
            ),
            CueIssueCode.INVALID_SYNTAX
        )
        assertIssue(
            parse(
                """
                FILE "disc.flac" FLAC
                TRACK 01 AUDIO
                  TITLE "One"
                  TITLE "Two"
                  INDEX 01 00:00:00
                """.trimIndent()
            ),
            CueIssueCode.INVALID_SYNTAX
        )
    }

    @Test
    fun rejectsMissingAudioAndMalformedStructure() {
        assertIssue(parse("REM no tracks"), CueIssueCode.NO_AUDIO_TRACKS)
        assertIssue(
            parse(
                """
                FILE "disc.flac" FLAC
                TRACK 01 AUDIO
                  INDEX 01 00:00:00
                TRACK
                """.trimIndent()
            ),
            CueIssueCode.INVALID_SYNTAX
        )
        assertIssue(
            parse(
                """
                TRACK 01 AUDIO
                  INDEX 01 00:00:00
                """.trimIndent()
            ),
            CueIssueCode.INVALID_ORDER
        )
        val fileWithoutTrack = parse("FILE \"disc.flac\" FLAC")
        assertIssue(fileWithoutTrack, CueIssueCode.NO_AUDIO_TRACKS)
        assertEquals(1, fileWithoutTrack.files.size)
    }

    @Test
    fun rejectsBadEncodingsWithoutReplacementCharacters() {
        val badUtf8 = byteArrayOf(0xC3.toByte(), 0x28.toByte())
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0xC3.toByte(), 0x28.toByte())
        val truncatedUtf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41.toByte())
        val onlyBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val control = "FILE \"disc.flac\" FLAC\u0000\n".toByteArray(Charsets.UTF_8)

        for (bytes in listOf(badUtf8, utf8Bom, truncatedUtf16, onlyBom, control, ByteArray(0))) {
            assertTrue(CueSheetParser.parse(bytes).issues.isNotEmpty())
        }
        assertIssue(CueSheetParser.parse(badUtf8), CueIssueCode.INVALID_ENCODING)
        assertIssue(CueSheetParser.parse(utf8Bom), CueIssueCode.INVALID_ENCODING)
        assertIssue(CueSheetParser.parse(truncatedUtf16), CueIssueCode.INVALID_ENCODING)
        assertIssue(CueSheetParser.parse(onlyBom), CueIssueCode.INVALID_ENCODING)
        assertIssue(CueSheetParser.parse(control), CueIssueCode.INVALID_ENCODING)
        assertIssue(CueSheetParser.parse(ByteArray(0)), CueIssueCode.INVALID_SYNTAX)
    }

    @Test
    fun enforcesByteLineAndCountLimits() {
        assertIssue(
            CueSheetParser.parse(ByteArray(CueSheetParser.MAX_BYTES + 1)),
            CueIssueCode.TOO_LARGE
        )
        assertIssue(
            parse(List(4_097) { "REM line" }.joinToString("\n")),
            CueIssueCode.LIMIT_REACHED
        )
        assertIssue(
            parse("REM " + "x".repeat(2_046)),
            CueIssueCode.LIMIT_REACHED
        )
        assertIssue(
            parse("REM " + "x".repeat(2_045)),
            CueIssueCode.NO_AUDIO_TRACKS
        )

        val hundredFiles = buildString {
            for (index in 0 until 100) appendLine("FILE \"disc$index.flac\" FLAC")
        }
        assertIssue(parse(hundredFiles), CueIssueCode.LIMIT_REACHED)

        val hundredTracks = buildString {
            appendLine("FILE \"disc.flac\" FLAC")
            for (track in 1..100) {
                appendLine("TRACK ${track.toString().padStart(2, '0')} AUDIO")
                appendLine("INDEX 01 00:00:00")
            }
        }
        assertIssue(parse(hundredTracks), CueIssueCode.INVALID_ORDER)
    }

    @Test
    fun acceptsMaxFileAndTrackCountsWithinLineLimits() {
        val ninetyNineTracks = buildString {
            appendLine("FILE \"disc.flac\" FLAC")
            for (track in 1..99) {
                appendLine("TRACK ${track.toString().padStart(2, '0')} AUDIO")
                appendLine("INDEX 01 ${track - 1}:00:00")
            }
        }
        val trackSheet = parse(ninetyNineTracks)
        assertEquals(99, trackSheet.tracks.size)
        assertValid(trackSheet)

        val ninetyNineFiles = buildString {
            for (index in 0 until 99) {
                appendLine("FILE \"disc$index.flac\" FLAC")
                appendLine("TRACK ${index + 1} AUDIO")
                appendLine("INDEX 01 00:00:00")
            }
        }
        val fileSheet = parse(ninetyNineFiles)
        assertEquals(99, fileSheet.files.size)
        assertEquals(99, fileSheet.tracks.size)
        assertIssue(fileSheet, CueIssueCode.UNSUPPORTED_MULTI_FILE)
    }

    @Test
    fun returnsDefensiveUnmodifiableListsAndSafeToString() {
        val sheet = parse(
            """
            FILE "private-disc-name.flac" FLAC
            TRACK 01 AUDIO
              INDEX 01 00:00:00
            """.trimIndent()
        )
        assertValid(sheet)

        assertUnmodifiable { @Suppress("UNCHECKED_CAST") (sheet.files as MutableList<CueFile>).clear() }
        assertUnmodifiable { @Suppress("UNCHECKED_CAST") (sheet.tracks as MutableList<CueTrack>).clear() }
        assertUnmodifiable { @Suppress("UNCHECKED_CAST") (sheet.issues as MutableList<CueIssue>).add(CueIssue(CueIssueCode.INVALID_SYNTAX)) }
        assertFalse(sheet.toString().contains("private-disc-name"))
        assertEquals(1, sheet.files.size)
        assertEquals(1, sheet.tracks.size)
        assertTrue(sheet.issues.isEmpty())
    }

    @Test
    fun copiesCallerListsAtTheModelBoundary() {
        val files = mutableListOf(CueFile(0, "disc.flac", "FLAC"))
        val tracks = mutableListOf(CueTrack(1, 0, "Song", null, 0L, 2))
        val issues = mutableListOf<CueIssue>()
        val sheet = CueSheet(null, null, "UTF-8", files, tracks, issues)

        files.clear()
        tracks.clear()
        issues.add(CueIssue(CueIssueCode.INVALID_SYNTAX))

        assertEquals(1, sheet.files.size)
        assertEquals(1, sheet.tracks.size)
        assertTrue(sheet.issues.isEmpty())
        assertUnmodifiable { @Suppress("UNCHECKED_CAST") (sheet.files as MutableList<CueFile>).clear() }
    }

    @Test
    fun handlesCrLfAndAcceptsCrOnlyNewlines() {
        val crlf = validSingleTrack().replace("\n", "\r\n")
        val sheet = CueSheetParser.parse(crlf.toByteArray(Charsets.UTF_8))

        assertValid(sheet)
        assertEquals(1, sheet.tracks.size)
        assertEquals(1, sheet.files.size)

        val crOnly = CueSheetParser.parse(validSingleTrack().replace("\n", "\r").toByteArray(Charsets.UTF_8))
        assertValid(crOnly)
        assertEquals(1, crOnly.tracks.size)
    }

    @Test
    fun stripsQuotesFromTitlesAndReferences() {
        val sheet = parse(
            """
            TITLE "Album"
            PERFORMER 'Artist'
            FILE 'disc.flac' FLAC
            TRACK 01 AUDIO
              TITLE "Song"
              PERFORMER 'Singer'
              INDEX 01 00:00:00
            """.trimIndent()
        )

        assertValid(sheet)
        assertEquals("Album", sheet.title)
        assertEquals("Artist", sheet.performer)
        assertEquals("disc.flac", sheet.files.single().reference)
        assertEquals("Song", sheet.tracks.single().title)
        assertEquals("Singer", sheet.tracks.single().performer)
    }

    private fun parse(text: String): CueSheet =
        CueSheetParser.parse(text.toByteArray(Charsets.UTF_8))

    private fun parse(bytes: ByteArray): CueSheet = CueSheetParser.parse(bytes)

    private fun validSingleTrack(title: String = "Song"): String =
        """
        TITLE "$title"
        PERFORMER "Artist"
        FILE "disc.flac" FLAC
        TRACK 01 AUDIO
          INDEX 01 00:00:00
        """.trimIndent()

    private fun singleTrackWithIndex(timestamp: String): String =
        """
        FILE "disc.flac" FLAC
        TRACK 01 AUDIO
          INDEX 01 $timestamp
        """.trimIndent()

    private fun assertValid(sheet: CueSheet) {
        assertTrue("Expected no issues, got ${sheet.issues}", sheet.issues.isEmpty())
    }

    private fun assertIssue(sheet: CueSheet, code: CueIssueCode) {
        assertTrue("Expected $code, got ${sheet.issues}", sheet.issues.any { it.code == code })
    }

    private fun assertUnmodifiable(action: () -> Unit) {
        try {
            action()
            fail("Expected an unmodifiable list")
        } catch (_: UnsupportedOperationException) {
            Unit
        }
    }
}
