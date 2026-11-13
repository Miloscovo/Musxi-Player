package tech.musxi.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPlayerActionsTest {
    @Test fun skipsStayInsideTheRecording() {
        assertEquals(45_000L, skipTarget(60_000L, -LocalSkipBackMs, 600_000L))
        assertEquals(90_000L, skipTarget(60_000L, LocalSkipForwardMs, 600_000L))
        assertEquals("not before the start", 0L, skipTarget(5_000L, -LocalSkipBackMs, 600_000L))
        // Not onto the very end either, which would finish the recording and move on.
        assertEquals(599_000L, skipTarget(590_000L, LocalSkipForwardMs, 600_000L))
        assertEquals("unknown length: forward still works", 90_000L, skipTarget(60_000L, LocalSkipForwardMs, 0L))
    }

    @Test fun speedLabelsAreShort() {
        assertEquals("1×", speedLabel(1f))
        assertEquals("2×", speedLabel(2f))
        assertEquals("1.25×", speedLabel(1.25f))
        assertEquals("0.75×", speedLabel(0.75f))
        assertEquals("1.5×", speedLabel(1.5f))
    }

    @Test fun speedChoicesAreTheOnesTheBackendAccepts() {
        assertEquals(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f), LocalSpeeds)
        LocalSpeeds.forEach { tech.musxi.core.localaudio.LocalAudioLimits.requirePlaybackSpeed(it) }
        assertEquals("1.75×", speedLabel(1.75f))
    }

    @Test fun bookmarksAreNumberedOnFromTheOnesThere() {
        assertEquals("书签 1", nextBookmarkName(0))
        assertEquals("书签 3", nextBookmarkName(2))
    }
}
