package tech.musxi.core.playback

import org.junit.Assert.*
import org.junit.Test

class FailureReasonTest {
    @Test fun explicitEntitlementsAndUnknownStayDistinct() {
        assertEquals(FailureReason.PREVIEW_ONLY, FailureReason.fromCategory("preview"))
        assertEquals(FailureReason.MEMBERSHIP_REQUIRED, FailureReason.fromCategory("membership"))
        assertEquals(FailureReason.COPYRIGHT_RESTRICTED, FailureReason.fromCategory("copyright"))
        assertEquals(FailureReason.PERMISSION_RESTRICTED, FailureReason.fromCategory("permission"))
        assertEquals(FailureReason.UNKNOWN, FailureReason.fromCategory("vip_maybe"))
        assertEquals(FailureReason.NETWORK, FailureReason.fromCategory("network"))
    }
}
