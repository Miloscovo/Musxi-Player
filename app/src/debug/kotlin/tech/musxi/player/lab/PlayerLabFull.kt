package tech.musxi.player.lab

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.musxi.player.ui.model.formatDuration
import tech.musxi.player.ui.theme.Motion

private const val FakeProgress = 0.35f

/** Full-screen controls. They fade in after p = 0.5 and sit in window coordinates like everything else. */
@Composable
internal fun PlayerFullLayer(s: PlayerLabState, palette: CoverPalette) {
    val l = s.layout
    val song = s.song
    val hasPrevious = s.index > 0
    val hasNext = s.index < s.songCount - 1
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = s.fullAlpha() }) {
        Box(Modifier.placeAt(l.handle).clip(CircleShape).background(Color.White.copy(alpha = 0.45f)))
        Box(
            Modifier.placeAt(l.options).clip(CircleShape).background(Color.White.copy(alpha = 0.16f))
                .clickable { s.optionsOpen = !s.optionsOpen },
            contentAlignment = Alignment.Center,
        ) { Text("方案", style = MaterialTheme.typography.labelMedium, color = Color.White) }

        // Two title blocks, one per layout; the cover mode decides which is visible.
        Crossfade(
            song, Modifier.placeAt(l.bigTitle).graphicsLayer { alpha = 1f - (s.modeP.value / 0.4f).coerceIn(0f, 1f) },
            Motion.enter(), "bigTitle",
        ) { TitleBlock(it, 22.sp, 16.sp, Arrangement.Top) }
        Crossfade(
            song, Modifier.placeAt(l.headerTitle).graphicsLayer { alpha = ((s.modeP.value - 0.5f) / 0.5f).coerceIn(0f, 1f) },
            Motion.enter(), "headerTitle",
        ) { TitleBlock(it, 17.sp, 14.sp, Arrangement.Center) }

        Column(Modifier.placeAt(l.progressRow)) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.28f))) {
                Box(Modifier.fillMaxWidth(FakeProgress).fillMaxHeight().background(Color.White))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                val now = (song.durationMs * FakeProgress).toLong()
                Text(formatDuration(now), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.weight(1f))
                Text("-" + formatDuration(song.durationMs - now), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
            }
        }

        Row(
            Modifier.placeAt(l.transportRow),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = { s.step(-1) }, enabled = hasPrevious, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipPrevious, "上一首", Modifier.size(36.dp), Color.White.copy(alpha = if (hasPrevious) 1f else 0.35f))
            }
            Box(
                Modifier.size(68.dp).clip(CircleShape).background(Color.White).clickable { s.playing = !s.playing },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (s.playing) "暂停" else "播放", Modifier.size(38.dp), palette.bottom,
                )
            }
            IconButton(onClick = { s.step(1) }, enabled = hasNext, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipNext, "下一首", Modifier.size(36.dp), Color.White.copy(alpha = if (hasNext) 1f else 0.35f))
            }
        }

        Row(
            Modifier.placeAt(l.buttonsRow),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        ) {
            ModePill("歌词", s.mode == PlayerMode.LYRICS) { s.toggleMode(PlayerMode.LYRICS) }
            val queueActive = if (s.queueStyle == QueueStyle.PAGE) s.mode == PlayerMode.QUEUE else s.sheetVisible
            ModePill("队列", queueActive) {
                if (s.queueStyle == QueueStyle.PAGE) s.toggleMode(PlayerMode.QUEUE) else s.sheetVisible = !s.sheetVisible
            }
        }
    }
}

@Composable
private fun TitleBlock(song: LabSong, titleSize: TextUnit, artistSize: TextUnit, arrangement: Arrangement.Vertical) {
    Column(Modifier.fillMaxSize(), verticalArrangement = arrangement) {
        Text(song.title, fontSize = titleSize, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song.artist, fontSize = artistSize, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Stand-in for a clear-glass control: the blur source is the page, not the cover colour, so it is faked with a white veil. */
@Composable
private fun ModePill(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(112.dp, 44.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = if (active) 0.32f else 0.14f))
            .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.titleSmall, color = Color.White) }
}
