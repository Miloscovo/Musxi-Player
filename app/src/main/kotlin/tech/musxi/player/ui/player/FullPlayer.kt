package tech.musxi.player.ui.player

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Forward30
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.HighQuality
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt
import coil3.compose.AsyncImage
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.components.OpticallyCentredText
import tech.musxi.player.ui.components.ProgressArc
import tech.musxi.player.ui.model.formatDuration
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.displayName
import tech.musxi.player.ui.theme.tabular
import tech.musxi.playback.PlaybackRepeatMode
import tech.musxi.player.ui.theme.SystemBarIcons

private val PlayerContentInset = 32.dp
private val PlayerActionWidth = 48.dp

/** The filled part of the seek bar; tests read its width. */
internal const val PlayerProgressFillTag = "player-progress-fill"

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
    /** Opens the play-order chooser; the shell owns the sheet so back can close it. */
    onShowPlayMode: () -> Unit = {},
    /** Opens the current source's available qualities. */
    onShowQuality: () -> Unit = {},
    onShowQueue: () -> Unit = {},
    onShowLyrics: () -> Unit = {},
    onShowOutput: () -> Unit = {},
    /** Non-null replaces the cover with lyrics and groups translation beside the song information. */
    lyrics: LyricsUi? = null,
    /** "颜色跟随封面": tint the current line with the cover's colour; off leaves it plainly brightest. */
    lyricsAccentCurrent: Boolean = false,
    /** "字号" of the 我的 page; sizes the lyrics only. */
    lyricsFontSize: tech.musxi.core.settings.LyricsFontSize = tech.musxi.core.settings.LyricsFontSize.MEDIUM,
    /** Where the song is between playback updates; lets a line with word timing sweep. */
    lyricsPlayhead: LyricsPlayhead? = null,
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
    /** Long press on the heart: "收藏到" (我喜欢, a playlist, or a new one). Null: no long press. */
    onCollectTo: (() -> Unit)? = null,
    /** The play-order menu, anchored to the transport row's left button. */
    playModeMenuOpen: Boolean = false,
    onDismissPlayMode: () -> Unit = {},
    onSelectPlayMode: (PlayMode) -> Unit = {},
    /** Non-null opens the quality menu on the transport row's right button; null keeps it closed. */
    qualityMenu: QualityMenuUi? = null,
    onDismissQuality: () -> Unit = {},
    onSelectQuality: (String) -> Unit = {},
    /** Non-null opens the output menu on the tool row's speaker; null keeps it closed. */
    outputMenu: OutputMenuUi? = null,
    onDismissOutput: () -> Unit = {},
    onSelectOutput: (String) -> Unit = {},
    onOpenSystemOutput: () -> Unit = {},
    /** Non-null opens the overflow menu under the top bar's "更多"; null keeps it closed. */
    moreMenu: MoreMenuUi? = null,
    onShowMore: () -> Unit = {},
    onDismissMore: () -> Unit = {},
    onMoreAction: (MoreAction) -> Unit = {},
    /** Opens the shell's playlist sheet for the current song; null hides "加入歌单". */
    onManagePlaylists: (() -> Unit)? = null,
    /** Opens the manual lyrics match sheet; null hides "匹配歌词". */
    onMatchLyrics: (() -> Unit)? = null,
    /** Manual search of the other platforms: "更多" item, and a button beside a failure. Null hides both. */
    onFindSources: (() -> Unit)? = null,
    /** A sleep timer is running: marks "更多" with a dot even while its menu is closed. */
    sleepTimerActive: Boolean = false,
    /** Flips "到点后播完当前…" on the timer page; null hides that row. */
    onSleepFinishTrackChange: ((Boolean) -> Unit)? = null,
    /**
     * Set when the continuous player (PlayerSurface) draws around this page: it then owns the
     * backdrop, the system-bar icons and the cover, and this page only reports where its cover slot
     * and progress bar are. Null keeps the page self-contained, as the design sheets use it.
     */
    surface: PlayerSurfaceLink? = null,
    /**
     * The live position, read only by the seek bar and its times so a playback tick recomposes that
     * row alone (spec section 12). Null uses [PlayerUi.positionMs], as the design sheets do.
     */
    liveProgress: (() -> PlaybackProgress)? = null,
    /** The controls that stand in for heart, quality and lyrics while a local recording plays. */
    localActions: LocalPlayerActions? = null,
) {
    // The page's colour comes from the cover, not from the theme: on this one screen the accent is
    // borrowed from the artwork so that other screens can stay neutral without feeling empty.
    //
    // The seed colour stands in until the cover's own colour is known; the palette then eases to it,
    // and eases again on every track change, so the room's light changes instead of cutting.
    val palette = surface?.palette
        ?: animatedPalette(playerPalette(ui.coverSeed, dark, rememberCoverColor(ui.coverUrl)))
    // The room's colour is under the status bar here, not the shell's.
    if (surface == null) SystemBarIcons(palette.backdropTop)
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize()
            .then(
                if (surface == null) {
                    Modifier.background(Brush.verticalGradient(listOf(palette.backdropTop, palette.backdropBottom)))
                } else {
                    Modifier
                },
            ),
    ) {
        // The gradient bleeds behind the status bar, the content does not: without this the top bar
        // sits exactly where the phone draws its clock and the two read as one crowded row.
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            // Breathing room above the bar even before the system inset. Relying on the inset alone
            // meant the design sheet — which has no status bar — showed the bar flush against the
            // top edge, and that is the picture the owner judges. 12dp here plus the inset on a real
            // phone.
            if (surface == null) {
                Spacer(Modifier.height(12.dp))
            } else {
                // Grab handle: the whole page pulls down to close, the handle only says so.
                Box(Modifier.fillMaxWidth().height(12.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(width = 36.dp, height = 5.dp).clip(CircleShape)
                            .background(cs.onSurface.copy(alpha = 0.18f)),
                    )
                }
            }
            PlayerTopBar(
                ui, onCollapse, moreMenu, sleepTimerActive, onShowMore, onDismissMore, onMoreAction,
                onManagePlaylists, onMatchLyrics, onFindSources, onSleepFinishTrackChange,
                onSkip = if (ui.local) localActions?.onSkip else null,
            )
            // Cover, information, progress AND the transport row share one elastic block.
            //
            // The transport row used to sit outside this block, pinned above the tool row. With the
            // fixed heights (296 + 28 + 72 + 20 + 48) the leftover space all pooled between the
            // progress bar and the controls — measured as a ~95dp void, which made the controls look
            // detached from the song they belong to.
            // Task 2: the cover page and the lyrics page are one layout, moved by one value t (0 cover,
            // 1 lyrics). The cover flies between its slot and the lyrics bar's tile (the surface draws
            // it from the same t), the lyrics rise from below, and the gap under the progress bar
            // closes. A tap halfway reverses the same spring from where it is.
            val ownLyricsT = rememberLyricsFraction(lyrics != null)
            val lyricsT = surface?.lyricsT ?: ownLyricsT
            // The lyrics stay on screen while they leave: the caller already passes null by then.
            val keptLyrics = remember { arrayOfNulls<LyricsUi>(1) }
            if (lyrics != null) keptLyrics[0] = lyrics
            val shownLyrics = lyrics ?: keptLyrics[0]
            val lyricsComposed by remember(lyricsT) { derivedStateOf { lyricsT.value > 0f } }
            val coverRowComposed by remember(lyricsT) { derivedStateOf { lyricsT.value < 1f } }
            val heart = favorite
            Layout(
                modifier = Modifier.fillMaxWidth().weight(1f),
                content = {
                    // 0: the cover page's group. The slot stays laid out on the lyrics page too, so
                    // the cover always knows where it goes back to.
                    Column(Modifier.fillMaxWidth()) {
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 32.dp).height(296.dp)
                                .graphicsLayer { if (surface == null) alpha = 1f - lyricsT.value },
                        ) {
                            if (surface != null) {
                                // The continuous player draws the cover (one image for the mini row,
                                // this page and the lyrics tile); the slot only says where it goes.
                                Spacer(
                                    Modifier.fillMaxWidth().aspectRatio(1f).onGloballyPositioned { coords ->
                                        val r = coords.unclippedBoundsInRoot()
                                        if (surface.probe.cover != r) surface.probe.cover = r
                                    },
                                )
                            } else {
                                Cover(
                                    seed = ui.coverSeed,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                    shape = MaterialTheme.shapes.extraLarge,
                                    url = ui.coverUrl,
                                )
                            }
                            // The small image above is the one the lists already cached, so it shows at
                            // once. The large rendition fades in over it when it arrives; if it never
                            // does, nothing changes. Keyed by address so a new song starts hidden.
                            val sharpUrl = hdCoverUrl(ui.coverUrl)
                            if (surface == null && !sharpUrl.isNullOrBlank() && sharpUrl != ui.coverUrl) {
                                key(sharpUrl) {
                                    SharpCover(
                                        url = sharpUrl,
                                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                        shape = MaterialTheme.shapes.extraLarge,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(end = PlayerContentInset)
                                .graphicsLayer { alpha = LyricsTransition.titleAlpha(lyricsT.value) }
                                // On the lyrics page the bar says all this; the row is only a shape.
                                .then(if (coverRowComposed) Modifier else Modifier.clearAndSetSemantics { }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Nothing in this row may take a tap once it has faded behind the lyrics.
                            Box(Modifier.weight(1f)) { TitleBlock(ui, onFindSources?.takeIf { coverRowComposed }) }
                            if (heart != null) {
                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                    if (coverRowComposed) {
                                        FavoriteButton(favorite = heart, onClick = onToggleFavorite, onLongPress = onCollectTo)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(13.dp))
                    }
                    // 1: the lyrics page's group: the bar with the cover tile, then the lines.
                    Box(Modifier.fillMaxSize()) {
                        val showing = shownLyrics
                        if (lyricsComposed && showing != null) {
                            Column(Modifier.fillMaxSize()) {
                                LyricsTitleBar(
                                    ui, showing, lyricsT, surface, palette, heart,
                                    onToggleFavorite, onCollectTo, onToggleTranslation, onRefreshTranslation,
                                    onBackToCover = onShowLyrics,
                                )
                                Box(
                                    Modifier.fillMaxWidth().weight(1f)
                                        .graphicsLayer {
                                            val t = lyricsT.value
                                            translationY = (1f - t) * LyricsTransition.Rise.toPx()
                                            alpha = LyricsTransition.lyricsAlpha(t)
                                        }
                                        // Pulling down on a list already at its top hands the drag to the player.
                                        .then(if (surface != null) Modifier.nestedScroll(surface.pull) else Modifier),
                                ) {
                                    LyricsBlock(
                                        ui = showing,
                                        modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 8.dp),
                                        accentCurrent = lyricsAccentCurrent,
                                        fontSize = lyricsFontSize,
                                        playhead = lyricsPlayhead,
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
                        }
                    }
                    // 2
                    ProgressBlock(
                        ui, palette, onSeek, dark, liveProgress,
                        // A drag that starts on the bar scrubs; the surface must not take it as a collapse.
                        modifier = if (surface == null) {
                            Modifier
                        } else {
                            Modifier.onGloballyPositioned { coords ->
                                val r = coords.unclippedBoundsInRoot()
                                if (surface.probe.scrub != r) surface.probe.scrub = r
                            }
                        },
                    )
                    // 3
                    TransportRow(
                        ui, palette, onPlayPause, onPrevious, onNext, onShowPlayMode, onShowQuality,
                        playModeMenuOpen, onDismissPlayMode, onSelectPlayMode,
                        qualityMenu, onDismissQuality, onSelectQuality,
                        localActions = localActions,
                    )
                },
            ) { measurables, constraints ->
                val w = constraints.maxWidth
                val h = constraints.maxHeight
                val row = Constraints(minWidth = w, maxWidth = w)
                val progress = measurables[2].measure(row)
                val transport = measurables[3].measure(row)
                val cover = measurables[0].measure(row)
                // With the cover, twice as much air above the cover as below the progress bar (the
                // old 1 : 0.45 weights). In lyrics mode that gap shrinks to a token 4dp: 20dp there
                // read as a hole against the space above the first line.
                val token = 4.dp.roundToPx()
                val lyricsHeight = (h - progress.height - transport.height - token).coerceAtLeast(0)
                val lyricsPage = measurables[1].measure(Constraints.fixed(w, lyricsHeight))
                val leftover = (h - cover.height - progress.height - transport.height).coerceAtLeast(0)
                val coverGap = leftover * 0.45f / 1.45f
                layout(w, h) {
                    // Read here, in placement only: the motion moves rows, it does not recompose them.
                    val t = lyricsT.value.coerceIn(0f, 1f)
                    val gap = lerp(coverGap, token.toFloat(), t).roundToInt()
                    val transportY = h - transport.height
                    val progressY = transportY - gap - progress.height
                    // The cover's group stays where the cover page has it: the slot it reports is
                    // where the flying cover lands on the way back, so it must not drift with t.
                    val restProgressY = transportY - coverGap.roundToInt() - progress.height
                    cover.place(0, restProgressY - cover.height)
                    lyricsPage.place(0, 0)
                    progress.place(0, progressY)
                    transport.place(0, transportY)
                }
            }
            Spacer(Modifier.height(12.dp))
            ToolRow(
                onShowOutput, onShowLyrics, onShowQueue, lyricsActive = lyrics != null,
                bookmarks = if (ui.local) localActions else null,
                // A local recording has no lyrics source; an entry would only open a skeleton.
                showLyrics = !ui.local,
                outputMenu = outputMenu, onDismissOutput = onDismissOutput,
                onSelectOutput = onSelectOutput, onOpenSystemOutput = onOpenSystemOutput,
            )
            Spacer(Modifier.height(12.dp))
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
private fun PlayerTopBar(
    ui: PlayerUi,
    onCollapse: () -> Unit,
    moreMenu: MoreMenuUi?,
    sleepTimerActive: Boolean,
    onShowMore: () -> Unit,
    onDismissMore: () -> Unit,
    onMoreAction: (MoreAction) -> Unit,
    onManagePlaylists: (() -> Unit)?,
    onMatchLyrics: (() -> Unit)?,
    onFindSources: (() -> Unit)?,
    onSleepFinishTrackChange: ((Boolean) -> Unit)?,
    /** Local recordings: back 15 s / forward 30 s, in the place the heart has for songs. */
    onSkip: ((Long) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    // Which page of the overflow menu is showing. It is reset when the menu is opened rather than
    // when it closes, so the card does not change size while it fades out.
    var morePage by remember { mutableStateOf(MorePage.MAIN) }
    Box(Modifier.fillMaxWidth().height(56.dp)) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCollapse) {
                Icon(Icons.Filled.KeyboardArrowDown, "收起", tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.weight(1f))
            if (onSkip != null) {
                IconButton(onClick = { onSkip(-LocalSkipBackMs) }) {
                    Icon(Icons.Outlined.Replay, "后退 15 秒", tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = { onSkip(LocalSkipForwardMs) }) {
                    Icon(Icons.Outlined.Forward30, "前进 30 秒", tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                }
            }
            Box {
                IconButton(onClick = {
                    morePage = MorePage.MAIN
                    onShowMore()
                }) {
                    Icon(
                        Icons.Outlined.MoreHoriz,
                        if (sleepTimerActive) "更多，定时停止已开启" else "更多",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
                if (sleepTimerActive) {
                    // A running timer must stay visible with the menu closed, or it is forgotten.
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 11.dp, end = 11.dp)
                            .size(6.dp).clip(CircleShape).background(cs.primary),
                    )
                }
                MoreMenu(
                    menu = moreMenu,
                    page = morePage,
                    onPage = { morePage = it },
                    onDismiss = onDismissMore,
                    onAction = onMoreAction,
                    onManagePlaylists = onManagePlaylists,
                    onMatchLyrics = onMatchLyrics,
                    onFindSources = onFindSources,
                    onSleepFinishTrackChange = onSleepFinishTrackChange,
                )
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
private fun TitleBlock(ui: PlayerUi, onFindSources: (() -> Unit)? = null) {
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
        // After a source swap the title and artist above stay the queue's song; this says which
        // recording is really playing. Absent otherwise, so the common case keeps its three lines.
        ui.actualSourceLine?.let { line ->
            Text(
                line, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(2.dp))
        val problem = isProblemLine(ui)
        // A failed song offers the way out next to its reason: search the other platforms.
        val canSwitch = onFindSources != null &&
            ui.status == tech.musxi.player.ui.player.PlayerStatus.FAILED && !ui.swapSearching
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f, fill = false)) {
                OpticallyCentredText(
                    thirdLineOf(ui),
                    MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (problem) FontWeight.Medium else FontWeight.Normal,
                    ),
                    color = if (problem) cs.error else cs.onSurfaceVariant,
                )
            }
            if (canSwitch) {
                androidx.compose.material3.TextButton(
                    onClick = { onFindSources?.invoke() },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 32.dp),
                ) {
                    Text("换个音源", style = MaterialTheme.typography.labelMedium, color = cs.primary)
                }
            }
        }
    }
}

/** 48dp: 4dp track (6dp while dragging), 12dp thumb, tabular times underneath. */
@Composable
private fun ProgressBlock(
    ui: PlayerUi,
    palette: PlayerPalette,
    onSeek: (Float) -> Unit,
    dark: Boolean,
    liveProgress: (() -> PlaybackProgress)?,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // Read in this row only: a new position recomposes the bar and its times, not the page.
    val live = liveProgress?.invoke()
    val positionMs = live?.positionMs ?: ui.positionMs
    val fraction = live?.fraction ?: ui.progress
    // While a finger is down, the bar draws the finger's position and nothing else. Without this
    // the service keeps reporting the old position during the drag and the thumb snaps backwards
    // under the finger, which reads as "the app did not hear me".
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shown = dragFraction ?: fraction
    val dragging = dragFraction != null
    Column(modifier.fillMaxWidth().height(48.dp).padding(horizontal = PlayerContentInset)) {
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
                    .background(palette.accent).testTag(PlayerProgressFillTag),
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
                formatDuration(positionMs),
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
    onShowPlayMode: () -> Unit,
    onShowQuality: () -> Unit,
    playModeMenuOpen: Boolean,
    onDismissPlayMode: () -> Unit,
    onSelectPlayMode: (PlayMode) -> Unit,
    qualityMenu: QualityMenuUi?,
    onDismissQuality: () -> Unit,
    onSelectQuality: (String) -> Unit,
    localActions: LocalPlayerActions? = null,
) {
    val cs = MaterialTheme.colorScheme
    val mode = playModeOf(ui.shuffle, ui.repeat)
    Row(
        Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        // One button names the current order and opens the choice, as on PC: the glyph is the mode,
        // so nothing has to be decoded from a shade of grey or a dot.
        // The menu is the button's sibling in one Box so the popup anchors to the button itself.
        Box {
            IconButton(onClick = onShowPlayMode) {
                Icon(mode.icon(), "播放顺序：${mode.label}", tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
            PlayModeMenu(
                expanded = playModeMenuOpen,
                current = mode,
                onDismiss = onDismissPlayMode,
                onSelect = onSelectPlayMode,
            )
        }
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
        if (ui.local) {
            // A local file has one fixed quality: its slot shows the playback speed instead, the
            // control long recordings actually need. Same width, so the transport never moves.
            val speed = localActions
            val onSelectSpeed = speed?.onSelectSpeed
            if (onSelectSpeed != null) {
                // The chooser is the button's sibling in one Box, so the popup anchors to the button.
                var speedMenu by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
                Box {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = { speedMenu = true })
                            .semantics { contentDescription = "播放速度 ${speedLabel(speed.speed)}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            speedLabel(speed.speed),
                            style = MaterialTheme.typography.labelLarge.tabular,
                            color = if (speed.speed == 1f) cs.onSurfaceVariant else palette.accent,
                        )
                    }
                    SpeedMenu(
                        expanded = speedMenu,
                        current = speed.speed,
                        onDismiss = { speedMenu = false },
                        onSelect = { value -> speedMenu = false; if (value != speed.speed) onSelectSpeed(value) },
                    )
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        } else {
            Box {
                IconButton(onClick = onShowQuality) {
                    Icon(Icons.Outlined.HighQuality, "音质选择", tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
                }
                QualityMenu(menu = qualityMenu, onDismiss = onDismissQuality, onSelect = onSelectQuality)
            }
        }
    }
}

/** Glyph for each play order; the button and the chooser rows share it. */
internal fun PlayMode.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    PlayMode.SEQUENTIAL -> Icons.Outlined.Repeat
    PlayMode.SHUFFLE -> Icons.Outlined.Shuffle
    PlayMode.REPEAT_ONE -> Icons.Outlined.RepeatOne
}

/**
 * Task 2: the lyrics page's title bar. The cover lands in the tile on its left (the continuous
 * player draws it there; the tile only reports where it is), and a tap on the tile goes back to the
 * cover. Title, state line, translation and heart arrive once the tile is nearly in place.
 */
@Composable
private fun LyricsTitleBar(
    ui: PlayerUi,
    lyrics: LyricsUi,
    lyricsT: State<Float>,
    surface: PlayerSurfaceLink?,
    palette: PlayerPalette,
    heart: FavoriteUi?,
    onToggleFavorite: () -> Unit,
    onCollectTo: (() -> Unit)?,
    onToggleTranslation: () -> Unit,
    onRefreshTranslation: () -> Unit,
    onBackToCover: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val tileShape = MaterialTheme.shapes.medium
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 24.dp, end = PlayerContentInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(LyricsTransition.TileSize)
                .onGloballyPositioned { coords ->
                    if (surface != null) {
                        val r = coords.unclippedBoundsInRoot()
                        if (surface.probe.tile != r) surface.probe.tile = r
                    }
                }
                .clip(tileShape)
                .clickable(onClickLabel = "回到封面", role = Role.Button, onClick = onBackToCover)
                .semantics { contentDescription = "封面" },
        ) {
            if (surface == null) {
                Cover(
                    seed = ui.coverSeed,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = lyricsT.value },
                    shape = tileShape,
                    url = ui.coverUrl,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).graphicsLayer { alpha = LyricsTransition.barAlpha(lyricsT.value) }) {
            Text(
                ui.title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(ui.artist, ui.album).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                thirdLineOf(ui), style = MaterialTheme.typography.labelSmall,
                color = if (isProblemLine(ui)) cs.error else cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // One centre line and two disjoint 48dp targets, as before the transition existed.
        Column(
            Modifier.width(PlayerActionWidth).graphicsLayer { alpha = LyricsTransition.barAlpha(lyricsT.value) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                TranslationChipButton(
                    lyrics.translation,
                    onToggle = onToggleTranslation,
                    onRefresh = onRefreshTranslation,
                    activeColor = palette.accent,
                    onActiveColor = palette.onAccent,
                )
            }
            if (heart != null) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    FavoriteButton(favorite = heart, onClick = onToggleFavorite, onLongPress = onCollectTo)
                }
            }
        }
    }
}

/** 48dp: output device, lyrics, queue. All neutral — none of these is the page's main action. */
@Composable
private fun ToolRow(
    onShowOutput: () -> Unit,
    onShowLyrics: () -> Unit,
    onShowQueue: () -> Unit,
    lyricsActive: Boolean,
    showLyrics: Boolean = true,
    /** Local recordings: the lyrics slot holds bookmarks. Tap adds one here, long press lists them. */
    bookmarks: LocalPlayerActions? = null,
    outputMenu: OutputMenuUi?,
    onDismissOutput: () -> Unit,
    onSelectOutput: (String) -> Unit,
    onOpenSystemOutput: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Box {
            ToolIcon(Icons.Outlined.Speaker, "输出设备", onShowOutput)
            OutputMenu(
                menu = outputMenu,
                onDismiss = onDismissOutput,
                onSelect = onSelectOutput,
                onOpenSystemOutput = onOpenSystemOutput,
            )
        }
        // The mode has to be visible on the control that opened it: without it the page gives no
        // hint of how to get back to the artwork.
        // The slot stays when the entry is hidden, so output and queue keep their places.
        val add = bookmarks?.onAddBookmark
        when {
            showLyrics -> ToolIcon(Icons.Outlined.Lyrics, "歌词", onShowLyrics, active = lyricsActive)
            add != null -> BookmarkTool(
                count = bookmarks.bookmarkCount,
                onAdd = add,
                onList = bookmarks.onShowBookmarks,
            )
            else -> Spacer(Modifier.size(48.dp))
        }
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
    // Task 2: the three tools move alike — the glyph sinks while held (the heart's press spring)
    // and an active tool's colour eases in rather than switching.
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, Motion.Heart.press(), label = "toolPress")
    val tint by animateColorAsState(if (active) cs.onSurface else cs.onSurfaceVariant, Motion.enter(), label = "toolTint")
    IconButton(onClick = onClick, interactionSource = source) {
        Icon(
            icon, label,
            tint = tint,
            modifier = Modifier.size(24.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        )
    }
}

/**
 * The bookmark button of a local recording: a tap marks where the listener is now, a long press
 * (or a tap when there are bookmarks to show) is not needed to discover them — the count beside the
 * glyph says they exist, and the long press opens the list.
 */
@Composable
private fun BookmarkTool(count: Int, onAdd: () -> Unit, onList: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(
                role = androidx.compose.ui.semantics.Role.Button,
                onClickLabel = "在当前位置加书签",
                onLongClickLabel = if (onList != null) "查看书签" else null,
                onClick = onAdd,
                onLongClick = onList,
            )
            .semantics { contentDescription = if (count > 0) "书签，已有 $count 个" else "书签" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.BookmarkAdd, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
        if (count > 0) {
            Text(
                "$count",
                style = MaterialTheme.typography.labelSmall.tabular,
                color = cs.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 4.dp),
            )
        }
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

/**
 * The large rendition of the cover, drawn over the small one. It is invisible until it has loaded,
 * then fades in; a failed load leaves it invisible and the small image stays.
 */
@Composable
internal fun SharpCover(url: String, modifier: Modifier, shape: Shape) {
    var loaded by remember { mutableStateOf(false) }
    val shown by animateFloatAsState(
        targetValue = if (loaded) 1f else 0f,
        animationSpec = Motion.enter(),
        label = "sharpCover",
    )
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onSuccess = { loaded = true },
        modifier = modifier.clip(shape).graphicsLayer { alpha = shown },
    )
}

/** Eases each colour of [target] on its own, so a new song's light arrives instead of cutting in. */
@Composable
internal fun animatedPalette(target: PlayerPalette): PlayerPalette {
    val top by animateColorAsState(target.backdropTop, Motion.enter(), label = "roomTop")
    val bottom by animateColorAsState(target.backdropBottom, Motion.enter(), label = "roomBottom")
    val accent by animateColorAsState(target.accent, Motion.enter(), label = "roomAccent")
    val onAccent by animateColorAsState(target.onAccent, Motion.enter(), label = "roomOnAccent")
    return PlayerPalette(top, bottom, accent, onAccent)
}
