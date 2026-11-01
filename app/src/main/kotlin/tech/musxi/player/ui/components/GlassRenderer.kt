package tech.musxi.player.ui.components

import android.os.Build
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How glass is drawn, best first (docs/design/interaction-spec.md, section 3). [GlassHost] picks one
 * per window with [chooseGlassRenderer]; callers of `Modifier.glass` never see which.
 */
internal enum class GlassRenderer {
    /** Backdrop library: blur, an AGSL lens at the rim, tint and a rim light. Needs runtime shaders (API 33). */
    REFRACTION,

    /** Haze: real-time blur and tint, no lens. Needs RenderEffect (API 31). */
    FROSTED,

    /** Opaque fill. Same shape, shadow and stroke as the other two. */
    SOLID,
}

/**
 * The renderer for this device. Solid whenever glass is switched off or battery saver is on, at any
 * API level; otherwise by API level: 33+ refraction, 31-32 frosted, below 31 solid.
 */
internal fun chooseGlassRenderer(sdkInt: Int, powerSave: Boolean, enabled: Boolean): GlassRenderer = when {
    !enabled || powerSave -> GlassRenderer.SOLID
    sdkInt >= Build.VERSION_CODES.TIRAMISU -> GlassRenderer.REFRACTION
    sdkInt >= Build.VERSION_CODES.S -> GlassRenderer.FROSTED
    else -> GlassRenderer.SOLID
}

/** Tint over the blurred page (spec section 3). Both blurring renderers use it, so text contrast is the same. */
internal fun glassTint(style: GlassStyle, dark: Boolean): Color =
    if (dark) Color.Black.copy(alpha = style.darkTint) else Color.White.copy(alpha = style.lightTint)

/**
 * Refraction tuning of one [GlassStyle]. [lensHeight] is the width of the ring at the rim that bends
 * the page and [lensAmount] how far that ring pulls pixels in from outside the edge. CLEAR bends most
 * (it sits over cover colour and carries no text), THIN least (menus are all text).
 *
 * [blur] is about a third of [GlassStyle.blur]: the lens needs a page it can still read, and a 24dp
 * blur leaves nothing to bend. [rimLight] is the alpha of the library's specular rim, drawn together
 * with the 1dp stroke every renderer has.
 */
internal class RefractionSpec(val blur: Dp, val lensHeight: Dp, val lensAmount: Dp, val rimLight: Float)

internal fun GlassStyle.refraction(): RefractionSpec = when (this) {
    GlassStyle.REGULAR -> RefractionSpec(blur = 8.dp, lensHeight = 20.dp, lensAmount = 28.dp, rimLight = 0.7f)
    GlassStyle.THIN -> RefractionSpec(blur = 7.dp, lensHeight = 14.dp, lensAmount = 18.dp, rimLight = 0.5f)
    GlassStyle.CLEAR -> RefractionSpec(blur = 5.dp, lensHeight = 24.dp, lensAmount = 40.dp, rimLight = 0.9f)
}

/**
 * Keeps a lens value (px) within half of the surface's short side, so a 44dp round control is not
 * bent past its own middle. Big surfaces are not affected.
 */
internal fun fitLens(valuePx: Float, shortSidePx: Float): Float = minOf(valuePx, shortSidePx / 2f)

/**
 * The library's lens and rim light are signed-distance shaders for rounded rectangles and throw for
 * any other shape. Those shapes still get blur and tint, just no bending.
 */
internal fun Shape.supportsLens(): Boolean = this is RoundedCornerShape || this is AbsoluteRoundedCornerShape
