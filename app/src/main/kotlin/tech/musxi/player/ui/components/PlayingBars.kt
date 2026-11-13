package tech.musxi.player.ui.components

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Whether sound is coming out right now. The shell provides it from playback; it is what makes the
 * "now playing" bars move. False by default, so design sheets, tests and any surface outside the
 * shell draw still bars, which also keeps screenshot tests from waiting on an endless animation.
 */
val LocalPlaybackActive = staticCompositionLocalOf { false }

/** Resting heights of the three bars, as a fraction of the tallest; the still picture. */
private val RestHeights = floatArrayOf(0.62f, 1f, 0.48f)

/**
 * Three bars in the accent: the playing signal, sized to the index slot it replaces.
 *
 * While sound plays ([LocalPlaybackActive]) each bar rises and falls on its own slow cycle, out of
 * step with the others, like a level meter that is never quite in time; paused, they settle on the
 * still picture. The cycles are slow (0.9 to 1.3 seconds) on purpose: a fast flicker beside every
 * playing row would be noise, and the bars only have to say "this one is sounding".
 */
@Composable
internal fun PlayingBars(accent: Color, modifier: Modifier = Modifier, tallest: Dp = 14.dp) {
    val active = LocalPlaybackActive.current
    val clock = remember { mutableLongStateOf(0L) }
    if (active) {
        LaunchedEffect(Unit) {
            val start = withInfiniteAnimationFrameMillis { it }
            while (true) withInfiniteAnimationFrameMillis { clock.longValue = it - start }
        }
    }
    val barWidth = 3.dp
    val gap = 2.dp
    Canvas(modifier.width(barWidth * 3 + gap * 2).height(tallest)) {
        val w = barWidth.toPx()
        val g = gap.toPx()
        val t = clock.longValue / 1000.0
        for (i in 0 until 3) {
            val fraction = if (!active) RestHeights[i] else barFraction(i, t)
            val h = size.height * fraction
            drawRoundRect(
                color = accent,
                topLeft = Offset(i * (w + g), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f, w / 2f),
            )
        }
    }
}

/** Height (0.3..1 of the tallest) of bar [index] at [seconds]; each bar has its own period and phase. */
internal fun barFraction(index: Int, seconds: Double): Float {
    val period = doubleArrayOf(1.1, 0.9, 1.3)[index]
    val phase = doubleArrayOf(0.0, 0.35, 0.7)[index]
    val wave = (sin(2 * PI * (seconds / period + phase)) + 1) / 2
    return (0.3 + 0.7 * wave).toFloat()
}
