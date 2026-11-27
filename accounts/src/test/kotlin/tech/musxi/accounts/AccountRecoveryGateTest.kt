package tech.musxi.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.AccountError
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.AccountProfile
import tech.musxi.core.account.AccountState
import tech.musxi.core.account.Platform

class AccountRecoveryGateTest {
    @Test fun rejectsNonPositiveCooldown() {
        assertThrows(IllegalArgumentException::class.java) {
            AccountRecoveryGate({ 1_000L }, cooldownMs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccountRecoveryGate({ 1_000L }, cooldownMs = -1L)
        }
    }

    @Test fun acceptsOnlyNetworkRetryableStateWithProfile() {
        val gate = AccountRecoveryGate({ 1_000L })
        val valid = state()

        assertNull(gate.delayUntilAllowed(valid.copy(profile = null)))
        assertNull(gate.delayUntilAllowed(valid.copy(error = AccountError.EXPIRED)))
        assertNull(gate.delayUntilAllowed(valid.copy(error = AccountError.PLATFORM)))
        assertNull(gate.delayUntilAllowed(valid.copy(error = AccountError.REJECTED)))
        assertNull(gate.delayUntilAllowed(valid.copy(phase = AccountPhase.REAUTH_REQUIRED)))
        assertTrue(gate.accept(valid))
    }

    @Test fun cooldownIncludesZeroAndAllowsAtCooldownBoundary() {
        var time = 1_000L
        val gate = AccountRecoveryGate({ time })
        assertTrue(gate.accept(state(generation = 1)))
        assertEquals(60_000L, gate.delayUntilAllowed(state(generation = 2)))

        time += 59_999L
        assertEquals(1L, gate.delayUntilAllowed(state(generation = 2)))
        time++
        assertEquals(0L, gate.delayUntilAllowed(state(generation = 2)))
        assertTrue(gate.accept(state(generation = 2)))
        assertNull(gate.delayUntilAllowed(state(generation = 2)))
    }

    @Test fun acceptedGenerationCannotBeAcceptedAgainAfterCooldownOrRecordAttempt() {
        var time = 1_000L
        val gate = AccountRecoveryGate({ time })
        assertTrue(gate.accept(state(generation = 4)))

        time += 60_000L
        assertNull(gate.delayUntilAllowed(state(generation = 4)))
        assertFalse(gate.accept(state(generation = 4)))

        gate.recordAttempt(Platform.KUGOU)
        assertNull(gate.delayUntilAllowed(state(generation = 4)))
        assertFalse(gate.accept(state(generation = 4)))
    }

    @Test fun platformsHaveIndependentCooldowns() {
        val gate = AccountRecoveryGate({ 1_000L })
        assertTrue(gate.accept(state(platform = Platform.KUGOU)))
        assertTrue(gate.accept(state(platform = Platform.QQ)))
        assertFalse(gate.accept(state(platform = Platform.KUGOU, generation = 2)))
    }

    @Test fun clockRollbackRejectsUntilManualAttemptRebasesTime() {
        var time = 10_000L
        val gate = AccountRecoveryGate({ time })
        assertTrue(gate.accept(state(generation = 1)))

        time = 9_000L
        assertNull(gate.delayUntilAllowed(state(generation = 2)))
        assertFalse(gate.accept(state(generation = 2)))

        gate.recordAttempt(Platform.KUGOU)
        assertEquals(60_000L, gate.delayUntilAllowed(state(generation = 2)))
        assertFalse(gate.accept(state(generation = 2)))

        time += 60_000L
        assertTrue(gate.accept(state(generation = 2)))
    }

    @Test fun recordAttemptPreventsImmediateAutomaticRetry() {
        var time = 1_000L
        val gate = AccountRecoveryGate({ time })
        gate.recordAttempt(Platform.KUGOU)
        assertEquals(60_000L, gate.delayUntilAllowed(state()))
        assertFalse(gate.accept(state()))

        time += 60_000L
        assertTrue(gate.accept(state()))
    }

    private fun state(
        platform: Platform = Platform.KUGOU,
        generation: Long = 1,
    ) = AccountState(
        platform = platform,
        phase = AccountPhase.RETRYABLE_ERROR,
        profile = AccountProfile(
            platform = platform,
            id = "test-id",
            displayName = "Test",
        ),
        error = AccountError.NETWORK,
        generation = generation,
    )
}
