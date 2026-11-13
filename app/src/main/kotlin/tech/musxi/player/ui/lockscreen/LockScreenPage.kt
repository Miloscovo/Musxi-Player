package tech.musxi.player.ui.lockscreen

import kotlin.math.abs
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import tech.musxi.core.settings.LyricsFontSize
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.components.ProgressArc
import tech.musxi.player.ui.player.LyricsBlock
import tech.musxi.player.ui.player.LyricsPlayhead
import tech.musxi.player.ui.player.PlayerPalette
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.LocalNowPlayingAccent
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.rememberHaptics
import tech.musxi.player.ui.theme.tabular

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
    /** A swipe passed [lockScreenExitOf] (or the handle was activated): dismiss the keyguard, then leave. */
    val onUnlock: () -> Unit = {},
)

/**
 * Musxi's own lock-screen page (plan C, docs/lockscreen-page-plan-20261111.md).
 *
 * One room in the cover's light, like the player: the clock on top, the song in the middle (its
 * lyrics when they follow the music, its cover otherwise), the transport at the bottom. The page is
 * read-only except for those three buttons and the swipe away (any direction) to unlock; lyrics neither seek nor
 * scroll by hand. Glass is not used: the page is content, and the spec keeps glass for controls
 * floating over content.
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
) {
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    // Where the page has been carried by the finger, in px. Released short of an exit, it springs
    // back; past one it leaves that way. Any of the four directions unlocks.
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    // The finger's own travel since the drag began; read only in gesture callbacks.
    val travel = remember { floatArrayOf(0f, 0f) }

    CompositionLocalProvider(LocalNowPlayingAccent provides palette.accent) {
        BoxWithConstraints(
            modifier
                .fillMaxSize()
                .testTag(LockScreenPageTag)
                .background(Brush.verticalGradient(listOf(palette.backdropTop, palette.backdropBottom))),
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()
            Column(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val o = offset.value
                        translationX = o.x
                        translationY = o.y
                        // The page fades as it is carried away, like iOS's lock screen leaving.
                        val gone = maxOf(abs(o.x) / widthPx, abs(o.y) / heightPx)
                        alpha = 1f - gone.coerceIn(0f, 1f) * 0.6f
                    }
                    .pointerInput(widthPx, heightPx) {
                        val tracker = VelocityTracker()
                        fun settle(exit: LockScreenExit) {
                            val v = tracker.calculateVelocity()
                            val target = when (exit) {
                                LockScreenExit.UP -> Offset(offset.value.x, -heightPx)
                                LockScreenExit.DOWN -> Offset(offset.value.x, heightPx)
                                LockScreenExit.LEFT -> Offset(-widthPx, offset.value.y)
                                LockScreenExit.RIGHT -> Offset(widthPx, offset.value.y)
                                LockScreenExit.NONE -> Offset.Zero
                            }
                            if (exit != LockScreenExit.NONE) {
                                haptic(Haptic.THRESHOLD)
                                actions.onUnlock()
                            }
                            scope.launch { offset.animateTo(target, Motion.release(), Offset(v.x, v.y)) }
                            travel[0] = 0f
                            travel[1] = 0f
                        }
                        detectDragGestures(
                            onDragStart = {
                                tracker.resetTracking()
                                scope.launch { offset.stop() }
                            },
                            onDragEnd = {
                                val v = tracker.calculateVelocity()
                                settle(lockScreenExitOf(travel[0], travel[1], v.x, v.y, widthPx, heightPx))
                            },
                            onDragCancel = { settle(LockScreenExit.NONE) },
                        ) { change, amount ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            travel[0] += amount.x
                            travel[1] += amount.y
                            scope.launch { offset.snapTo(Offset(travel[0], travel[1])) }
                        }
                    }
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Clock(ui.time, ui.date)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (lockScreenBodyOf(ui.lyrics)) {
                        LockScreenBody.LYRICS -> LyricsBlock(
                            ui = ui.lyrics!!,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                            accentCurrent = accentCurrent,
                            fontSize = fontSize,
                            playhead = playhead,
                            readOnly = true,
                            sizeScale = LockScreenLyricsScale,
                        )
                        LockScreenBody.COVER -> BigCover(ui, Modifier.align(Alignment.Center))
                    }
                }
                SongLine(ui)
                Spacer(Modifier.height(14.dp))
                ProgressLine(palette, progress)
                Spacer(Modifier.height(4.dp))
                Transport(ui, palette, actions)
                UnlockHandle(onUnlock = actions.onUnlock)
            }
        }
    }
}

@Composable
private fun Clock(time: String, date: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Light and large: the clock is the one thing read at a glance, and a heavy weight at this
        // size would outshout the lyrics it sits above.
        Text(
            time,
            style = MaterialTheme.typography.displayLarge.tabular.copy(fontSize = 60.sp, fontWeight = FontWeight.Light),
            color = cs.onSurface,
        )
        Text(date, style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
    }
}

/** No timed lyrics: the cover takes the middle, as large as reads well, with nothing else in it. */
@Composable
private fun BigCover(ui: LockScreenUi, modifier: Modifier = Modifier) {
    Cover(
        seed = ui.coverSeed,
        url = ui.coverUrl,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.padding(horizontal = 56.dp).fillMaxWidth().aspectRatio(1f),
    )
}

@Composable
private fun SongLine(ui: LockScreenUi) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.page), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            ui.title,
            style = MaterialTheme.typography.titleMedium,
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (ui.subtitle.isNotEmpty()) {
            Text(
                ui.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                maxLines = 1,
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
 * A slim handle in place of a "swipe to unlock" caption: the page can be swiped away in any direction,
 * and naming four directions would be clutter. It is also a button for screen readers, which
 * cannot swipe.
 */
@Composable
private fun UnlockHandle(onUnlock: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onUnlock,
            )
            .semantics { contentDescription = "解锁" },
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
