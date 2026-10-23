package tech.musxi.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Seeds the two colours of a placeholder cover. Kept separate because the player page derives its
 * whole palette from these, so the cover and the room it sits in always agree.
 */
fun coverColors(seed: String): Pair<Color, Color> {
    val h = ((seed.hashCode() and 0x7fffffff) % 360).toFloat()
    return Color.hsl(h, 0.30f, 0.64f) to Color.hsl((h + 38f) % 360f, 0.34f, 0.36f)
}

/** Soft cover-derived tint for the now-playing backdrop; heavily blended toward the base. */
fun coverTint(seed: String, base: Color, dark: Boolean): Color =
    lerp(base, coverColors(seed).first, if (dark) 0.30f else 0.38f)

/**
 * Placeholder artwork until real images are served: a two-tone diagonal gradient with two soft light
 * blooms.
 *
 * A nested-squares pattern was tried here and reverted. It was more "designed" but less pleasant,
 * and the owner of the product preferred this. Beauty calls belong to them; the argument that a
 * pattern is more distinctive than a gradient does not survive being looked at side by side.
 */
@Composable
fun Cover(seed: String, modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.large) {
    val (a, b) = coverColors(seed)
    Box(modifier.clip(shape).background(Brush.linearGradient(listOf(a, b)))) {
        Canvas(Modifier.fillMaxSize()) {
            val m = size.minDimension
            drawCircle(
                color = Color.White.copy(alpha = 0.12f),
                radius = m * 0.36f,
                center = Offset(size.width * 0.74f, size.height * 0.28f),
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.10f),
                radius = m * 0.62f,
                center = Offset(size.width * 0.12f, size.height * 1.02f),
            )
        }
    }
}

/**
 * The empty version of the same shape: one rounded-rectangle outline with a short rule inside, so
 * "nothing here" still reads as "a cover would go here" without pretending to be artwork.
 */
@Composable
fun CoverOutline(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
    cornerRadius: Dp = 20.dp,
) {
    Canvas(modifier) {
        val inset = 0.8.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
            cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx()),
            style = Stroke(width = 1.2.dp.toPx()),
        )
        drawLine(
            color = color.copy(alpha = 0.6f),
            start = Offset(size.width * 0.30f, size.height * 0.52f),
            end = Offset(size.width * 0.70f, size.height * 0.52f),
            strokeWidth = 1.2.dp.toPx(),
        )
    }
}
