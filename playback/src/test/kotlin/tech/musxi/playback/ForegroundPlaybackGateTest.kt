package tech.musxi.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundPlaybackGateTest {
    private data class Timer(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)

    private class Fixture(
        var foreground: Boolean = false,
        private val onChanged: (ForegroundPlaybackGate) -> Unit = {},
    ) {
        var current = true
        var time = 0L
        val timers = mutableListOf<Timer>()
        val changes = mutableListOf<Boolean>()
        var readyCount = 0
        var deniedCount = 0
        lateinit var gate: ForegroundPlaybackGate

        init {
            gate = ForegroundPlaybackGate(
                now = { time },
                isForeground = { foreground },
                schedule = { delay, action ->
                    val timer = Timer(time + delay, action).also(timers::add)
                    val cancel = { timer.cancelled = true }
                    cancel
                },
                changed = {
                    changes += gate.pending
                    onChanged(gate)
                },
                ready = { ++readyCount },
                denied = { ++deniedCount },
            )
        }

        fun advance(delta: Long) {
            val end = time + delta
            while (true) {
                val next = timers
                    .filter { !it.cancelled && it.at <= end }
                    .minByOrNull { it.at }
                    ?: break
                next.cancelled = true
                time = next.at
                next.action()
            }
            time = end
        }
    }

    @Test fun foregroundAvailableBeforeStartBecomesReadyExactlyOnce() {
        val f = Fixture(foreground = true)

        f.gate.start { f.current }
        f.advance(5_000)

        assertFalse(f.gate.pending)
        assertEquals(1, f.readyCount)
        assertEquals(0, f.deniedCount)
        assertEquals(listOf(true, false), f.changes)
        assertTrue(f.timers.isEmpty())
    }

    @Test fun foregroundEstablishedAsynchronouslyBecomesReadyWithinBudget() {
        val f = Fixture()
        f.gate.start { f.current }

        f.advance(24)
        assertTrue(f.gate.pending)
        assertEquals(0, f.readyCount)

        f.foreground = true
        f.advance(1)
        f.advance(5_000)

        assertFalse(f.gate.pending)
        assertEquals(1, f.readyCount)
        assertEquals(0, f.deniedCount)
        assertEquals(listOf(true, false), f.changes)
    }

    @Test fun timeoutIsDeniedOnceAndLatePollCannotStartAgain() {
        val f = Fixture()
        f.gate.start { f.current }

        f.advance(3_000)
        val timeoutCallback = f.timers.last().action

        assertFalse(f.gate.pending)
        assertEquals(0, f.readyCount)
        assertEquals(1, f.deniedCount)

        timeoutCallback()
        f.advance(5_000)

        assertEquals(0, f.readyCount)
        assertEquals(1, f.deniedCount)
    }

    @Test fun cancelAfterPauseInvalidatesQueuedForegroundPoll() {
        val f = Fixture()
        f.gate.start { f.current }
        val latePoll = f.timers.single()

        f.gate.cancel()
        latePoll.action()

        assertFalse(f.gate.pending)
        assertEquals(0, f.readyCount)
        assertEquals(0, f.deniedCount)
        assertEquals(listOf(true, false), f.changes)
    }

    @Test fun supersedeRejectsOldPollAndAllowsOnlyNewRequestToComplete() {
        val f = Fixture()
        f.gate.start { f.current }
        val oldPoll = f.timers.single()

        f.gate.start { f.current }
        oldPoll.action()

        assertTrue(f.gate.pending)
        assertEquals(0, f.readyCount)
        assertEquals(listOf(true, true), f.changes)

        f.foreground = true
        f.advance(25)

        assertFalse(f.gate.pending)
        assertEquals(1, f.readyCount)
        assertEquals(0, f.deniedCount)
        assertEquals(listOf(true, true, false), f.changes)
    }

    @Test fun expiredCurrentRequestCancelsWithoutReadyOrDenied() {
        val f = Fixture()
        f.gate.start { f.current }
        f.current = false
        f.foreground = true

        f.advance(25)

        assertFalse(f.gate.pending)
        assertEquals(0, f.readyCount)
        assertEquals(0, f.deniedCount)
        assertEquals(listOf(true, false), f.changes)
    }

    @Test fun changedCanReentrantlyCancelBeforePollingBegins() {
        lateinit var f: Fixture
        f = Fixture(onChanged = { gate ->
            if (gate.pending && f.changes.size == 1) gate.cancel()
        })

        f.gate.start { f.current }

        assertFalse(f.gate.pending)
        assertEquals(0, f.readyCount)
        assertEquals(0, f.deniedCount)
        assertEquals(listOf(true, false), f.changes)
        assertTrue(f.timers.isEmpty())
    }

    @Test fun completionPublicationCannotDemoteForegroundBeforeStart() {
        lateinit var f: Fixture
        f = Fixture(foreground = true, onChanged = { gate ->
            if (!gate.pending) {
                assertEquals(1, f.readyCount)
                f.foreground = false
            }
        })
        f.gate.start { f.current }
        assertFalse(f.gate.pending)
        assertEquals(1, f.readyCount)
        assertEquals(0, f.deniedCount)
    }
}
