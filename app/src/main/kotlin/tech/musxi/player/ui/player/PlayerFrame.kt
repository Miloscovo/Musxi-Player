package tech.musxi.player.ui.player

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.shell.ShellTabRowHeight

/** The mini row's own measures. MiniPlayer lays itself out with them, and the cover travels from them. */
internal val MiniRowHeight = 64.dp
internal val MiniCoverSize = 48.dp
internal val MiniCoverStart = 8.dp

/**
 * Every rectangle the continuous player moves between, in window pixels. The surface grows from
 * [mini] (the mini row in the dock) to [full]; the cover goes from [miniCover] to the page's cover
 * slot, which the page measures and reports through [PlayerProbe]. [fallbackCover] stands in until
 * the page has reported once.
 */
@Immutable
internal class PlayerFrame(
    val width: Float,
    val height: Float,
    navBottom: Float,
    density: Density,
    measuredMini: Rect?,
) {
    val full = Rect(0f, 0f, width, height)

    // Unmeasured, the dock's structure gives the row: 8dp spacer, the tab bar's minimum height and 1dp divider below it.
    val mini: Rect = measuredMini ?: with(density) {
        val bottom = height - navBottom - 8.dp.toPx() - ShellTabRowHeight.toPx() - 1.dp.toPx()
        Rect(20.dp.toPx(), bottom - MiniRowHeight.toPx(), width - 20.dp.toPx(), bottom)
    }

    val miniCover: Rect = with(density) {
        val side = MiniCoverSize.toPx()
        val left = mini.left + MiniCoverStart.toPx()
        val top = mini.top + (mini.height - side) / 2f
        Rect(left, top, left + side, top + side)
    }

    val fallbackCover: Rect = with(density) {
        val side = (width - 64.dp.toPx()).coerceAtLeast(1f)
        val top = height * 0.17f
        Rect(32.dp.toPx(), top, 32.dp.toPx() + side, top + side)
    }

    /** Vertical finger distance that covers p = 0 to 1. */
    val travel: Float = (mini.top - full.top).coerceAtLeast(1f)

    /** Distance between neighbouring covers; a full-width slot keeps the neighbours off screen. */
    val slot: Float = width

    /** The surface at progress [p] (clamped, so a spring overshoot never inverts it). */
    fun surface(p: Float): Rect = PlayerMotion.lerpRect(mini, full, p.coerceIn(0f, 1f))
}

/**
 * Bounds in root pixels without the ancestors' clipping. `boundsInRoot()` cuts the rectangle to the
 * growing surface, so a slot that is still outside it (early in the drag) would report a short or
 * empty rectangle and bend the cover's path; this one always says where the slot is.
 */
internal fun LayoutCoordinates.unclippedBoundsInRoot(): Rect {
    val origin = positionInRoot()
    return Rect(origin.x, origin.y, origin.x + size.width, origin.y + size.height)
}

/** The page's cover slot once it has reported a usable size, else the stand-in. */
internal fun PlayerProbe.coverOr(frame: PlayerFrame): Rect = cover?.takeIf { it.width > 1f } ?: frame.fallbackCover

/** What the page reports back so the surface can draw the cover and keep clear of the progress bar. */
@Stable
class PlayerProbe internal constructor() {
    /** The page's cover slot in window pixels, null until the page has been laid out once. */
    internal var cover by mutableStateOf<Rect?>(null)

    /** The cover tile in the lyrics page's title bar, null until the lyrics page has been laid out. */
    internal var tile by mutableStateOf<Rect?>(null)

    /** The progress bar's touch area; a vertical drag that starts there scrubs instead of collapsing. */
    internal var scrub by mutableStateOf<Rect?>(null)
}

/**
 * Handed to [FullPlayer] when the continuous player draws around it: the page then skips its own
 * background, system-bar icons and cover, and reports where its cover slot and progress bar are.
 */
@Stable
class PlayerSurfaceLink internal constructor(
    val palette: PlayerPalette,
    internal val probe: PlayerProbe,
    internal val pull: NestedScrollConnection,
    /** Task 2: the cover ⇄ lyrics value the surface's cover layer runs; the page follows the same one. */
    internal val lyricsT: androidx.compose.runtime.State<Float>? = null,
)
