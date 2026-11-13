package tech.musxi.player.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The song menu's defaults; the playlist list rules live in MembershipEditorTest. */
class SongActionsMenuTest {

    @Test fun menuStateDefaults() {
        // Favouriting stays off until the shell has seen the platform's collection; playlists are on.
        val ui = SongActionsUi()
        assertFalse(ui.liked)
        assertFalse(ui.canFavorite)
        assertTrue(ui.canAddToPlaylist)
    }
}
