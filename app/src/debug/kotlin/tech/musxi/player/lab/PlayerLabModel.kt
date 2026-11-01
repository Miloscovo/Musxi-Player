package tech.musxi.player.lab

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.min

/** What the player page shows below the cover: nothing (cover), the lyrics, or the queue (variant A). */
internal enum class PlayerMode { COVER, LYRICS, QUEUE }

/** Player background options (spec section 8). BLUR needs API 31 and is hidden below it. */
internal enum class PlayerBackdrop(val label: String) {
    COLOR("封面取色"), BLUR("模糊封面"), SOLID("实色"),
}

/** Queue variants kept side by side until the product owner picks one. */
internal enum class QueueStyle(val label: String) {
    PAGE("播放页内"), SHEET("玻璃 Sheet"),
}

/** Injectable starting state, so screenshot tests can freeze any moment. Defaults are normal use. */
internal data class PlayerLabInitial(
    val progress: Float = 0f,
    val mode: PlayerMode = PlayerMode.COVER,
    val backdrop: PlayerBackdrop = PlayerBackdrop.COLOR,
    val queueStyle: QueueStyle = QueueStyle.PAGE,
    val songIndex: Int = 0,
    val playing: Boolean = true,
)

/** A rectangle in window pixels. */
internal data class PlayerRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

internal fun lerpRect(a: PlayerRect, b: PlayerRect, t: Float) = PlayerRect(
    lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.width, b.width, t), lerp(a.height, b.height, t),
)

/**
 * Every measured rectangle the player needs, in window pixels. The surface grows from [dock] to
 * [full]; the cover is interpolated between [miniCover] and [bigCover] (or [headerCover] in a mode).
 * Measuring once here, instead of letting each part lay itself out, is what makes the cover a
 * single drawn object that a finger can take over mid-flight.
 */
internal class PlayerLayout(
    private val density: Density,
    val width: Float,
    val height: Float,
    topInset: Float,
    bottomInset: Float,
) {
    private fun px(dp: Float): Float = with(density) { dp.dp.toPx() }

    val full = PlayerRect(0f, 0f, width, height)

    // Docked state: a floating card holding the mini player row above a row of three fake tabs.
    private val dockHeight = px(64f + 52f)
    private val dockMargin = px(12f)
    val dock = PlayerRect(dockMargin, height - bottomInset - px(10f) - dockHeight, width - dockMargin * 2, dockHeight)
    val miniRow = PlayerRect(dock.left, dock.top, dock.width, px(64f))
    val tabsRow = PlayerRect(dock.left, dock.top + px(64f), dock.width, px(52f))
    val miniCover = PlayerRect(dock.left + px(10f), dock.top + px(8f), px(48f), px(48f))
    val miniPlay = PlayerRect(dock.right - px(10f + 36f + 4f + 40f), dock.top + px(12f), px(40f), px(40f))
    val miniNext = PlayerRect(dock.right - px(10f + 36f), dock.top + px(14f), px(36f), px(36f))
    val miniText = PlayerRect(
        miniCover.right + px(12f), dock.top, miniPlay.left - px(8f) - (miniCover.right + px(12f)), px(64f),
    )

    // Full-screen controls are stacked up from the bottom edge.
    private val sideMargin = px(24f)
    private val bottomEdge = height - bottomInset
    val buttonsRow = PlayerRect(sideMargin, bottomEdge - px(16f + 44f), width - sideMargin * 2, px(44f))
    val transportRow = PlayerRect(sideMargin, buttonsRow.top - px(12f + 72f), width - sideMargin * 2, px(72f))
    val progressRow = PlayerRect(sideMargin, transportRow.top - px(8f + 40f), width - sideMargin * 2, px(40f))

    // Cover mode: cover plus title block centred in the space above the progress row.
    private val regionTop = topInset + px(56f)
    private val regionBottom = progressRow.top - px(8f)
    private val titleHeight = px(56f)
    private val titleGap = px(24f)
    private val coverSide = min(width - px(56f), (regionBottom - regionTop - titleHeight - titleGap).coerceAtLeast(px(120f)))
    private val groupTop = (regionTop + (regionBottom - regionTop - (coverSide + titleGap + titleHeight)) / 2f).coerceAtLeast(regionTop)
    val bigCover = PlayerRect((width - coverSide) / 2f, groupTop, coverSide, coverSide)
    val bigTitle = PlayerRect(sideMargin, bigCover.bottom + titleGap, width - sideMargin * 2, titleHeight)

    // Mode (lyrics / queue): the cover shrinks into a title row, the list takes the space below it.
    val headerCover = PlayerRect(px(20f), topInset + px(52f), px(48f), px(48f))
    val headerTitle = PlayerRect(headerCover.right + px(12f), headerCover.top, width - headerCover.right - px(32f), px(48f))
    val modeArea = PlayerRect(0f, headerCover.bottom + px(12f), width, progressRow.top - px(8f) - (headerCover.bottom + px(12f)))

    val handle = PlayerRect((width - px(36f)) / 2f, topInset + px(10f), px(36f), px(5f))
    val options = PlayerRect(width - px(20f + 64f), topInset + px(6f), px(64f), px(32f))

    /** Vertical drag distance that covers p = 0 to 1. */
    val travel: Float = (dock.top - full.top).coerceAtLeast(1f)

    /** Horizontal distance between neighbouring covers; a full screen keeps the neighbours off-screen. */
    val slot: Float = width
}
