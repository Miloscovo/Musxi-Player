package tech.musxi.core.account

import org.junit.Assert.*
import org.junit.Test

class AccountInvalidationTest {
    @Test fun completedSessionCanExpireAndOldResponsesCannotExpireNewAccount() {
        val c = AccountCoordinator()
        val old = c.begin(Platform.NETEASE)
        val profile = AccountProfile(Platform.NETEASE, "42", "User")
        assertTrue(c.connected(Platform.NETEASE, old, profile))
        assertFalse(c.fail(Platform.NETEASE, old, AccountError.EXPIRED))
        assertTrue(c.invalidate(Platform.NETEASE, old))
        assertEquals(AccountPhase.REAUTH_REQUIRED, c.states.value.getValue(Platform.NETEASE).phase)
        assertEquals(profile, c.states.value.getValue(Platform.NETEASE).profile)
        val current = c.begin(Platform.NETEASE)
        assertTrue(c.connected(Platform.NETEASE, current, profile))
        assertFalse(c.invalidate(Platform.NETEASE, old))
        assertEquals(AccountPhase.CONNECTED, c.states.value.getValue(Platform.NETEASE).phase)
        assertEquals(AccountPhase.DISCONNECTED, c.states.value.getValue(Platform.QQ).phase)
    }
}
