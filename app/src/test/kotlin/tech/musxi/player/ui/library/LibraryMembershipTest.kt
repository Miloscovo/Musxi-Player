package tech.musxi.player.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.PlaylistChangeResult
import tech.musxi.core.library.PlaylistChangeStatus
import tech.musxi.core.library.PlaylistSummary
import tech.musxi.core.library.SongSummary

/** How the lists on screen follow a playlist membership save. */
class LibraryMembershipTest {

    private fun key(id: String) = MediaKey(Platform.NETEASE, id)
    private fun playlist(id: String, count: Int) = PlaylistSummary(key(id), "歌单 $id", null, count)
    private fun song(id: String) = SongSummary(key(id), id, listOf("歌手"), "", 1_000)
    private fun change(id: String, wanted: Boolean, status: PlaylistChangeStatus) =
        PlaylistChangeResult(key(id), wanted, status)

    private val open = TrackListUiState(playlist = playlist("p", 3), items = listOf(song("s1"), song("s2"), song("s3")))

    @Test fun aConfirmedRemovalDropsTheRowAndTheCount() {
        val (after, reread) = open.afterMembership(key("s2"), listOf(change("p", false, PlaylistChangeStatus.APPLIED)))
        assertEquals(listOf("s1", "s3"), after.items.map { it.key.id })
        assertEquals(2, after.playlist!!.trackCount)
        assertFalse("the whole list is loaded, nothing to re-read", reread)
    }

    @Test fun aRemovalFromAPartlyLoadedListDoesNotCollapseIt() {
        // The browser re-fetches the page it holds after every write; re-reading the first page here
        // would throw away what the user scrolled to and flash the pre-write cache.
        val partial = open.copy(nextOffset = 3, playlist = playlist("p", 50))
        val (after, reread) = partial.afterMembership(key("s1"), listOf(change("p", false, PlaylistChangeStatus.APPLIED)))
        assertEquals(listOf("s2", "s3"), after.items.map { it.key.id })
        assertFalse(reread)
    }

    @Test fun theSamePageDeliveredTwiceDoesNotDoubleRows() {
        // After a write the browser hands over its current page again, still at offset 30.
        val shown = (1..60).map { song("s$it") }
        val page2 = (31..60).map { song("s$it") }
        val merged = splicePage(shown, 30, page2) { it.key }
        assertEquals(60, merged.size)
        assertEquals(merged.size, merged.map { it.key }.distinct().size)
    }

    @Test fun aPageShiftedByARemovalKeepsTheSongThatMovedUp() {
        // s15 was removed from the first page, so every later song moved up by one: s31 is now the
        // last song of page 1 and the server's page 2 (offset 30) starts at s32.
        val shown = (1..60).map { song("s$it") }.filterNot { it.key.id == "s15" }
        val page2 = (32..61).map { song("s$it") }
        val merged = splicePage(shown, 30, page2) { it.key }
        assertEquals((1..61).filter { it != 15 }.map { "s$it" }, merged.map { it.key.id })
    }

    @Test fun theFirstPageReplacesTheList() {
        val merged = splicePage((1..60).map { song("s$it") }, 0, (1..30).map { song("s$it") }) { it.key }
        assertEquals(30, merged.size)
    }

    @Test fun anUnconfirmedResultLeavesRowsAndRereads() {
        val (after, reread) = open.afterMembership(key("s2"), listOf(change("p", false, PlaylistChangeStatus.UNKNOWN)))
        assertEquals(open, after)
        assertTrue(reread)
    }

    @Test fun aFailedRemovalKeepsTheRow() {
        val (after, reread) = open.afterMembership(key("s2"), listOf(change("p", false, PlaylistChangeStatus.FAILED)))
        assertEquals(open, after)
        assertFalse(reread)
    }

    @Test fun aChangeToAnotherListLeavesTheOpenOneAlone() {
        val (after, reread) = open.afterMembership(key("s2"), listOf(change("other", false, PlaylistChangeStatus.APPLIED)))
        assertEquals(open, after)
        assertFalse(reread)
    }

    @Test fun anAdditionCountsAndRereadsForItsPosition() {
        val (after, reread) = open.afterMembership(key("new"), listOf(change("p", true, PlaylistChangeStatus.APPLIED)))
        assertEquals(4, after.playlist!!.trackCount)
        assertEquals(open.items, after.items)
        assertTrue(reread)
    }

    @Test fun theGridCountsFollowOnlyConfirmedChanges() {
        val grid = PlaylistUiState(items = listOf(playlist("a", 5), playlist("b", 0), playlist("c", 7)))
        val after = grid.afterMembership(listOf(
            change("a", true, PlaylistChangeStatus.APPLIED),
            change("b", false, PlaylistChangeStatus.APPLIED),
            change("c", false, PlaylistChangeStatus.UNKNOWN),
        ))
        assertEquals(listOf(6, 0, 7), after.items.map { it.trackCount })
    }

    @Test fun anUntouchedGridIsTheSameObject() {
        val grid = PlaylistUiState(items = listOf(playlist("a", 5)))
        assertTrue(grid.afterMembership(listOf(change("a", true, PlaylistChangeStatus.UNCHANGED))) === grid)
    }
}
