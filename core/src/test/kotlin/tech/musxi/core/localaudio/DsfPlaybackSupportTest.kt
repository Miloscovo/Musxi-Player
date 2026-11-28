package tech.musxi.core.localaudio

import org.junit.Assert.*
import org.junit.Test

class DsfPlaybackSupportTest {
    @Test fun decoderRangeDoesNotFollowEveryRecognizedDsdRateOrChannelLayout() {
        for (rate in listOf(2_822_400, 5_644_800, 11_289_600)) {
            assertTrue(DsfPlaybackSupport.supportsFormat(rate, 1))
            assertTrue(DsfPlaybackSupport.supportsFormat(rate, 2))
            assertFalse(DsfPlaybackSupport.supportsFormat(rate, 6))
        }
        for (rate in listOf(44_100, 22_579_200, 45_158_400)) assertFalse(DsfPlaybackSupport.supportsFormat(rate, 2))
        assertFalse(DsfPlaybackSupport.supportsFormat(2_822_400, 0))
    }

    @Test fun candidatesAreOnlyASelectionHintAndDoNotIncludeOtherDsdContainers() {
        assertTrue(DsfPlaybackSupport.isCandidate("Mixed.DSF"))
        assertTrue(DsfPlaybackSupport.isCandidate("unnamed", "AUDIO/X-DSF"))
        assertFalse(DsfPlaybackSupport.isCandidate("album.dff"))
        assertFalse(DsfPlaybackSupport.isCandidate("file.bin", "application/octet-stream"))
    }
}
