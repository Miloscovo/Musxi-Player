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

    @Test fun bestCandidatesStartAtPlatformMaximumAndDescend() {
        assertEquals(listOf("kugou:high", "kugou:flac", "kugou:320", "kugou:128"),
            PlaybackQualities.candidates("kugou", "kugou:best"))
        assertEquals(listOf("qq:lossless", "qq:high", "qq:standard"),
            PlaybackQualities.candidates("qq", "qq:best"))
        assertEquals(listOf("netease:hires", "netease:lossless", "netease:exhigh",
            "netease:higher", "netease:standard"),
            PlaybackQualities.candidates("netease", "netease:best"))
    }

    @Test fun standardIsTheOnlyCandidateForTheStandardPolicy() {
        assertEquals(listOf("kugou:128"), PlaybackQualities.candidates("kugou", "kugou:128"))
        assertEquals(listOf("qq:standard"), PlaybackQualities.candidates("qq", "qq:standard"))
        assertEquals(listOf("netease:standard"), PlaybackQualities.candidates("netease", "netease:standard"))
    }
}
