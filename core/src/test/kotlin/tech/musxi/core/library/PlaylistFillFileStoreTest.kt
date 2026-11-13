package tech.musxi.core.library

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.musxi.core.account.Platform

class PlaylistFillFileStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = "a".repeat(64)

    @Test fun qqRetainsAll167PagesOfFiveThousandSongsAcrossColdReadAndCachedSearch() {
        val directory = temporary.newFolder()
        val playlist = MediaKey(Platform.QQ, "1_1")
        val store = FileLibraryPageStore(directory, Platform.QQ)
        for (offset in 0 until 5000 step 30) {
            val end = minOf(offset + 30, 5000)
            val songs = (offset until end).map {
                SongSummary(MediaKey(Platform.QQ, it.toString().padStart(14, '0')), "Song $it", listOf("Artist"), "Album", 100)
            }
            assertTrue(store.writeTracksRetainingPages(owner, playlist, offset,
                SavedLibraryPage(Page(songs, end.takeIf { it < 5000 }, 5000), 1000)))
        }
        val pages = FileLibraryPageStore(directory, Platform.QQ).readTrackPages(owner, playlist)
        assertEquals(167, pages.size)
        val search = CachedPlaylistSearch.search(pages, "Song 4999")
        assertTrue(search.complete); assertEquals(5000, search.loadedCount); assertEquals(1, search.items.size)
    }

    @Test fun byteBudgetStopsAppendBeforeEvictingAnyPreviouslyWrittenPage() {
        val directory = temporary.newFolder()
        val playlist = MediaKey(Platform.NETEASE, "9")
        val store = FileLibraryPageStore(directory)
        var rejected = false
        for (offset in 0 until 1000 step 50) {
            val before = store.readTrackPages(owner, playlist)
            val songs = (offset until offset + 50).map {
                SongSummary(MediaKey(Platform.NETEASE, "${it + 1}"), "歌".repeat(2048), listOf("Artist"), "专".repeat(2048), 100)
            }
            val success = store.writeTracksRetainingPages(owner, playlist, offset,
                SavedLibraryPage(Page(songs, (offset + 50).takeIf { it < 950 }, 950), 1000))
            if (!success) {
                rejected = true
                assertEquals(before, FileLibraryPageStore(directory).readTrackPages(owner, playlist))
                break
            }
        }
        assertTrue("Must exercise the existing 2 MiB collection budget", rejected)
    }
}
