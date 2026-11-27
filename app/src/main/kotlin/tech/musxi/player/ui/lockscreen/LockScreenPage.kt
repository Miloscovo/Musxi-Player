package tech.musxi.player.ui.lockscreen

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tech.musxi.core.settings.LyricsFontSize
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.components.ProgressArc
import tech.musxi.player.ui.components.listLines
import tech.musxi.player.ui.player.LyricsBlock
import tech.musxi.player.ui.player.LyricsPlayhead
import tech.musxi.player.ui.player.PlayerPalette
import tech.musxi.player.ui.player.WallpaperLook
import tech.musxi.player.ui.player.drawWallpaper
import tech.musxi.player.ui.player.hdCoverUrl
import tech.musxi.player.ui.player.lumaOf
import tech.musxi.player.ui.player.wallpaperScrim
import tech.musxi.player.ui.player.wallpaperSource
import tech.musxi.player.ui.player.wallpaperTone
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.LocalNowPlayingAccent
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.MusxiShapes
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.SystemBarIcons
import tech.musxi.player.ui.theme.isDarkSurface
import tech.musxi.player.ui.theme.rememberHaptics
import tech.musxi.player.ui.theme.tabular
import kotlin.math.abs

/** Test handles. */
internal const val LockScreenPageTag = "lock-screen-page"
internal const val LockScreenProgressFillTag = "lock-screen-progress-fill"

/**
 * The lock screen reads lyrics from a little further away than the player. 1.25 was tried: with the
 * current line's own 1.12 a long line touched both edges and short phrases wrapped awkwardly.
 */
internal const val LockScreenLyricsScale = 1.15f

/** What a press on the page asks for. Nothing here acts on its own; the host decides. */
class LockScreenActions(
    val onPlayPause: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    /** A swipe passed [lockScreenExitOf] (or the handle was activated): leave to the system lock screen. */
    val onUnlock: () -> Unit = {},
)

/**
 * Musxi's own lock-screen page (plan C, docs/lockscreen-page-plan-20261111.md).
 *
 * One room in the cover's light — or the applied player wallpaper when enabled — with the clock on
 * top, the song in the middle (its timed lyrics when they follow the music, its HD cover
 * otherwise), and the three transport buttons at the bottom. The page is read-only except for those
 * three buttons and swiping away in any of the four directions to return to the still-locked system
 * lock screen; lyrics neither seek nor scroll by hand.
 *
 * [progress] is read at draw time only (a lambda, like the player's live progress), so the page does
 * not recompose twice a second for a moving bar.
 */
@Composable
fun LockScreenPage(
    ui: LockScreenUi,
    palette: PlayerPalette,
    actions: LockScreenActions,
    modifier: Modifier = Modifier,
    progress: () -> Float = { 0f },
    playhead: LyricsPlayhead? = null,
    fontSize: LyricsFontSize = LyricsFontSize.MEDIUM,
    accentCurrent: Boolean = true,
    wallpaper: WallpaperLook? = null,
    dark: Boolean = MaterialTheme.colorScheme.background.isDarkSurface(),
) {
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val curOnUnlock by rememberUpdatedState(actions.onUnlock)
    // Where the page has been carried by the finger, in px. Released short of an exit, it springs
    // back; past one it animates out and then asks the host to close.
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val exiting = remember { booleanArrayOf(false) }

    val base = MaterialTheme.colorScheme.background
    val body = lockScreenBodyOf(ui.lyrics)
    val lyricsPage = body == LockScreenBody.LYRICS
    SystemBarIcons(behind = if (wallpaper != null) base else palette.backdropTop)

    CompositionLocalProvider(LocalNowPlayingAccent provides palette.accent) {
        BoxWithConstraints(
            modifier
                .fillMaxSize()
                .testTag(LockScreenPageTag),
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()
            val source = remember(wallpaper?.image, wallpaper?.frame, constraints.maxWidth, constraints.maxHeight) {
                wallpaper?.let {
                    wallpaperSource(it.image.width, it.image.height, widthPx, heightPx, it.frame)
                }
            }
            val tone = remember(wallpaper?.probe, source) {
                if (wallpaper != null && source != null && !source.isEmpty) {
                    wallpaperTone(wallpaper.image, wallpaper.probe, source)
                } else {
                    null
                }
            }
            val scrim = if (wallpaper != null && tone != null) {
                wallpaperScrim(wallpaper.strength, dark, lyricsPage, tone, lumaOf(base))
            } else {
                1f
            }
            val animatedScrim by animateFloatAsState(scrim, Motion.standard(), label = "lockscreen-wallpaper-scrim")

            val top = palette.backdropTop
            val bottom = palette.backdropBottom
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val o = offset.value
                        translationX = o.x
                        translationY = o.y
                        // The page fades as it is carried away, revealing the still-locked system lock screen.
                        val gone = maxOf(
                            if (widthPx > 0f) abs(o.x) / widthPx else 0f,
                            if (heightPx > 0f) abs(o.y) / heightPx else 0f,
                        )
                        alpha = 1f - gone.coerceIn(0f, 1f) * 0.65f
                    }
                    .drawBehind {
                        if (wallpaper != null && source != null) {
                            drawWallpaper(wallpaper, source, base, animatedScrim, 1f)
                        } else {
                            drawRect(Brush.verticalGradient(listOf(top, bottom)))
                        }
                    }
                    .pointerInput(widthPx, heightPx) {
                        val tracker = VelocityTracker()
                        var dragStart = Offset.Zero
                        var current = Offset.Zero
                        fun settle(exit: LockScreenExit) {
                            if (exiting[0]) return
                            val v = tracker.calculateVelocity()
                            val target = when (exit) {
                                LockScreenExit.UP -> Offset(offset.value.x, -heightPx)
                                LockScreenExit.DOWN -> Offset(offset.value.x, heightPx)
                                LockScreenExit.LEFT -> Offset(-widthPx, offset.value.y)
                                LockScreenExit.RIGHT -> Offset(widthPx, offset.value.y)
                                LockScreenExit.NONE -> Offset.Zero
                            }
                            if (exit != LockScreenExit.NONE) {
                                exiting[0] = true
                                haptic(Haptic.THRESHOLD)
                                scope.launch {
                                    withTimeoutOrNull(280L) {
                                        offset.animateTo(target, Motion.release(), Offset(v.x, v.y))
                                    }
                                    curOnUnlock()
                                }
                            } else {
                                scope.launch {
                                    offset.animateTo(Offset.Zero, Motion.release(), Offset(v.x, v.y))
                                }
                            }
                        }
                        detectDragGestures(
                            onDragStart = {
                                if (exiting[0]) return@detectDragGestures
                                tracker.resetTracking()
                                current = offset.value
                                dragStart = current
                                scope.launch { offset.stop() }
                            },
                            onDragEnd = {
                                if (exiting[0]) return@detectDragGestures
                                val v = tracker.calculateVelocity()
                                val dx = current.x - dragStart.x
                                val dy = current.y - dragStart.y
                                settle(lockScreenExitOf(dx, dy, v.x, v.y, widthPx, heightPx))
                            },
                            onDragCancel = {
                                if (!exiting[0]) settle(LockScreenExit.NONE)
                            },
                        ) { change, amount ->
                            if (exiting[0]) return@detectDragGestures
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            current += amount
                            val next = current
                            scope.launch { offset.snapTo(next) }
                        }
                    },
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Clock(ui.time, ui.date)
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        Crossfade(
                            targetState = body,
                            animationSpec = Motion.enter(),
                            label = "lockscreen-body",
                            modifier = Modifier.fillMaxSize(),
                        ) { currentBody ->
                            when (currentBody) {
                                LockScreenBody.LYRICS -> {
                                    val lyrics = ui.lyrics
                                    if (lyrics != null) {
                                        LyricsBlock(
                                            ui = lyrics,
                                            modifier = Modifier.fillMaxSize().padding(horizontal = Space.page),
                                            accentCurrent = accentCurrent,
                                            fontSize = fontSize,
                                            playhead = playhead,
                                            readOnly = true,
                                            sizeScale = LockScreenLyricsScale,
                                        )
                                    } else {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            BigCover(ui)
                                        }
                                    }
                                }
                                LockScreenBody.COVER -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    BigCover(ui)
                                }
                            }
                        }
                    }
                    SongLine(ui)
                    Spacer(Modifier.height(14.dp))
                    ProgressLine(palette, progress)
                    Spacer(Modifier.height(4.dp))
                    // Isolate drag gestures on the transport controls so a slight finger drift while
                    // tapping Previous / Play / Next never drags the whole lock-screen page away.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectDragGestures { change, _ -> change.consume() }
                            },
                    ) {
                        Transport(ui, palette, actions)
                    }
                    UnlockHandle(onUnlock = {
                        if (!exiting[0]) {
                            exiting[0] = true
                            curOnUnlock()
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun Clock(time: String, date: String) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val timeDensity = remember(density) {
        Density(density.density, lockScreenClockScale(density.fontScale, LockScreenClockMaxFontScale))
    }
    val dateDensity = remember(density) {
        Density(density.density, lockScreenClockScale(density.fontScale, LockScreenDateMaxFontScale))
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Light and large: the clock is the one thing read at a glance, and a heavy weight at this
        // size would outshout the lyrics it sits above. Font scale is capped so 200% system font
        // keeps the middle lyrics area spacious.
        CompositionLocalProvider(LocalDensity provides timeDensity) {
            Text(
                time,
                style = MaterialTheme.typography.displayLarge.tabular.copy(fontSize = 60.sp, fontWeight = FontWeight.Light),
                color = cs.onSurface,
                maxLines = 1,
            )
        }
        CompositionLocalProvider(LocalDensity provides dateDensity) {
            Text(
                date,
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** No timed lyrics: the HD cover takes the middle, bounded so landscape or large-font layouts never clip it. */
@Composable
private fun BigCover(ui: LockScreenUi, modifier: Modifier = Modifier) {
    val sharpUrl = remember(ui.coverUrl) { hdCoverUrl(ui.coverUrl) }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth - 96.dp, maxHeight - 24.dp).coerceIn(120.dp, 280.dp)
        Cover(
            seed = ui.coverSeed,
            url = sharpUrl,
            shape = MusxiShapes.large,
            modifier = Modifier.size(side),
        )
    }
}

@Composable
private fun SongLine(ui: LockScreenUi) {
    val cs = MaterialTheme.colorScheme
    val maxLines = listLines()
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.page), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            ui.title,
            style = MaterialTheme.typography.titleMedium,
            color = cs.onSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (ui.subtitle.isNotEmpty()) {
            Text(
                ui.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A thin, untouchable bar: seeking on a locked phone is exactly the mistake the page avoids. */
@Composable
private fun ProgressLine(palette: PlayerPalette, progress: () -> Float) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .padding(horizontal = 48.dp)
            .fillMaxWidth()
            .height(3.dp)
            .clip(CircleShape)
            .background(cs.onSurface.copy(alpha = 0.12f))
            .testTag(LockScreenProgressFillTag)
            .drawBehind {
                val fraction = progress().coerceIn(0f, 1f)
                drawRect(palette.accent, size = size.copy(width = size.width * fraction))
            },
    )
}

@Composable
private fun Transport(ui: LockScreenUi, palette: PlayerPalette, actions: LockScreenActions) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(72.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        IconButton(onClick = actions.onPrevious, enabled = ui.hasPrevious) {
            Icon(
                Icons.Outlined.SkipPrevious, "上一首",
                tint = if (ui.hasPrevious) cs.onSurfaceVariant else cs.onSurfaceVariant.copy(alpha = 0.38f),
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.width(28.dp))
        Box(
            Modifier.size(60.dp).clip(CircleShape).background(palette.accent),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = actions.onPlayPause) {
                if (ui.buffering) {
                    ProgressArc(color = palette.onAccent, size = 24.dp, strokeWidth = 2.5.dp)
                } else {
                    Icon(
                        if (ui.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (ui.playing) "暂停" else "播放",
                        tint = palette.onAccent,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(28.dp))
        IconButton(onClick = actions.onNext, enabled = ui.hasNext) {
            Icon(
                Icons.Outlined.SkipNext, "下一首",
                tint = if (ui.hasNext) cs.onSurfaceVariant else cs.onSurfaceVariant.copy(alpha = 0.38f),
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

/**
 * A slim handle in place of a "swipe to dismiss" caption: the page can be swiped away in any
 * direction, and naming four directions would be clutter. Its 44dp touch height also provides a
 * comfortable accessibility button for screen readers to return to the system lock screen.
 */
@Composable
private fun UnlockHandle(onUnlock: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onUnlock,
            )
            .semantics { contentDescription = "关闭锁屏歌词，返回系统锁屏" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(cs.onSurfaceVariant.copy(alpha = 0.45f)),
        )
    }
}
