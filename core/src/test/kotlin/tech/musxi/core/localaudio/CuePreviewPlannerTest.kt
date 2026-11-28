package tech.musxi.core.localaudio

import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CuePreviewPlannerTest {
    @Test fun rejectsExtremeFramesWithoutOverflowAndSupportsLargeSampleCounts() {
        val large = CueAudioCandidate(0, "album.flac", 1, 0xFFFFFFFFFL, 2, 16)
        val valid = CuePreviewPlanner.plan(customSheet(listOf(0L)), listOf(large))
        assertTrue(valid.canProceed)
        assertEquals(0xFFFFFFFFFL * 1_000_000, valid.tracks.single().durationUs)
        val invalid = CuePreviewPlanner.plan(customSheet(listOf(Long.MAX_VALUE)), listOf(large))
        assertFalse(invalid.canProceed)
        assertNull(invalid.tracks.single().durationUs)
        assertNull(large.copy(totalSamples = Long.MAX_VALUE).durationUs)
    }

    @Test
    fun plansEveryParsedTrackDynamically() {
        val sheet = parse(
            buildString {
                appendLine("TITLE \"Album\"")
                appendLine("FILE \"album.flac\" WAVE")
                repeat(10) { offset ->
                    appendLine("TRACK ${(offset + 1).toString().padStart(2, '0')} AUDIO")
                    appendLine("INDEX 01 00:${(offset * 5).toString().padStart(2, '0')}:00")
                }
            }
        )

        val preview = CuePreviewPlanner.plan(sheet, listOf(audio("album.flac")))

        assertTrue(preview.canProceed)
        assertEquals((1..10).toList(), preview.tracks.map { it.number })
        assertEquals(10, preview.tracks.size)
    }

    @Test
    fun matchesExactNamesBeforeCaseInsensitiveAndAcceptsUniqueCaseVariant() {
        val sheet = parse(cue(file = "Album.flac", tracks = 2))
        val exact = CuePreviewPlanner.plan(
            sheet,
            listOf(audio("album.flac", index = 0), audio("Album.flac", index = 1)),
        )
        val caseVariant = CuePreviewPlanner.plan(
            sheet,
            listOf(audio("aLbUm.FlAc")),
        )

        assertEquals(CueAssociationStatus.MATCHED, exact.files.single().status)
        assertEquals(1, exact.files.single().candidateIndex)
        assertTrue(exact.canProceed)
        assertEquals(CueAssociationStatus.MATCHED, caseVariant.files.single().status)
        assertTrue(caseVariant.canProceed)
    }

    @Test
    fun reportsAmbiguityForDuplicateCaseInsensitiveNames() {
        val preview = CuePreviewPlanner.plan(
            parse(cue(file = "album.flac")),
            listOf(audio("ALBUM.FLAC", index = 0), audio("album.FLAC", index = 1)),
        )

        assertEquals(CueAssociationStatus.AMBIGUOUS, preview.files.single().status)
        assertNull(preview.files.single().candidateIndex)
        assertIssue(preview, CuePreviewIssueCode.AMBIGUOUS_AUDIO)
        assertFalse(preview.canProceed)
    }

    @Test
    fun explicitBindingSelectsOneOfSeveralMatchingCandidates() {
        val preview = CuePreviewPlanner.plan(
            parse(cue(file = "album.flac")),
            listOf(audio("album.flac", index = 0), audio("album.flac", index = 1)),
            bindings = mapOf(0 to 1),
        )

        assertEquals(CueAssociationStatus.MANUAL, preview.files.single().status)
        assertEquals(1, preview.files.single().candidateIndex)
        assertTrue(preview.canProceed)
    }

    @Test
    fun explicitBindingOverridesNameMatching() {
        val preview = CuePreviewPlanner.plan(
            parse(cue(file = "album.flac")),
            listOf(audio("album.flac", index = 0), audio("other.flac", index = 1)),
            bindings = mapOf(0 to 1),
        )

        assertEquals(CueAssociationStatus.MANUAL, preview.files.single().status)
        assertEquals(1, preview.files.single().candidateIndex)
        assertTrue(preview.canProceed)
    }

    @Test
    fun doesNotGuessSimilarFileNames() {
        val preview = CuePreviewPlanner.plan(
            parse(cue(file = "album.flac")),
            listOf(audio("album-copy.flac"), audio("album (1).flac", index = 1)),
        )

        assertEquals(CueAssociationStatus.MISSING, preview.files.single().status)
        assertNull(preview.files.single().candidateIndex)
        assertIssue(preview, CuePreviewIssueCode.MISSING_AUDIO)
        assertFalse(preview.canProceed)
    }

    @Test
    fun reportsMissingAndCandidateError() {
        val missing = CuePreviewPlanner.plan(
            parse(cue(file = "album.flac")),
            listOf(audio("other.flac")),
        )
        val failed = CuePreviewPlanner.plan(
            parse(cue(file = "album.flac")),
            listOf(audio("album.flac", error = CuePreviewError.INVALID_FLAC)),
        )

        assertEquals(CueAssociationStatus.MISSING, missing.files.single().status)
        assertIssue(missing, CuePreviewIssueCode.MISSING_AUDIO)
        assertEquals(CueAssociationStatus.INVALID_AUDIO, failed.files.single().status)
        assertIssue(failed, CuePreviewIssueCode.INVALID_AUDIO)
        assertNull(failed.tracks.single().durationUs)
        assertFalse(missing.canProceed)
        assertFalse(failed.canProceed)
    }

    @Test
    fun computesMiddleDurationsAtSeventyFiveFramesPerSecond() {
        val preview = CuePreviewPlanner.plan(
            parse(
                """
                FILE "album.flac" WAVE
                TRACK 01 AUDIO
                  INDEX 01 00:00:00
                TRACK 02 AUDIO
                  INDEX 01 00:00:01
                TRACK 03 AUDIO
                  INDEX 01 00:00:05
                """.trimIndent()
            ),
            listOf(audio("album.flac")),
        )

        assertEquals(listOf(13_333L, 53_333L, 119_933_333L), preview.tracks.map { it.durationUs })
        assertEquals(0L, preview.tracks[0].startFrame)
        assertEquals(1L, preview.tracks[0].endFrameExclusive)
    }

    @Test
    fun computesFinalDurationFromRemainingSamples() {
        val rate = 44_100
        val totalSamples = 44_100L * 120L
        val startFrame = 4_904L
        val preview = CuePreviewPlanner.plan(
            parse(cue(file = "album.flac", index = "01:05:29")),
            listOf(audio("album.flac", rate = rate, samples = totalSamples)),
        )

        assertEquals(
            (totalSamples * 75L - startFrame * rate) * 1_000_000L / (rate * 75L),
            preview.tracks.single().durationUs,
        )
    }

    @Test
    fun rejectsStartAtOrBeyondAudioEnd() {
        val rate = 44_100
        val totalSamples = 44_100L * 120L
        val atEnd = CuePreviewPlanner.plan(
            parse(cue(index = "02:00:00")),
            listOf(audio("album.flac", rate = rate, samples = totalSamples)),
        )
        val beyondEnd = CuePreviewPlanner.plan(
            parse(cue(index = "02:00:01")),
            listOf(audio("album.flac", rate = rate, samples = totalSamples)),
        )

        assertIndexOutOfRange(atEnd)
        assertIndexOutOfRange(beyondEnd)
        assertNull(atEnd.tracks.single().durationUs)
        assertNull(beyondEnd.tracks.single().durationUs)
    }

    @Test
    fun rejectsNegativeStartAndNextStartBeyondAudioEnd() {
        val negative = CuePreviewPlanner.plan(
            customSheet(startFrames = listOf(-1L)),
            listOf(audio("album.flac")),
        )
        val nextBeyond = CuePreviewPlanner.plan(
            customSheet(startFrames = listOf(0L, 9_001L)),
            listOf(audio("album.flac", samples = 44_100L * 120L)),
        )

        assertIndexOutOfRange(negative)
        assertIndexOutOfRange(nextBeyond)
        assertNull(nextBeyond.tracks[0].durationUs)
        assertNull(nextBeyond.tracks[1].durationUs)
    }

    @Test
    fun rejectsZeroDurationAndNonIncreasingNextStart() {
        val zero = CuePreviewPlanner.plan(
            customSheet(startFrames = listOf(10L, 10L)),
            listOf(audio("album.flac")),
        )
        val backwards = CuePreviewPlanner.plan(
            customSheet(startFrames = listOf(10L, 9L)),
            listOf(audio("album.flac")),
        )

        assertIndexOutOfRange(zero)
        assertIndexOutOfRange(backwards)
        assertNull(zero.tracks[0].durationUs)
        assertNull(backwards.tracks[0].durationUs)
    }

    @Test
    fun usesFallbackTitlesAndInheritsAlbumPerformer() {
        val preview = CuePreviewPlanner.plan(
            parse(
                """
                TITLE "Album"
                PERFORMER "Album Artist"
                FILE "album.flac" WAVE
                TRACK 01 AUDIO
                  INDEX 01 00:00:00
                TRACK 02 AUDIO
                  TITLE "Named"
                  INDEX 01 00:00:01
                """.trimIndent()
            ),
            listOf(audio("album.flac")),
        )

        assertEquals(listOf("曲目 1", "Named"), preview.tracks.map { it.title })
        assertEquals(listOf("Album Artist", "Album Artist"), preview.tracks.map { it.performer })
        assertEquals("Album", preview.title)
        assertEquals("Album Artist", preview.performer)
    }

    @Test
    fun exposesDetachedUnmodifiableCollectionsAndSafeLabels() {
        val preview = CuePreviewPlanner.plan(
            customSheet(
                startFrames = listOf(0L),
                reference = "../private/album.flac",
                title = "../private/album.flac",
            ),
            listOf(audio("../private/album.flac")),
        )

        assertUnmodifiable { @Suppress("UNCHECKED_CAST") (preview.candidates as MutableList<CueAudioCandidate>).clear() }
        assertUnmodifiable { @Suppress("UNCHECKED_CAST") (preview.files as MutableList<CueFileAssociation>).clear() }
        assertUnmodifiable { @Suppress("UNCHECKED_CAST") (preview.tracks as MutableList<CuePreviewTrack>).clear() }
        assertUnmodifiable { @Suppress("UNCHECKED_CAST") (preview.parseIssues as MutableList<CueIssue>).clear() }
        assertUnmodifiable { @Suppress("UNCHECKED_CAST") (preview.issues as MutableList<CuePreviewIssue>).clear() }
        assertEquals("album.flac", preview.files.single().name)
        assertEquals("album.flac", preview.candidates.single().name)
        assertEquals("../private/album.flac", preview.title)
        assertFalse(preview.toString().contains("private"))
    }

    @Test
    fun rejectsBadBindings() {
        val sheet = parse(cue(file = "album.flac"))
        val candidates = listOf(audio("album.flac", index = 0), audio("other.flac", index = 1))

        assertIllegalArgument { CuePreviewPlanner.plan(sheet, candidates, mapOf(1 to 0)) }
        assertIllegalArgument { CuePreviewPlanner.plan(sheet, candidates, mapOf(0 to 2)) }
    }

    @Test
    fun parseIssuesPreventProceeding() {
        val preview = CuePreviewPlanner.plan(
            parse("FILE \"album.flac\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\nBOGUS"),
            listOf(audio("album.flac")),
        )

        assertEquals(1, preview.tracks.size)
        assertTrue(preview.parseIssues.isNotEmpty())
        assertIssue(preview, CuePreviewIssueCode.PARSE_ERROR)
        assertFalse(preview.canProceed)
    }

    private fun parse(text: String): CueSheet = CueSheetParser.parse(text.toByteArray(Charsets.UTF_8))

    private fun cue(
        file: String = "album.flac",
        tracks: Int = 1,
        index: String = "00:00:00",
        title: String = "Album",
    ): String = buildString {
        appendLine("TITLE \"$title\"")
        appendLine("FILE \"$file\" WAVE")
        repeat(tracks) { offset ->
            appendLine("TRACK ${(offset + 1).toString().padStart(2, '0')} AUDIO")
            appendLine("INDEX 01 ${if (offset == 0) index else "${offset}:00:00"}")
        }
    }

    private fun customSheet(
        startFrames: List<Long>,
        reference: String = "album.flac",
        title: String? = "Album",
    ): CueSheet = CueSheet(
        title = title,
        performer = "Album Artist",
        encoding = "UTF-8",
        files = Collections.unmodifiableList(listOf(CueFile(0, reference, "FLAC"))),
        tracks = Collections.unmodifiableList(
            startFrames.mapIndexed { offset, startFrame ->
                CueTrack(offset + 1, 0, "Track ${offset + 1}", null, startFrame, offset + 2)
            }
        ),
        issues = Collections.emptyList(),
    )

    private fun audio(
        name: String,
        index: Int = 0,
        rate: Int = 44_100,
        samples: Long = 44_100L * 120L,
        error: CuePreviewError? = null,
    ) = CueAudioCandidate(
        index = index,
        name = name,
        sampleRate = rate,
        totalSamples = samples,
        channels = 2,
        bitsPerSample = 16,
        error = error,
    )

    private fun assertIssue(preview: CueImportPreview, code: CuePreviewIssueCode) {
        assertTrue("Expected $code, got ${preview.issues}", preview.issues.any { it.code == code })
    }

    private fun assertIndexOutOfRange(preview: CueImportPreview) {
        assertIssue(preview, CuePreviewIssueCode.INDEX_OUT_OF_RANGE)
        assertFalse(preview.canProceed)
    }

    private fun assertIllegalArgument(action: () -> Unit) {
        try {
            action()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            Unit
        }
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
