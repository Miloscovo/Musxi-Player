package tech.musxi.core.library

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.musxi.core.account.Platform

class LibraryPageStoreTest {
    @Test fun defaultReadTrackPagesFollowsNextOffsetChain() {
        val playlist = MediaKey(Platform.NETEASE, "9")
        val pages = mapOf(
            0 to saved(0, 2, listOf("1")),
            2 to saved(2, null, listOf("2")),
        )
        val store = object : LibraryPageStore {
            override fun readPlaylists(owner: String, offset: Int) = null
            override fun writePlaylists(owner: String, offset: Int, value: SavedLibraryPage<PlaylistSummary>) = Unit
            override fun readTracks(owner: String, playlist: MediaKey, offset: Int) = pages[offset]
            override fun writeTracks(owner: String, playlist: MediaKey, offset: Int,
                value: SavedLibraryPage<SongSummary>) = Unit
            override fun clear() = Unit
        }
        assertEquals(listOf(0, 2), store.readTrackPages("a".repeat(64), playlist).map { it.offset })
    }

    private fun saved(offset: Int, next: Int?, ids: List<String>) =
        SavedLibraryPage(Page(ids.map {
            SongSummary(MediaKey(Platform.NETEASE, it), "Song $offset", emptyList(), "", 1_000)
        }, next, 2), 10L + offset)
}
