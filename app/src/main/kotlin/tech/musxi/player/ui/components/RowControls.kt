package tech.musxi.player.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.theme.LocalMusxi
import tech.musxi.player.ui.theme.Motion

/**
 * The small controls that end a row. They only show state: the row around them is the touch target,
 * so there is one touch area, not two.
 */

/**
 * A switch. "On" is ink, not accent: the track moves from a pale grey to the card colour mixed toward
 * `onSurface` (dark grey in light mode, light grey in dark mode). The default Material switch fills
 * the whole track with the accent colour, which made a page of six of them look heavy. Its size is
 * in dp on purpose: a switch does not grow with the font scale, its row does.
 */
@Composable
fun RowSwitch(checked: Boolean) {
    val cs = MaterialTheme.colorScheme
    val dark = LocalMusxi.current.dark
    val card = sectionCardColor()
    val off = lerp(card, cs.onSurface, 0.14f)
    val on = lerp(card, cs.onSurface, if (dark) 0.45f else 0.78f)
    val thumb = if (dark) cs.onSurface else Color.White
    val progress by animateFloatAsState(if (checked) 1f else 0f, Motion.standard(), label = "rowSwitch")
    Canvas(Modifier.size(width = 50.dp, height = 30.dp)) {
        val p = progress
        drawRoundRect(color = lerp(off, on, p), cornerRadius = CornerRadius(size.height / 2))
        val radius = 13.dp.toPx()
        val cx = size.height / 2 + (size.width - size.height) * p
        val cy = size.height / 2
        // Two faint discs under the thumb stand in for a blurred shadow; dark mode has nothing to cast on.
        if (!dark) {
            drawCircle(Color.Black.copy(alpha = 0.06f), radius + 1.dp.toPx(), Offset(cx, cy + 1.5.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = 0.10f), radius, Offset(cx, cy + 1.dp.toPx()))
        }
        drawCircle(thumb, radius, Offset(cx, cy))
    }
}

/** Right-pointing chevron at the end of a row that opens something. Hand drawn so its stroke matches the check. */
@Composable
fun RowChevron() {
    val color = MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(width = 8.dp, height = 13.dp)) {
        val w = 1.6.dp.toPx()
        val path = Path().apply {
            moveTo(w / 2, w / 2)
            lineTo(size.width - w / 2, size.height / 2)
            lineTo(w / 2, size.height - w / 2)
        }
        drawPath(path, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Selected mark of a single-choice row. Ink, like the switch, so the page keeps one accent at most. */
@Composable
fun RowCheck() {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(width = 16.dp, height = 12.dp)) {
        val w = 1.8.dp.toPx()
        val path = Path().apply {
            moveTo(w / 2, size.height * 0.55f)
            lineTo(size.width * 0.36f, size.height - w / 2)
            lineTo(size.width - w / 2, w / 2)
        }
        drawPath(path, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
