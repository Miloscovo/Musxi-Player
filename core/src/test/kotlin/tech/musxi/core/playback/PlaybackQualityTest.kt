package tech.musxi.core.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackQualityTest {
    @Test fun preferenceCandidatesDescendWithoutCrossingPlatformOrExceedingPreference() {
        assertEquals(listOf("qq:high", "qq:standard"), PlaybackQualities.candidates("qq", "qq:high"))
        assertEquals(listOf("netease:standard"), PlaybackQualities.candidates("netease", "qq:lossless"))
        assertEquals(listOf("kugou:128"), PlaybackQualities.candidates("kugou", null))
        assertEquals(listOf("netease:lossless", "netease:exhigh", "netease:higher", "netease:standard"),
            PlaybackQualities.candidates("netease", "netease:lossless"))
    }
}
