package tech.musxi.player.ui.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

/** Lays the node out at [rect] (window px) with that exact size; the node itself reports the parent's size. */
internal fun Modifier.atRect(rect: () -> Rect): Modifier = layout { measurable, constraints ->
    val r = rect()
    val placeable = measurable.measure(
        Constraints.fixed(r.width.roundToInt().coerceAtLeast(0), r.height.roundToInt().coerceAtLeast(0)),
    )
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
}

/** A window-sized canvas shifted so its coordinates stay window coordinates while the surface is smaller. */
internal fun Modifier.canvasAt(width: Int, height: Int, rect: () -> Rect): Modifier = layout { measurable, constraints ->
    val r = rect()
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-r.left.roundToInt(), -r.top.roundToInt()) }
}

/** Fixed-rectangle placement inside the window-sized canvas. */
internal fun Modifier.placeAt(r: Rect): Modifier = layout { measurable, _ ->
    val placeable = measurable.measure(
        Constraints.fixed(r.width.roundToInt().coerceAtLeast(0), r.height.roundToInt().coerceAtLeast(0)),
    )
    layout(r.right.roundToInt(), r.bottom.roundToInt()) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
}

/** Card corners while docked, square corners at full screen; the radius follows the current height. */
internal class SurfaceShape(
    private val radius: Float,
    private val miniHeight: Float,
    private val fullHeight: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val t = ((size.height - miniHeight) / (fullHeight - miniHeight).coerceAtLeast(1f)).coerceIn(0f, 1f)
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius * (1f - t))))
    }
}

/**
 * The vertical half of the surface drag: progress follows the finger. A drag that starts on the
 * progress bar is left to the bar, so scrubbing never collapses the page.
 */
internal class PlayerPull(
    private val progress: PlayerProgress,
    private val probe: PlayerProbe,
) : AxisDragHandler {
    /** Window position of the surface's top-left corner, to turn the touch point into window pixels. */
    var origin: () -> Offset = { Offset.Zero }

    override fun allows(vertical: Boolean, down: Offset): Boolean {
        if (!vertical) return false
        val scrub = probe.scrub ?: return true
        return !(progress.p.value > 0.9f && scrub.contains(down + origin()))
    }

    override fun onLock(vertical: Boolean) = progress.pullBegin()
    override fun onDelta(vertical: Boolean, delta: Float) = progress.pullBy(delta)
    override fun onEnd(vertical: Boolean, velocity: Float) = progress.pullEnd(velocity)
    override fun onCancel(vertical: Boolean) = progress.pullEnd(0f)
}
