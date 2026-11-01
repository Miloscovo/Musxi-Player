package tech.musxi.player.lab

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.model.formatDuration
import tech.musxi.player.ui.theme.Motion

internal val FakeLyrics = listOf(
    "夜里的风吹过旧站台", "灯影慢慢落在你的肩", "我把没说完的话", "折成一张薄薄的船票",
    "列车远了，月亮还在", "沿着铁轨一路向海", "你说天亮以后就回来", "我就在这里等天亮",
    "雾散开的时候", "整座城市都安静下来", "只剩下我们的脚步", "一前一后，像两句和声",
    "如果时间愿意慢一点", "就让这首歌再长一点", "让每个路口都有光", "让每次回头都有你",
    "后来风向变了", "票根也泛了黄", "可我还记得那个夜晚", "你笑着把手放进我口袋",
)

/** Lyrics and queue A share one area under the title row; the cover mode never shows it. */
@Composable
internal fun PlayerModeLayer(s: PlayerLabState) {
    val area = s.layout.modeArea
    // Pulling down on a list that is already at its top hands the drag to the player (spec section 6).
    val pull = remember(s) { PullToCollapse(s) }
    Box(
        Modifier
            .placeAt(area)
            .nestedScroll(pull)
            .graphicsLayer { alpha = ((s.modeP.value - 0.5f) / 0.5f).coerceIn(0f, 1f) },
    ) {
        Crossfade(s.shown, animationSpec = Motion.enter(), label = "mode") { shown ->
            if (shown == PlayerMode.QUEUE) {
                QueueList(s, Color.White, Modifier.fillMaxSize())
            } else {
                LyricsList(s.song)
            }
        }
    }
}

private class PullToCollapse(private val s: PlayerLabState) : NestedScrollConnection {
    private var active = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!active || available.y == 0f) return Offset.Zero
        // Scrolling back up first undoes the pull, then goes to the list.
        val dy = if (available.y < 0f) maxOf(available.y, -s.pullTotal) else available.y
        s.pullBy(dy)
        if (s.pullTotal <= 0f) active = false
        return Offset(0f, dy)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
        if (!active) {
            active = true
            s.pullBegin()
        }
        s.pullBy(available.y)
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!active) return Velocity.Zero
        active = false
        s.pullEnd(available.y)
        return available
    }
}

@Composable
private fun LyricsList(song: LabSong) {
    var current by remember(song.id) { mutableIntStateOf(6) }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(song.id) { state.scrollToItem((current - 2).coerceAtLeast(0)) }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
        itemsIndexed(FakeLyrics) { i, line ->
            val active = i == current
            Text(
                line,
                fontSize = 24.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = if (active) 1f else 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable {
                        current = i
                        scope.launch { state.animateScrollToItem((i - 2).coerceAtLeast(0)) }
                    }
                    .padding(vertical = 8.dp),
            )
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

/** The upcoming queue: the playing song first, then what follows. Colours come from the caller (white on the player, theme on the sheet). */
@Composable
internal fun QueueList(s: PlayerLabState, ink: Color, modifier: Modifier = Modifier, bottomInset: Dp = 0.dp) {
    val upcoming = s.songs.drop(s.index + 1)
    LazyColumn(modifier, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 4.dp + bottomInset)) {
        item { QueueHeading("正在播放", ink) }
        item { QueueRow(s.song, ink, current = true) {} }
        item { QueueHeading("接下来播放", ink) }
        itemsIndexed(upcoming, key = { _, song -> song.id }) { i, song ->
            QueueRow(song, ink, current = false) { s.jumpTo(s.index + 1 + i) }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun QueueHeading(text: String, ink: Color) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = ink.copy(alpha = 0.6f), modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun QueueRow(song: LabSong, ink: Color, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(song.seed, Modifier.size(44.dp), MaterialTheme.shapes.small, song.coverUrl)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(song.title, style = MaterialTheme.typography.titleSmall, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            formatDuration(song.durationMs), style = MaterialTheme.typography.labelMedium,
            color = ink.copy(alpha = 0.6f), modifier = Modifier.alpha(if (current) 1f else 0.9f),
        )
    }
}
