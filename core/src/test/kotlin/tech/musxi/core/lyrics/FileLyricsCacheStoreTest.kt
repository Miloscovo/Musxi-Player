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
