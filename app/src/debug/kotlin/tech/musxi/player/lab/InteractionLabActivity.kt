package tech.musxi.player.lab

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import tech.musxi.core.library.SongSummary
import tech.musxi.player.GlassPreviewActivity
import tech.musxi.player.MusxiApplication
import tech.musxi.player.ui.components.pressableRow
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/**
 * Debug-only interaction lab (docs/design/interaction-spec.md, section 13). Each experiment tries
 * one interaction with fake data so the product owner can judge the feel before it reaches the real
 * screens. Launch with
 * `adb shell am broadcast -n tech.musxi.player.dev/tech.musxi.player.ValidationReceiver --es step interaction_lab`.
 *
 * Experiments never call playback, accounts or the network; covers of the current queue are only
 * read to make the samples look real.
 */
class InteractionLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val songs = labSongs(runCatching {
            (application as MusxiApplication).playback.queueState.value.songs
        }.getOrDefault(emptyList()))
        setContent {
            var darkOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
            val dark = darkOverride ?: isSystemInDarkTheme()
            var tuning by rememberSaveable { mutableStateOf(LabTuning.STANDARD) }
            var open by rememberSaveable { mutableStateOf<LabExperiment?>(null) }
            var glass by rememberSaveable { mutableStateOf(true) }
            val controller = remember { WindowCompat.getInsetsController(window, window.decorView) }
            LaunchedEffect(dark) {
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
            // Registered before the experiment so an experiment's own back handling wins.
            BackHandler(enabled = open != null) { open = null }
            val env = LabEnv(
                songs = songs,
                dark = dark,
                tuning = tuning,
                onTuning = { tuning = it },
                onToggleDark = { darkOverride = !dark },
                onExit = { open = null },
                glass = glass,
                onToggleGlass = { glass = !glass },
            )
            MusxiTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    when (open) {
                        null -> LabIndex(env) { experiment ->
                            if (experiment == LabExperiment.GLASS) {
                                startActivity(Intent(this@InteractionLabActivity, GlassPreviewActivity::class.java))
                            } else {
                                open = experiment
                            }
                        }
                        LabExperiment.PLAYER -> PlayerLab(env)
                        LabExperiment.NAVIGATION -> NavigationLab(env)
                        LabExperiment.SHEETS -> SheetLab(env)
                        LabExperiment.TOUCH -> TouchLab(env)
                        LabExperiment.GLASS -> Unit
                    }
                }
            }
        }
    }
}

internal enum class LabExperiment(val title: String, val note: String) {
    PLAYER("连续播放器", "迷你条拖成全屏、封面横滑切歌、歌词与队列模式、封面取色与模糊背景"),
    NAVIGATION("页面推入与返回", "歌单详情推入、跟手返回与视差、再点标签回顶"),
    SHEETS("Sheet 与换源", "中/全两档、下拉关闭、列表与拖动交接、换源各状态"),
    TOUCH("触感与长按", "八种触感逐个体验、长按歌曲行浮起并出菜单"),
    GLASS("玻璃材质", "打开已有的玻璃预览页"),
}

/** Spring response presets, so the product owner can compare the same interaction at three speeds. */
internal enum class LabTuning(val label: String, val responseSeconds: Float) {
    SNAPPY("利落", 0.24f),
    STANDARD("标准", 0.3f),
    SOFT("柔和", 0.4f);

    val stiffness: Float get() = Motion.stiffnessFor(responseSeconds)

    /** Lab version of Motion.standard() at this preset's speed. */
    fun <T> standard(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = stiffness)

    /** Lab version of Motion.release() at this preset's speed. */
    fun <T> release(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.85f, stiffness = stiffness)
}

/** One sample song. [coverUrl] is null for synthetic songs; [seed] feeds the gradient placeholder. */
internal data class LabSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String?,
    val seed: String = id,
)

/** What every experiment receives from the host. */
internal data class LabEnv(
    val songs: List<LabSong>,
    val dark: Boolean,
    val tuning: LabTuning,
    val onTuning: (LabTuning) -> Unit,
    val onToggleDark: () -> Unit,
    val onExit: () -> Unit,
    /** Floating layers use glass; false shows the solid fallback, for comparison. */
    val glass: Boolean = true,
    val onToggleGlass: () -> Unit = {},
)

private val SyntheticSongs = listOf(
    "夜航" to "林间", "回声" to "Aster", "薄雾" to "沈宁", "海盐" to "Noa", "晴空万里" to "周屿",
    "慢速列车" to "Kite", "静默时区" to "宋禾", "月光白" to "Luma", "旧唱片" to "陈默", "远方来信" to "Orin",
)

/** Up to 40 queue songs (real titles and covers make the feel easier to judge), topped up to 30. */
private fun labSongs(queue: List<SongSummary>): List<LabSong> {
    val real = queue.take(40).mapIndexed { i, song ->
        LabSong(
            id = "queue-$i",
            title = song.title,
            artist = song.artists.joinToString(" / "),
            album = song.album,
            durationMs = song.durationMs,
            coverUrl = song.coverUrl?.takeIf { it.isNotBlank() },
            seed = song.key.stableId,
        )
    }
    val fill = (0 until (30 - real.size).coerceAtLeast(0)).map { i ->
        val (title, artist) = SyntheticSongs[i % SyntheticSongs.size]
        LabSong("sample-$i", title, artist, "示例专辑", 180_000L + i * 7_000L, coverUrl = null)
    }
    return real + fill
}

/** Shared top bar: back to the lab index, the experiment title, speed presets and the theme switch. */
@Composable
internal fun LabTopBar(env: LabEnv, title: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = env.onExit) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回实验室")
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        LabControls(env, compact = true)
    }
}

/**
 * Speed presets, the glass/solid switch and the light/dark switch. [compact] (the top bar) shows the
 * speed as one chip that cycles on tap, so the experiment title keeps its room on a narrow phone.
 */
@Composable
internal fun LabControls(env: LabEnv, compact: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val presets = if (compact) listOf(env.tuning) else LabTuning.entries
        presets.forEach { preset ->
            val selected = preset == env.tuning
            Text(
                if (compact) "速度·${preset.label}" else preset.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (selected) cs.primary else cs.surfaceContainerHigh)
                    .clickable {
                        val next = LabTuning.entries[(preset.ordinal + 1) % LabTuning.entries.size]
                        env.onTuning(if (compact) next else preset)
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Text(
            if (env.glass) "玻璃" else "实色",
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .background(cs.surfaceContainerHigh)
                .clickable(onClick = env.onToggleGlass)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        IconButton(onClick = env.onToggleDark) {
            Icon(
                if (env.dark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                contentDescription = if (env.dark) "切换到浅色" else "切换到深色",
            )
        }
    }
}

@Composable
private fun LabIndex(env: LabEnv, onOpen: (LabExperiment) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Text("交互实验室", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(
            "这里用假数据试交互手感，不会播放、不会联网，也不改账号和队列。顶部三档速度可以对比同一个动作的快慢。",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        LabControls(env)
        Spacer(Modifier.height(8.dp))
        LabExperiment.entries.forEach { experiment ->
            Row(
                Modifier.fillMaxWidth().pressableRow(onClick = { onOpen(experiment) }).padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(experiment.title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                    Spacer(Modifier.height(2.dp))
                    Text(experiment.note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "每项试完告诉我：太快还是太慢、太软还是太硬、跟不跟手、会不会想再玩一次。",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** Stand-in shown by an experiment that has not been built yet. */
@Composable
internal fun LabPlaceholder(env: LabEnv, title: String) {
    Column(Modifier.fillMaxSize()) {
        LabTopBar(env, title)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("还没做好", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
