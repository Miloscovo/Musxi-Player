package tech.musxi.player.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import tech.musxi.player.ui.shell.dockColor
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.LocalNowPlayingAccent
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.SystemBarIcons
import tech.musxi.player.ui.theme.rememberHaptics
import tech.musxi.player.ui.theme.rememberReducedMotion

/**
 * The dock under the surface leaves with the mini controls (gone by p = 0.3). Without it the tab
 * bar would peek out below the growing page until the page had covered it. Read in draw only.
 */
internal fun Modifier.fadesWithPlayer(progress: PlayerProgress): Modifier =
    graphicsLayer { alpha = PlayerMotion.miniAlpha(progress.p.value) }

/**
 * The mini row and the full page as one surface (spec section 8). The surface grows from the mini
 * row [miniRect] (measured in the dock) to the whole window with `progress.p`, its fill fades from
 * the dock material to the cover colour, the cover is drawn once and travels between the two
 * cover rectangles, the mini controls are gone by p = 0.3 and the page's controls come in after 0.5.
 *
 * Everything inside is placed in window coordinates in a window-sized canvas, so nothing is laid out
 * again while a finger or a spring moves p: the frame-by-frame reads happen in layout and draw only.
 * [page] draws the full page; it is handed a [PlayerSurfaceLink] and must not paint its own backdrop.
 */
@Composable
internal fun PlayerSurface(
    progress: PlayerProgress,
    mini: MiniPlayerUi,
    ui: PlayerUi,
    dark: Boolean,
    miniRect: Rect?,
    nowPlaying: Color?,
    previous: CoverRef?,
    next: CoverRef?,
    coverShown: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    /** The live position as 0..1 for the mini row's ring; null uses [MiniPlayerUi.progress]. */
    miniProgress: (() -> Float)? = null,
    /** A local recording's cover options, opened by a long press on the cover; null on songs. */
    coverActions: CoverActions? = null,
    /** The player-page wallpaper; null keeps the cover-colour gradient. */
    wallpaper: WallpaperLook? = null,
    /** The dock is folded: the mini row is drawn without previous and next. */
    miniCompact: Boolean = false,
    /**
     * Read after the surface's own drag: a sideways drag it leaves alone (on the mini row) goes here,
     * so the shell can walk the tabs from the mini row as from the tab bar.
     */
    sideSwipe: Modifier = Modifier,
    page: @Composable (PlayerSurfaceLink) -> Unit,
) {
    if (!mini.visible && !progress.busy) return
    val palette = animatedPalette(playerPalette(ui.coverSeed, dark, rememberCoverColor(ui.coverUrl)))
    val scope = rememberCoroutineScope()
    val probe = remember { PlayerProbe() }
    val swipe = remember { CoverSwipe(scope, ui.queueIndex) }
    val pull = remember(progress, probe) { PlayerPull(progress, probe) }
    val nested = remember(progress) { PullToCollapse(progress) }
    val density = LocalDensity.current
    val navBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
    val miniShown by remember(progress) { derivedStateOf { progress.p.value < 0.3f } }
    // Over a wallpaper the page is the theme's colour veiled over a picture, so the theme decides.
    if (progress.covering) SystemBarIcons(if (wallpaper != null) MaterialTheme.colorScheme.background else palette.backdropTop)
    val radius = MaterialTheme.shapes.extraLarge.radiusPx(density)
    val dockFill = dockColor()
    swipe.haptic = rememberHaptics()
    swipe.reduced = rememberReducedMotion()
    swipe.density = density.density
    swipe.hasPrevious = ui.hasPrevious
    swipe.hasNext = ui.hasNext
    swipe.previous = previous
    swipe.next = next
    swipe.onNext = onNext
    swipe.onPrevious = onPrevious
    swipe.verticalDrag = pull
    swipe.interactive = { progress.p.value > 0.95f && coverShown }
    // Task 2: one spring for the cover flying to the lyrics tile and for the page around it.
    val lyricsT = rememberLyricsFraction(!coverShown)
    LaunchedEffect(ui.queueIndex) { swipe.indexChanged(ui.queueIndex) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val frame = remember(w, h, navBottom, density.density, miniRect) {
            PlayerFrame(w.toFloat(), h.toFloat(), navBottom, density, miniRect)
        }
        progress.travel = frame.travel
        swipe.slot = frame.slot
        swipe.coverWidth = probe.coverOr(frame).width
        pull.origin = { frame.surface(progress.p.value).topLeft }
        val shape = remember(frame, radius) { SurfaceShape(radius, frame.mini.height, frame.full.height) }
        val top = palette.backdropTop
        val bottom = palette.backdropBottom
        // The wallpaper fills the window; the theme layer over it is decided once per picture,
        // frame, page and strength, never per frame of a drag.
        val base = MaterialTheme.colorScheme.background
        val lyricsPage = !coverShown
        val source = remember(wallpaper?.image, wallpaper?.frame, w, h) {
            wallpaper?.let { wallpaperSource(it.image.width, it.image.height, w.toFloat(), h.toFloat(), it.frame) }
        }
        val tone = remember(wallpaper?.probe, source) {
            if (wallpaper != null && source != null && !source.isEmpty) wallpaperTone(wallpaper.image, wallpaper.probe, source) else null
        }
        val scrim = if (wallpaper != null && tone != null) {
            wallpaperScrim(wallpaper.strength, dark, lyricsPage, tone, lumaOf(base))
        } else {
            1f
        }
        val animatedScrim by animateFloatAsState(scrim, Motion.standard(), label = "wallpaperScrim")
        Box(
            Modifier
                .atRect { frame.surface(progress.p.value) }
                .clip(shape)
                .drawBehind { drawRect(dockFill, alpha = PlayerMotion.fillAlpha(progress.p.value)) }
                .then(sideSwipe)
                .axisLockedDrag(swipe)
                .testTag("player-surface"),
        ) {
            Box(
                Modifier
                    .canvasAt(w, h) { frame.surface(progress.p.value) }
                    .drawBehind {
                        val alpha = PlayerMotion.backdropAlpha(progress.p.value)
                        if (alpha <= 0f) return@drawBehind
                        if (wallpaper != null && source != null) {
                            drawWallpaper(wallpaper, source, base, animatedScrim, alpha)
                        } else {
                            drawRect(Brush.verticalGradient(listOf(top, bottom)), alpha = alpha)
                        }
                    },
            ) {
                if (miniShown) {
                    CompositionLocalProvider(LocalNowPlayingAccent provides nowPlaying) {
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = PlayerMotion.miniAlpha(progress.p.value) }) {
                            Box(Modifier.placeAt(frame.mini)) {
                                MiniPlayer(
                                    ui = mini,
                                    onExpand = progress::expand,
                                    onPlayPause = onPlayPause,
                                    onPrevious = onPrevious,
                                    onNext = onNext,
                                    embedded = true,
                                    hideCover = true,
                                    liveProgress = miniProgress,
                                    compact = miniCompact,
                                )
                            }
                        }
                    }
                }
                val current = remember(ui.coverSeed, ui.coverUrl) { CoverRef(ui.coverSeed, ui.coverUrl) }
                PlayerCoverStack(progress, swipe, frame, probe, ui.queueIndex, current, lyricsT)
                if (progress.busy) {
                    val link = remember(palette, probe, nested, lyricsT) { PlayerSurfaceLink(palette, probe, nested, lyricsT) }
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = PlayerMotion.fullAlpha(progress.p.value) }) {
                        page(link)
                    }
                    // Until the page is on screen it must not catch touches meant for the dock row.
                    if (!progress.covering) {
                        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } })
                    }
                }
                // Over everything at the cover's place, but only at rest: in the dock row and on the
                // open page while the cover (not the lyrics) shows. A drag still reaches the swipe and
                // the pull above, which cancel the press.
                if (coverActions != null) {
                    val rest by remember(progress) {
                        derivedStateOf {
                            val p = progress.p.value
                            if (p < 0.02f) 0 else if (p > 0.98f) 1 else -1
                        }
                    }
                    val coverRect = when (rest) {
                        0 -> frame.miniCover
                        1 -> if (coverShown) probe.coverOr(frame).let { Rect(it.left, it.top, it.left + it.width, it.top + it.width) } else null
                        else -> null
                    }
                    if (coverRect != null) {
                        key(rest) {
                            CoverPress(coverRect, coverActions, mini = rest == 0, onTap = if (rest == 0) progress::expand else ({}))
                        }
                    }
                }
            }
        }
    }
}

/** The cover's touch area: a tap does what the row does there, a long press opens [CoverActionsMenu]. */
@Composable
private fun CoverPress(rect: Rect, actions: CoverActions, mini: Boolean, onTap: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val haptic = rememberHaptics()
    Box(
        Modifier.placeAt(rect)
            .semantics { onLongClick(label = "封面选项") { open = true; true } }
            .pointerInput(mini) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { haptic(Haptic.LONG_PRESS); open = true },
                )
            }
            .testTag("player-cover-press"),
    ) {
        // The mini row sits at the bottom, so its menu opens upwards; the page's opens below the cover.
        CoverActionsMenu(open, actions, onDismiss = { open = false }, preferAbove = mini)
    }
}
