package tech.musxi.player.lab

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.components.MenuItem
import tech.musxi.player.ui.theme.Motion
import kotlin.math.roundToInt

/** One row of a lab menu. [icon] is optional, as in the product's MenuItem. */
internal data class LabMenuItem(
    val label: String,
    val icon: ImageVector?,
    val onClick: () -> Unit,
)

/** Where the card goes in the host, and which of its corners grows first. */
internal data class LabMenuPlacement(val offset: IntOffset, val origin: TransformOrigin)

/**
 * Same-window version of the product's AnchoredMenu. The product menu uses a Popup, which is a
 * separate window and cannot blur the page, so the lab draws the card as an overlay inside the
 * same window. Place it as the last child of a [tech.musxi.player.lab.LabGlassHost] overlay.
 *
 * [anchor] is the anchor row's bounds in root coordinates. The card sits under the anchor, or above
 * it when there is no room below, and its left or right edge lines up with the anchor's. A
 * full-size scrim eats taps outside the card while the menu is open, so they do not reach the
 * list underneath. Back closes the menu.
 */
@Composable
internal fun LabAnchoredMenu(
    open: Boolean,
    anchor: Rect?,
    items: List<LabMenuItem>,
    onDismiss: () -> Unit,
    /** Start fully open with no animation. Used by screenshot tests only. */
    startSettled: Boolean = false,
) {
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.roundToPx() }
    val margin = with(density) { 12.dp.roundToPx() }
    val shape = RoundedCornerShape(16.dp)

    // 0 = closed, 1 = fully open. Opening uses the menu spring; closing fades on the exit curve.
    val progress = remember { Animatable(if (startSettled && open) 1f else 0f) }
    LaunchedEffect(open) {
        if (open) progress.animateTo(1f, Motion.menu<Float>())
        else progress.animateTo(0f, Motion.exit<Float>())
    }

    // The host's top-left in root coordinates; the anchor rect is moved into host space with it.
    // Both are plain values in state, so a moved row or host re-places the card.
    var hostOrigin by remember { mutableStateOf<Offset?>(null) }
    // Corner the card grows from, decided by the last placement.
    var origin by remember { mutableStateOf(TransformOrigin.Center) }
    // Scrolls the card's rows only when the card is capped to the room on its side.
    val scroll = rememberScrollState()

    BackHandler(enabled = open) { onDismiss() }

    val shown = open || progress.value > 0.001f
    // Always composed, so the host's coordinates are known before the first long press. Captured
    // on the menu's own Layout they arrived a frame late, and the first open laid out empty.
    // No pointer modifiers: while the menu is closed this box lets every touch through.
    Box(Modifier.fillMaxSize().onGloballyPositioned { hostOrigin = it.positionInRoot() }) {
        if (shown && anchor != null) {
            if (open) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
                )
            }
            Layout(
                content = {
                    Column(
                        Modifier
                            .width(IntrinsicSize.Max)
                            .widthIn(min = 168.dp, max = 260.dp)
                            .graphicsLayer {
                                val p = progress.value
                                val s = 0.85f + 0.15f * p
                                scaleX = s
                                scaleY = s
                                alpha = p.coerceIn(0f, 1f)
                                transformOrigin = origin
                            }
                            .labGlass(shape, LabGlassStyle.THIN)
                            .verticalScroll(scroll)
                            .padding(vertical = 4.dp),
                    ) {
                        items.forEach { item ->
                            MenuItem(
                                label = item.label,
                                onClick = {
                                    item.onClick()
                                },
                                icon = item.icon,
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) { measurables, constraints ->
                val hostSize = IntSize(constraints.maxWidth, constraints.maxHeight)
                val host = hostOrigin
                // Nothing to anchor to until the host has been placed once.
                if (host == null || anchor == null) {
                    return@Layout layout(constraints.maxWidth, constraints.maxHeight) {}
                }
                // Row bounds in host space, then lifted: the menu keeps clear of the lifted edges.
                val rowRect = anchor.translate(-host.x, -host.y)
                val lifted = rowRect.liftedBy(LabLiftScale)
                val local = IntRect(
                    left = lifted.left.roundToInt(),
                    top = lifted.top.roundToInt(),
                    right = lifted.right.roundToInt(),
                    bottom = lifted.bottom.roundToInt(),
                )
                // Capped to the larger side of the row, so the card fits whole on one side.
                val card = measurables.first().measure(
                    Constraints(
                        maxWidth = (constraints.maxWidth - 2 * margin).coerceAtLeast(0),
                        maxHeight = labMenuMaxHeight(local, hostSize, gap, margin),
                    ),
                )
                val placement = labMenuPlacement(
                    anchor = local,
                    host = hostSize,
                    card = IntSize(card.width, card.height),
                    gap = gap,
                    margin = margin,
                )
                origin = placement.origin
                layout(constraints.maxWidth, constraints.maxHeight) {
                    card.place(placement.offset.x, placement.offset.y)
                }
            }
        }
    }
}

/** Scale of the lifted row (spec section 6). The menu anchors to the lifted bounds. */
internal const val LabLiftScale = 1.02f

/** [this] grown about its centre by [scale]. */
private fun Rect.liftedBy(scale: Float): Rect {
    val dx = width * (scale - 1f) / 2f
    val dy = height * (scale - 1f) / 2f
    return Rect(left - dx, top - dy, right + dx, bottom + dy)
}

/**
 * Room below and above the anchor, in pixels. Below stops [margin] short of the host's bottom edge,
 * above stops [margin] short of its top edge; both keep [gap] clear of the anchor.
 */
internal fun labMenuSpace(anchor: IntRect, host: IntSize, gap: Int, margin: Int): Pair<Int, Int> {
    val below = (host.height - margin - (anchor.bottom + gap)).coerceAtLeast(0)
    val above = (anchor.top - gap - margin).coerceAtLeast(0)
    return below to above
}

/** Height cap for the card: the larger of the two sides. */
internal fun labMenuMaxHeight(anchor: IntRect, host: IntSize, gap: Int, margin: Int): Int {
    val (below, above) = labMenuSpace(anchor, host, gap, margin)
    return maxOf(below, above)
}

/**
 * Card position inside the host, in pixels. The card goes below the anchor when it fits there,
 * otherwise above. The card was measured capped to the larger side, so it always fits the side
 * chosen here and never covers the anchor. Horizontally the leading edge lines up with the
 * anchor's, or the trailing edge when the anchor is in the right half, clamped to [margin].
 */
internal fun labMenuPlacement(anchor: IntRect, host: IntSize, card: IntSize, gap: Int, margin: Int): LabMenuPlacement {
    val (spaceBelow, _) = labMenuSpace(anchor, host, gap, margin)
    val below = card.height <= spaceBelow

    val anchorCentreX = (anchor.left + anchor.right) / 2
    val alignLeft = anchorCentreX < host.width / 2
    val wantedX = if (alignLeft) anchor.left else anchor.right - card.width
    val x = wantedX.coerceIn(margin, (host.width - margin - card.width).coerceAtLeast(margin))
    val y = if (below) anchor.bottom + gap else anchor.top - gap - card.height

    // The corner nearest the anchor: left or right edge of the card, top when below the anchor.
    val originX = if (anchorCentreX <= x + card.width / 2) 0f else 1f
    val originY = if (below) 0f else 1f
    return LabMenuPlacement(IntOffset(x, y), TransformOrigin(originX, originY))
}
