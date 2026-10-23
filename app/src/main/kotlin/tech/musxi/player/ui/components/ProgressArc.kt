package tech.musxi.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Static "in progress" arc. Deliberately not a `CircularProgressIndicator`.
 *
 * An indeterminate progress indicator is an endless animation: it never reaches an idle frame, so
 * Roborazzi's `captureRoboImage` waits forever and the Gradle test worker spins at 100% CPU. That
 * hung worker then held `app/build/test-results/.../binary/output.bin`, which surfaced as a
 * misleading "Unable to delete directory" on the *next* build.
 *
 * A fixed three-quarter arc says the same thing, renders deterministically, and keeps the quiet
 * character these surfaces are going for.
 */
@Composable
fun ProgressArc(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Dp = 12.dp,
    strokeWidth: Dp = 1.5.dp,
) {
    Canvas(modifier.size(size)) {
        val width = strokeWidth.toPx()
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = width, cap = StrokeCap.Round),
        )
    }
}
