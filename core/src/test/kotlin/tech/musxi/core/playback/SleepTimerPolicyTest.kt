package tech.musxi.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SleepTimerPolicyTest {
    private val policy = SleepTimerPolicy()

    @Test fun fixedDurationExpiresOnceAndLaterTicksAreSilent() {
        policy.startDuration(1_000, 10_000, finishTrack = false)
        assertEquals(SleepTimerPhase.COUNTING, policy.state.value.phase)
        assertEquals(11_000L, policy.state.value.deadlineElapsedMs)
        assertFalse(policy.tick(10_999, "slot-a", canFinish = true))
        assertTrue(policy.tick(11_000, "slot-a", canFinish = true))
        val consumed = policy.state.value
        assertEquals(SleepTimerPhase.OFF, consumed.phase)
        assertEquals(SleepTimerMode.OFF, consumed.mode)
        assertNull(consumed.deadlineElapsedMs)
        assertFalse(policy.tick(11_000, "slot-a", canFinish = true))
        assertFalse(policy.tick(99_000, "slot-a", canFinish = true))
        assertEquals(consumed, policy.state.value)
    }

    @Test fun expiredFinishTrackModeWaitsInsteadOfPausingMidTrack() {
        policy.startDuration(0, 5_000, finishTrack = true)
        assertEquals(SleepTimerMode.AFTER_DURATION_FINISH_TRACK, policy.state.value.mode)
        assertFalse(policy.tick(5_000, "slot-a", canFinish = true))
        val waiting = policy.state.value
        assertEquals(SleepTimerPhase.WAITING_FOR_TRACK_END, waiting.phase)
        assertEquals(SleepTimerMode.AFTER_DURATION_FINISH_TRACK, waiting.mode)
        assertEquals(5_000L, waiting.deadlineElapsedMs)
        assertTrue(waiting.active)
        assertFalse(policy.tick(5_000, "slot-a", canFinish = true))
        assertFalse(policy.tick(60_000, "slot-a", canFinish = true))
        assertEquals(waiting, policy.state.value)
        assertTrue(policy.trackEnded("slot-a"))
    }

    @Test fun noCurrentSlotAtDeadlinePausesImmediatelyAndConsumes() {
        for (ticket in listOf(null, "slot-a")) {
            val fresh = SleepTimerPolicy()
            fresh.startDuration(0, 1_000, finishTrack = true)
            assertTrue(fresh.tick(1_000, ticket, canFinish = false))
            assertEquals(SleepTimerPhase.OFF, fresh.state.value.phase)
            assertFalse(fresh.tick(1_000, ticket, canFinish = false))
        }
    }

    @Test fun boundSlotEndConsumesExactlyOnce() {
        policy.finishCurrent("slot-a")
        assertEquals(SleepTimerPhase.WAITING_FOR_TRACK_END, policy.state.value.phase)
        assertFalse(policy.trackChanged("slot-a"))
        assertTrue(policy.trackEnded("slot-a"))
        assertEquals(SleepTimerPhase.OFF, policy.state.value.phase)
        assertFalse(policy.trackEnded("slot-a"))
        assertFalse(policy.trackChanged("slot-b"))
        assertFalse(policy.trackChanged(null))
    }

    @Test fun sameSlotSourceQualityAndSeekChangesKeepWaiting() {
        policy.finishCurrent("slot-a")
        repeat(5) {
            assertFalse(policy.trackChanged("slot-a"))
            assertEquals(SleepTimerPhase.WAITING_FOR_TRACK_END, policy.state.value.phase)
        }
        assertFalse(policy.trackEnded("slot-b"))
        assertEquals(SleepTimerPhase.WAITING_FOR_TRACK_END, policy.state.value.phase)
        assertTrue(policy.trackEnded("slot-a"))
    }

    @Test fun leavingBoundSlotForDuplicateSongPausesOnlyOnce() {
        policy.finishCurrent("slot-a")
        assertTrue(policy.trackChanged("slot-b"))
        assertEquals(SleepTimerPhase.OFF, policy.state.value.phase)
        assertFalse(policy.trackChanged("slot-b"))
        assertFalse(policy.trackChanged("slot-a"))
        assertFalse(policy.trackEnded("slot-a"))
    }

    @Test fun explicitQueueReplacementDropsBindingButKeepsCountdown() {
        policy.startDuration(0, 10_000, finishTrack = false)
        val counting = policy.state.value
        policy.queueReplaced()
        assertEquals(counting, policy.state.value)
        assertFalse(policy.tick(9_999, "slot-a", canFinish = true))
        assertTrue(policy.tick(10_000, "slot-a", canFinish = true))

        policy.finishCurrent("slot-a")
        policy.queueReplaced()
        assertEquals(SleepTimerPhase.OFF, policy.state.value.phase)
        assertFalse(policy.trackEnded("slot-a"))
    }

    @Test fun startFinishAndCancelSurviveRestartWithoutGhostPauses() {
        policy.startDuration(0, 10_000, finishTrack = false)
        policy.finishCurrent("slot-a")
        assertEquals(SleepTimerMode.END_OF_TRACK, policy.state.value.mode)
        assertNull(policy.state.value.deadlineElapsedMs)
        assertFalse(policy.tick(20_000, "slot-a", canFinish = true))

        val revision = policy.state.value.revision
        policy.startDuration(20_000, 2_000, finishTrack = true)
        assertEquals(revision + 1, policy.state.value.revision)
        assertFalse(policy.trackEnded("slot-a"))
        assertFalse(policy.trackChanged("slot-b"))
        assertFalse(policy.tick(22_000, "slot-b", canFinish = true))
        assertEquals(SleepTimerPhase.WAITING_FOR_TRACK_END, policy.state.value.phase)
        assertTrue(policy.trackEnded("slot-b"))
        assertFalse(policy.tick(22_000, "slot-b", canFinish = true))

        policy.startDuration(30_000, 1_000, finishTrack = false)
        val started = policy.state.value.revision
        policy.cancel()
        assertEquals(started + 1, policy.state.value.revision)
        assertFalse(policy.tick(90_000, "slot-a", canFinish = true))

        val restarted = SleepTimerPolicy()
        assertEquals(SleepTimerState(), restarted.state.value)
        assertFalse(restarted.tick(1_000_000, "slot-a", canFinish = true))
        assertFalse(restarted.trackEnded("slot-a"))
    }

    @Test fun lateTicksWithLargeGapsStillExpireExactlyOnce() {
        policy.startDuration(0, SleepTimerPolicy.MAX_DURATION_MS, finishTrack = false)
        val deadline = policy.state.value.deadlineElapsedMs!!
        assertFalse(policy.tick(deadline - 1, "slot-a", canFinish = true))
        assertTrue(policy.tick(Long.MAX_VALUE, "slot-a", canFinish = true))
        assertFalse(policy.tick(Long.MAX_VALUE, "slot-a", canFinish = true))
        assertEquals(SleepTimerPhase.OFF, policy.state.value.phase)
    }

    @Test fun rejectedDurationsLeavePreviousStateUntouched() {
        policy.startDuration(0, 3_000, finishTrack = false)
        val stable = policy.state.value
        val invalid = listOf(
            0L to 0L,
            0L to -1L,
            0L to SleepTimerPolicy.MAX_DURATION_MS + 1,
            -1L to 1_000L,
            Long.MAX_VALUE to 1_000L,
            Long.MAX_VALUE - 10 to 11_000L,
        )
        for ((now, duration) in invalid) {
            try {
                policy.startDuration(now, duration, finishTrack = true)
                fail("expected rejection for now=$now duration=$duration")
            } catch (expected: IllegalArgumentException) {
                assertEquals(stable, policy.state.value)
            }
        }
        assertFalse(policy.tick(2_999, "slot-a", canFinish = true))
        assertTrue(policy.tick(3_000, "slot-a", canFinish = true))
    }
}
