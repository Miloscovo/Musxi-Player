package tech.musxi.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize

/**
 * What every page draws in place of its flat colour: the 界面壁纸. It is laid out against the
 * window, not the page, so the base under the glass and each page of a stack line up into one
 * picture, and a list scrolling over it does not move it.
 */
@Stable
fun interface PageBackdrop {
    /** Draws the backdrop for a window of [window] whose top-left corner is at [topLeft] here. */
    fun DrawScope.draw(topLeft: Offset, window: Size)
}

/** The shell's [PageBackdrop]; null draws the plain page colour, as before. */
val LocalPageBackdrop = compositionLocalOf<PageBackdrop?> { null }

/**
 * An opaque page background: [color], with the shell's [PageBackdrop] over it when there is one.
 * Pages of a stack must stay opaque so the page below never shows through a sliding one.
 */
fun Modifier.pageBackground(color: Color): Modifier = composed {
    val backdrop = LocalPageBackdrop.current
    if (backdrop == null) return@composed background(color)
    val place = remember { PagePlace() }
    onGloballyPositioned { coordinates ->
        // Taken once per size: a page sliding or shrinking under the back gesture keeps the
        // picture it had at rest instead of re-anchoring it every frame.
        if (coordinates.size != place.size) {
            place.size = coordinates.size
            place.top = Offset(0f, coordinates.positionInWindow().y)
            place.window = coordinates.findRootCoordinates().size.toSize()
        }
    }.drawBehind {
        drawRect(color)
        val window = place.window.takeIf { it.width > 0f && it.height > 0f } ?: size
        with(backdrop) { draw(-place.top, window) }
    }
}

private class PagePlace {
    var size = IntSize.Zero
    var top by mutableStateOf(Offset.Zero)
    var window by mutableStateOf(Size.Zero)
}
