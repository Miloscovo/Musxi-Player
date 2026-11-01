package tech.musxi.player

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode
import tech.musxi.core.library.SongSummary
import kotlin.math.roundToInt

/**
 * Debug-only style preview: "what would the whole app look like as liquid glass?"
 *
 * This is a comparison page for the product owner, not a product feature, and it is not wired into
 * the release build (Haze is a `debugImplementation` dependency; this file lives in the debug source
 * set). Launch it with
 * `adb shell am broadcast -n tech.musxi.player.dev/tech.musxi.player.ValidationReceiver --es step glass_preview`.
 *
 * What it does: a scrolling cover grid is the blur source. Floating glass layers (control strip,
 * bottom dock, a menu and a sheet) sample it with Haze's real-time background blur, then add a tint,
 * a gradient highlight stroke, a top sheen and a soft outer shadow.
 *
 * What it does not do: real refraction (the lens-like bending at the glass edge). That needs the
 * Kyant Backdrop library, which requires Kotlin 2.3+; this project is on Kotlin 2.2.x. Below Android
 * 12 (API 31) Haze falls back to a plain translucent tint, so no blur is visible there.
 */
class GlassPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val tracks = previewTracks(runCatching {
            (application as MusxiApplication).playback.queueState.value.songs
        }.getOrDefault(emptyList()))
        setContent {
            var dark by remember { mutableStateOf<Boolean?>(null) } // null follows the system setting
            val isDark = dark ?: isSystemInDarkTheme()
            val controller = remember { WindowCompat.getInsetsController(window, window.decorView) }
            LaunchedEffect(isDark) {
                // Dark theme wants light system-bar icons and vice versa.
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
            }
            MusxiTheme(mode = if (isDark) ThemeMode.DARK else ThemeMode.LIGHT) {
                GlassPreviewScreen(tracks = tracks, dark = isDark, onToggleDark = { dark = !isDark })
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Data
// ---------------------------------------------------------------------------------------------

private data class GlassTrack(val seed: String, val title: String, val artist: String, val coverUrl: String?)

private val SampleNames = listOf(
    "夜航" to "林间", "回声" to "Aster", "薄雾" to "沈宁", "海盐" to "Noa", "晴空万里" to "周屿",
    "慢速列车" to "Kite", "静默时区" to "宋禾", "月光白" to "Luma", "旧唱片" to "陈默", "远方来信" to "Orin",
)

/** Up to 30 queue songs that have artwork, topped up with gradient placeholders to at least 24 cells. */
private fun previewTracks(songs: List<SongSummary>): List<GlassTrack> {
    val real = songs.filter { !it.coverUrl.isNullOrBlank() }.take(30).map {
        GlassTrack(it.key.stableId, it.title, it.artists.joinToString(" / "), it.coverUrl)
    }
    val fill = (0 until (24 - real.size).coerceAtLeast(0)).map { i ->
        val (title, artist) = SampleNames[i % SampleNames.size]
        GlassTrack("sample-$i", title, artist, null)
    }
    return real + fill
}

private enum class GlassBarMode(val label: String) {
    CURRENT("当前样式"), GLASS("玻璃"), GLASS_SPLIT("玻璃·分离搜索")
}

private enum class GlassStyle(val label: String, val blur: Dp) {
    REGULAR("常规", 24.dp), CLEAR("通透", 16.dp), TINTED("着色", 24.dp);

    fun next(): GlassStyle = entries[(ordinal + 1) % entries.size]
}

private enum class DockTab(val label: String, val icon: ImageVector) {
    LIBRARY("曲库", Icons.Rounded.LibraryMusic),
    SEARCH("搜索", Icons.Rounded.Search),
    MINE("我的", Icons.Rounded.Person),
}

/** Everything a glass surface needs besides its shape. */
private data class GlassEnv(val haze: HazeState, val dark: Boolean, val accent: Color, val style: GlassStyle)

// ---------------------------------------------------------------------------------------------
// Glass material
// ---------------------------------------------------------------------------------------------

private fun glassTint(env: GlassEnv): Color = when (env.style) {
    GlassStyle.REGULAR -> if (env.dark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.55f)
    GlassStyle.CLEAR -> if (env.dark) Color.Black.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.25f)
    GlassStyle.TINTED -> env.accent.copy(alpha = 0.35f)
}

/**
 * Liquid-glass approximation, bottom to top: soft outer shadow, a clip to [shape], Haze background
 * blur with tint and film grain, a top sheen and bottom shade, then a gradient highlight stroke.
 * The shadow comes first so it is cast by the shape, not clipped away by it.
 *
 * The sheen is drawn behind the content, so text on glass stays crisp.
 */
private fun Modifier.glass(
    env: GlassEnv,
    shape: Shape,
    material: GlassStyle = env.style,
    elevation: Dp = 18.dp,
): Modifier {
    val dark = env.dark
    val tint = glassTint(env.copy(style = material))
    val blur = material.blur
    return this
        .shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.20f))
        .clip(shape)
        .hazeEffect(env.haze) {
            blurRadius = blur
            tints = listOf(HazeTint(tint))
            noiseFactor = 0.08f
            // Used only when blur is unavailable (below Android 12).
            fallbackTint = HazeTint(tint.copy(alpha = (tint.alpha + 0.25f).coerceAtMost(0.85f)))
        }
        .drawBehind {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (dark) 0.16f else 0.35f),
                    0.35f to Color.Transparent,
                    0.75f to Color.Transparent,
                    1f to Color.Black.copy(alpha = if (dark) 0.14f else 0.06f),
                ),
            )
        }
        .border(
            1.dp,
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = if (dark) 0.50f else 0.85f),
                    Color.White.copy(alpha = if (dark) 0.08f else 0.15f),
                    Color.White.copy(alpha = if (dark) 0.28f else 0.45f),
                ),
            ),
            shape,
        )
}

/** A brighter glass lozenge for the selected tab and segment. Sits on top of glass, so no second blur. */
private fun Modifier.glassPill(dark: Boolean, shape: Shape): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(
        Color.White.copy(alpha = if (dark) 0.26f else 0.80f),
        Color.White.copy(alpha = if (dark) 0.12f else 0.45f),
    )))
    .border(1.dp, Brush.linearGradient(listOf(
        Color.White.copy(alpha = if (dark) 0.55f else 0.95f),
        Color.White.copy(alpha = 0.10f),
        Color.White.copy(alpha = if (dark) 0.30f else 0.60f),
    )), shape)

/** Today's app dock for comparison: an opaque, un-blurred card with a soft shadow. */
private fun Modifier.dockCard(shape: Shape, color: Color, accent: Color, dark: Boolean): Modifier = this
    .then(
        if (dark) Modifier else Modifier.shadow(
            10.dp, shape, ambientColor = accent.copy(alpha = 0.10f), spotColor = accent.copy(alpha = 0.16f),
        ),
    )
    .clip(shape)
    .background(color)
    .then(if (dark) Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), shape) else Modifier)

// ---------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------

private val GridGap = 14.dp
private val ControlsHeight = 84.dp
private const val SheetFraction = 0.55f

/**
 * Drag state of the glass sheet. [offset] is how far the sheet is pushed below its resting place,
 * in px: 0 is fully open, [height] is fully hidden. Dragging writes it directly, release and
 * open/close animate it, and starting a new drag cancels whatever animation is running.
 */
private class SheetController(private val scope: CoroutineScope) {
    var open by mutableStateOf(false)
        private set
    var offset by mutableFloatStateOf(0f)
        private set
    var height = 0f
    private var job: Job? = null

    fun show() {
        job?.cancel()
        offset = height
        open = true
        job = scope.launch { animate(height, 0f, 0f, spring(0.85f, 400f)) { v, _ -> offset = v } }
    }

    fun hide(velocity: Float = 0f) {
        job?.cancel()
        job = scope.launch {
            animate(offset, height, velocity, spring(1f, 500f)) { v, _ -> offset = v }
            open = false
        }
    }

    /** Returns how much of [delta] was used, so nested scrolling can hand the rest back. */
    fun dragBy(delta: Float): Float {
        job?.cancel()
        val before = offset
        offset = (before + delta).coerceIn(0f, height)
        return offset - before
    }

    fun release(velocity: Float) {
        if (offset > height * 0.3f || velocity > 1000f) {
            hide(velocity)
        } else {
            job?.cancel()
            job = scope.launch { animate(offset, 0f, velocity, spring(0.85f, 500f)) { v, _ -> offset = v } }
        }
    }
}

@Composable
private fun GlassPreviewScreen(tracks: List<GlassTrack>, dark: Boolean, onToggleDark: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val haze = remember { HazeState() }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val sheet = remember { SheetController(scope) }
    var barMode by remember { mutableStateOf(GlassBarMode.GLASS) }
    var material by remember { mutableStateOf(GlassStyle.REGULAR) }
    var tab by remember { mutableStateOf(DockTab.LIBRARY) }
    var trackIndex by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var quality by remember { mutableIntStateOf(0) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    var rootSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var titleHeightPx by remember { mutableIntStateOf(0) }

    val env = GlassEnv(haze, dark, cs.primary, material)
    val track = tracks[trackIndex % tracks.size]
    val statusTop = WindowInsets.statusBars.getTop(density)
    val gapPx = with(density) { GridGap.toPx() }
    sheet.height = rootSize.height * SheetFraction

    CompositionLocalProvider(LocalContentColor provides cs.onSurface) {
        Box(
            Modifier.fillMaxSize().onGloballyPositioned { rootSize = it.size },
        ) {
            // Layer 1: everything Haze samples. The page colour and the sheet's dimming live inside
            // the source so the glass blurs them too.
            Box(Modifier.fillMaxSize().hazeSource(haze).background(cs.background)) {
                PreviewGrid(tracks, gridState, onTitleHeight = { titleHeightPx = it })
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        val progress = if (sheet.open && sheet.height > 0f) 1f - sheet.offset / sheet.height else 0f
                        drawRect(Color.Black.copy(alpha = 0.18f * progress))
                    },
                )
            }

            // Layer 2: control strip. Rests under the title, then sticks below the status bar.
            ControlStrip(
                env = env, mode = barMode, onMode = { barMode = it },
                onMaterial = { material = material.next() },
                onToggleDark = onToggleDark,
                onQueue = { sheet.show() },
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .offset {
                        val scrolled = if (gridState.firstVisibleItemIndex == 0) {
                            gridState.firstVisibleItemScrollOffset
                        } else {
                            Int.MAX_VALUE / 2
                        }
                        val rest = statusTop + titleHeightPx + gapPx.roundToInt()
                        IntOffset(0, (rest - scrolled).coerceAtLeast(statusTop + 8.dp.roundToPx()))
                    },
            )

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Text(
                    "系统版本低于 Android 12 时无模糊",
                    modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBars).padding(end = 8.dp),
                    fontSize = 10.sp,
                    color = cs.onSurfaceVariant,
                )
            }

            // Layer 3: bottom dock. It fades out while the sheet covers it, because the sheet only
            // blurs the grid and would otherwise show the dock through its tint, unblurred.
            BottomChrome(
                env = env, mode = barMode, track = track, playing = playing, tab = tab,
                onTab = { tab = it },
                onPlay = { playing = !playing },
                onNext = { trackIndex++ },
                onMore = { menuOpen = true },
                onMoreBounds = { anchor = it },
                modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer {
                    alpha = if (sheet.open && sheet.height > 0f) (sheet.offset / sheet.height).coerceIn(0f, 1f) else 1f
                },
            )

            // Layer 4: menu. A sibling of the grid, not a Popup, so it can blur the grid behind it.
            if (menuOpen) {
                Box(
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { menuOpen = false },
                )
            }
            anchor?.let { a ->
                val endPad = with(density) { (rootSize.width - a.right).coerceAtLeast(0f).toDp() }
                val bottomPad = with(density) { (rootSize.height - a.top).coerceAtLeast(0f).toDp() } + 8.dp
                GlassMenu(
                    env = env, visible = menuOpen, selected = quality,
                    onSelect = { quality = it; menuOpen = false },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = endPad, bottom = bottomPad),
                )
            }

            // Layer 5: queue sheet with a tap-outside scrim.
            if (sheet.open) {
                Box(
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { sheet.hide() },
                )
                GlassSheet(env, tracks, sheet, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Background content (the blur source)
// ---------------------------------------------------------------------------------------------

@Composable
private fun PreviewGrid(tracks: List<GlassTrack>, state: LazyGridState, onTitleHeight: (Int) -> Unit) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top, bottom = 200.dp + bottom),
        horizontalArrangement = Arrangement.spacedBy(GridGap),
        verticalArrangement = Arrangement.spacedBy(GridGap),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.onSizeChanged { onTitleHeight(it.height) }.padding(top = 16.dp)) {
                Text("液态玻璃预览", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "背景实时模糊 + 着色 + 高光描边，无折射",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Room for the floating control strip, which is laid over this spot.
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(ControlsHeight)) }
        items(tracks.size) { TrackCard(tracks[it]) }
    }
}

@Composable
private fun TrackCard(t: GlassTrack) {
    Column {
        Cover(
            seed = t.seed, url = t.coverUrl, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Spacer(Modifier.height(8.dp))
        Text(t.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            t.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Control strip
// ---------------------------------------------------------------------------------------------

@Composable
private fun ControlStrip(
    env: GlassEnv,
    mode: GlassBarMode,
    onMode: (GlassBarMode) -> Unit,
    onMaterial: () -> Unit,
    onToggleDark: () -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().height(ControlsHeight),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(40.dp).glass(env, CircleShape, elevation = 10.dp).padding(3.dp)) {
            GlassBarMode.entries.forEach { m ->
                val selected = m == mode
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .then(if (selected) Modifier.glassPill(env.dark, CircleShape) else Modifier.clip(CircleShape))
                        .clickable { onMode(m) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        m.label, fontSize = 12.sp, maxLines = 1,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipButton("材质：${env.style.label}", env, Modifier.weight(1.3f), onMaterial)
            ChipButton(if (env.dark) "主题：深色" else "主题：浅色", env, Modifier.weight(1.1f), onToggleDark)
            ChipButton("队列", env, Modifier.weight(0.8f), onQueue)
        }
    }
}

@Composable
private fun ChipButton(label: String, env: GlassEnv, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxHeight().glass(env, CircleShape, elevation = 8.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// Bottom dock
// ---------------------------------------------------------------------------------------------

/** Mini player on top, tabs below. In split mode the search tab becomes its own round button. */
@Composable
private fun BottomChrome(
    env: GlassEnv,
    mode: GlassBarMode,
    track: GlassTrack,
    playing: Boolean,
    tab: DockTab,
    onTab: (DockTab) -> Unit,
    onPlay: () -> Unit,
    onNext: () -> Unit,
    onMore: () -> Unit,
    onMoreBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val split = mode == GlassBarMode.GLASS_SPLIT
    val shape = RoundedCornerShape(28.dp)
    val dockColor = if (env.dark) cs.surfaceContainerHigh else cs.surfaceContainerLowest
    val surface = if (mode == GlassBarMode.CURRENT) {
        Modifier.dockCard(shape, dockColor, cs.primary, env.dark)
    } else {
        Modifier.glass(env, shape)
    }
    Row(
        modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f).then(surface)) {
            MiniPlayerRow(track, playing, showNext = !split, onPlay, onNext, onMore, onMoreBounds)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = cs.onSurface.copy(alpha = 0.10f))
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                DockTab.entries.filter { !split || it != DockTab.SEARCH }.forEach { t ->
                    DockTabItem(t, selected = t == tab, mode = mode, env = env) { onTab(t) }
                }
            }
        }
        if (split) {
            SearchOrb(env, selected = tab == DockTab.SEARCH, onClick = { onTab(DockTab.SEARCH) }, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
private fun MiniPlayerRow(
    track: GlassTrack,
    playing: Boolean,
    showNext: Boolean,
    onPlay: () -> Unit,
    onNext: () -> Unit,
    onMore: () -> Unit,
    onMoreBounds: (Rect) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(seed = track.seed, url = track.coverUrl, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                track.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MiniButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "播放/暂停", onPlay)
        if (showNext) MiniButton(Icons.Rounded.SkipNext, "下一首", onNext)
        MiniButton(
            Icons.Rounded.MoreHoriz, "更多", onMore,
            Modifier.onGloballyPositioned { onMoreBounds(it.boundsInRoot()) },
        )
    }
}

@Composable
private fun MiniButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(24.dp)) }
}

@Composable
private fun DockTabItem(tab: DockTab, selected: Boolean, mode: GlassBarMode, env: GlassEnv, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val pillShape = RoundedCornerShape(22.dp)
    val tint = when {
        !selected -> cs.onSurfaceVariant
        mode == GlassBarMode.CURRENT -> cs.primary
        else -> cs.onSurface
    }
    // Current style: flat tinted capsule. Glass styles: a brighter glass lozenge.
    val pill = when {
        !selected -> Modifier.clip(pillShape)
        mode == GlassBarMode.CURRENT -> Modifier.clip(pillShape).background(cs.primary.copy(alpha = 0.14f))
        else -> Modifier.glassPill(env.dark, pillShape)
    }
    Column(
        Modifier.then(pill)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .widthIn(min = 72.dp)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(tab.icon, tab.label, Modifier.size(24.dp), tint = tint)
        Text(
            tab.label, fontSize = 11.sp, color = tint,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** MeiloX-style detached search button: a 56dp round glass disc next to the dock. */
@Composable
private fun SearchOrb(env: GlassEnv, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(56.dp).glass(env, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(44.dp).then(if (selected) Modifier.glassPill(env.dark, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Search, "搜索", Modifier.size(24.dp),
                tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Glass menu
// ---------------------------------------------------------------------------------------------

private val QualityLabels = listOf("标准音质", "高品音质", "无损音质")

/** Scales and fades out of its bottom-right corner, next to the "..." button that opened it. */
@Composable
private fun GlassMenu(
    env: GlassEnv,
    visible: Boolean,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val origin = TransformOrigin(1f, 1f)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = scaleIn(spring(dampingRatio = 0.8f, stiffness = 600f), initialScale = 0.4f, transformOrigin = origin) + fadeIn(),
        exit = scaleOut(spring(dampingRatio = 0.8f, stiffness = 600f), targetScale = 0.4f, transformOrigin = origin) + fadeOut(),
    ) {
        Column(Modifier.width(220.dp).glass(env, RoundedCornerShape(18.dp)).padding(vertical = 6.dp)) {
            QualityLabels.forEachIndexed { i, label -> GlassMenuItem(label, checked = i == selected) { onSelect(i) } }
            HorizontalDivider(Modifier.padding(vertical = 4.dp, horizontal = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            GlassMenuItem("定时停止") { onSelect(selected) }
            GlassMenuItem("歌曲信息") { onSelect(selected) }
        }
    }
}

@Composable
private fun GlassMenuItem(label: String, checked: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (checked) Icon(Icons.Rounded.Check, "已选", Modifier.size(18.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Glass sheet
// ---------------------------------------------------------------------------------------------

/**
 * Bottom sheet, 55% of the screen. Dragging the handle area moves it; dragging the list moves it
 * only when the list is already at the top, through [NestedScrollConnection].
 */
@Composable
private fun GlassSheet(env: GlassEnv, tracks: List<GlassTrack>, sheet: SheetController, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val connection = remember(sheet) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && available.y < 0f && sheet.offset > 0f) {
                    Offset(0f, sheet.dragBy(available.y))
                } else {
                    Offset.Zero
                }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    Offset(0f, sheet.dragBy(available.y))
                } else {
                    Offset.Zero
                }

            override suspend fun onPreFling(available: Velocity): Velocity =
                if (sheet.offset > 0f) {
                    sheet.release(available.y)
                    available
                } else {
                    Velocity.Zero
                }
        }
    }
    val dragState = rememberDraggableState { sheet.dragBy(it) }
    Column(
        modifier.fillMaxWidth()
            .height(with(density) { sheet.height.toDp() })
            .offset { IntOffset(0, sheet.offset.roundToInt()) }
            .glass(env, shape, elevation = 24.dp)
            .nestedScroll(connection),
    ) {
        Column(
            Modifier.fillMaxWidth().draggable(dragState, Orientation.Vertical, onDragStopped = { sheet.release(it) }),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.padding(top = 10.dp, bottom = 8.dp).size(width = 36.dp, height = 5.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)),
            )
            Text(
                "播放队列", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = navBottom + 8.dp)) {
            items(tracks.take(8)) { QueueRow(it) }
        }
    }
}

@Composable
private fun QueueRow(t: GlassTrack) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(seed = t.seed, url = t.coverUrl, shape = RoundedCornerShape(10.dp), modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                t.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Rounded.DragHandle, "拖动排序", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
