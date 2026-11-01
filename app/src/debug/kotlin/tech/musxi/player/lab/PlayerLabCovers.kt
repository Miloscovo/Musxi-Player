package tech.musxi.player.lab

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import tech.musxi.player.ui.components.Cover

/** Where the one cover is right now: mini rect at p = 0, big rect or title-row rect at p = 1. */
internal fun PlayerLabState.coverRect(): PlayerRect {
    val full = lerpRect(layout.bigCover, layout.headerCover, modeP.value)
    return lerpRect(layout.miniCover, full, p.value)
}

/**
 * The cover, drawn once. Every cover is laid out at the big size and moved with a graphics layer:
 * scale and translation come straight from the interpolated rect, so a finger can take over at any
 * moment and nothing is re-laid-out. The neighbours exist only for the horizontal swipe.
 */
@Composable
internal fun PlayerCoverStack(s: PlayerLabState) {
    val index = s.index
    for (rel in -1..1) {
        val song = s.songs.getOrNull(index + rel) ?: continue
        key(song.id) { PlayerCoverLayer(s, song, rel) }
    }
}

@Composable
private fun PlayerCoverLayer(s: PlayerLabState, song: LabSong, rel: Int) {
    val density = LocalDensity.current
    val big = s.layout.bigCover
    Box(
        Modifier
            .size(with(density) { big.width.toDp() }, with(density) { big.height.toDp() })
            .graphicsLayer {
                val rect = s.coverRect()
                val scale = rect.width / big.width
                val pp = s.p.value
                val mode = s.modeP.value
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = scale
                translationX = rect.left + (rel * s.layout.slot + s.swipe) * scale
                translationY = rect.top
                // Neighbours only show once the big cover is at rest size; the current one always does.
                alpha = if (rel == 0) 1f else ((pp - 0.9f) / 0.1f).coerceIn(0f, 1f) * (1f - (mode / 0.1f).coerceIn(0f, 1f))
                val visual = lerp(12.dp.toPx(), lerp(20.dp.toPx(), 10.dp.toPx(), mode), pp.coerceIn(0f, 1f))
                shape = androidx.compose.foundation.shape.RoundedCornerShape(visual / scale.coerceAtLeast(0.01f))
                clip = true
                // Resting shadow only where the cover is big; a 48dp thumbnail stays flat.
                shadowElevation = 20.dp.toPx() * pp.coerceIn(0f, 1f) * (1f - mode) * (if (rel == 0) 1f else 0f)
            },
    ) {
        Cover(seed = song.seed, url = song.coverUrl, shape = RectangleShape, modifier = Modifier.size(
            with(density) { big.width.toDp() }, with(density) { big.height.toDp() },
        ))
    }
}
