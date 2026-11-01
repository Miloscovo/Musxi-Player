package tech.musxi.player.lab

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.components.pressableRow
import tech.musxi.player.ui.model.formatDuration
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.displayName

/** The sheets this experiment can open. */
internal enum class SheetKind(val label: String, val note: String) {
    SOURCE_SEARCHING("换源（自动）· 搜索中", "自动换源时逐平台的进度"),
    SOURCE_CHOOSE("换源 · 选择版本", "候选列表，点一行换过去并出提示"),
    SOURCE_FAILED("换源 · 没找到", "逐平台原因，重试回到搜索中"),
    LONG_LIST("长列表 · 30 个歌单", "体验列表与 Sheet 的拖动交接、两档切换"),
    INFO("歌曲信息", "几行键值"),
}

private val SampleSong = LabSong("sample", "夜航", "林间", "示例专辑", 213_000L, coverUrl = null)

private fun LabSheetDetent.label(): String = if (this == LabSheetDetent.FULL) "全屏" else "中档"

/**
 * Sheet experiment (spec sections 9 and 13.4): the shared [LabSheet] with the source-switch states
 * and two plain sheets, over a fake song list. The initial arguments only exist so screenshot tests
 * can start in a given state; the default is the normal closed page.
 *
 * Try: drag the handle or the title row; pull a list down once it is at the top; swipe up from the
 * middle detent; fling down from full (stops in the middle first); tap the scrim; press back; and
 * flip "打开到" to compare opening in the middle against opening full.
 */
@Composable
internal fun SheetLab(
    env: LabEnv,
    initialKind: SheetKind = SheetKind.SOURCE_SEARCHING,
    initialOpen: Boolean = false,
    initialDetent: LabSheetDetent = LabSheetDetent.MEDIUM,
) {
    var kind by remember { mutableStateOf(initialKind) }
    var open by remember { mutableStateOf(initialOpen) }
    var openAt by remember { mutableStateOf(initialDetent) }
    var landed by remember { mutableStateOf("还没动过") }
    var toastText by remember { mutableStateOf("") }
    var toastOn by remember { mutableStateOf(false) }
    var toastToken by remember { mutableIntStateOf(0) }
    val song = env.songs.firstOrNull() ?: SampleSong

    // A tip that only gives feedback goes away after 4 seconds; a new one restarts the timer.
    LaunchedEffect(toastToken) {
        if (toastToken > 0) {
            delay(4_000)
            toastOn = false
        }
    }

    fun close(why: String) {
        open = false
        landed = why
    }

    fun tip(text: String) {
        toastText = text
        toastOn = true
        toastToken += 1
    }

    LabGlassHost(
        env,
        overlay = {
            LabSheet(
                visible = open,
                onDismiss = { close("已关闭") },
                env = env,
                initialDetent = openAt,
                onDetentChange = { landed = it.label() },
            ) {
                SheetBody(
                    kind = kind,
                    song = song,
                    dark = env.dark,
                    onRetry = { kind = SheetKind.SOURCE_SEARCHING },
                    onPickSource = { picked ->
                        close("选了一个版本")
                        tip("已换到 ${picked.platform.displayName} 的版本")
                    },
                    onPickPlaylist = { name ->
                        close("选了一个歌单")
                        tip("已加入「$name」")
                    },
                )
            }
            SourceTip(toastText, toastOn)
        },
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            LabTopBar(env, LabExperiment.SHEETS.title)
            Box(Modifier.fillMaxSize()) {
                FakeSongList(env.songs)
                ControlCard(
                    openAt = openAt,
                    onOpenAt = { openAt = it },
                    landed = landed,
                    onOpen = { picked ->
                        kind = picked
                        open = true
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/** Buttons for each sheet plus the "open in the middle or full" switch. */
@Composable
private fun ControlCard(
    openAt: LabSheetDetent,
    onOpenAt: (LabSheetDetent) -> Unit,
    landed: String,
    onOpen: (SheetKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .padding(horizontal = 28.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(cs.surfaceContainerLowest)
            .border(1.dp, cs.outlineVariant, shape)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text("打开哪一种", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
        Spacer(Modifier.height(4.dp))
        SheetKind.entries.forEach { k ->
            Column(
                Modifier.fillMaxWidth().pressableRow(onClick = { onOpen(k) }).padding(vertical = 10.dp),
            ) {
                Text(k.label, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
                Text(k.note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("打开到", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            LabSheetDetent.entries.forEach { d ->
                Chip(d.label(), selected = d == openAt) { onOpenAt(d) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("上次落在：$landed", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) cs.primary else cs.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Page content for the glass to blur: rows of the fake songs. */
@Composable
private fun FakeSongList(songs: List<LabSong>) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
        items(songs) { song ->
            Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(song.seed, Modifier.size(44.dp), RoundedCornerShape(10.dp), song.coverUrl)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.artist, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    formatDuration(song.durationMs),
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

/** Non-blocking feedback (spec section 9): a thin glass pill above the bottom edge, fading in and out. */
@Composable
private fun BoxScope.SourceTip(text: String, visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp),
        enter = fadeIn(Motion.enter()),
        exit = fadeOut(Motion.exit()),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .labGlass(RoundedCornerShape(22.dp), LabGlassStyle.THIN)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}
