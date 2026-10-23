package tech.musxi.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.playback.PlaybackRepeatMode
import tech.musxi.playback.PlaybackState
import tech.musxi.playback.PlaybackSuppression

/**
 * The full player has exactly one line to say anything, so the priority between simultaneous
 * problems has to be pinned down: two states must never fight over it.
 */
class FullPlayerUiTest {

    private fun state(
        suppression: PlaybackSuppression = PlaybackSuppression.NONE,
        failure: tech.musxi.core.playback.PlaybackFailure? = null,
        error: String? = null,
        ended: Boolean = false,
        repeat: PlaybackRepeatMode = PlaybackRepeatMode.OFF,
        hasNext: Boolean = true,
        queuePlatform: Platform? = Platform.NETEASE,
        sourcePlatform: Platform? = null,
    ) = PlaybackState(
        title = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        suppression = suppression,
        failure = failure,
        error = error,
        ended = ended,
        repeatMode = repeat,
        hasNext = hasNext,
        queuePlatform = queuePlatform,
        sourcePlatform = sourcePlatform,
        durationMs = 200_000,
        positionMs = 50_000,
    )

    @Test fun noOutputOutranksEverything() {
        val ui = playerUiOf(
            state(
                suppression = PlaybackSuppression.OUTPUT,
                error = "boom",
                ended = true,
            ),
        )
        assertEquals(PlayerStatus.NO_OUTPUT, ui.status)
        assertEquals("没有可用的音频输出 · 点按重试", thirdLineOf(ui))
    }

    @Test fun failureOutranksInterruption() {
        val ui = playerUiOf(state(suppression = PlaybackSuppression.AUDIO_FOCUS, error = "boom"))
        assertEquals(PlayerStatus.FAILED, ui.status)
        assertEquals("暂无可用音源", thirdLineOf(ui))
        assertTrue(isProblemLine(ui))
    }

    @Test fun aSwapInProgressIsNotReportedAsAnError() {
        val ui = playerUiOf(state(error = "boom"), swapSearching = true)
        assertEquals(PlayerStatus.FAILED, ui.status)
        assertEquals("当前音源不可用 · 正在换源", thirdLineOf(ui))
        // The app is already handling it: red would say "you must do something".
        assertFalse(isProblemLine(ui))
    }

    @Test fun interruptionIsNotAFailure() {
        listOf(PlaybackSuppression.AUDIO_FOCUS, PlaybackSuppression.OTHER).forEach { suppression ->
            val ui = playerUiOf(state(suppression = suppression))
            assertEquals(PlayerStatus.INTERRUPTED, ui.status)
            assertEquals("已被其他应用暂停", thirdLineOf(ui))
            // Red would report a temporary handover as something broken.
            assertFalse(isProblemLine(ui))
        }
    }

    @Test fun endedSaysWhetherTheQueueIsOver() {
        val last = playerUiOf(state(ended = true, hasNext = false))
        assertEquals("队列已播完", thirdLineOf(last))

        // Repeat keeps the queue alive, so it is not "the end".
        val repeating = playerUiOf(state(ended = true, hasNext = false, repeat = PlaybackRepeatMode.ALL))
        assertEquals("已播完", thirdLineOf(repeating))

        // More tracks after this one: also not the end of the queue.
        val more = playerUiOf(state(ended = true, hasNext = true))
        assertEquals("已播完", thirdLineOf(more))
    }

    @Test fun normalSpellsThePlatformOutInFull() {
        // "网易云" next to "QQ音乐" reads as two different kinds of thing in one sentence.
        assertEquals("网易云音乐", thirdLineOf(playerUiOf(state())))
        assertEquals("酷狗音乐", thirdLineOf(playerUiOf(state(queuePlatform = Platform.KUGOU))))
    }

    @Test fun aSwapNamesBothPlatformsInOrder() {
        val ui = playerUiOf(state(queuePlatform = Platform.QQ, sourcePlatform = Platform.NETEASE))
        assertEquals("QQ音乐 · 由网易云音乐提供", thirdLineOf(ui))
        assertFalse(isProblemLine(ui))
    }

    @Test fun samePlatformIsNotASwap() {
        val ui = playerUiOf(state(queuePlatform = Platform.QQ, sourcePlatform = Platform.QQ))
        assertEquals("QQ音乐", thirdLineOf(ui))
    }

    @Test fun noQueuePlatformLeavesTheLineEmptyButReserved() {
        val ui = playerUiOf(state(queuePlatform = null))
        assertEquals("", thirdLineOf(ui))
        assertFalse(isProblemLine(ui))
    }

    @Test fun progressIsClamped() {
        assertEquals(0.25f, playerUiOf(state()).progress, 0.001f)
        assertEquals(0f, playerUiOf(PlaybackState(durationMs = 0, positionMs = 9_000)).progress, 0f)
        assertEquals(1f, playerUiOf(PlaybackState(durationMs = 1_000, positionMs = 9_000)).progress, 0f)
    }

    @Test fun bufferingDoesNotClaimItIsPlaying() {
        val ui = playerUiOf(PlaybackState(title = "晴天", playing = true, buffering = true))
        assertFalse(ui.playing)
        assertTrue(ui.buffering)
    }

    @Test fun backdropStaysDarkInDarkModeAndLightInLightMode() {
        val seeds = listOf("晴天", "夜に駆ける", "Wherever You Are", "群青", "zzzz")
        seeds.forEach { seed ->
            val (top, bottom) = playerBackdrop(seed, dark = true)
            assertTrue("$seed dark top too light", top.luminance() < 0.30f)
            assertTrue("$seed dark bottom too light", bottom.luminance() < 0.30f)

            val (lt, lb) = playerBackdrop(seed, dark = false)
            assertTrue("$seed light top too dark", lt.luminance() > 0.80f)
            assertTrue("$seed light bottom too dark", lb.luminance() > 0.80f)
        }
    }

    @Test fun backdropHasAlmostNoSaturationSoItCannotShout() {
        val (top, _) = playerBackdrop("晴天", dark = true)
        val max = maxOf(top.red, top.green, top.blue)
        val min = minOf(top.red, top.green, top.blue)
        assertTrue("backdrop saturation too high", max - min < 0.12f)
    }

    private fun androidx.compose.ui.graphics.Color.luminance(): Float =
        0.2126f * red + 0.7152f * green + 0.0722f * blue
}
