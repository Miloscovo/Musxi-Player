package tech.musxi.player.ui.player

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.rememberReducedMotion

/**
 * Task 2: cover ⇄ lyrics as one motion. `t` is 0 on the cover page and 1 on the lyrics page.
 *
 * The big cover shrinks into the tile at the left of the lyrics page's title bar while the lyrics
 * rise from below; going back, the tile grows into the cover and the title settles in under it. The
 * page and the continuous player's cover layer run the same spring from the same flag, so they stay
 * together, and a tap halfway reverses from where the motion is, with its speed (a spring, not a tween).
 */
object LyricsTransition {
    /** Side of the cover tile in the lyrics page's title bar. */
    val TileSize = 52.dp

    /** How far below their place the lyrics start. */
    val Rise = 56.dp

    /** Critically damped: the cover must not overshoot past the tile and bounce back. */
    fun <T> spec(): SpringSpec<T> = Motion.lyricsSwap()

    /** Lyrics stay hidden for the first part, while the cover is still large over where they go. */
    fun lyricsAlpha(t: Float): Float = ((t - 0.3f) / 0.7f).coerceIn(0f, 1f)

    /** The title bar's text and buttons arrive once the tile is nearly in place. */
    fun barAlpha(t: Float): Float = ((t - 0.45f) / 0.55f).coerceIn(0f, 1f)

    /** The cover page's title comes back as the cover grows back. */
    fun titleAlpha(t: Float): Float = ((0.7f - t) / 0.7f).coerceIn(0f, 1f)

    /** The swipe neighbours belong to the big cover only; they are gone early on the way to the tile. */
    fun neighbourAlpha(t: Float): Float = (1f - t * 4f).coerceIn(0f, 1f)

    /** Where the tile is before the page has reported it: the cover's own top-left corner. */
    fun fallbackTile(cover: Rect, sidePx: Float): Rect = Rect(cover.left, cover.top, cover.left + sidePx, cover.top + sidePx)
}

/** The transition's `t`, interruptible; reduced motion jumps. */
@Composable
internal fun rememberLyricsFraction(lyrics: Boolean): State<Float> {
    val reduced = rememberReducedMotion()
    return animateFloatAsState(
        targetValue = if (lyrics) 1f else 0f,
        animationSpec = if (reduced) snap() else LyricsTransition.spec(),
        label = "lyricsFraction",
    )
}
