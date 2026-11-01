package tech.musxi.player.lab

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.theme.Motion

/** Blur needs RenderEffect, so the blurred-cover option only exists from API 31. */
internal val BlurSupported: Boolean get() = Build.VERSION.SDK_INT >= 31

private val SolidBackdrop = Color(0xFF242426)

/** Scrim strength for the blurred cover: brighter covers get a darker veil so white text stays readable. */
internal fun blurScrimAlpha(luma: Float): Float = (0.32f + 0.55f * luma).coerceIn(0.32f, 0.82f)

/**
 * The player's background, drawn at window size inside the surface and faded in by [alpha] as the
 * surface leaves the glass dock. Colours change with the song over the standard 220 ms curve.
 */
@Composable
internal fun PlayerBackdropLayer(
    kind: PlayerBackdrop,
    palette: CoverPalette,
    song: LabSong,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    val fade = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha() }
    when {
        kind == PlayerBackdrop.BLUR && BlurSupported -> Box(modifier.then(fade)) {
            Cover(
                seed = song.seed, url = song.coverUrl, shape = RectangleShape,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }
                    .blur(48.dp, BlurredEdgeTreatment.Unbounded),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = blurScrimAlpha(palette.luma))))
        }
        kind == PlayerBackdrop.SOLID -> Box(modifier.then(fade).background(SolidBackdrop))
        else -> {
            val top by animateColorAsState(palette.top, Motion.enter(), label = "backdropTop")
            val bottom by animateColorAsState(palette.bottom, Motion.enter(), label = "backdropBottom")
            Box(modifier.then(fade).drawBehind { drawRect(Brush.verticalGradient(listOf(top, bottom))) })
        }
    }
}
