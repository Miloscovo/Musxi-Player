package tech.musxi.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackStallWatchTest {
    private data class Timer(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
    private class Fixture {
        var time = 0L
        var wait: PlaybackStallWatch.Wait? = null
        val timeouts = mutableListOf<PlaybackStallWatch.Kind>()
        val timers = mutableListOf<Timer>()
        val watch = PlaybackStallWatch({ time }, { delay, action ->
            val timer = Timer(time + delay, action).also(timers::add)
            val cancel = { timer.cancelled = true }
            cancel
        }, { wait }, { timeouts.add(it); wait = null })

        fun start(slot: String, kind: PlaybackStallWatch.Kind) {
            wait = PlaybackStallWatch.Wait(slot, kind)
            watch.observe()
        }
        fun advance(to: Long) {
            time = to
            timers.toList().filter { !it.cancelled && it.at <= time }.forEach { it.action() }
        }
    }

    @Test fun sameBufferingEventsCannotExtendTheStartupDeadline() {
        val f = Fixture()
        f.start("first", PlaybackStallWatch.Kind.PREPARE)
        f.advance(44_999)
        f.watch.observe()
        assertTrue(f.timeouts.isEmpty())
        f.advance(45_000)
        f.advance(100_000)
        assertEquals(listOf(PlaybackStallWatch.Kind.PREPARE), f.timeouts)
    }

    @Test fun anOldLoadCannotTimeOutTheReplacementSlot() {
        val f = Fixture()
        f.start("first", PlaybackStallWatch.Kind.PREPARE)
        val old = f.timers.single()
        f.advance(40_000)
        f.start("second", PlaybackStallWatch.Kind.BUFFER)
        f.advance(45_000)
        old.action()
        assertTrue(f.timeouts.isEmpty())
        f.advance(100_000)
        assertEquals(listOf(PlaybackStallWatch.Kind.BUFFER), f.timeouts)
    }

    @Test fun pauseFocusSuppressionOrReadyInvalidatesAnAlreadyQueuedTimeout() {
        val f = Fixture()
        f.start("first", PlaybackStallWatch.Kind.PREPARE)
        val queued = f.timers.single()
        // The platform state can change before its listener dispatch reaches observe().
        f.wait = null
        f.advance(45_000)
        queued.action()
        assertTrue(f.timeouts.isEmpty())
        f.start("first", PlaybackStallWatch.Kind.PREPARE)
        f.advance(89_999)
        assertTrue(f.timeouts.isEmpty())
    }
}
