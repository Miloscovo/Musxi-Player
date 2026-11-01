package tech.musxi.player.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI

/**
 * The app's only motion values (docs/design/interaction-spec.md, section 4). Feature code takes
 * springs and curves from here instead of writing its own numbers.
 *
 * Springs are converted from iOS response times: stiffness = (2π / response)², mass 1.
 */
object Motion {
    /** Stiffness of a spring that settles in roughly [responseSeconds]. */
    fun stiffnessFor(responseSeconds: Float): Float = ((2 * PI / responseSeconds) * (2 * PI / responseSeconds)).toFloat()

    val StandardStiffness = stiffnessFor(0.3f)

    /** Non-gesture push/pop, a sheet landing on a detent, the player expanding or collapsing. */
    fun <T> standard(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = StandardStiffness)

    /** What a released gesture does: finish or snap back, keeping the finger's velocity. */
    fun <T> release(): SpringSpec<T> = spring(dampingRatio = 0.85f, stiffness = StandardStiffness)

    /** Floating menu growing out of its anchor (scale 0.85 → 1 plus fade). */
    fun <T> menu(): SpringSpec<T> = spring(dampingRatio = 0.82f, stiffness = 600f)

    /** Small emphasis such as a heart or a check mark. */
    fun <T> pop(): SpringSpec<T> = spring(dampingRatio = 0.5f, stiffness = 500f)

    /** Colour, fade and text changes. Same curve as the PC client: cubic-bezier(.22, 1, .36, 1). */
    val Curve = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    fun <T> enter(): TweenSpec<T> = tween(durationMillis = 220, easing = Curve)
    fun <T> exit(): TweenSpec<T> = tween(durationMillis = 120, easing = Curve)

    /** Push transition: the page underneath travels this share of the width and is dimmed this much. */
    const val PushUnderlayShift = 0.3f
    const val PushUnderlayDim = 0.1f
}

/** Release decisions shared by every draggable surface (spec sections 7–9). */
object Gestures {
    /**
     * True when a released drag should complete. [progress] is 0–1 toward the target and
     * [velocity] is in progress units per second toward the target (negative means away).
     */
    fun completes(progress: Float, velocity: Float, lookAheadSeconds: Float, threshold: Float): Boolean =
        progress + lookAheadSeconds * velocity > threshold

    fun backCompletes(progress: Float, velocity: Float) = completes(progress, velocity, 0.2f, 0.45f)
    fun playerCompletes(progress: Float, velocity: Float) = completes(progress, velocity, 0.25f, 0.35f)

    /** A sheet closes past this share of its own height, or on a downward fling above [FlingDpPerSecond]. */
    const val SheetDismissFraction = 0.25f
    const val FlingDpPerSecond = 1000f

    /** A cover swipe changes song past this share of the cover width, or on a fling. */
    const val CoverSwipeFraction = 0.3f

    /** Drag resistance past the first or last song. */
    const val RubberBand = 0.5f
}

/**
 * True when the system "remove animations" setting is on. Compose's own animations already jump to
 * their end value then; read this only for motion driven frame by frame (parallax, rubber band,
 * decorative loops) so it can be skipped too.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
