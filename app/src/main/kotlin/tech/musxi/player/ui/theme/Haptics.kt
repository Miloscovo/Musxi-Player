package tech.musxi.player.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * The app's only haptic vocabulary (docs/design/interaction-spec.md, section 5). One user action
 * plays at most one haptic; passive changes (a source swap finishing, the next song starting) play
 * none. The system haptics setting is honoured by the platform.
 */
enum class Haptic {
    /** Segment, detent or play-mode change. */
    TICK,
    LIKE,
    UNLIKE,
    LONG_PRESS,
    /** A drag crossed its completion threshold (player, sheet, back). */
    THRESHOLD,
    /** A drag was released and settled. */
    SETTLE,
    /** An action succeeded (added to a playlist or the queue). */
    CONFIRM,
    /** An action was refused (read-only account, queue on another platform). */
    REJECT,
}

internal fun Haptic.feedbackType(): HapticFeedbackType = when (this) {
    Haptic.TICK -> HapticFeedbackType.SegmentTick
    Haptic.LIKE -> HapticFeedbackType.ToggleOn
    Haptic.UNLIKE -> HapticFeedbackType.ToggleOff
    Haptic.LONG_PRESS -> HapticFeedbackType.LongPress
    Haptic.THRESHOLD -> HapticFeedbackType.GestureThresholdActivate
    Haptic.SETTLE -> HapticFeedbackType.GestureEnd
    Haptic.CONFIRM -> HapticFeedbackType.Confirm
    Haptic.REJECT -> HapticFeedbackType.Reject
}

/** Returns a function that plays one [Haptic]. */
@Composable
fun rememberHaptics(): (Haptic) -> Unit {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { { haptic -> feedback.performHapticFeedback(haptic.feedbackType()) } }
}
