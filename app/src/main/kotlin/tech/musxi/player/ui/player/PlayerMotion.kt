package tech.musxi.player.ui.player

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.util.lerp
import tech.musxi.player.ui.theme.Gestures

/**
 * The continuous player's rules as pure functions (docs/design/interaction-spec.md, sections 4, 6
 * and 8), so every number can be tested without a screen. `p` is the one progress value:
 * 0 = the mini row in the dock, 1 = the full page.
 */
object PlayerMotion {
    /** Mini controls are gone by p = 0.3. */
    fun miniAlpha(p: Float): Float = (1f - p / 0.3f).coerceIn(0f, 1f)

    /** Full-page controls fade in after p = 0.5 and are solid at 0.8. */
    fun fullAlpha(p: Float): Float = ((p - 0.5f) / 0.3f).coerceIn(0f, 1f)

    /** Cover-colour backdrop over the dock material: smoothstep from p = 0.05 to 0.55. */
    fun backdropAlpha(p: Float): Float {
        val t = ((p - 0.05f) / 0.5f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** The surface's own fill is see-through at rest, so the dock card's outline and shadow stay visible. */
    fun fillAlpha(p: Float): Float = (p / 0.08f).coerceIn(0f, 1f)

    /** The big cover is replaced by lyrics: it fades out only on the way to the full page. */
    fun coverAlpha(p: Float, shown: Float): Float = 1f - (1f - shown) * ((p - 0.5f) / 0.5f).coerceIn(0f, 1f)

    fun lerpRect(a: Rect, b: Rect, t: Float): Rect =
        Rect(lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t))

    /** Progress under a dragging finger: one to one, [totalDy] px downwards since the drag began. */
    fun pullProgress(base: Float, totalDy: Float, travel: Float): Float = (base - totalDy / travel).coerceIn(0f, 1f)

    /** How far the drag has gone toward its target; a collapse counts from the top. */
    fun towardProgress(p: Float, fromExpanded: Boolean): Float = if (fromExpanded) 1f - p else p

    /** True once the drag has passed the completion point; the one threshold haptic fires on this. */
    fun pastThreshold(p: Float, fromExpanded: Boolean): Boolean =
        Gestures.playerCompletes(towardProgress(p, fromExpanded), 0f)

    /** Finger velocity (px/s, down is positive) as progress per second. */
    fun progressVelocity(velocityY: Float, travel: Float): Float = -velocityY / travel

    /** True when a released drag ends on the full page, false when it ends on the mini row. */
    fun expandsOnRelease(p: Float, velocityY: Float, travel: Float, fromExpanded: Boolean): Boolean {
        val velocity = progressVelocity(velocityY, travel)
        val toward = if (fromExpanded) -velocity else velocity
        val completes = Gestures.playerCompletes(towardProgress(p, fromExpanded), toward)
        return completes != fromExpanded
    }

    /** Cover offset for a raw finger offset: half speed past the first or last song, none when motion is reduced. */
    fun swipeOffset(raw: Float, hasPrevious: Boolean, hasNext: Boolean, reduced: Boolean): Float {
        val blocked = (raw > 0f && !hasPrevious) || (raw < 0f && !hasNext)
        return when {
            !blocked -> raw
            reduced -> 0f
            else -> raw * Gestures.RubberBand
        }
    }

    /** -1 = previous song, 0 = stay, 1 = next song. [velocity] is px/s along x, [flingPx] the fling threshold in px/s. */
    fun swipeDirection(
        offset: Float,
        velocity: Float,
        coverWidth: Float,
        flingPx: Float,
        hasPrevious: Boolean,
        hasNext: Boolean,
    ): Int = when {
        velocity <= -flingPx && hasNext -> 1
        velocity >= flingPx && hasPrevious -> -1
        offset <= -coverWidth * Gestures.CoverSwipeFraction && hasNext -> 1
        offset >= coverWidth * Gestures.CoverSwipeFraction && hasPrevious -> -1
        else -> 0
    }
}
