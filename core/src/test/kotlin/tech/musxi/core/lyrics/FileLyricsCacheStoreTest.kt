package tech.musxi.core.lyrics

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey

class FileLyricsCacheStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val ownerA = "a".repeat(64)
    private val ownerB = "b".repeat(64)
    private var now = 1_000_000L

    @Test
    fun coldReadRoundTripsLyricsTranslationAndBinding() {
        val directory = temporary.newFolder("cold")
        val key = mediaKey(1)
        val binding = LyricsCacheBinding(
            origin = MediaKey(Platform.QQ, "AbCdEf12345678"),
            offsetMs = -250,
            candidateKeys = listOf(MediaKey(Platform.KUGOU, "${"c".repeat(32)}_12_34")),
        )
        val lyrics = translatedLyrics()
        val store = FileLyricsCacheStore(directory, ::clock)
        store.write(ownerA, key, lyrics, binding)

        val restored = FileLyricsCacheStore(directory, ::clock).read(ownerA, key)

        assertNotNull(restored)
        assertEquals(lyrics, restored!!.lyrics)
        assertEquals(binding, restored.binding)
        assertFalse(restored.stale)
        assertEquals(now + LyricsCacheStore.LYRICS_TTL_MS, restored.expiresAtEpochMs)
    }

    @Test
    fun lyricAndNoLyricTtlBecomeStaleWithoutLosingFallback() {
        val directory = temporary.newFolder("ttl")
        val store = FileLyricsCacheStore(directory, ::clock)
        val lyricsKey = mediaKey(1)
        val noLyricsKey = mediaKey(2)
        store.write(ownerA, lyricsKey, translatedLyrics())

        now += LyricsCacheStore.LYRICS_TTL_MS - 1
        assertFalse(store.read(ownerA, lyricsKey)!!.stale)
        now += 1
        assertTrue(store.read(ownerA, lyricsKey)!!.stale)

        now += 10
        store.write(ownerA, noLyricsKey, ParsedLyrics(lines = emptyList(), plainText = "  \n"))
        now += LyricsCacheStore.NO_LYRICS_TTL_MS - 1
        assertFalse(store.read(ownerA, noLyricsKey)!!.stale)
        now += 1
        assertTrue(store.read(ownerA, noLyricsKey)!!.stale)
    }

    @Test
    fun ownerIsolationClearOwnerAndClearAll() {
        val directory = temporary.newFolder("owners")
        val key = mediaKey(1)
        val store = FileLyricsCacheStore(directory, ::clock)
        store.write(ownerA, key, parsedLyrics("owner A"))
        now++
        store.write(ownerB, key, parsedLyrics("owner B"))

        assertEquals("owner A", store.read(ownerA, key)!!.lyrics.lines.single().text)
        assertEquals("owner B", store.read(ownerB, key)!!.lyrics.lines.single().text)
        store.clearOwner(ownerA)
        assertNull(store.read(ownerA, key))
        assertEquals("owner B", store.read(ownerB, key)!!.lyrics.lines.single().text)
        store.clear()
        assertNull(store.read(ownerB, key))
    }

    @Test
    fun corruptFileIsIgnoredAndRemoved() {
        val directory = temporary.newFolder("corrupt")
        val key = mediaKey(1)
        val store = FileLyricsCacheStore(directory, ::clock)
        store.write(ownerA, key, parsedLyrics("valid"))
        val file = entryFiles(directory.toPath()).single()
        Files.write(file, byteArrayOf(1, 2, 3, 4, 5))

        assertNull(store.read(ownerA, key))
        assertFalse(Files.exists(file))
    }

    @Test
    fun wordTimingsRoundTripAndStayImmutableAcrossCopies() {
        val directory = temporary.newFolder("words")
        val key = mediaKey(1)
        val mutableWords = arrayListOf(
            LyricWord(0, 5, 1_000, 1_400),
            LyricWord(5, 6, 1_400, 1_600),
        )
        val expectedWords = mutableWords.toList()
        val lyrics = ParsedLyrics(
            lines = listOf(LyricLine(1_000, "Hello!", "translated", mutableWords)),
            plainText = "Hello!",
            translation = LyricsTranslation(
                TranslationAvailability.AVAILABLE,
                TranslationLookup(TranslationLookupStatus.SUCCEEDED),
            ),
        )
        val store = FileLyricsCacheStore(directory, ::clock)
        store.write(ownerA, key, lyrics)
        mutableWords.clear()

        val restored = FileLyricsCacheStore(directory, ::clock).read(ownerA, key)

        assertNotNull(restored)
        assertEquals(
            listOf(LyricLine(1_000, "Hello!", "translated", expectedWords)),
            restored!!.lyrics.lines,
        )
        assertEquals(expectedWords, restored.lyrics.lines.single().words)
        assertImmutable { mutateWords(restored.lyrics.lines.single().words) }
    }

    @Test
    fun schemaOneFileWithoutWordSectionStillLoadsLineLevelLyrics() {
        val directory = temporary.newFolder("schema-one")
        val key = mediaKey(1)
        val store = FileLyricsCacheStore(directory, ::clock)
        val lyrics = translatedLyrics()
        store.write(ownerA, key, lyrics)
        writeRawFile(directory, ownerA, key, lyrics.lines, lyrics.plainText, 1, lyrics.translation)

        val restored = store.read(ownerA, key)

        assertNotNull(restored)
        assertEquals(lyrics, restored!!.lyrics)
        assertTrue(restored.lyrics.lines.all { it.words.isEmpty() })
        assertEquals(0, restored.lyrics.timingRevision)
        assertTrue(Files.exists(entryFiles(directory.toPath()).single()))
    }

    @Test
    fun schemaTwoFileWithoutTimingRevisionReadsZero() {
        val directory = temporary.newFolder("schema-two-revision")
        val key = mediaKey(1)
        val store = FileLyricsCacheStore(directory, ::clock)
        val lines = listOf(
            LyricLine(
                1_000,
                "Hello!",
                "translated",
                listOf(LyricWord(0, 5, 1_000, 1_400), LyricWord(5, 6, 1_400, 1_600)),
            ),
        )
        val lyrics = ParsedLyrics(lines, "Hello!", translatedLyrics().translation)
        writeRawFile(directory, ownerA, key, lines, "Hello!", 2, lyrics.translation)

        val restored = store.read(ownerA, key)

        assertNotNull(restored)
        assertEquals(lyrics, restored!!.lyrics)
        assertEquals(lines, restored.lyrics.lines)
        assertEquals(0, restored.lyrics.timingRevision)
        assertTrue(Files.exists(entryFiles(directory.toPath()).single()))
    }

    @Test
    fun schemaThreeRoundTripsTimingRevisionWithWordsAndTranslation() {
        val directory = temporary.newFolder("schema-three-revision")
        val store = FileLyricsCacheStore(directory, ::clock)
        listOf(0, 1, 7, 1_000_000).forEachIndexed { index, revision ->
            val key = mediaKey(index + 1)
            val lyrics = translatedLyrics().copy(
                lines = listOf(
                    LyricLine(
                        1_000,
                        "Hello",
                        "translated",
                        listOf(LyricWord(0, 5, 1_000, 1_400)),
                    ),
                    LyricLine(2_000, "Bye.", "translated too"),
                ),
                plainText = "Hello\nBye.",
                timingRevision = revision,
            )
            store.write(ownerA, key, lyrics)

            val restored = FileLyricsCacheStore(directory, ::clock).read(ownerA, key)

            assertNotNull(restored)
            assertEquals(lyrics, restored!!.lyrics)
            assertEquals(revision, restored.lyrics.timingRevision)
            assertEquals(3, storedVersion(directory, ownerA, key))
        }
    }

    @Test
    fun writeRejectsOutOfRangeTimingRevisionBeforeStoring() {
        val directory = temporary.newFolder("bad-revision")
        val store = FileLyricsCacheStore(directory, ::clock)

        assertRejectedData {
            store.write(ownerA, mediaKey(1), parsedLyrics("bad").copy(timingRevision = -1))
        }
        assertRejectedData {
            store.write(ownerA, mediaKey(2), parsedLyrics("bad").copy(timingRevision = 1_000_001))
        }

        assertTrue(entryFiles(directory.toPath()).isEmpty())
    }

    @Test
    fun schemaThreeFileWithInvalidTimingRevisionIsRejectedAndRemoved() {
        listOf(-1, 1_000_001).forEachIndexed { index, revision ->
            val directory = temporary.newFolder("schema-three-bad-$index")
            val key = mediaKey(1)
            val store = FileLyricsCacheStore(directory, ::clock)
            store.write(ownerA, key, parsedLyrics("Hello"))
            writeRawFile(
                directory,
                ownerA,
                key,
                listOf(LyricLine(1_000, "Hello")),
                "Hello",
                3,
                timingRevision = revision,
            )

            assertNull(store.read(ownerA, key))
            assertTrue(entryFiles(directory.toPath()).isEmpty())
        }
    }

    @Test
    fun schemaTwoFileWithInvalidWordSpansIsRejectedAndRemoved() {
        val directory = temporary.newFolder("bad-spans")
        val key = mediaKey(1)
        val store = FileLyricsCacheStore(directory, ::clock)
        store.write(ownerA, key, parsedLyrics("Hello"))
        writeRawFile(
            directory,
            ownerA,
            key,
            listOf(LyricLine(1_000, "Hello", null, listOf(LyricWord(1, 5, 1_000, 1_200)))),
            "Hello",
            2,
        )

        assertNull(store.read(ownerA, key))
        assertTrue(entryFiles(directory.toPath()).isEmpty())
    }

    @Test
    fun schemaTwoFileWithExcessiveWordCountIsRejectedAndRemoved() {
        val directory = temporary.newFolder("too-many-words")
        val key = mediaKey(1)
        val store = FileLyricsCacheStore(directory, ::clock)
        store.write(ownerA, key, parsedLyrics("Hello"))
        writeRawFile(
            directory,
            ownerA,
            key,
            listOf(LyricLine(1_000, "Hello")),
            "Hello",
            2,
            wordCountOverride = LyricsWordTimeline.MAX_WORDS_PER_LINE + 1,
        )

        assertNull(store.read(ownerA, key))
        assertTrue(entryFiles(directory.toPath()).isEmpty())
    }

    @Test
    fun writeRejectsWordSpansThatBreakCoverageAndDocumentLimit() {
        val store = FileLyricsCacheStore(temporary.newFolder("word-limits"), ::clock)

        assertRejectedData {
            store.write(
                ownerA,
                mediaKey(1),
                ParsedLyrics(
                    lines = listOf(LyricLine(1_000, "Hello", null, listOf(LyricWord(0, 4, 1_000, 1_200)))),
                    plainText = "Hello",
                ),
            )
        }
        assertRejectedData {
            store.write(
                ownerA,
                mediaKey(2),
                ParsedLyrics(
                    lines = listOf(
                        LyricLine(
                            1_000,
                            "Hello",
                            null,
                            listOf(LyricWord(0, 5, 1_000, LyricsWordTimeline.MAX_TIME_MS + 1)),
                        )
                    ),
                    plainText = "Hello",
                ),
            )
        }

        val row = "x".repeat(LyricsWordTimeline.MAX_WORDS_PER_LINE)
        val fullLineWords = List(LyricsWordTimeline.MAX_WORDS_PER_LINE) { index ->
            LyricWord(index, index + 1, index.toLong(), index + 1L)
        }
        val overflowing = ParsedLyrics(
            lines = List(LyricsWordTimeline.MAX_WORDS_PER_DOCUMENT / LyricsWordTimeline.MAX_WORDS_PER_LINE + 1) {
                LyricLine(it.toLong(), row, null, fullLineWords)
            },
            plainText = row,
        )

        assertRejectedData { store.write(ownerA, mediaKey(3), overflowing) }
    }

    @Test
    fun replacementIsAtomicAndLeavesNoTemporaryFile() {
        val directory = temporary.newFolder("replace")
        val key = mediaKey(1)
        val store = FileLyricsCacheStore(directory, ::clock)
        store.write(ownerA, key, parsedLyrics("old"))
        now++
        store.write(ownerA, key, parsedLyrics("new"))

        assertEquals("new", store.read(ownerA, key)!!.lyrics.lines.single().text)
        assertTrue(ownedFiles(directory.toPath()).none { it.fileName.toString().endsWith(".tmp") })
    }

    @Test
    fun unsafeOwnerIsRejectedBeforeAnyPathIsUsed() {
        val directory = temporary.newFolder("unsafe-owner")
        val store = FileLyricsCacheStore(directory, ::clock)
        val key = mediaKey(1)

        assertIllegalArgument { store.read("../$ownerA", key) }
        assertIllegalArgument { store.write("$ownerA/..", key, parsedLyrics("bad")) }
        assertIllegalArgument { store.clearOwner("..\\$ownerA") }
        assertFalse(Files.exists(directory.toPath().parent.resolve(ownerA)))
    }

    @Test
    fun evictsOldestEntryAtEntryLimit() {
        val directory = temporary.newFolder("count-limit")
        val store = FileLyricsCacheStore(directory, ::clock)
        repeat(LyricsCacheStore.MAX_ENTRIES + 1) { index ->
            now++
            store.write(ownerA, mediaKey(index + 1), parsedLyrics("entry $index"))
        }

        assertNull(store.read(ownerA, mediaKey(1)))
        assertNotNull(store.read(ownerA, mediaKey(2)))
        assertNotNull(store.read(ownerA, mediaKey(LyricsCacheStore.MAX_ENTRIES + 1)))
        assertEquals(LyricsCacheStore.MAX_ENTRIES, entryFiles(directory.toPath()).size)
    }

    @Test
    fun evictsOldestEntryAtSixteenMebibyteLimit() {
        val directory = temporary.newFolder("byte-limit")
        val store = FileLyricsCacheStore(directory, ::clock)
        repeat(5) { index ->
            now++
            store.write(ownerA, mediaKey(index + 1), largeLyrics())
        }

        assertNull(store.read(ownerA, mediaKey(1)))
        assertNotNull(store.read(ownerA, mediaKey(2)))
        assertTrue(entryFiles(directory.toPath()).sumOf { Files.size(it) } <= LyricsCacheStore.MAX_BYTES)
    }

    private fun parsedLyrics(text: String) = ParsedLyrics(
        lines = listOf(LyricLine(1_000, text)),
        plainText = text,
    )

    private fun translatedLyrics() = ParsedLyrics(
        lines = listOf(
            LyricLine(1_000, "original one", "translated one"),
            LyricLine(2_000, "original two", "translated two"),
        ),
        plainText = "original one\noriginal two",
        translation = LyricsTranslation(
            TranslationAvailability.AVAILABLE,
            TranslationLookup(TranslationLookupStatus.SUCCEEDED),
        ),
    )

    private fun largeLyrics(): ParsedLyrics {
        val row = "x".repeat(32 * 1024)
        return ParsedLyrics(
            lines = List(112) { index -> LyricLine(index.toLong(), row) },
            plainText = row,
        )
    }

    private fun mediaKey(index: Int) = MediaKey(Platform.NETEASE, index.toString())

    private fun clock(): Long = now

    /**
     * Writes a file in the exact on-disk layout so older schema versions and hand-built payloads can
     * be exercised without a writer that would refuse them.
     */
    private fun writeRawFile(
        directory: File,
        owner: String,
        key: MediaKey,
        lines: List<LyricLine>,
        plainText: String,
        version: Int,
        translation: LyricsTranslation = LyricsTranslation(
            TranslationAvailability.NONE,
            TranslationLookup(TranslationLookupStatus.SUCCEEDED),
        ),
        wordCountOverride: Int? = null,
        timingRevision: Int = 0,
    ) {
        val payload = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(payload).use { output ->
            val ownerBytes = owner.toByteArray(Charsets.UTF_8)
            output.writeInt(ownerBytes.size)
            output.write(ownerBytes)
            output.writeByte(key.platform.ordinal)
            val idBytes = key.id.toByteArray(Charsets.UTF_8)
            output.writeInt(idBytes.size)
            output.write(idBytes)
            output.writeLong(now)
            output.writeLong(now)
            output.writeLong(
                if (lines.isEmpty() && plainText.isBlank()) {
                    LyricsCacheStore.NO_LYRICS_TTL_MS
                } else {
                    LyricsCacheStore.LYRICS_TTL_MS
                }
            )
            output.writeBoolean(false)
            output.writeInt(lines.size)
            lines.forEach { line ->
                output.writeLong(line.timeMs)
                val textBytes = line.text.toByteArray(Charsets.UTF_8)
                output.writeInt(textBytes.size)
                output.write(textBytes)
                output.writeBoolean(line.translation != null)
                line.translation?.let {
                    val bytes = it.toByteArray(Charsets.UTF_8)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                }
                if (version >= 2) {
                    output.writeInt(wordCountOverride ?: line.words.size)
                    if (wordCountOverride == null) {
                        line.words.forEach { word ->
                            output.writeInt(word.textStart)
                            output.writeInt(word.textEnd)
                            output.writeLong(word.startTimeMs)
                            output.writeLong(word.endTimeMs)
                        }
                    }
                }
            }
            val plainBytes = plainText.toByteArray(Charsets.UTF_8)
            output.writeInt(plainBytes.size)
            output.write(plainBytes)
            output.writeByte(translation.availability.ordinal)
            output.writeByte(translation.lookup.status.ordinal)
            output.writeBoolean(translation.lookup.reason != null)
            translation.lookup.reason?.let { output.writeByte(it.ordinal) }
            if (version >= 3) output.writeInt(timingRevision)
        }
        val body = payload.toByteArray()
        val header = java.io.ByteArrayOutputStream(16)
        java.io.DataOutputStream(header).use { output ->
            output.writeInt(0x4d584c31)
            output.writeInt(version)
            output.writeInt(body.size)
            output.writeInt(java.util.zip.CRC32().apply { update(body) }.value.toInt())
        }

        val ownerDirectory = File(directory, owner)
        ownerDirectory.mkdirs()
        File(ownerDirectory, entryFileName(key)).writeBytes(header.toByteArray() + body)
    }

    private fun entryFileName(key: MediaKey): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${key.platform.name}\n${key.id}".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".mxl"
    }

    private fun storedVersion(directory: File, owner: String, key: MediaKey): Int {
        val bytes = File(File(directory, owner), entryFileName(key)).readBytes()
        return java.nio.ByteBuffer.wrap(bytes, 4, 4).int
    }

    private inline fun assertImmutable(block: () -> Unit) {
        try {
            block()
        } catch (_: UnsupportedOperationException) {
            return
        }
        fail("Expected immutable collection")
    }

    @Suppress("UNCHECKED_CAST")
    private fun mutateWords(words: List<LyricWord>) {
        (words as MutableList<LyricWord>).add(LyricWord(0, 1, 0, 1))
    }

    private inline fun assertRejectedData(block: () -> Unit) {
        try {
            block()
        } catch (_: RuntimeException) {
            return
        }
        fail("Expected rejected lyrics data")
    }

    private fun entryFiles(root: Path): List<Path> =
        ownedFiles(root).filter { it.fileName.toString().endsWith(".mxl") }

    private fun ownedFiles(root: Path): List<Path> =
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) }.toList()
        }

    private inline fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
