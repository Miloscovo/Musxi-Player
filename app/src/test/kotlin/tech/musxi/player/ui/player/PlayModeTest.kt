package tech.musxi.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.musxi.playback.PlaybackRepeatMode

class PlayModeTest {
    @Test fun everyModeRoundTrips() {
        PlayMode.entries.forEach { assertEquals(it, playModeOf(it.shuffle, it.repeat)) }
    }

    @Test fun legacyCombinationsFoldIntoThreeModes() {
        // Earlier builds could leave repeat off; that is still read as plain sequential order.
        assertEquals(PlayMode.SEQUENTIAL, playModeOf(false, PlaybackRepeatMode.OFF))
        assertEquals(PlayMode.SHUFFLE, playModeOf(true, PlaybackRepeatMode.OFF))
        assertEquals(PlayMode.REPEAT_ONE, playModeOf(true, PlaybackRepeatMode.ONE))
    }
}
