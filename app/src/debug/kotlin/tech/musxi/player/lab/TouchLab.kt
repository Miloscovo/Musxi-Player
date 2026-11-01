package tech.musxi.player.lab

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.rememberHaptics

/** One card in the haptic sampler: which haptic, its Chinese name and where the app uses it. */
private data class HapticSample(val haptic: Haptic, val title: String, val usage: String)

/** The eight haptics of spec section 5, in the spec's order. */
private val HapticSamples = listOf(
    HapticSample(Haptic.TICK, "分段 / 档位", "播放顺序、分段控件等切换"),
    HapticSample(Haptic.LIKE, "收藏", "收藏一首歌"),
    HapticSample(Haptic.UNLIKE, "取消收藏", "取消收藏一首歌"),
    HapticSample(Haptic.LONG_PRESS, "长按出菜单", "长按歌曲行，弹出操作菜单"),
    HapticSample(Haptic.THRESHOLD, "越过完成阈值", "拖动首次越过阈值：播放器展开收起、Sheet 下拉关闭"),
    HapticSample(Haptic.SETTLE, "松手落定", "Sheet 松手后落到另一个档位"),
    HapticSample(Haptic.CONFIRM, "操作成功", "加入歌单、加入播放队列"),
    HapticSample(Haptic.REJECT, "操作被拒", "账号不可写、队列属于另一个平台"),
)

private val PlayModes = listOf("顺序", "随机", "单曲")

/** Rows in the long-press list. */
private const val RowCount = 12

/** A hint banner stays this long, then goes away. */
private const val HintMillis = 4_000L

/**
 * Touch and long-press lab. [initialMenuRow] opens the menu on that row from the start, for
 * screenshot tests only; normal use leaves it null.
 */
@Composable
internal fun TouchLab(env: LabEnv, initialMenuRow: Int? = null, scrollToEnd: Boolean = false) {
    val haptic = rememberHaptics()
    val scroll = rememberScrollState()
    // Screenshot tests only: waits for the list to be measured, then scrolls to its end.
    LaunchedEffect(scrollToEnd) {
        if (scrollToEnd) {
            snapshotFlow { scroll.maxValue }.first { it > 0 }
            scroll.scrollTo(scroll.maxValue)
        }
    }
    val cs = MaterialTheme.colorScheme
    var mode by remember { mutableStateOf(0) }
    var menuRow by remember { mutableStateOf(initialMenuRow) }
    var menuOpen by remember { mutableStateOf(initialMenuRow != null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var hintId by remember { mutableStateOf(0) }
    // Row bounds in root coordinates. State-backed so the menu re-anchors when a row moves.
    // Root-space rects of the rows. Plain values, so a row that moves (scroll, reflow) recomposes the menu.
    val rowBounds = remember { mutableStateMapOf<Int, Rect>() }

    // Each new hint restarts the timer because the effect is keyed on hintId.
    LaunchedEffect(hintId) {
        if (hint != null) {
            delay(HintMillis)
            hint = null
        }
    }

    fun show(text: String) {
        hint = text
        hintId += 1
    }

    // Closes the menu, plays the haptic for this one action, and shows the confirmation.
    fun pick(text: String, tick: Haptic) {
        menuOpen = false
        haptic(tick)
        show(text)
    }

    val menuItems = listOf(
        LabMenuItem("下一首播放", Icons.Outlined.SkipNext) { pick("已设为下一首播放", Haptic.CONFIRM) },
        LabMenuItem("加入播放队列", Icons.AutoMirrored.Outlined.QueueMusic) { pick("已加入播放队列", Haptic.CONFIRM) },
        LabMenuItem("收藏", Icons.Outlined.FavoriteBorder) { pick("已收藏", Haptic.LIKE) },
        LabMenuItem("加入歌单", Icons.AutoMirrored.Outlined.PlaylistAdd) { pick("已加入歌单", Haptic.CONFIRM) },
        LabMenuItem("歌曲信息", Icons.Outlined.Info) { pick("歌曲信息（假的）", Haptic.CONFIRM) },
    )

    Column(Modifier.fillMaxSize()) {
        LabTopBar(env, LabExperiment.TOUCH.title)
        LabGlassHost(
            env,
            modifier = Modifier.weight(1f),
            overlay = {
                hint?.let { LabHint(it, Modifier.align(Alignment.BottomCenter)) }
                LabAnchoredMenu(
                    open = menuOpen,
                    anchor = menuRow?.let { rowBounds[it] },
                    items = menuItems,
                    onDismiss = { menuOpen = false },
                    startSettled = initialMenuRow != null,
                )
            },
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp),
            ) {
                Spacer(Modifier.height(12.dp))
                Text("触感样板", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                Spacer(Modifier.height(4.dp))
                Text("点一下卡片，播放对应的触感。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                HapticSamples.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { sample -> HapticCard(sample, haptic, Modifier.weight(1f)) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("分段控件", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                Spacer(Modifier.height(4.dp))
                Text("切换播放顺序时震一下（TICK）。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                ModeSwitch(
                    selected = mode,
                    onSelect = { if (it != mode) { mode = it; haptic(Haptic.TICK) } },
                )
                Spacer(Modifier.height(20.dp))
                Text("长按歌曲行", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    "点按只显示假的播放提示。长按时震动、行浮起，并弹出操作菜单。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                repeat(RowCount) { i ->
                    SongRow(
                        song = env.songs[i],
                        lifted = menuOpen && menuRow == i,
                        tuning = env.tuning,
                        onTap = { show("播放（假的）") },
                        onLongPress = {
                            haptic(Haptic.LONG_PRESS)
                            menuRow = i
                            menuOpen = true
                        },
                        // Written only when the rect changes, so scrolling with the menu closed costs nothing.
                        onBounds = { if (rowBounds[i] != it) rowBounds[i] = it },
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(96.dp))
            }
        }
    }
}

@Composable
private fun HapticCard(sample: HapticSample, haptic: (Haptic) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceContainerHigh)
            .clickable(role = Role.Button) { haptic(sample.haptic) }
            .padding(12.dp),
    ) {
        Text(sample.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(sample.usage, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(sample.haptic.name, style = MaterialTheme.typography.labelSmall, color = cs.primary)
    }
}

/** Three-segment control for the play mode. Each segment is a radio-style button. */
@Composable
private fun ModeSwitch(selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(cs.surfaceContainerHigh)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PlayModes.forEachIndexed { index, label ->
            val on = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (on) cs.primary else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(index) })
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) cs.onPrimary else cs.onSurfaceVariant,
                    fontWeight = if (on) FontWeight.Medium else null,
                )
            }
        }
    }
}

/**
 * One song row. A long press lifts it (scale 1.02 and a soft shadow) on the release spring; the
 * bounds are reported before the lift transform so the menu anchors to the resting row.
 */
@Composable
private fun SongRow(
    song: LabSong,
    lifted: Boolean,
    tuning: LabTuning,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val cardShape = RoundedCornerShape(14.dp)
    val scale by animateFloatAsState(if (lifted) LabLiftScale else 1f, animationSpec = tuning.release<Float>())
    val elevation by animateFloatAsState(if (lifted) 10f else 0f, animationSpec = tuning.release<Float>())
    // The outer Box is never transformed, so its bounds are the resting row the menu anchors to.
    Box(Modifier.fillMaxWidth().onGloballyPositioned { onBounds(Rect(it.positionInRoot(), it.size.toSize())) }) {
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shape = cardShape
                clip = false
                shadowElevation = elevation.coerceAtLeast(0f).dp.toPx()
            }
            .background(cs.surfaceContainerLow, cardShape)
            .combinedClickable(role = Role.Button, onClick = onTap, onLongClick = onLongPress)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(
            seed = song.seed,
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(8.dp),
            url = song.coverUrl,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${song.artist} · ${song.album}",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(formatDuration(song.durationMs), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
    }
    }
}

/** Bottom hint pill for a confirmation or a fake-playback note. Lives in the overlay, above the list. */
@Composable
private fun LabHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
            .labGlass(RoundedCornerShape(50), LabGlassStyle.THIN)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
