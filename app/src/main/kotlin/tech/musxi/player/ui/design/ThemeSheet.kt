package tech.musxi.player.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.player.MiniPlayer
import tech.musxi.player.ui.player.MiniPlayerUi
import tech.musxi.player.ui.shell.BarShape
import tech.musxi.player.ui.shell.ShellBottomBar
import tech.musxi.player.ui.shell.ShellTab
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.ThemeMode
import tech.musxi.player.ui.theme.displayName
import tech.musxi.player.ui.theme.identityColor
import tech.musxi.player.ui.theme.isDarkSurface

/**
 * A single design sheet used to review the palette before wiring real screens.
 * It shows the pieces that decide the look: neutral surfaces, the accent used only as a
 * signal, platform dots, cover placeholders, the tab bar and the mini player.
 */
@Composable
fun ThemeSheet(accent: Accent, mode: ThemeMode) {
    MusxiTheme(accent = accent, mode = mode) {
        val cs = MaterialTheme.colorScheme
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(Modifier.fillMaxSize()) {
                // Content that may shrink lives in a weighted block; the chrome below it keeps its
                // intrinsic height so the bar can never be squeezed out.
                Column(Modifier.weight(1f).padding(horizontal = Space.page)) {
                    Spacer(Modifier.height(Space.page))
                    SheetHeader(accent, mode)
                    Spacer(Modifier.height(Space.section))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sibling), modifier = Modifier.fillMaxWidth()) {
                        PlaylistCard("我喜欢的音乐", "746 首", Platform.QQ, Modifier.weight(1f))
                        PlaylistCard("私人雷达", "28 首", Platform.NETEASE, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(Space.sibling))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sibling), modifier = Modifier.fillMaxWidth()) {
                        PlaylistCard("跑步歌单", "63 首", Platform.KUGOU, Modifier.weight(1f))
                        PlaylistCard("夜读", "41 首", Platform.NETEASE, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(Space.section))
                    TrackRows()
                }
                // The shared chrome, not a private copy. This sheet used to draw its own mini
                // player and tab bar, and it kept the old ones after the real shell was rebuilt —
                // the same drift that once left the account sheet promising a menu that no longer
                // existed.
                Column(
                    Modifier.padding(horizontal = Space.page),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MiniPlayer(
                        ui = themeSheetMiniPlayer,
                        onExpand = {}, onPlayPause = {}, onPrevious = {}, onNext = {},
                    )
                    Spacer(Modifier.height(8.dp))
                    ShellBottomBar(
                        selected = ShellTab.LIBRARY, onSelect = {},
                        shape = BarShape.WIDE_PILL,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/**
 * A fixed playing state for the theme sheet, so the palette is judged with real chrome rather than
 * with an empty bar. Deliberately includes a swapped source: the subtitle then exercises both the
 * artist and the source note.
 */
private val themeSheetMiniPlayer = MiniPlayerUi(
    visible = true,
    title = "晴天",
    subtitle = "周杰伦 · 网易云音源",
    playing = true,
    progress = 0.42f,
    hasPrevious = true,
    hasNext = true,
    sourcePlatform = Platform.NETEASE,
)

@Composable
private fun SheetHeader(accent: Accent, mode: ThemeMode) {
    val cs = MaterialTheme.colorScheme
    Column {
        Text(
            "${accent.label} · ${if (mode == ThemeMode.LIGHT) "浅色" else if (mode == ThemeMode.DARK) "深色" else "纯黑"}",
            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text("曲库", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
        Spacer(Modifier.height(2.dp))
        Text("共 12 个歌单 · 本机缓存 10 分钟前", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun PlaylistCard(title: String, count: String, platform: Platform, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val dark = MaterialTheme.colorScheme.background.isDarkSurface()
    Column(modifier) {
        Cover(seed = title, modifier = Modifier.fillMaxWidth().height(92.dp), shape = MaterialTheme.shapes.large)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(platform.identityColor(dark)))
            Spacer(Modifier.width(6.dp))
            Text("$count · ${platform.displayName}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

/** Three list rows: the playing one carries the accent and a level bar, the rest stay neutral. */
@Composable
private fun TrackRows() {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TrackRow(1, "晴天", "周杰伦", playing = true)
        TrackRow(2, "夜的第七章", "周杰伦", playing = false)
        TrackRow(3, "枫", "周杰伦", playing = false)
    }
}

@Composable
private fun TrackRow(index: Int, title: String, artist: String, playing: Boolean) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.isDarkSurface()
    val tint = if (playing) cs.primary else cs.onSurface
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            if (playing) LevelBar(cs.primary) else Text("$index", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(artist, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                StageBadge(playing, dark)
            }
        }
        Text("4:29", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun StageBadge(playing: Boolean, dark: Boolean) {
    val cs = MaterialTheme.colorScheme
    val color = if (playing) Platform.QQ.identityColor(dark) else Platform.NETEASE.identityColor(dark)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(
            if (playing) "QQ 歌单 · 网易云音源" else "网易云",
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
        )
    }
}

/** Small accent-only equaliser; the accent never fills a large area. */
@Composable
private fun LevelBar(color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(10.dp, 16.dp, 6.dp).forEach { h ->
            Box(Modifier.width(3.dp).height(h).clip(CircleShape).background(color))
        }
    }
}
