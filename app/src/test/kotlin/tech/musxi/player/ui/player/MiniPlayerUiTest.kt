package tech.musxi.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.playback.PlaybackState
import tech.musxi.playback.PlaybackSuppression

/**
 * The subtitle is one line, and the rule is "reason beats name".
 *
 * The first version put the artist first, which on a 64dp bar ellipsised the reason away:
 * "周杰伦 · 被其他音频暂…". These tests pin the corrected order down.
 */
class MiniPlayerUiTest {

    private fun state(
        artist: String = "周杰伦",
        suppression: PlaybackSuppression = PlaybackSuppression.NONE,
        buffering: Boolean = false,
        ended: Boolean = false,
        failure: tech.musxi.core.playback.PlaybackFailure? = null,
        error: String? = null,
        queuePlatform: Platform? = null,
        sourcePlatform: Platform? = null,
    ) = PlaybackState(
        artist = artist,
        suppression = suppression,
        buffering = buffering,
        ended = ended,
        failure = failure,
        error = error,
        queuePlatform = queuePlatform,
        sourcePlatform = sourcePlatform,
    )

    @Test fun aReasonReplacesTheArtistEntirely() {
        // The title line already names the track, so the subtitle can spend its width on "why".
        assertEquals(
            "被其他音频暂时打断",
            subtitleOf(state(suppression = PlaybackSuppression.AUDIO_FOCUS)),
        )
        assertEquals("没有可用的音频输出", subtitleOf(state(suppression = PlaybackSuppression.OUTPUT)))
        assertEquals("播放暂时被暂停", subtitleOf(state(suppression = PlaybackSuppression.OTHER)))
        assertEquals("缓冲中", subtitleOf(state(buffering = true)))
        assertEquals("播放失败", subtitleOf(state(error = "boom")))
        assertEquals("已播完", subtitleOf(state(ended = true)))
    }

    @Test fun aLongArtistCannotPushTheReasonOut() {
        val longName = "一个非常非常长的乐队名字用来把副标题挤爆"
        assertEquals(
            "被其他音频暂时打断",
            subtitleOf(state(artist = longName, suppression = PlaybackSuppression.AUDIO_FOCUS)),
        )
    }

    @Test fun interferenceOutranksOtherReasons() {
        assertEquals(
            "被其他音频暂时打断",
            subtitleOf(state(suppression = PlaybackSuppression.AUDIO_FOCUS, ended = true, buffering = true)),
        )
        assertEquals(
            "播放暂时被暂停",
            subtitleOf(state(suppression = PlaybackSuppression.OTHER, ended = true)),
        )
    }

    @Test fun plainPlayingShowsTheArtist() {
        assertEquals("周杰伦", subtitleOf(state()))
        assertEquals("未知歌手", subtitleOf(state(artist = "")))
    }

    @Test fun aSwappedSourceIsNamedAfterTheArtist() {
        val swapped = state(queuePlatform = Platform.QQ, sourcePlatform = Platform.NETEASE)
        assertEquals("周杰伦 · 网易云音源", subtitleOf(swapped))
        assertEquals(Platform.NETEASE, swappedSourceOf(swapped))
    }

    @Test fun samePlatformNeedsNoNote() {
        val same = state(queuePlatform = Platform.QQ, sourcePlatform = Platform.QQ)
        assertNull(swappedSourceOf(same))
        assertEquals("周杰伦", subtitleOf(same))
        // A queue with no known source platform must not claim a swap.
        assertNull(swappedSourceOf(state(queuePlatform = Platform.QQ, sourcePlatform = null)))
    }

    @Test fun statusWinsOverTheSourceNote() {
        val state = state(
            suppression = PlaybackSuppression.AUDIO_FOCUS,
            queuePlatform = Platform.QQ,
            sourcePlatform = Platform.NETEASE,
        )
        assertEquals("被其他音频暂时打断", subtitleOf(state))
        // The dot still marks the streaming platform even while the reason owns the text.
        assertEquals(Platform.NETEASE, swappedSourceOf(state))
    }

    @Test fun progressIsClampedAndSafeWithoutADuration() {
        assertEquals(0f, miniPlayerUi(PlaybackState(durationMs = 0, positionMs = 5_000)).progress, 0f)
        assertEquals(0.5f, miniPlayerUi(PlaybackState(durationMs = 200_000, positionMs = 100_000)).progress, 0.001f)
        assertEquals(1f, miniPlayerUi(PlaybackState(durationMs = 100_000, positionMs = 900_000)).progress, 0f)
        assertEquals(0f, miniPlayerUi(PlaybackState(durationMs = 100_000, positionMs = -50_000)).progress, 0f)
    }

    @Test fun barHidesWhenNothingIsLoaded() {
        assertFalse(miniPlayerUi(PlaybackState()).visible)
        assertTrue(miniPlayerUi(PlaybackState(mediaId = "qq:1")).visible)
        assertTrue(miniPlayerUi(PlaybackState(title = "晴天")).visible)
    }

    @Test fun bufferingShowsAsNotPlayingSoTheButtonReadsPlay() {
        val buffering = miniPlayerUi(PlaybackState(title = "晴天", playing = true, buffering = true))
        assertFalse(buffering.playing)
        assertTrue(buffering.buffering)

        val playing = miniPlayerUi(PlaybackState(title = "晴天", playing = true, buffering = false))
        assertTrue(playing.playing)
    }
}
