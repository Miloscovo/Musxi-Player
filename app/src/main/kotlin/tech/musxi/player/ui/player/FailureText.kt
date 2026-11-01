package tech.musxi.player.ui.player

import tech.musxi.core.playback.FailureReason

/**
 * One short phrase per reason. The wording states what the platform told us and nothing more:
 * where there is no evidence the phrase says so instead of guessing at a cause. The `when` has no
 * `else` on purpose, so a new [FailureReason] cannot ship without a sentence.
 */
fun failureReasonText(reason: FailureReason): String = when (reason) {
    FailureReason.MEMBERSHIP_REQUIRED -> "需要会员"
    FailureReason.COPYRIGHT_RESTRICTED -> "暂无版权"
    FailureReason.PREVIEW_ONLY -> "只能试听"
    FailureReason.PERMISSION_RESTRICTED -> "账号无权播放"
    FailureReason.NETWORK -> "网络连接失败"
    FailureReason.TIMEOUT -> "响应超时"
    FailureReason.AUTH_REQUIRED -> "登录已失效"
    FailureReason.PLATFORM_UNAVAILABLE -> "暂时不可播"
    FailureReason.RATE_LIMIT -> "请求太频繁"
    FailureReason.PROTOCOL -> "平台返回异常"
    FailureReason.BACKGROUND_RESTRICTED -> "系统限制了后台播放"
    FailureReason.UNKNOWN -> "原因未知"
}

/**
 * Appends the reason to a status line, `base · reason`. Every surface joins them the same way, and
 * a missing reason leaves [base] untouched rather than printing a placeholder.
 */
fun withFailureReason(base: String, reason: FailureReason?): String =
    if (reason == null) base else "$base · ${failureReasonText(reason)}"

/** "播放失败 · 需要会员", or just "播放失败" while the reason is not known. */
fun playbackFailedText(reason: FailureReason?): String = withFailureReason("播放失败", reason)
