package tech.musxi.playback

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import tech.musxi.core.playback.SleepTimerPhase

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class ServiceSleepTimerTest {
    /** A port stub verifies real Handler/listener wiring; it does not claim audio decoding coverage. */
    private class Fixture {
        var current: MediaItem? = item("a")
        var playbackState = Player.STATE_READY
        var boundaryPause = false
        var pauses = 0
        val listeners = mutableListOf<Player.Listener>()
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { proxy, method, args ->
            when (method.name) {
                "getCurrentMediaItem" -> current
                "getPlaybackState" -> playbackState
                "getPlayerError" -> null
                "getPauseAtEndOfMediaItems" -> boundaryPause
                "setPauseAtEndOfMediaItems" -> { boundaryPause = args!![0] as Boolean; null }
                "addListener" -> { listeners += args!![0] as Player.Listener; null }
                "removeListener" -> { listeners -= args!![0] as Player.Listener; null }
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SleepTimerTestPlayer"
                else -> throw AssertionError("Unexpected player call: ${method.name}")
            }
        } as ExoPlayer
        val timer = ServiceSleepTimer(player, Handler(Looper.getMainLooper())) { pauses++ }
        fun end() { listeners.toList().forEach { it.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) } }
        fun transition(id: String) {
            current = item(id)
            listeners.toList().forEach { it.onMediaItemTransition(current, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) }
        }
        fun advance(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
        companion object { fun item(id: String) = MediaItem.Builder().setMediaId("same-song").setUri("musxi://queue/$id").build() }
    }

    @Test fun deadlinePausesOnceAndCannotBeUndoneByDelayedGatedPlay() {
        val f = Fixture()
        try {
            f.timer.startDuration(1_000, false)
            f.advance(1_001)
            assertEquals(1, f.pauses)
            assertFalse(sleepTimerSnapshot.value.active)
            assertTrue(f.timer.synchronize())
            f.advance(10_000)
            assertEquals(1, f.pauses)
            f.timer.explicitPlay()
            assertFalse(f.timer.synchronize())
        } finally { f.timer.close() }
    }

    @Test fun softDeadlineArmsPlayerBoundaryWithoutPausingUntilTrackEnd() {
        val f = Fixture()
        try {
            f.timer.startDuration(1_000, true)
            f.advance(1_001)
            assertEquals(0, f.pauses)
            assertTrue(f.boundaryPause)
            assertEquals(SleepTimerPhase.WAITING_FOR_TRACK_END, sleepTimerSnapshot.value.phase)
            f.end()
            assertEquals(1, f.pauses)
            assertFalse(f.boundaryPause)
            f.end()
            assertEquals(1, f.pauses)
        } finally { f.timer.close() }
    }


    @Test fun duplicateSongNavigationConsumesBoundSlotBeforeRestart() {
        val f = Fixture()
        try {
            assertTrue(f.timer.finishCurrent("musxi://queue/a"))
            f.timer.explicitPlay()
            f.transition("b")
            assertEquals(1, f.pauses)
            assertTrue(f.timer.synchronize())
            assertFalse(f.boundaryPause)
            f.timer.explicitPlay()
            assertFalse(f.timer.synchronize())
        } finally { f.timer.close() }
    }

    @Test fun staleTicketAndEndedOrIdleTracksCannotArmNewBoundTimer() {
        val f = Fixture()
        try {
            assertFalse(f.timer.finishCurrent("musxi://queue/stale"))
            f.playbackState = Player.STATE_ENDED
            assertFalse(f.timer.finishCurrent("musxi://queue/a"))
            f.playbackState = Player.STATE_IDLE
            assertFalse(f.timer.finishCurrent("musxi://queue/a"))
            assertFalse(sleepTimerSnapshot.value.active)
        } finally { f.timer.close() }
    }

    @Test fun queueReplaceDisarmsBoundButRetainsCountingAndCloseCancelsCallbacks() {
        val f = Fixture()
        assertTrue(f.timer.finishCurrent("musxi://queue/a"))
        f.timer.queueReplaced()
        f.transition("b")
        assertEquals(0, f.pauses)
        assertFalse(f.boundaryPause)
        f.timer.startDuration(1_000, false)
        f.timer.queueReplaced()
        f.timer.close()
        f.advance(2_000)
        assertEquals(0, f.pauses)
        assertTrue(f.listeners.isEmpty())
        assertFalse(sleepTimerSnapshot.value.active)
    }

    @Test fun elapsedRealtimeRecheckConsumesDeadlineAfterSuspension() {
        val f = Fixture()
        try {
            f.timer.startDuration(60_000, false)
            ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
            assertTrue(f.timer.synchronize())
            assertEquals(1, f.pauses)
            assertTrue(f.timer.synchronize())
            assertEquals(1, f.pauses)
        } finally { f.timer.close() }
    }

    @Test fun automaticBoundaryStraddlingDeadlineBindsTheOldSlotIncludingRepeatOne() {
        for (repeatOne in listOf(false, true)) {
            val f = Fixture()
            try {
                f.timer.startDuration(1_000, true)
                ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
                val old = f.current
                f.current = Fixture.item(if (repeatOne) "a" else "b")
                val transition = if (repeatOne) Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT else Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                f.listeners.toList().forEach { it.onMediaItemTransition(f.current, transition) }
                fun position(item: MediaItem?, index: Int, time: Long) = Player.PositionInfo(
                    null, index, item, null, 0, time, time, -1, -1)
                f.listeners.toList().forEach { it.onPositionDiscontinuity(position(old, 0, 200_000),
                    position(f.current, if (repeatOne) 0 else 1, 0), Player.DISCONTINUITY_REASON_AUTO_TRANSITION) }
                assertEquals(1, f.pauses)
                assertFalse(sleepTimerSnapshot.value.active)
                assertTrue(f.timer.synchronize())
            } finally { f.timer.close() }
        }
    }

    @Test fun replacingTimerAfterConsumptionSchedulesFreshDeadline() {
        val f = Fixture()
        try {
            f.timer.startDuration(1_000, false)
            f.advance(1_001)
            assertEquals(1, f.pauses)
            f.timer.startDuration(1_000, false)
            f.advance(1_001)
            assertEquals(2, f.pauses)
        } finally { f.timer.close() }
    }
}
