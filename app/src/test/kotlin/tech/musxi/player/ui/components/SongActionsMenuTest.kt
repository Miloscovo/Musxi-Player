package tech.musxi.player.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.EditablePlaylist
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.PlaylistSummary

/** The playlist picker's list rule; pure logic, no Android runtime needed. */
class SongActionsMenuTest {

    private fun playlist(id: String, favorite: Boolean = false) = EditablePlaylist(
        playlist = PlaylistSummary(MediaKey(Platform.NETEASE, id), "歌单 $id", null, 3),
        accountGeneration = 1L,
        favorite = favorite,
    )

    private fun ids(list: List<EditablePlaylist>) = list.map { it.playlist.key.id }

    @Test fun likedPlaylistIsLeftOut() {
        val list = listOf(playlist("liked", favorite = true), playlist("a"), playlist("b"))
        assertEquals(listOf("a", "b"), ids(writableTargets(list)))
    }

    @Test fun orderIsKept() {
        val list = listOf(playlist("c"), playlist("liked", favorite = true), playlist("a"), playlist("b"))
        assertEquals(listOf("c", "a", "b"), ids(writableTargets(list)))
    }

    @Test fun listWithoutLikedPlaylistComesBackWhole() {
        val list = listOf(playlist("a"), playlist("b"))
        assertEquals(listOf("a", "b"), ids(writableTargets(list)))
    }

    @Test fun emptyListStaysEmpty() {
        assertTrue(writableTargets(emptyList()).isEmpty())
    }

    @Test fun onlyLikedPlaylistLeavesNothing() {
        assertTrue(writableTargets(listOf(playlist("liked", favorite = true))).isEmpty())
    }

    @Test fun inputListIsNotChanged() {
        val list = listOf(playlist("liked", favorite = true), playlist("a"))
        writableTargets(list)
        assertEquals(listOf("liked", "a"), ids(list))
    }

    @Test fun menuStateDefaults() {
        // Favouriting stays off until the shell has seen the platform's collection; playlists are on.
        val ui = SongActionsUi()
        assertFalse(ui.liked)
        assertFalse(ui.canFavorite)
        assertTrue(ui.canAddToPlaylist)
    }
}
