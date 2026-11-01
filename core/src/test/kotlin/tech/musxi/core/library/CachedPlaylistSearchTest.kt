package tech.musxi.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform

class CachedPlaylistSearchTest {
    private fun song(id: String, title: String,
        artists: List<String> = listOf("Artist"), album: String = "Album") =
        SongSummary(MediaKey(Platform.NETEASE, id), title, artists, album, 1_000)

    private fun page(offset: Int, next: Int?, total: Int?, fetchedAt: Long,
        vararg items: SongSummary) =
        CachedTrackPage(offset, SavedLibraryPage(Page(items.toList(), next, total), fetchedAt))

    @Test fun gapKeepsAvailablePagesButIsIncomplete() {
        val result = CachedPlaylistSearch.search(listOf(
            page(20, null, 30, 20, song("3", "Gamma")),
            page(0, 10, 30, 10, song("1", "Alpha")),
        ), "")
        assertEquals(setOf(0, 20), result.loadedOffsets)
        assertEquals(2, result.loadedCount)
        assertEquals(30, result.total)
        assertFalse(result.complete)
    }

    @Test fun totalChangesAreIncompleteAndTotalIsSuppressed() {
        val result = CachedPlaylistSearch.search(listOf(
            page(0, 1, 2, 10, song("1", "Alpha")),
            page(1, null, 3, 20, song("2", "Beta")),
        ), "")
        assertNull(result.total)
        assertFalse(result.complete)
    }

    @Test fun repeatedOffsetsAndDuplicateSongsAreCountedOnce() {
        val result = CachedPlaylistSearch.search(listOf(
            page(1, null, 2, 20, song("2", "Beta")),
            page(0, 1, 2, 10, song("1", "Alpha")),
            page(0, 1, 2, 5, song("1", "Alpha")),
        ), "")
        assertEquals(listOf("1", "2"), result.items.map { it.key.id })
        assertEquals(2, result.loadedCount)
        assertEquals(5L, result.oldestFetchedAtEpochMs)
        assertFalse(result.complete)
    }

    @Test fun searchesTitleArtistAndAlbumAfterNormalization() {
        val pages = listOf(
            page(0, 2, 3, 10, song("1", "Hello World"), song("2", "Other", artists = listOf("星 歌手"))),
            page(2, null, 3, 20, song("3", "Different", album = "Ｃｉｔｙ Album")),
        )
        assertEquals(listOf("1"), CachedPlaylistSearch.search(pages, "hello  world").items.map { it.key.id })
        assertEquals(listOf("2"), CachedPlaylistSearch.search(pages, "星歌 手").items.map { it.key.id })
        assertEquals(listOf("3"), CachedPlaylistSearch.search(pages, "cityalbum").items.map { it.key.id })
        val all = CachedPlaylistSearch.search(pages, "  ")
        assertEquals(3, all.items.size)
        assertEquals(3, all.total)
        assertTrue(all.complete)
    }
}
