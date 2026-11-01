package tech.musxi.player.ui.nav

import androidx.activity.BackEventCompat
import tech.musxi.player.ui.theme.Motion

/** How the top page leaves under the system back gesture (docs/design/interaction-spec.md, section 7). */
enum class BackStyle {
    /** iOS style: the page follows the finger to the right and the page below slides home. */
    FOLLOW,

    /** Android style: the page shrinks, gains rounded corners and drifts toward the gesture. */
    SHRINK,
}

/**
 * Transform of the top page. [translationX] is a share of the page width, [cornerDp] a corner
 * radius, [edgeShadow] the strength (0 to 1) of the faint shadow on the left edge, and [lift] the
 * strength (0 to 1) of the all-round shadow that gives a shrunken card some depth.
 */
data class ForegroundPose(
    val translationX: Float,
    val scale: Float,
    val cornerDp: Float,
    val edgeShadow: Float,
    val lift: Float,
)

/** Transform of the page below. [shiftX] is a share of the page width (zero or negative), [dim] a black alpha. */
data class UnderlayPose(val shiftX: Float, val dim: Float)

/**
 * Pure transition maths. One scalar, the top page's exit `e`, drives both pages: 0 is the page at
 * rest on top, 1 is the page gone. A push animates e from 1 to 0, a pop from 0 to 1, and a back
 * gesture sets it frame by frame and then lets a spring finish it. With one scalar, any hand-over
 * (grab a running push, cancel a gesture, reverse a pop) continues from the current value, so
 * nothing jumps. A push at progress `p` (0 just started, 1 settled) is `e = 1 - p`; see [exitForPush].
 */
object NavPose {
    /** In [BackStyle.SHRINK] the finger drives e from 0 to this; the release slides the page out the rest. */
    const val ShrinkPhase = 0.4f
    const val ShrinkScale = 0.9f
    const val ShrinkCornerDp = 28f

    /** How far a shrunken page drifts toward the gesture, as a share of the width. */
    const val ShrinkDrift = 0.04f

    /** Left-edge shadow of a sliding page: black alpha at its darkest and its width (spec section 7). */
    const val EdgeShadowAlpha = 0.04f
    const val EdgeShadowWidthDp = 12f

    /** Elevation of a shrunken card at full [ForegroundPose.lift]. */
    const val LiftElevationDp = 20f

    /** The exit value of a push at [progress]: 1 when the page has just been placed off-screen, 0 when it has settled. */
    fun exitForPush(progress: Float): Float = 1f - progress.coerceIn(0f, 1f)

    /** The direction a shrunken page drifts for a swipe edge: with the finger, so -1 from the right edge. */
    fun directionFor(swipeEdge: Int): Float = if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f

    /**
     * Pose of the top page at [exit]. [direction] is +1 or -1 and only matters for
     * [BackStyle.SHRINK]. A spring may undershoot below 0, which is read as 0.
     */
    fun foreground(exit: Float, style: BackStyle, direction: Float): ForegroundPose {
        val e = exit.coerceAtLeast(0f)
        return when (style) {
            // The page always leaves to the right, so the page below (shifted left) is never uncovered.
            // At rest it sits at the screen edge, where its shadow would fall off-screen: nothing to draw.
            BackStyle.FOLLOW -> ForegroundPose(e, 1f, 0f, if (e > 0f) 1f else 0f, 0f)
            BackStyle.SHRINK -> {
                val a = (e / ShrinkPhase).coerceIn(0f, 1f)
                val b = ((e - ShrinkPhase) / (1f - ShrinkPhase)).coerceIn(0f, 1f)
                ForegroundPose(
                    translationX = direction * (ShrinkDrift * a + (1f - ShrinkDrift) * b),
                    scale = 1f - (1f - ShrinkScale) * a,
                    cornerDp = ShrinkCornerDp * a,
                    edgeShadow = 0f,
                    lift = a * (1f - b),
                )
            }
        }
    }

    /** Pose of the page below at [exit]: shifted left and dimmed while covered, home and clear once the top page is gone. */
    fun underlay(exit: Float, style: BackStyle): UnderlayPose {
        val e = exit.coerceIn(0f, 1f)
        val home = when (style) {
            BackStyle.FOLLOW -> e
            // The shrunken card exposes the page's edges, so the page below is home by half of phase one.
            BackStyle.SHRINK -> (e / (ShrinkPhase * 0.5f)).coerceIn(0f, 1f)
        }
        return UnderlayPose(
            shiftX = -Motion.PushUnderlayShift * (1f - home),
            dim = Motion.PushUnderlayDim * (1f - e),
        )
    }

    /** Maps the system's gesture [progress] (0 to 1) to the exit value, starting from [start]. */
    fun exitAt(style: BackStyle, start: Float, progress: Float): Float {
        val reach = maxOf(if (style == BackStyle.FOLLOW) 1f else ShrinkPhase, start)
        return start + (reach - start) * progress.coerceIn(0f, 1f)
    }

    /** Exit units per unit of gesture progress; turns progress velocity into exit velocity. */
    fun exitPerProgress(style: BackStyle, start: Float): Float =
        maxOf(if (style == BackStyle.FOLLOW) 1f else ShrinkPhase, start) - start
}
