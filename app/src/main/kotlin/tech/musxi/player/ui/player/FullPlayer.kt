package tech.musxi.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.components.OpticallyCentredText
import tech.musxi.player.ui.components.ProgressArc
import tech.musxi.player.ui.model.formatDuration
import tech.musxi.player.ui.theme.LocalMusxi
import tech.musxi.player.ui.theme.displayName
import tech.musxi.player.ui.theme.likedHeartColor
import tech.musxi.player.ui.theme.tabular
import tech.musxi.playback.PlaybackRepeatMode

private val PlayerContentInset = 32.dp
private val PlayerActionWidth = 48.dp

/**
 * Full-screen player: the one place that takes its colour from the cover.
 *
 * Layout follows a fixed grid so the seven states never move anything: the state always lands in the
 * third line of the title block, and everything else keeps its height.
 */
@Composable
fun FullPlayer(
    ui: PlayerUi,
    dark: Boolean,
    onCollapse: () -> Unit = {},
    onPlayPause: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onSeek: (Float) -> Unit = {},
    onToggleShuffle: () -> Unit = {},
    onCycleRepeat: () -> Unit = {},
    onShowQueue: () -> Unit = {},
    onShowLyrics: () -> Unit = {},
    onShowOutput: () -> Unit = {},
    /** Non-null replaces the cover with lyrics and groups translation beside the song information. */
    lyrics: LyricsUi? = null,
    /** Off by the owner's decision: enlargement and brightness already mark the current line. */
    lyricsAccentCurrent: Boolean = false,
    onSeekLine: (Long) -> Unit = {},
    onRetryLyrics: () -> Unit = {},
    onReloginLyrics: () -> Unit = {},
    onUserScrollLyrics: () -> Unit = {},
    onToggleTranslation: () -> Unit = {},
    onRefreshTranslation: () -> Unit = {},
    onReturnToCurrent: () -> Unit = {},
    /** Null hides the heart entirely (nothing is playing, or the queue has no song). */
    favorite: FavoriteUi? = null,
    onToggleFavorite: () -> Unit = {},
) {
    // The page's colour comes from the cover, not from the theme: on this one screen the accent is
    // borrowed from the artwork so that other screens can stay neutral without feeling empty.
    val palette = playerPalette(ui.coverSeed, dark)
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(palette.backdropTop, palette.backdropBottom))),
    ) {
        // The gradient bleeds behind the status bar, the content does not: without this the top bar
        // sits exactly where the phone draws its clock and the two read as one crowded row.
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            // Breathing room above the bar even before the system inset. Relying on the inset alone
            // meant the design sheet — which has no status bar — showed the bar flush against the
            // top edge, and that is the picture the owner judges. 12dp here plus the inset on a real
            // phone.
            Spacer(Modifier.height(12.dp))
            PlayerTopBar(ui, onCollapse)
            // Cover, information, progress AND the transport row share one elastic block.
            //
            // The transport row used to sit outside this block, pinned above the tool row. With the
            // fixed heights (296 + 28 + 72 + 20 + 48) the leftover space all pooled between the
            // progress bar and the controls — measured as a ~95dp void, which made the controls look
            // detached from the song they belong to.
            Column(Modifier.fillMaxWidth().weight(1f)) {
                val showing = lyrics
                if (showing == null) {
                    // Twice as much air above the cover as below the progress bar: the eye reads a
                    // roomy top as breathing, and a roomy gap above the controls as a hole.
                    Spacer(Modifier.weight(1f))
                    // The cover's slot.
                    Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp).height(296.dp)) {
                        Cover(
                            seed = ui.coverSeed,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                            shape = MaterialTheme.shapes.extraLarge,
                        )
                    }
                } else {
                    // Lyrics use the cover's slot and the air above it. Their actions belong to
                    // the information group below, leaving this entire width available to reading.
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        LyricsBlock(
                            ui = showing,
                            modifier = Modifier.fillMaxSize()
                                // Match the 20dp gap below this slot. Extra bottom padding would
                                // move even a centred short document above the page's reading centre.
                                .padding(start = 24.dp, end = 24.dp, top = 20.dp),
                            accentCurrent = lyricsAccentCurrent,
                            onSeekLine = onSeekLine,
                            onRetry = onRetryLyrics,
                            onRelogin = onReloginLyrics,
                            onUserScroll = onUserScrollLyrics,
                            onReturnToCurrent = onReturnToCurrent,
                            onToggleTranslation = onToggleTranslation,
                            onRefreshTranslation = onRefreshTranslation,
                        )
                    }
                }
                Spacer(Modifier.height(if (showing == null) 28.dp else 20.dp))
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = if (showing == null) 72.dp else 104.dp)
                        .padding(end = PlayerContentInset),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { TitleBlock(ui) }
                    val heart = favorite
                    if (showing != null) {
                        // One centre line and two disjoint 48dp targets. The small top inset
                        // balances the badge's visible top with the title's visible top.
                        Column(
                            Modifier.width(PlayerActionWidth).padding(top = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                TranslationChipButton(
                                    showing.translation,
                                    onToggle = onToggleTranslation,
                                    onRefresh = onRefreshTranslation,
                                    activeColor = palette.accent,
                                    onActiveColor = palette.onAccent,
                                )
                            }
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                if (heart != null) {
                                    FavoriteButton(favorite = heart, onClick = onToggleFavorite)
                                }
                            }
                        }
                    } else if (heart != null) {
                        FavoriteButton(favorite = heart, onClick = onToggleFavorite)
                    }
                }
                // The paired actions make the row taller. Tighten the next structural gap so
                // the platform caption and progress track still form one information group.
                Spacer(Modifier.height(if (showing == null) 13.dp else 4.dp))
                ProgressBlock(ui, palette, onSeek, dark)
                // With the cover, the gap under the progress bar is proportional elastic air. In
                // lyrics mode it shrinks to a token gap: measured against the space above the first
                // line, 20dp here still read as a hole — 20 above versus ~49 below. The transport
                // row moves up with it, which is fine as long as it stays inside the thumb zone.
                if (showing == null) {
                    Spacer(Modifier.weight(0.45f))
                } else {
                    Spacer(Modifier.height(4.dp))
                }
                TransportRow(ui, palette, onPlayPause, onPrevious, onNext, onToggleShuffle, onCycleRepeat)
            }
            Spacer(Modifier.height(12.dp))
            ToolRow(onShowOutput, onShowLyrics, onShowQueue, lyricsActive = lyrics != null)
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * The favourite toggle.
 *
 * A confirmed like is a filled red heart; an unliked song keeps a muted outline.
 * While the backend verifies a write, keep the last confirmed state and show a waiting ring.
 */
@Composable
internal fun FavoriteButton(
    favorite: FavoriteUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val likedColor = likedHeartColor(LocalMusxi.current.dark)
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        // "Not confirmed yet" is a ring, not a dimmed heart. Dimming meant previewing the target
        // state, and for an un-like that preview is an outline at 50% — indistinguishable from the
        // read-only state. The icon therefore keeps showing the state the backend last verified,
        // and the ring says a write is in flight.
        if (favorite.pending) {
            Box(
                Modifier.size(34.dp).clip(CircleShape)
                    .border(2.dp, cs.onSurface.copy(alpha = 0.22f), CircleShape),
            )
        }
        IconButton(
            onClick = onClick,
            enabled = favorite.enabled && !favorite.pending,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                if (favorite.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                if (favorite.liked) "取消收藏" else "收藏",
                tint = when {
                    favorite.liked -> likedColor.copy(alpha = if (favorite.enabled) 1f else 0.4f)
                    !favorite.enabled -> cs.onSurfaceVariant.copy(alpha = 0.3f)
                    else -> cs.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * 56dp: collapse, where in the queue this is, overflow.
 *
 * The label is centred on the *screen*, not on the space left over between the buttons: adding the
 * heart to the right end moved the centre by 24dp, which is enough to read as a mistake.
 */
@Composable
private fun PlayerTopBar(ui: PlayerUi, onCollapse: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(56.dp)) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCollapse) {
                Icon(Icons.Filled.KeyboardArrowDown, "收起", tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {}) {
                Icon(Icons.Outlined.MoreHoriz, "更多", tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
        }
        OpticallyCentredText(
            when {
                // "正在播放 12 / 12" contradicts "队列已播完" two lines below it.
                ui.status == PlayerStatus.ENDED ->
                    if (ui.queueSize > 0) "队列 · ${ui.queueIndex + 1} / ${ui.queueSize}" else "队列"
                ui.queueSize > 0 -> "正在播放 · ${ui.queueIndex + 1} / ${ui.queueSize}"
                else -> "正在播放"
            },
            MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * At least 72dp for title, artist · album and the state line. Allow the group to grow with the
 * user's font scale. An empty platform still reserves its line so status changes do not jump.
 */
@Composable
private fun TitleBlock(ui: PlayerUi) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = PlayerContentInset)) {
        Text(
            ui.title, style = MaterialTheme.typography.headlineSmall, color = cs.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOf(ui.artist, ui.album).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        val problem = isProblemLine(ui)
        OpticallyCentredText(
            thirdLineOf(ui),
            MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (problem) FontWeight.Medium else FontWeight.Normal,
            ),
            color = if (problem) cs.error else cs.onSurfaceVariant,
        )
    }
}

/** 48dp: 4dp track (6dp while dragging), 12dp thumb, tabular times underneath. */
@Composable
private fun ProgressBlock(ui: PlayerUi, palette: PlayerPalette, onSeek: (Float) -> Unit, dark: Boolean) {
    val cs = MaterialTheme.colorScheme
    // While a finger is down, the bar draws the finger's position and nothing else. Without this
    // the service keeps reporting the old position during the drag and the thumb snaps backwards
    // under the finger, which reads as "the app did not hear me".
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shown = dragFraction ?: ui.progress
    val dragging = dragFraction != null
    Column(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = PlayerContentInset)) {
        Box(
            Modifier.fillMaxWidth().height(12.dp)
                // Tap to jump, drag to scrub. Two detectors because a tap must land immediately
                // while a drag only claims the pointer once it passes touch slop.
                .pointerInput(ui.durationMs) {
                    detectTapGestures { offset ->
                        if (size.width > 0) {
                            onSeek((offset.x / size.width).coerceIn(0f, 1f))
                        }
                    }
                }
                .pointerInput(ui.durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            if (size.width > 0) {
                                dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                            }
                        },
                        onHorizontalDrag = { change, _ ->
                            if (size.width > 0) {
                                change.consume()
                                dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            }
                        },
                        // Committed once, on release: seeking on every move would fight the
                        // service's own position updates.
                        onDragEnd = {
                            dragFraction?.let(onSeek)
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier.fillMaxWidth().height(if (dragging) 6.dp else 4.dp).clip(CircleShape)
                    // Tinted, not neutral: a grey track on a tinted page reads as a leftover.
                    .background(palette.accent.copy(alpha = if (dark) 0.32f else 0.22f)),
            )
            Box(
                Modifier.fillMaxWidth(shown).height(if (dragging) 6.dp else 4.dp).clip(CircleShape)
                    .background(palette.accent),
            )
            // The thumb is drawn as an offset child so it travels with the filled part.
            Box(
                Modifier.fillMaxWidth(shown),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(palette.accent))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                formatDuration(ui.positionMs),
                style = MaterialTheme.typography.labelSmall.tabular, color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatDuration(ui.durationMs),
                style = MaterialTheme.typography.labelSmall.tabular, color = cs.onSurfaceVariant,
            )
        }
    }
}

/**
 * 80dp transport row. Only the centre button takes the accent; shuffle and repeat are deliberately
 * neutral, and they are the only two that also carry an "on" state.
 */
@Composable
private fun TransportRow(
    ui: PlayerUi,
    palette: PlayerPalette,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ModeToggle(
            icon = Icons.Outlined.Shuffle,
            label = "随机播放",
            on = ui.shuffle,
            onClick = onToggleShuffle,
        )
        IconButton(onClick = onPrevious, enabled = ui.hasPrevious) {
            Icon(
                Icons.Outlined.SkipPrevious, "上一首",
                tint = if (ui.hasPrevious) cs.onSurfaceVariant else cs.onSurfaceVariant.copy(alpha = 0.38f),
                modifier = Modifier.size(32.dp),
            )
        }
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(palette.accent),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onPlayPause) {
                if (ui.buffering || ui.swapSearching) {
                    // Searching for a replacement is the app working, not the user's turn: the
                    // button must not look like a live play button while that runs.
                    // Static arc: an indeterminate spinner never idles and hangs the sheet tests.
                    ProgressArc(
                        modifier = Modifier.size(28.dp),
                        color = palette.onAccent,
                        strokeWidth = 2.5.dp,
                    )
                } else {
                    Icon(
                        if (ui.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (ui.playing) "暂停" else "播放",
                        tint = palette.onAccent, modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
        IconButton(onClick = onNext, enabled = ui.hasNext) {
            Icon(
                Icons.Outlined.SkipNext, "下一首",
                tint = if (ui.hasNext) cs.onSurfaceVariant else cs.onSurfaceVariant.copy(alpha = 0.38f),
                modifier = Modifier.size(32.dp),
            )
        }
        ModeToggle(
            icon = if (ui.repeat == PlaybackRepeatMode.ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
            label = when (ui.repeat) {
                PlaybackRepeatMode.OFF -> "循环关闭"
                PlaybackRepeatMode.ALL -> "列表循环"
                PlaybackRepeatMode.ONE -> "单曲循环"
            },
            on = ui.repeat != PlaybackRepeatMode.OFF,
            onClick = onCycleRepeat,
        )
    }
}

/**
 * A mode control, off / on.
 *
 * Off is `onSurfaceVariant` at full strength and on is `onSurface` plus a 4dp dot underneath. The
 * dot exists because the first version told off from on by a shade of grey alone, which read the
 * same as the disabled arrow buttons next to it — three meanings, two visuals.
 */
@Composable
private fun ModeToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(
                icon, label,
                tint = if (on) cs.onSurface else cs.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        Box(
            Modifier.size(4.dp).clip(CircleShape)
                .background(if (on) cs.onSurface else Color.Transparent),
        )
    }
}

/** 48dp: output device, lyrics, queue. All neutral — none of these is the page's main action. */
@Composable
private fun ToolRow(
    onShowOutput: () -> Unit,
    onShowLyrics: () -> Unit,
    onShowQueue: () -> Unit,
    lyricsActive: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ToolIcon(Icons.Outlined.Speaker, "输出设备", onShowOutput)
        // The mode has to be visible on the control that opened it: without it the page gives no
        // hint of how to get back to the artwork.
        ToolIcon(Icons.Outlined.Lyrics, "歌词", onShowLyrics, active = lyricsActive)
        ToolIcon(Icons.AutoMirrored.Outlined.QueueMusic, "播放队列", onShowQueue)
    }
}

@Composable
private fun ToolIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    IconButton(onClick = onClick) {
        Icon(
            icon, label,
            tint = if (active) cs.onSurface else cs.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Kept so a caller can dim the page behind a bottom sheet without recomputing the gradient. */
@Composable
fun PlayerScrim(visible: Boolean) {
    if (!visible) return
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
}

/** Exposed for the sheet: the platform caption without the status override. */
fun playerPlatformCaption(ui: PlayerUi): String = ui.queuePlatform?.displayName.orEmpty()
