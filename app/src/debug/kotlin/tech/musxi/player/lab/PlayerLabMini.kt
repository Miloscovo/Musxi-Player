package tech.musxi.player.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private class FakeTab(val label: String, val icon: ImageVector)

private val FakeTabs = listOf(
    FakeTab("曲库", Icons.Rounded.LibraryMusic),
    FakeTab("搜索", Icons.Rounded.Search),
    FakeTab("我的", Icons.Rounded.Person),
)

/**
 * What the dock shows at p = 0: the mini player row and three fake tabs. All of it fades out by
 * p = 0.3 and leaves the composition once invisible, so it can never catch a stray tap.
 */
@Composable
internal fun PlayerMiniLayer(s: PlayerLabState) {
    val cs = MaterialTheme.colorScheme
    val l = s.layout
    val density = LocalDensity.current
    val song = s.song
    val noRipple = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = s.miniAlpha() }) {
        // Tap anywhere on the mini row to expand; the two buttons sit above it and keep their own taps.
        Box(Modifier.placeAt(l.miniRow).clickable(interactionSource = noRipple, indication = null) { s.expand() })
        Column(Modifier.placeAt(l.miniText), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            Text(
                song.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artist, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier.placeAt(l.miniPlay).clickable { s.playing = !s.playing },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (s.playing) "暂停" else "播放",
                tint = cs.onSurface, modifier = Modifier.size(28.dp),
            )
        }
        val hasNext = s.index < s.songCount - 1
        Box(
            Modifier.placeAt(l.miniNext).clickable(enabled = hasNext) { s.step(1) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.SkipNext, contentDescription = "下一首",
                tint = cs.onSurface.copy(alpha = if (hasNext) 1f else 0.3f), modifier = Modifier.size(24.dp),
            )
        }
        val rule = with(density) { 16.dp.toPx() }
        Box(
            Modifier
                .placeAt(PlayerRect(l.tabsRow.left + rule, l.tabsRow.top, l.tabsRow.width - rule * 2, with(density) { 1.dp.toPx() }))
                .background(cs.onSurface.copy(alpha = 0.08f)),
        )
        Row(Modifier.placeAt(l.tabsRow).fillMaxWidth()) {
            FakeTabs.forEachIndexed { i, tab ->
                val selected = s.tab == i
                Column(
                    Modifier.weight(1f).fillMaxSize().clickable { s.tab = i },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    val tint = if (selected) cs.primary else cs.onSurfaceVariant
                    Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    Text(tab.label, style = MaterialTheme.typography.labelSmall, color = tint)
                }
            }
        }
    }
}
