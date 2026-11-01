package tech.musxi.player.lab

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.components.PlatformTag
import tech.musxi.player.ui.components.ProgressArc
import tech.musxi.player.ui.components.pressableRow
import tech.musxi.player.ui.model.formatDuration
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.displayName
import tech.musxi.player.ui.theme.tabular
import kotlin.math.abs

/** One replacement version found on another platform (fake data). */
internal data class SourceCandidate(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val platform: Platform,
)

/** "长 12 秒" or "短 8 秒" when the candidate differs from the original by 5 seconds or more. */
internal fun durationDiffLabel(originalMs: Long, candidateMs: Long): String? {
    val diff = candidateMs - originalMs
    val seconds = abs(diff) / 1000
    return when {
        abs(diff) < 5_000L -> null
        diff > 0 -> "长 $seconds 秒"
        else -> "短 $seconds 秒"
    }
}

/** Four versions around the original's length: two nearly identical, one live (+12s), one short (-8s). */
internal fun fakeCandidates(song: LabSong): List<SourceCandidate> {
    val base = song.durationMs.coerceAtLeast(60_000L)
    return listOf(
        SourceCandidate(song.title, song.artist, song.album, base + 1_000L, Platform.NETEASE),
        SourceCandidate("${song.title}（Live）", song.artist, "巡演现场", base + 12_000L, Platform.NETEASE),
        SourceCandidate(song.title, song.artist, song.album, base - 8_000L, Platform.QQ),
        SourceCandidate("${song.title}（伴奏版）", song.artist, song.album, base + 3_000L, Platform.QQ),
    )
}

private val PlaylistBases = listOf(
    "通勤路上", "深夜电台", "雨天适合", "跑步 150 BPM", "安静写代码",
    "老歌新听", "周末公路", "睡前十首", "咖啡馆", "回忆杀",
)

private fun playlistNames(): List<String> =
    List(30) { i -> if (i < PlaylistBases.size) PlaylistBases[i] else "${PlaylistBases[i % PlaylistBases.size]} ${i / PlaylistBases.size + 1}" }

/**
 * Body of the experiment's sheet. Changing [kind] while the sheet is open cross-fades (retry goes
 * back to searching); the list kinds take the remaining height so they hand drags to the sheet.
 */
@Composable
internal fun ColumnScope.SheetBody(
    kind: SheetKind,
    song: LabSong,
    dark: Boolean,
    onRetry: () -> Unit,
    onPickSource: (SourceCandidate) -> Unit,
    onPickPlaylist: (String) -> Unit,
) {
    Crossfade(targetState = kind, modifier = Modifier.weight(1f).fillMaxWidth(), animationSpec = Motion.enter()) { k ->
        when (k) {
            SheetKind.SOURCE_SEARCHING -> SearchingBody(song, dark)
            SheetKind.SOURCE_CHOOSE -> ChooseBody(song, dark, onPickSource)
            SheetKind.SOURCE_FAILED -> FailedBody(song, dark, onRetry)
            SheetKind.LONG_LIST -> PlaylistBody(onPickPlaylist)
            SheetKind.INFO -> InfoBody(song)
        }
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String?) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

/**
 * One platform line: its dot, a sentence and, while it is still working, a ring. The ring is the
 * repo's static arc on purpose: the lab allows no endless animation, and Roborazzi cannot settle one.
 */
@Composable
private fun PlatformLine(platform: Platform, text: String, dark: Boolean, busy: Boolean = false, muted: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlatformTag(platform, dark, showName = false)
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (muted) cs.onSurfaceVariant else cs.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (busy) ProgressArc(color = cs.onSurfaceVariant, size = 16.dp, strokeWidth = 2.dp)
    }
}

@Composable
private fun SearchingBody(song: LabSong, dark: Boolean) {
    Column(Modifier.fillMaxSize()) {
        SheetHeader("《${song.title}》在酷狗放不了（需要会员）", "正在其他平台找能播的版本")
        Spacer(Modifier.height(8.dp))
        PlatformLine(Platform.QQ, "QQ音乐 · 正在找", dark, busy = true)
        PlatformLine(Platform.NETEASE, "网易云 · 找到 2 个版本", dark)
        PlatformLine(Platform.KUGOU, "酷狗 · 原平台", dark, muted = true)
    }
}

@Composable
private fun navBottomPadding() = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

@Composable
private fun ChooseBody(song: LabSong, dark: Boolean, onPick: (SourceCandidate) -> Unit) {
    val candidates = remember(song) { fakeCandidates(song) }
    val navBottom = navBottomPadding()
    Column(Modifier.fillMaxSize()) {
        SheetHeader("换个音源", "《${song.title}》· ${song.artist} · ${formatDuration(song.durationMs)}")
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = navBottom + 8.dp),
        ) {
            items(candidates) { c -> CandidateRow(c, song.durationMs, dark) { onPick(c) } }
        }
    }
}

/** Title, artist and album, the platform, the length, and how far it is from the original's length. */
@Composable
private fun CandidateRow(c: SourceCandidate, originalMs: Long, dark: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val diff = durationDiffLabel(originalMs, c.durationMs)
    Row(
        Modifier.fillMaxWidth().pressableRow(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(seed = c.title + c.platform.name, modifier = Modifier.size(48.dp), shape = MaterialTheme.shapes.small)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                c.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${c.artist} · ${c.album}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            PlatformTag(c.platform, dark)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatDuration(c.durationMs),
                style = MaterialTheme.typography.labelSmall.tabular, color = cs.onSurfaceVariant,
            )
            if (diff != null) {
                Text(diff, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant.copy(alpha = 0.75f))
            }
        }
    }
}

@Composable
private fun FailedBody(song: LabSong, dark: Boolean, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SheetHeader("没找到能播的《${song.title}》", "三个平台都试过了")
        Spacer(Modifier.height(8.dp))
        PlatformLine(Platform.NETEASE, "网易云：暂无版权", dark)
        PlatformLine(Platform.QQ, "QQ音乐：需要会员", dark)
        PlatformLine(Platform.KUGOU, "酷狗：没找到这首", dark)
        Spacer(Modifier.height(12.dp))
        // Fake: goes back to the searching state, nothing is actually searched.
        Button(onClick = onRetry, modifier = Modifier.padding(horizontal = 20.dp)) {
            Text("重试", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PlaylistBody(onPick: (String) -> Unit) {
    val names = remember { playlistNames() }
    val cs = MaterialTheme.colorScheme
    val navBottom = navBottomPadding()
    Column(Modifier.fillMaxSize()) {
        SheetHeader("加入歌单", "共 ${names.size} 个歌单")
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = navBottom + 8.dp),
        ) {
            items(names) { name ->
                Row(
                    Modifier.fillMaxWidth().pressableRow(onClick = { onPick(name) }).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(seed = name, modifier = Modifier.size(44.dp), shape = MaterialTheme.shapes.small)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                        Text(
                            "${name.length * 3 + 8} 首",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoBody(song: LabSong) {
    val rows = listOf(
        "歌名" to song.title,
        "歌手" to song.artist,
        "专辑" to song.album,
        "时长" to formatDuration(song.durationMs),
        "来源" to Platform.KUGOU.displayName,
        "音质" to "标准 128k",
        "格式" to "MP3",
        "采样率" to "44.1 kHz",
        "比特率" to "128 kbps",
        "文件大小" to "3.2 MB",
    )
    val navBottom = navBottomPadding()
    Column(Modifier.fillMaxSize()) {
        SheetHeader("歌曲信息", null)
        Spacer(Modifier.height(4.dp))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = navBottom + 8.dp),
        ) {
            rows.forEach { (label, value) -> InfoRow(label, value) }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.width(72.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, modifier = Modifier.weight(1f))
    }
}
