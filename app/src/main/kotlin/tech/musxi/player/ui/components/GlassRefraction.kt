package tech.musxi.player.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight

/**
 * The refraction renderer's surface (Android 13+): the host's recording of the page (inside a
 * [GlassLayer], the page with the layer over it), saturated, blurred, bent by an AGSL lens in a
 * ring at the rim, then tinted, with a specular rim light.
 * Strength per style is in [refraction]; the tint is the one the Haze renderer uses.
 *
 * Callers pass the shadow-and-clip modifier first and add the 1dp stroke after, as for every
 * renderer, so the library's own shadow is off. Below API 33 the library skips the lens by itself
 * and the surface is blur and tint only. [shape] decides whether lens and rim light are used at all,
 * see [supportsLens].
 */
internal fun Modifier.refractedSurface(
    backdrop: Backdrop,
    shape: Shape,
    style: GlassStyle,
    dark: Boolean,
): Modifier {
    val spec = style.refraction()
    val tint = glassTint(style, dark)
    val bends = shape.supportsLens()
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(spec.blur.toPx())
            if (bends) {
                val shortSide = size.minDimension
                val height = fitLens(spec.lensHeight.toPx(), shortSide)
                // Zero until the first measure; the shader divides by the height.
                if (height > 0f) lens(refractionHeight = height, refractionAmount = fitLens(spec.lensAmount.toPx(), shortSide))
            }
        },
        highlight = if (bends) {
            { Highlight(width = 1.dp, alpha = if (dark) spec.rimLight * 0.9f else spec.rimLight) }
        } else {
            null
        },
        shadow = null,
        onDrawSurface = {
            drawRect(tint)
            drawSheen(dark)
        },
    )
}
