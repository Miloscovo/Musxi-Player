package tech.musxi.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.playback.FailureReason
import tech.musxi.player.ui.model.PlayerStatus as ModelPlayerStatus

/** Every reason needs its own sentence; two reasons sharing one would hide the difference. */
class FailureTextTest {

    @Test fun everyReasonHasANonBlankText() {
        for (reason in FailureReason.values()) {
            assertTrue("$reason", failureReasonText(reason).isNotBlank())
        }
    }

    @Test fun noTwoReasonsShareATextWithEachOther() {
        val texts = FailureReason.values().map(::failureReasonText)
        assertEquals(texts.toString(), FailureReason.values().size, texts.toSet().size)
    }

    @Test fun commonReasonsKeepTheirAgreedWording() {
        assertEquals("需要会员", failureReasonText(FailureReason.MEMBERSHIP_REQUIRED))
        assertEquals("暂无版权", failureReasonText(FailureReason.COPYRIGHT_RESTRICTED))
        assertEquals("只能试听", failureReasonText(FailureReason.PREVIEW_ONLY))
        assertEquals("账号无权播放", failureReasonText(FailureReason.PERMISSION_RESTRICTED))
        assertEquals("原因未知", failureReasonText(FailureReason.UNKNOWN))
    }

    @Test fun aReasonIsAppendedAfterAMiddleDot() {
        assertEquals("播放失败 · 需要会员", playbackFailedText(FailureReason.MEMBERSHIP_REQUIRED))
        assertEquals(
            "没找到能播的版本 · 网络连接失败",
            withFailureReason("没找到能播的版本", FailureReason.NETWORK),
        )
    }

    @Test fun noReasonLeavesTheBaseAlone() {
        assertEquals("播放失败", playbackFailedText(null))
        assertEquals("暂无可用音源", withFailureReason("暂无可用音源", null))
    }

    @Test fun theModelStatusFollowsTheSameRule() {
        assertEquals("播放失败", ModelPlayerStatus.FAILED.textFor(null))
        assertEquals("播放失败 · 暂无版权", ModelPlayerStatus.FAILED.textFor(FailureReason.COPYRIGHT_RESTRICTED))
        // Other statuses are not failures: a stray reason must not leak into them.
        assertEquals(
            ModelPlayerStatus.INTERRUPTED.text,
            ModelPlayerStatus.INTERRUPTED.textFor(FailureReason.NETWORK),
        )
    }
}
