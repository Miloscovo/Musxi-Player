package tech.musxi.core.playback

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class QueueRecoveryPolicyTest {

    private lateinit var policy: QueueRecoveryPolicy

    @Before
    fun setUp() {
        policy = QueueRecoveryPolicy()
    }

    @Test
    fun `source expired gets exactly one refresh then stops`() {
        assertEquals(RecoveryAction.REFRESH_SOURCE, policy.decide(PlaybackFailure.SOURCE_EXPIRED, hasNext = true))
        assertEquals(RecoveryAction.STOP, policy.decide(PlaybackFailure.SOURCE_EXPIRED, hasNext = true))
        assertEquals(RecoveryAction.STOP, policy.decide(PlaybackFailure.SOURCE_EXPIRED, hasNext = false))
    }

    @Test
    fun `reset restores the source expired budget`() {
        assertEquals(RecoveryAction.REFRESH_SOURCE, policy.decide(PlaybackFailure.SOURCE_EXPIRED, hasNext = false))
        assertEquals(RecoveryAction.STOP, policy.decide(PlaybackFailure.SOURCE_EXPIRED, hasNext = false))

        policy.reset()

        assertEquals(RecoveryAction.REFRESH_SOURCE, policy.decide(PlaybackFailure.SOURCE_EXPIRED, hasNext = true))
        assertEquals(RecoveryAction.STOP, policy.decide(PlaybackFailure.SOURCE_EXPIRED, hasNext = true))
    }

    @Test
    fun `network failures never skip to the next track`() {
        val failures = arrayOf(
            PlaybackFailure.NETWORK,
            PlaybackFailure.TIMEOUT,
            PlaybackFailure.RATE_LIMIT,
            PlaybackFailure.AUTH_REQUIRED,
            PlaybackFailure.PROTOCOL,
            PlaybackFailure.UNKNOWN
        )
        for (failure in failures) {
            for (hasNext in booleanArrayOf(true, false)) {
                assertEquals(
                    "$failure must stop (hasNext=$hasNext)",
                    RecoveryAction.STOP,
                    policy.decide(failure, hasNext)
                )
            }
        }
    }

    @Test
    fun `network failures stay stopping even after many decisions`() {
        repeat(10) {
            assertEquals(RecoveryAction.STOP, policy.decide(PlaybackFailure.NETWORK, hasNext = true))
            assertEquals(RecoveryAction.STOP, policy.decide(PlaybackFailure.TIMEOUT, hasNext = true))
        }
    }

    @Test
    fun `permission skips when next exists`() {
        assertEquals(RecoveryAction.SKIP_NEXT, policy.decide(PlaybackFailure.PERMISSION, hasNext = true))
        assertEquals(RecoveryAction.SKIP_NEXT, policy.decide(PlaybackFailure.PERMISSION, hasNext = true))
    }

    @Test
    fun `unavailable skips when next exists`() {
        assertEquals(RecoveryAction.SKIP_NEXT, policy.decide(PlaybackFailure.UNAVAILABLE, hasNext = true))
        assertEquals(RecoveryAction.SKIP_NEXT, policy.decide(PlaybackFailure.UNAVAILABLE, hasNext = true))
    }

    @Test
    fun `permission and unavailable stop at the end of the queue`() {
        assertEquals(RecoveryAction.STOP, policy.decide(PlaybackFailure.PERMISSION, hasNext = false))
        assertEquals(RecoveryAction.STOP, policy.decide(PlaybackFailure.UNAVAILABLE, hasNext = false))
        assertEquals(RecoveryAction.STOP, policy.decide(PlaybackFailure.PERMISSION, hasNext = false))
    }

    @Test
    fun `source expired budget is independent of skip decisions`() {
        assertEquals(RecoveryAction.REFRESH_SOURCE, policy.decide(PlaybackFailure.SOURCE_EXPIRED, hasNext = true))
        assertEquals(RecoveryAction.SKIP_NEXT, policy.decide(PlaybackFailure.PERMISSION, hasNext = true))
        assertEquals(RecoveryAction.STOP, policy.decide(PlaybackFailure.SOURCE_EXPIRED, hasNext = true))
    }

    @Test
    fun `source expired budget is not consumed by non expired failures`() {
        assertEquals(RecoveryAction.STOP, policy.decide(PlaybackFailure.NETWORK, hasNext = true))
        assertEquals(RecoveryAction.SKIP_NEXT, policy.decide(PlaybackFailure.UNAVAILABLE, hasNext = true))
        assertEquals(RecoveryAction.REFRESH_SOURCE, policy.decide(PlaybackFailure.SOURCE_EXPIRED, hasNext = false))
    }
}
