package tech.musxi.accounts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform

class MembershipRefreshGateTest {
    @Test fun foregroundRefreshResumesAfterOneMinute() {
        var time = 1_000L
        val gate = MembershipRefreshGate { time }
        assertTrue(gate.accept(Platform.KUGOU, 7, false))
        time += 59_999
        assertFalse(gate.accept(Platform.KUGOU, 7, false))
        time++
        assertTrue(gate.accept(Platform.KUGOU, 7, false))
    }

    @Test fun reconnectAndAnotherPlatformDoNotInheritThePreviousThrottle() {
        val gate = MembershipRefreshGate { 1_000L }
        assertTrue(gate.accept(Platform.KUGOU, 7, false))
        assertTrue(gate.accept(Platform.KUGOU, 8, false))
        assertTrue(gate.accept(Platform.QQ, 7, false))
        assertFalse(gate.accept(Platform.KUGOU, 8, false))
    }

    @Test fun explicitRefreshIsAvailableImmediatelyAndStillThrottlesAutomaticRefresh() {
        val gate = MembershipRefreshGate { 1_000L }
        assertTrue(gate.accept(Platform.KUGOU, 7, false))
        assertTrue(gate.accept(Platform.KUGOU, 7, true))
        assertFalse(gate.accept(Platform.KUGOU, 7, false))
    }
}
