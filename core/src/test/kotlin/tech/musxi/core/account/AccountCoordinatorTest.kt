package tech.musxi.core.account

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AccountCoordinatorTest {
    private val coordinator = AccountCoordinator()
    private val platform = Platform.NETEASE
    private val profile = AccountProfile(platform, "private-account-123", "Listener")
    private fun state() = coordinator.states.value.getValue(platform)

    @Test fun initialStatesCoverEveryPlatform() {
        assertEquals(Platform.entries.toSet(), coordinator.states.value.keys)
        coordinator.states.value.forEach { (platform, state) ->
            assertEquals(AccountState(platform, AccountPhase.DISCONNECTED), state)
            assertFalse(coordinator.isCurrent(platform, 0))
        }
    }

    @Test fun loginCompletesOnceAndRejectsAllSubsequentCallbacks() {
        val token = coordinator.begin(platform)
        assertEquals(AccountPhase.PREPARING, state().phase)
        assertTrue(coordinator.awaiting(platform, token))
        assertEquals(AccountPhase.WAITING_CONFIRMATION, state().phase)
        assertTrue(coordinator.verifying(platform, token))
        assertEquals(AccountPhase.VERIFYING, state().phase)
        assertFalse(coordinator.verifying(platform, token))
        assertFalse(coordinator.awaiting(platform, token))
        assertTrue(coordinator.connected(platform, token, profile))
        val completed = state()
        assertEquals(AccountState(platform, AccountPhase.CONNECTED, profile, generation = token), completed)
        assertFalse(coordinator.isCurrent(platform, token))
        assertFalse(coordinator.connected(platform, token, profile.copy(id = "replacement")))
        assertFalse(coordinator.fail(platform, token, AccountError.NETWORK))
        assertFalse(coordinator.awaiting(platform, token))
        assertFalse(coordinator.verifying(platform, token))
        assertEquals(completed, state())
    }

    @Test fun explicitBeginSupersedesEvenAnActiveAttempt() {
        val old = coordinator.begin(platform)
        val current = coordinator.begin(platform)
        assertTrue(current > old)
        assertFalse(coordinator.connected(platform, old, profile))
        assertFalse(coordinator.fail(platform, old, AccountError.EXPIRED))
        assertFalse(coordinator.awaiting(platform, old))
        assertFalse(coordinator.verifying(platform, old))
        assertTrue(coordinator.connected(platform, current, profile))
    }

    @Test fun cancelledAttemptCannotConnectLate() {
        val token = coordinator.begin(platform)
        coordinator.awaiting(platform, token)
        val cancelled = coordinator.cancel(platform)
        assertTrue(cancelled > token)
        assertEquals(AccountState(platform, AccountPhase.DISCONNECTED, generation = cancelled), state())
        assertFalse(coordinator.isCurrent(platform, token))
        assertFalse(coordinator.isCurrent(platform, cancelled))
        assertFalse(coordinator.connected(platform, token, profile))
    }

    @Test fun platformsHaveDistinctTokensAndIndependentState() {
        val first = coordinator.begin(platform)
        val other = coordinator.begin(Platform.QQ)
        assertNotEquals(first, other)
        val snapshot = state()
        assertFalse(coordinator.connected(Platform.QQ, first, profile.copy(platform = Platform.QQ)))
        assertFalse(coordinator.connected(platform, other, profile))
        coordinator.cancel(Platform.QQ)
        assertEquals(snapshot, state())
        assertTrue(coordinator.isCurrent(platform, first))
        assertTrue(coordinator.connected(platform, first, profile))
    }

    @Test fun mismatchedProfileDoesNotConsumeValidAttempt() {
        val token = coordinator.begin(platform)
        assertFalse(coordinator.connected(platform, token, profile.copy(platform = Platform.QQ)))
        assertTrue(coordinator.isCurrent(platform, token))
        assertTrue(coordinator.connected(platform, token, profile))
    }

    @Test fun restoreWaitsForStorageAndVerification() {
        val loading = coordinator.restore(platform, null)
        assertEquals(AccountPhase.REVALIDATING, state().phase)
        assertNull(state().profile)
        assertTrue(coordinator.isCurrent(platform, loading))
        val loaded = coordinator.restore(platform, profile)
        assertTrue(loaded > loading)
        assertEquals(AccountPhase.REVALIDATING, state().phase)
        assertEquals(profile, state().profile)
        assertFalse(coordinator.connected(platform, loading, profile))
        assertTrue(coordinator.connected(platform, loaded, profile))
        assertEquals(AccountPhase.CONNECTED, state().phase)
    }

    @Test fun emptyStorageDisconnectsAndInvalidatesRestore() {
        val token = coordinator.restore(platform, null)
        val disconnected = coordinator.disconnect(platform)
        assertTrue(disconnected > token)
        assertEquals(AccountState(platform, AccountPhase.DISCONNECTED, generation = disconnected), state())
        assertFalse(coordinator.connected(platform, token, profile))
    }

    @Test fun cancelledRestoreRetainsUnverifiedTrust() {
        val token = coordinator.restore(platform, profile)
        coordinator.verifying(platform, token)
        coordinator.cancel(platform)
        assertEquals(profile, state().profile)
        assertEquals(AccountPhase.RETRYABLE_ERROR, state().phase)
        assertNull(state().error)
        assertFalse(coordinator.connected(platform, token, profile))
        val retry = coordinator.restore(platform, profile)
        assertTrue(coordinator.connected(platform, retry, profile))
    }

    @Test fun cancellationOfSupersedingAttemptsRetainsConnectedIdentity() {
        coordinator.connected(platform, coordinator.begin(platform), profile)
        coordinator.begin(platform)
        val latest = coordinator.begin(platform)
        coordinator.cancel(platform)
        assertEquals(AccountPhase.CONNECTED, state().phase)
        assertEquals(profile, state().profile)
        assertFalse(coordinator.connected(platform, latest, profile.copy(id = "another")))
    }

    @Test fun cancellingAttemptAfterRestoreDoesNotElevateTrust() {
        coordinator.restore(platform, profile)
        coordinator.begin(platform)
        coordinator.cancel(platform)
        assertEquals(AccountPhase.RETRYABLE_ERROR, state().phase)
        assertEquals(profile, state().profile)
    }

    @Test fun cancellingStorageLoadLeavesAnInactiveRetryableState() {
        val token = coordinator.restore(platform, null)
        coordinator.cancel(platform)
        assertEquals(AccountPhase.RETRYABLE_ERROR, state().phase)
        assertNull(state().profile)
        assertNull(state().error)
        assertFalse(coordinator.isCurrent(platform, token))
        val retry = coordinator.restore(platform, null)
        assertTrue(coordinator.isCurrent(platform, retry))
        coordinator.disconnect(platform)
        assertEquals(AccountPhase.DISCONNECTED, state().phase)
    }

    @Test fun allFailuresRetainIdentityAndCloseAttempt() {
        AccountError.entries.forEach { error ->
            val token = coordinator.restore(platform, profile)
            assertTrue(coordinator.fail(platform, token, error))
            assertEquals(profile, state().profile)
            assertEquals(error, state().error)
            assertEquals(if (error == AccountError.EXPIRED) AccountPhase.REAUTH_REQUIRED
                else AccountPhase.RETRYABLE_ERROR, state().phase)
            assertFalse(coordinator.isCurrent(platform, token))
            assertFalse(coordinator.connected(platform, token, profile))
            assertFalse(coordinator.fail(platform, token, AccountError.NETWORK))
        }
    }

    @Test fun cancellingNewAttemptPreservesPreviousErrorAndTrust() {
        val token = coordinator.restore(platform, profile)
        coordinator.fail(platform, token, AccountError.EXPIRED)
        coordinator.begin(platform)
        assertNull(state().error)
        coordinator.cancel(platform)
        assertEquals(AccountPhase.REAUTH_REQUIRED, state().phase)
        assertEquals(AccountError.EXPIRED, state().error)
        assertEquals(profile, state().profile)
    }

    @Test fun disconnectClearsIdentityAndInvalidatesActiveAttempt() {
        coordinator.connected(platform, coordinator.begin(platform), profile)
        val token = coordinator.begin(platform)
        coordinator.disconnect(platform)
        assertEquals(AccountPhase.DISCONNECTED, state().phase)
        assertNull(state().profile)
        assertNull(state().error)
        assertFalse(coordinator.connected(platform, token, profile))
        coordinator.cancel(platform)
        assertEquals(AccountPhase.DISCONNECTED, state().phase)
    }

    @Test fun snapshotsCannotBeMutatedAndDoNotChangeAfterPublication() {
        val snapshot = coordinator.states.value
        coordinator.begin(platform)
        assertEquals(AccountPhase.DISCONNECTED, snapshot.getValue(platform).phase)
        try {
            (coordinator.states.value as MutableMap<Platform, AccountState>).clear()
            fail("Published state map must be immutable")
        } catch (_: UnsupportedOperationException) {
            assertEquals(Platform.entries.size, coordinator.states.value.size)
        }
    }

    @Test fun diagnosticStringsDoNotExposeIdentityOrUrls() {
        val sensitive = AccountProfile(platform, "private-account-123", "https://example.test/private")
        val diagnostic = AccountState(platform, AccountPhase.CONNECTED, sensitive).toString()
        assertFalse(diagnostic.contains(sensitive.id))
        assertFalse(diagnostic.contains(sensitive.displayName))
        assertFalse(diagnostic.contains("https://"))
        assertEquals("private-account-123", sensitive.id)
    }

    @Test fun simultaneousCompletionAndCancellationLeaveNoLiveAttempt() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val token = coordinator.begin(platform)
                val start = CountDownLatch(1)
                val connected = executor.submit(Callable {
                    check(start.await(5, TimeUnit.SECONDS))
                    coordinator.connected(platform, token, profile)
                })
                val cancelled = executor.submit(Callable {
                    check(start.await(5, TimeUnit.SECONDS))
                    coordinator.cancel(platform)
                })
                start.countDown()
                val completedFirst = connected.get(5, TimeUnit.SECONDS)
                val cancellationGeneration = cancelled.get(5, TimeUnit.SECONDS)
                assertEquals(cancellationGeneration, state().generation)
                assertFalse(coordinator.isCurrent(platform, token))
                assertFalse(coordinator.connected(platform, token, profile))
                if (completedFirst) assertEquals(AccountPhase.CONNECTED, state().phase)
                coordinator.disconnect(platform)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun concurrentPlatformUpdatesDoNotLoseStates() {
        val executor = Executors.newFixedThreadPool(Platform.entries.size)
        try {
            val tasks = Platform.entries.map { target ->
                executor.submit(Callable {
                    repeat(100) {
                        val token = coordinator.begin(target)
                        check(coordinator.connected(target, token, profile.copy(platform = target)))
                    }
                })
            }
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(Platform.entries.size, coordinator.states.value.size)
            coordinator.states.value.values.forEach {
                assertEquals(AccountPhase.CONNECTED, it.phase)
                assertEquals(it.platform, it.profile?.platform)
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
