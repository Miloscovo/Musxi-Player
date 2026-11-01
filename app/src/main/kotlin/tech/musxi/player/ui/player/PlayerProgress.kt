package tech.musxi.player.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.rememberHaptics

/**
 * The one progress value of the continuous player: [p] = 0 is the mini row, 1 is the full page.
 * A finger drives it with `snapTo` (no spring lag); everything else animates it with
 * [Motion.standard] or, after a release, [Motion.release] carrying the finger's velocity.
 *
 * `showPlayer` in the shell stays the saved "is it open" state; [rememberPlayerProgress] keeps the
 * two in step in both directions.
 */
@Stable
class PlayerProgress internal constructor(initial: Float, private val scope: CoroutineScope) {
    val p = Animatable(initial)

    /** Where the last animation or release is heading; the shell flag is compared against this. */
    internal var targetExpanded = initial >= 0.5f
        private set

    /** Vertical finger distance that covers p = 0 to 1; set from the measured layout. */
    internal var travel = 1f
    internal var haptic: (Haptic) -> Unit = {}
    internal var onExpandedChange: (Boolean) -> Unit = {}

    /** The page is anywhere off the dock, or on its way there. Composition only sees this flip. */
    val busy by derivedStateOf { p.value > 0.001f || p.targetValue > 0.001f }

    /** The full page is what the user sees: system bar icons and the notice follow it. */
    val covering by derivedStateOf { p.value > 0.5f }

    // Vertical pull (expand / collapse).
    private var pullBase = 0f
    private var pullValue = 0f
    private var pullFromExpanded = false
    private var pullCrossed = false

    /** Finger travel since the pull began, in px, down is positive. Lists read it to give a drag back. */
    var pullTotal = 0f
        private set

    fun pullBegin() {
        pullBase = p.value.coerceIn(0f, 1f)
        pullValue = pullBase
        pullTotal = 0f
        pullFromExpanded = pullBase > 0.5f
        pullCrossed = false
    }

    /** [dy] is finger movement in px, positive downwards; progress follows the finger one to one. */
    fun pullBy(dy: Float) {
        pullTotal += dy
        pullValue = PlayerMotion.pullProgress(pullBase, pullTotal, travel)
        val target = pullValue
        scope.launch(start = CoroutineStart.UNDISPATCHED) { p.snapTo(target) }
        // One buzz per drag: the first time it passes the completion point, never again.
        if (!pullCrossed && PlayerMotion.pastThreshold(pullValue, pullFromExpanded)) {
            pullCrossed = true
            haptic(Haptic.THRESHOLD)
        }
    }

    fun pullEnd(velocityY: Float) {
        val expands = PlayerMotion.expandsOnRelease(pullValue, velocityY, travel, pullFromExpanded)
        if (expands != pullFromExpanded && !pullCrossed) haptic(Haptic.THRESHOLD)
        settle(expands, PlayerMotion.progressVelocity(velocityY, travel), release = true)
    }

    fun expand() = settle(true)

    fun collapse() = settle(false)

    /**
     * Runs p to the end it was asked for. Takes over a running animation from where it is, with its
     * velocity, so nothing restarts or jumps. [notify] is false when the shell asked for it.
     */
    internal fun settle(expanded: Boolean, velocity: Float = p.velocity, release: Boolean = false, notify: Boolean = true) {
        targetExpanded = expanded
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val spec: FiniteAnimationSpec<Float> = if (release) Motion.release() else Motion.standard()
            p.animateTo(if (expanded) 1f else 0f, spec, velocity)
        }
        if (notify) onExpandedChange(expanded)
    }
}

/**
 * [expanded] is the shell's saved flag; [onExpandedChange] reports a tap, a release or a back press
 * that changed it. [initialProgress] freezes p for screenshots and skips the follow-the-flag effect.
 */
@Composable
fun rememberPlayerProgress(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    initialProgress: Float? = null,
): PlayerProgress {
    val scope = rememberCoroutineScope()
    val progress = remember { PlayerProgress(initialProgress ?: if (expanded) 1f else 0f, scope) }
    progress.haptic = rememberHaptics()
    progress.onExpandedChange = onExpandedChange
    if (initialProgress == null) {
        LaunchedEffect(expanded) {
            if (progress.targetExpanded != expanded) progress.settle(expanded, notify = false)
        }
    }
    return progress
}
