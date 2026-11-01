package tech.musxi.player.ui.player

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
import tech.musxi.player.ui.shell.dockColor
import tech.musxi.player.ui.theme.LocalNowPlayingAccent
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
    if (progress.covering) SystemBarIcons(palette.backdropTop)
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
        Box(
            Modifier
                .atRect { frame.surface(progress.p.value) }
                .clip(shape)
                .drawBehind { drawRect(dockFill, alpha = PlayerMotion.fillAlpha(progress.p.value)) }
                .axisLockedDrag(swipe)
                .testTag("player-surface"),
        ) {
            Box(
                Modifier
                    .canvasAt(w, h) { frame.surface(progress.p.value) }
                    .drawBehind {
                        val alpha = PlayerMotion.backdropAlpha(progress.p.value)
                        if (alpha > 0f) drawRect(Brush.verticalGradient(listOf(top, bottom)), alpha = alpha)
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
                                )
                            }
                        }
                    }
                }
                val current = remember(ui.coverSeed, ui.coverUrl) { CoverRef(ui.coverSeed, ui.coverUrl) }
                PlayerCoverStack(progress, swipe, frame, probe, ui.queueIndex, current, coverShown)
                if (progress.busy) {
                    val link = remember(palette, probe, nested) { PlayerSurfaceLink(palette, probe, nested) }
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = PlayerMotion.fullAlpha(progress.p.value) }) {
                        page(link)
                    }
                    // Until the page is on screen it must not catch touches meant for the dock row.
                    if (!progress.covering) {
                        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } })
                    }
                }
            }
        }
    }
}
