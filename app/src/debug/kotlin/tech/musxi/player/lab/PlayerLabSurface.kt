package tech.musxi.player.lab

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The surface grows from the dock to the whole window with p (clamped, so a bounce never inverts it). */
internal fun PlayerLabState.surfaceRect(): PlayerRect = lerpRect(layout.dock, layout.full, p.value.coerceIn(0f, 1f))

/** Lays the node out at [rect] (window px) with that exact size; the node itself reports the parent's size. */
internal fun Modifier.atRect(rect: () -> PlayerRect): Modifier = layout { measurable, constraints ->
    val r = rect()
    val placeable = measurable.measure(
        Constraints.fixed(r.width.roundToInt().coerceAtLeast(0), r.height.roundToInt().coerceAtLeast(0)),
    )
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
}

/** Fixed-rectangle placement inside the window-sized canvas. */
internal fun Modifier.placeAt(r: PlayerRect): Modifier = layout { measurable, _ ->
    val placeable = measurable.measure(
        Constraints.fixed(r.width.roundToInt().coerceAtLeast(0), r.height.roundToInt().coerceAtLeast(0)),
    )
    layout(r.right.roundToInt(), r.bottom.roundToInt()) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
}

/** A window-sized canvas shifted so its coordinates stay window coordinates while the surface is smaller. */
private fun Modifier.canvasAt(width: Int, height: Int, rect: () -> PlayerRect): Modifier = layout { measurable, constraints ->
    val r = rect()
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-r.left.roundToInt(), -r.top.roundToInt()) }
}

/** Card corners while docked, square corners at full screen; the radius follows the current height. */
private class SurfaceShape(private val miniRadius: Float, private val dockHeight: Float, private val fullHeight: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val t = ((size.height - dockHeight) / (fullHeight - dockHeight).coerceAtLeast(1f)).coerceIn(0f, 1f)
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(miniRadius * (1f - t))))
    }
}

/**
 * The one surface: a glass dock card that grows into the full-screen player. All children are
 * placed in window coordinates inside a canvas, so the cover can travel across them freely.
 */
@Composable
internal fun PlayerSurface(s: PlayerLabState, env: LabEnv, palette: CoverPalette) {
    val l = s.layout
    val density = LocalDensity.current
    val shape = remember(l) { SurfaceShape(with(density) { 28.dp.toPx() }, l.dock.height, l.full.height) }
    // Glass is only worth its blur while the page still shows through; past 0.9 the colour covers it.
    val glassOn by remember { derivedStateOf { s.p.value < 0.9f } }
    Box(
        Modifier
            .atRect { s.surfaceRect() }
            .axisLockedDrag(s)
            .then(if (glassOn) Modifier.labGlass(shape, LabGlassStyle.REGULAR) else Modifier.clip(shape))
            .testTag("player-surface"),
    ) {
        Box(Modifier.canvasAt(l.width.roundToInt(), l.height.roundToInt()) { s.surfaceRect() }) {
            PlayerBackdropLayer(s.backdrop, palette, s.song, alpha = { s.backdropAlpha() })
            if (s.miniVisible) PlayerMiniLayer(s)
            PlayerCoverStack(s)
            if (s.fullVisible) PlayerFullLayer(s, palette)
            if (s.modeVisible) PlayerModeLayer(s)
            if (s.fullVisible && s.optionsOpen) PlayerOptionsPopup(s)
        }
    }
}
