package tech.musxi.player.ui.components

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Whether "working" indicators may move. The activity provides true; tests and design sheets keep
 * the default false. An endless animation never reaches an idle frame, so Roborazzi's capture would
 * wait forever and the Gradle worker would spin at 100% CPU (it then held the test output files and
 * broke the next build). Still in tests, turning on a phone.
 */
val LocalLiveIndicators = staticCompositionLocalOf { false }

/** One turn of the arc, in ms: quick enough to read as busy, slow enough to stay quiet. */
private const val ArcTurnMillis = 900f

/**
 * "In progress" arc. Deliberately not a `CircularProgressIndicator`, whose endless animation hangs
 * screenshot tests (see [LocalLiveIndicators]).
 *
 * On a phone it turns; in tests and design sheets it is the same three-quarter arc standing still,
 * which renders deterministically and says the same thing.
 */
@Composable
fun ProgressArc(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Dp = 12.dp,
    strokeWidth: Dp = 1.5.dp,
) {
    val live = LocalLiveIndicators.current
    val angle = remember { mutableFloatStateOf(0f) }
    if (live) {
        LaunchedEffect(Unit) {
            val start = withInfiniteAnimationFrameMillis { it }
            while (true) withInfiniteAnimationFrameMillis {
                angle.floatValue = ((it - start) % ArcTurnMillis.toLong()) / ArcTurnMillis * 360f
            }
        }
    }
    Canvas(modifier.size(size)) {
        val width = strokeWidth.toPx()
        rotate(angle.floatValue) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = width, cap = StrokeCap.Round),
            )
        }
    }
}
