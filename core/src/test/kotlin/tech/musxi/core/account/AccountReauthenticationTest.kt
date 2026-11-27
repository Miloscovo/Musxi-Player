package tech.musxi.core.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountReauthenticationTest {
    private val coordinator = AccountCoordinator()
    private val platform = Platform.NETEASE
    private val profile = AccountProfile(platform, "original-owner", "Original")

    private fun state(target: Platform = platform) = coordinator.states.value.getValue(target)

    private fun connectedState(
        target: Platform = platform,
        identity: AccountProfile = profile,
        membership: Membership = Membership(MembershipLevel.YES, "Annual"),
    ): AccountState {
        val token = coordinator.begin(target)
        assertTrue(coordinator.connected(target, token, identity))
        assertTrue(coordinator.membership(target, token, membership))
        return state(target)
    }

    @Test
    fun connectedFailureRestoresOriginalMembershipWithNewGeneration() {
        val original = connectedState()
        val token = requireNotNull(coordinator.reauthenticate(platform))

        assertTrue(token > original.generation)
        assertEquals(AccountPhase.PREPARING, state().phase)
        assertEquals(original.profile, state().profile)
        assertEquals(original.membership, state().membership)

        assertTrue(coordinator.fail(platform, token, AccountError.TIMEOUT))
        val rolledBack = state()
        assertEquals(AccountPhase.CONNECTED, rolledBack.phase)
        assertEquals(original.profile, rolledBack.profile)
        assertEquals(original.membership, rolledBack.membership)
        assertNull(rolledBack.error)
        assertEquals(token, rolledBack.generation)
        assertFalse(coordinator.isCurrent(platform, token))
    }

    @Test
    fun expiredAccountFailureStaysExpiredAndRejectsLateCompletion() {
        val original = connectedState()
        assertTrue(coordinator.invalidate(platform, original.generation))
        val expired = state()
        assertEquals(AccountPhase.REAUTH_REQUIRED, expired.phase)
        assertEquals(AccountError.EXPIRED, expired.error)

        val token = requireNotNull(coordinator.reauthenticate(platform))
        assertTrue(coordinator.verifying(platform, token))
        assertTrue(coordinator.fail(platform, token, AccountError.NETWORK))

        val rolledBack = state()
        assertEquals(AccountPhase.REAUTH_REQUIRED, rolledBack.phase)
        assertEquals(AccountError.EXPIRED, rolledBack.error)
        assertEquals(profile, rolledBack.profile)
        assertEquals(token, rolledBack.generation)
        assertFalse(coordinator.connected(platform, token, profile.copy(id = "late-owner")))
        assertEquals(rolledBack, state())
    }

    @Test
    fun cancellationRestoresConnectedIdentityButRejectsLateCallbacks() {
        val original = connectedState()
        val token = requireNotNull(coordinator.reauthenticate(platform))
        assertTrue(coordinator.awaiting(platform, token))

        val cancelled = coordinator.cancel(platform)
        assertTrue(cancelled > token)
        val rolledBack = state()
        assertEquals(AccountPhase.CONNECTED, rolledBack.phase)
        assertEquals(original.profile, rolledBack.profile)
        assertEquals(original.membership, rolledBack.membership)
        assertEquals(cancelled, rolledBack.generation)
        assertFalse(coordinator.isCurrent(platform, token))
        assertFalse(coordinator.connected(platform, token, profile.copy(id = "late-owner")))
        assertFalse(coordinator.fail(platform, token, AccountError.REJECTED))
        assertEquals(rolledBack, state())
    }

    @Test
    fun timeoutRestoresOriginalRetryableErrorAndRejectsLateInvalidation() {
        val connected = connectedState()
        val failed = coordinator.begin(platform)
        assertTrue(coordinator.fail(platform, failed, AccountError.NETWORK))
        val retryable = state()
        assertEquals(AccountPhase.RETRYABLE_ERROR, retryable.phase)
        assertEquals(AccountError.NETWORK, retryable.error)
        assertEquals(connected.profile, retryable.profile)
        assertEquals(connected.membership, retryable.membership)

        val token = requireNotNull(coordinator.reauthenticate(platform))
        assertTrue(coordinator.fail(platform, token, AccountError.TIMEOUT))
        val rolledBack = state()
        assertEquals(AccountPhase.RETRYABLE_ERROR, rolledBack.phase)
        assertEquals(AccountError.NETWORK, rolledBack.error)
        assertEquals(profile, rolledBack.profile)
        assertEquals(token, rolledBack.generation)
        assertFalse(coordinator.invalidate(platform, token))
        assertEquals(rolledBack, state())
    }

    @Test
    fun differentUserReauthenticationCommitsNewIdentityAndClearsOldMembership() {
        val original = connectedState()
        assertTrue(coordinator.invalidate(platform, original.generation))
        val token = requireNotNull(coordinator.reauthenticate(platform))
        val replacement = profile.copy(id = "replacement-owner", displayName = "Replacement")

        assertTrue(coordinator.connected(platform, token, replacement))
        val completed = state()
        assertEquals(AccountPhase.CONNECTED, completed.phase)
        assertEquals(replacement, completed.profile)
        assertEquals(Membership(), completed.membership)
        assertEquals(token, completed.generation)
        assertNull(completed.error)
        assertFalse(coordinator.membership(platform, original.generation,
            Membership(MembershipLevel.YES, "Stale")))
        assertEquals(completed, state())
    }

    @Test
    fun reauthenticationOnOnePlatformDoesNotAffectAnotherPlatform() {
        val other = Platform.QQ
        val otherProfile = AccountProfile(other, "qq-owner", "QQ User")
        val originalOther = connectedState(other, otherProfile,
            Membership(MembershipLevel.NO, "Free"))
        val original = connectedState()

        val neteaseToken = requireNotNull(coordinator.reauthenticate(platform))
        val qqToken = requireNotNull(coordinator.reauthenticate(other))
        assertNotEquals(neteaseToken, qqToken)
        assertEquals(originalOther.profile, state(other).profile)
        assertTrue(coordinator.fail(platform, neteaseToken, AccountError.TIMEOUT))
        assertEquals(AccountPhase.PREPARING, state(other).phase)

        val qqReplacement = otherProfile.copy(id = "different-qq-owner")
        assertTrue(coordinator.connected(other, qqToken, qqReplacement))
        assertEquals(AccountPhase.CONNECTED, state(other).phase)
        assertEquals(qqReplacement, state(other).profile)
        assertEquals(AccountPhase.CONNECTED, state(platform).phase)
        assertEquals(original.profile, state(platform).profile)
    }

    @Test
    fun missingIdentityAndActiveOperationsCannotStartReauthentication() {
        assertNull(coordinator.reauthenticate(platform))

        coordinator.restore(platform, null)
        assertNull(coordinator.reauthenticate(platform))
        coordinator.disconnect(platform)

        val connected = connectedState()
        val active = coordinator.begin(platform)
        assertTrue(coordinator.isCurrent(platform, active))
        assertEquals(connected.profile, state().profile)
        assertNull(coordinator.reauthenticate(platform))
        assertEquals(AccountPhase.PREPARING, state().phase)
    }

    @Test
    fun ordinaryAttemptOnAnotherPlatformRemainsIndependent() {
        connectedState()
        val reauthToken = requireNotNull(coordinator.reauthenticate(platform))
        val other = Platform.KUGOU
        val otherToken = coordinator.begin(other)

        assertNotEquals(reauthToken, otherToken)
        assertTrue(coordinator.fail(other, otherToken, AccountError.REJECTED))
        assertTrue(coordinator.isCurrent(platform, reauthToken))
        assertEquals(AccountPhase.PREPARING, state(platform).phase)

        assertTrue(coordinator.connected(platform, reauthToken, profile.copy(id = "replacement-owner")))
        assertEquals(AccountPhase.RETRYABLE_ERROR, state(other).phase)
        assertEquals(AccountError.REJECTED, state(other).error)
    }
}
