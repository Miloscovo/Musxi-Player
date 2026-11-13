package tech.musxi.player.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
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

    /** A hard fling (at or above [Gestures.FlingDpPerSecond]) settling: a touch more give than [release]. */
    fun <T> releaseFast(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = StandardStiffness)

    /** A selection indicator sliding to a new segment or tab (iOS segmented control, response 0.25s). */
    fun <T> indicator(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = stiffnessFor(0.25f))

    /** Floating menu growing out of its anchor (scale 0.85 → 1 plus fade). */
    fun <T> menu(): SpringSpec<T> = spring(dampingRatio = 0.82f, stiffness = 600f)

    /**
     * Floating menu shrinking back toward its anchor. Same spring family as [menu] but critically
     * damped and quicker (response 0.15s), so the card leaves about as fast as [exit] fades it.
     */
    fun <T> menuDismiss(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = stiffnessFor(0.15f))

    /** Small emphasis such as a heart or a check mark. */
    fun <T> pop(): SpringSpec<T> = spring(dampingRatio = 0.5f, stiffness = 500f)

    /**
     * Cover ⇄ lyrics on the player (task 2): the cover flies into the lyrics bar's tile. Critically
     * damped so it never overshoots the tile, and a little slower than [standard] (response 0.42s)
     * because the cover travels most of the screen.
     */
    fun <T> lyricsSwap(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = stiffnessFor(0.42f))

    /** Colour, fade and text changes. Same curve as the PC client: cubic-bezier(.22, 1, .36, 1). */
    val Curve = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    fun <T> enter(): TweenSpec<T> = tween(durationMillis = 220, easing = Curve)
    fun <T> exit(): TweenSpec<T> = tween(durationMillis = 120, easing = Curve)

    /** A progress bar easing between the steps a backend reports (a download's 256 KB chunks). */
    fun <T> progress(): TweenSpec<T> = tween(durationMillis = 250, easing = Curve)

    /**
     * The favourite heart's own small effects. Its colour and sync dot use [enter]; the settle after
     * the like pop uses [pop]; these are the parts no shared token covers.
     */
    object Heart {
        /** The heart shrinking while held and growing back on release: quick, no overshoot. */
        fun <T> press(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 900f)

        /** Liking: the quick swell before [pop] settles it. */
        fun <T> popUp(): TweenSpec<T> = tween(durationMillis = 90)

        /** Liking: the ring and six sparks travelling out and fading, at constant speed. */
        fun <T> burst(): TweenSpec<T> = tween(durationMillis = 380, easing = LinearEasing)

        /** How far the ring and sparks have travelled for a given share of [burst]: fast, then slowing. */
        val BurstTravel: Easing = LinearOutSlowInEasing

        /** Un-liking: the slight dip, then back with [exit]. */
        fun <T> dip(): TweenSpec<T> = tween(durationMillis = 80)

        /** A refused request: each leg of the sideways shake. */
        fun <T> shakeStep(): TweenSpec<T> = tween(durationMillis = 60, easing = LinearEasing)
    }

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

    /**
     * iOS rubber band: how far a surface shows when the finger has pulled [excess] px past its
     * limit. Grows with the pull but never reaches [extent], so it gets heavier the further it goes.
     * Callers keep the finger's raw offset and draw this, so pulling back never jumps.
     */
    fun rubberBand(excess: Float, extent: Float): Float {
        if (excess <= 0f || extent <= 0f) return 0f
        return extent * (1f - 1f / (excess / extent * RubberBandCoefficient + 1f))
    }
    const val RubberBandCoefficient = 0.55f

    /** A sheet may be pulled past FULL by at most this, through [rubberBand]. */
    const val SheetOverdragDp = 100f

    /**
     * Where a released sheet would coast to: position + velocity × this. Releases slower than
     * [MinProjectionDpPerSecond] count as still, so a slow lift lands where the finger left it.
     */
    const val SheetProjectionSeconds = 0.099f
    const val MinProjectionDpPerSecond = 250f

    /**
     * A sheet release at least this fast that would only return the sheet to the detent it has just
     * left steps on to the next detent in its direction (a short flick up from MEDIUM opens FULL).
     * Between [MinProjectionDpPerSecond] and [FlingDpPerSecond]: with the [SheetProjectionSeconds]
     * projection alone a 500dp/s flick moves the landing point by only ~50dp and would fall back.
     */
    const val SheetStepDpPerSecond = 400f
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
