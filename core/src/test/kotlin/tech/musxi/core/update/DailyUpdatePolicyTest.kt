package tech.musxi.core.update

import org.junit.Assert.*
import org.junit.Test

class DailyUpdatePolicyTest {
    @Test fun firstReleaseCheckAndExactDayBoundary() {
        assertTrue(DailyUpdatePolicy.shouldCheck(true, true, null, 1_000))
        assertFalse(DailyUpdatePolicy.shouldCheck(true, true, 1_000, 1_000 + DailyUpdatePolicy.INTERVAL_MILLIS - 1))
        assertTrue(DailyUpdatePolicy.shouldCheck(true, true, 1_000, 1_000 + DailyUpdatePolicy.INTERVAL_MILLIS))
    }
    @Test fun developmentDisabledAndClockRollbackDoNotCheck() {
        assertFalse(DailyUpdatePolicy.shouldCheck(false, true, null, 1_000))
        assertFalse(DailyUpdatePolicy.shouldCheck(true, false, null, 1_000))
        assertFalse(DailyUpdatePolicy.shouldCheck(true, true, 2_000, 1_000))
    }
}
