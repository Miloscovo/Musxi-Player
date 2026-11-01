package tech.musxi.core.account

import org.junit.Assert.*
import org.junit.Test

class AccountMembershipTest {
    private val platform = Platform.QQ
    private val profile = AccountProfile(platform, "fixture-owner", "Listener")

    @Test fun optionalQueryFailureKeepsVerifiedAccountAndReportsUnknown() {
        val accounts = AccountCoordinator()
        val generation = accounts.begin(platform)
        accounts.connected(platform, generation, profile)
        accounts.membership(platform, generation, Membership(MembershipLevel.YES, "会员", 1_900_000_000_000))
        assertTrue(accounts.membership(platform, generation, Membership()))
        val state = accounts.states.value.getValue(platform)
        assertEquals(AccountPhase.CONNECTED, state.phase)
        assertEquals(profile, state.profile)
        assertEquals(generation, state.generation)
        assertEquals(MembershipLevel.UNKNOWN, state.membership.level)
        assertNull(state.error)
    }

    @Test fun lateQueryCannotWriteIntoReplacementAccount() {
        val accounts = AccountCoordinator()
        val previous = accounts.begin(platform)
        accounts.connected(platform, previous, profile)
        accounts.disconnect(platform)
        val current = accounts.begin(platform)
        accounts.connected(platform, current, profile.copy(id = "different-owner"))
        assertFalse(accounts.membership(platform, previous, Membership(MembershipLevel.YES)))
        assertEquals(MembershipLevel.UNKNOWN, accounts.states.value.getValue(platform).membership.level)
        assertTrue(accounts.membership(platform, current, Membership(MembershipLevel.NO)))
        assertEquals(MembershipLevel.NO, accounts.states.value.getValue(platform).membership.level)
    }

    @Test fun logoutAndReverificationClearEntitlementsAndRejectLateResults() {
        val accounts = AccountCoordinator()
        val generation = accounts.begin(platform)
        accounts.connected(platform, generation, profile)
        accounts.membership(platform, generation, Membership(MembershipLevel.YES))
        val restoring = accounts.restore(platform, profile)
        assertFalse(accounts.membership(platform, generation, Membership(MembershipLevel.YES)))
        assertFalse(accounts.membership(platform, restoring, Membership(MembershipLevel.YES)))
        accounts.connected(platform, restoring, profile)
        assertEquals(MembershipLevel.UNKNOWN, accounts.states.value.getValue(platform).membership.level)
        accounts.disconnect(platform)
        assertFalse(accounts.membership(platform, restoring, Membership(MembershipLevel.YES)))
        assertEquals(AccountPhase.DISCONNECTED, accounts.states.value.getValue(platform).phase)
    }
}
