package tech.musxi.player.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey

class PlaylistReorderTest {
    private val keys = List(4) { MediaKey(Platform.QQ, "$it") }

    @Test fun draggingDownDropsBeforeTheNextRow() {
        assertEquals(keys[2], playlistDropAnchor(0, 60f, 64f, keys))
        assertNull(playlistDropAnchor(0, 190f, 64f, keys))
    }

    @Test fun draggingUpDropsBeforeTheTargetRow() {
        assertEquals(keys[0], playlistDropAnchor(2, -130f, 64f, keys))
    }

    @Test fun aSmallMovementKeepsTheConfirmedOrder() {
        assertEquals(keys[1], playlistDropAnchor(1, 20f, 64f, keys))
    }

    @Test fun invalidGeometryNeverWrites() {
        assertNull(playlistDropAnchor(-1, 0f, 64f, keys))
        assertNull(playlistDropAnchor(0, 0f, 0f, keys))
    }
}
