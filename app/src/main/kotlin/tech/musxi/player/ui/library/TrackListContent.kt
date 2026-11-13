package tech.musxi.player.ui.library

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.musxi.core.library.PlaylistSummary
import tech.musxi.core.library.SongSummary
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.components.LoadMorePill
import tech.musxi.player.ui.components.PlayingBars
import tech.musxi.player.ui.components.PlainRow
import tech.musxi.player.ui.components.PlatformTag
import tech.musxi.player.ui.components.RowSpec
import tech.musxi.player.ui.components.SongActionsMenu
import tech.musxi.player.ui.components.SongActionsUi
import tech.musxi.player.ui.components.listLines
import tech.musxi.player.ui.components.songSubtitle
import tech.musxi.player.ui.model.formatDuration
import tech.musxi.player.ui.shell.PageTopBar
import tech.musxi.player.ui.shell.TitleProbe
import tech.musxi.player.ui.shell.TopBarHeight
import tech.musxi.player.ui.shell.hasShellTopBar
import tech.musxi.player.ui.shell.largeTitle
import tech.musxi.player.ui.shell.rememberTitleProbe
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.nowPlayingAccent
import tech.musxi.player.ui.theme.tabular

/**
 * How a track line looks. NUMBERED is the shipped reading; COVER is a recorded alternative.
 */
enum class TrackRowStyle {
    /** No cover, the position number instead. */
    NUMBERED,

    /** A 40dp cover per row: flush by construction, but a screen of covers is louder. */
    COVER,
}

/**
 * Where the number sits inside [TrackRowStyle.NUMBERED].
 *
 * The first attempt right-aligned it in a 32dp column starting at the page margin, which put the
 * ink of a single digit at 44dp while the durations ended at 340dp. Measured on the render, that is
 * 23dp of asymmetry, and the owner spotted it immediately as "left wide, right narrow". The fix is
 * the same rule the right edge already followed: the outer edge is fixed at the page margin and the
 * ragged side faces inward.
 */
enum class NumberStyle {
    /**
     * Shipped. Page margin, 24dp column, natural digits, one size smaller than the title and 70%
     * of the variant colour, aligned to the title's baseline rather than centred in the row.
     */
    FLUSH_SMALL,

    /**
     * Recorded alternative. Zero-padded to the playlist's own digit count, which freezes the inner
     * edge too — at the cost of spelling "002" in a long playlist.
     */
    ZERO_PAD,
}

private data class NumberSlot(val width: Dp, val padded: Boolean)

private fun NumberStyle.slot(digits: Int): NumberSlot = when (this) {
    // Three-digit playlists need the wider column. The direction is explicit that the whole list
    // switches together rather than row by row.
    NumberStyle.ZERO_PAD -> NumberSlot(if (digits >= 3) 28.dp else 20.dp, true)
    NumberStyle.FLUSH_SMALL -> NumberSlot(24.dp, false)
}

private fun NumberStyle.label(index: Int, digits: Int): String =
    if (this == NumberStyle.ZERO_PAD) "${index + 1}".padStart(digits, '0') else "${index + 1}"

/**
 * One playlist's tracks: the library tab's second level.
 *
 * Not an overlay. The full player is the app's only overlay, and the reason is not taste: playing
 * feedback lives in the dock, and an overlay would cover the dock at the exact moment the user taps
 * a song. This page keeps the dock, so tapping a track shows the mini player change artwork.
 *
 * The header keeps the playlist's known name and cover even when the read failed — a page that goes
 * blank on failure loses the only context the user has.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackListContent(
    state: TrackListUiState,
    dark: Boolean,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onSongClick: (SongSummary) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    /** Pull-to-refresh: re-read the first page from the platform. */
    onPullRefresh: () -> Unit = {},
    /** Identity of the track that is playing right now, if it belongs to this list. */
    nowPlayingId: String? = null,
    /** The row the user just tapped, while its queue is still being prepared. */
    pendingSongId: String? = null,
    /** Room for the floating dock: content scrolls under it, so the last row must clear it. */
    bottomInset: Dp = 0.dp,
    rowStyle: TrackRowStyle = TrackRowStyle.NUMBERED,
    numberStyle: NumberStyle = NumberStyle.FLUSH_SMALL,
    /** The same song menu search has. A row gets its "more" button when any action below is given. */
    songActions: (SongSummary) -> SongActionsUi = { SongActionsUi() },
    onPlayNext: ((SongSummary) -> Unit)? = null,
    onAddToQueue: ((SongSummary) -> Unit)? = null,
    onToggleFavorite: ((SongSummary) -> Unit)? = null,
    onManagePlaylists: ((SongSummary) -> Unit)? = null,
    /** Search inside this playlist. Without [onSearch] the page has no search button. */
    search: PlaylistSearchUi = PlaylistSearchUi(),
    onOpenSearch: () -> Unit = {},
    onSearch: ((String) -> Unit)? = null,
    onCloseSearch: () -> Unit = {},
    /** Reads the rest of the playlist so the search covers it all; null offers nothing. */
    onFillForSearch: (() -> Unit)? = null,
    onCancelFill: () -> Unit = {},
) {
    val playlist = state.playlist ?: return
    // Every number in the list pads to the same width, decided once from the playlist's own count.
    val digits = maxOf(2, playlist.trackCount.toString().length)
    val canMore = onPlayNext != null || onAddToQueue != null || onToggleFavorite != null || onManagePlaylists != null
    // Stable id of the row whose menu is open, if any.
    var menuFor by remember { mutableStateOf<String?>(null) }
    val trackList = androidx.compose.foundation.lazy.rememberLazyListState()
    val searching = search.open && onSearch != null
    // Under the shell the back and search buttons sit on its glass top bar, and the list runs
    // beneath it; the playlist's name moves onto the bar once the header's has gone under it.
    val shellBar = hasShellTopBar() && !searching
    val titleProbe = rememberTitleProbe()
    titleProbe.away = { trackList.firstVisibleItemIndex > 0 }
    val searchButton: (@Composable () -> Unit)? = if (onSearch != null) {
        { SearchButton(onOpenSearch) }
    } else {
        null
    }
    if (shellBar) {
        PageTopBar(
            title = playlist.title,
            probe = titleProbe,
            scrolled = {
                if (trackList.firstVisibleItemIndex > 0) Float.MAX_VALUE
                else trackList.firstVisibleItemScrollOffset.toFloat()
            },
            leading = { BackButton(onBack) },
            trailing = searchButton,
        )
    }
    Column(Modifier.fillMaxSize().padding(horizontal = Space.page)) {
        if (searching) {
            PlaylistSearchBar(query = search.query, onChange = onSearch, onClose = onCloseSearch)
        } else if (!shellBar) {
            TopBar(onBack, onSearch = if (onSearch != null) onOpenSearch else null)
        }
        val pull = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onPullRefresh,
            modifier = Modifier.fillMaxSize(),
            state = pull,
            // Under the shell's bar the spinner comes out below it, not behind the back button.
            indicator = {
                androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                    state = pull,
                    isRefreshing = state.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = if (shellBar) TopBarHeight else 0.dp),
                )
            },
        ) {
            // As on the library page: the next page loads by itself near the end; the footer pill
            // is the retry after a failure.
            tech.musxi.player.ui.components.AutoLoadMore(
                listState = trackList,
                canLoadMore = state.canLoadMore,
                failed = state.error != null,
                onLoadMore = onLoadMore,
            )
            LazyColumn(
                Modifier.fillMaxSize(),
                state = trackList,
                contentPadding = PaddingValues(top = if (shellBar) TopBarHeight else 0.dp, bottom = bottomInset),
            ) {
                if (search.active) {
                    item { SearchNote(search, onFillForSearch, onCancelFill) }
                    // Row numbers stay the song's place in the playlist, so a result still says
                    // where it sits.
                    val positions = state.items.withIndex().associate { (i, s) -> s.key.stableId to i }
                    itemsIndexed(search.results, key = { _, song -> "found-" + song.key.stableId }) { _, song ->
                        TrackLine(
                            index = positions[song.key.stableId] ?: 0,
                            song = song,
                            style = rowStyle,
                            numberStyle = numberStyle,
                            digits = digits,
                            playing = song.key.stableId == nowPlayingId,
                            preparing = song.key.stableId == pendingSongId,
                            onClick = { onSongClick(song) },
                        )
                    }
                    return@LazyColumn
                }
                item { Header(state, playlist, dark, titleProbe) }
                item { ActionRow(state, onPlayAll) }
                when {
                    state.items.isEmpty() && state.loading -> item { LoadingBlock() }
                    state.items.isEmpty() && state.error != null -> item { FailureBlock(onRetry) }
                    else -> itemsIndexed(
                        state.items,
                        key = { _, song -> song.key.stableId },
                    ) { index, song ->
                        TrackLine(
                            index = index,
                            song = song,
                            style = rowStyle,
                            numberStyle = numberStyle,
                            digits = digits,
                            playing = song.key.stableId == nowPlayingId,
                            preparing = song.key.stableId == pendingSongId,
                            onClick = { onSongClick(song) },
                            onMore = if (canMore) ({ menuFor = song.key.stableId }) else null,
                            menu = if (canMore) ({
                                SongActionsMenu(
                                    expanded = menuFor == song.key.stableId,
                                    onDismiss = { menuFor = null },
                                    ui = songActions(song),
                                    onPlayNext = onPlayNext?.let { act -> { act(song) } },
                                    onAddToQueue = onAddToQueue?.let { act -> { act(song) } },
                                    onToggleFavorite = onToggleFavorite?.let { act -> { act(song) } },
                                    onManagePlaylists = onManagePlaylists?.let { act -> { act(song) } },
                                )
                            }) else null,
                        )
                    }
                }
                item { Footer(state, onLoadMore, onRetry) }
            }
        }
    }
}

/** 56dp. Back is the only thing here; the playlist name takes over once it scrolls out. */
@Composable
private fun TopBar(onBack: () -> Unit, onSearch: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton(onBack)
        Spacer(Modifier.weight(1f))
        if (onSearch != null) SearchButton(onSearch)
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack, "返回",
            tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun SearchButton(onSearch: () -> Unit) {
    IconButton(onClick = onSearch) {
        Icon(
            Icons.Outlined.Search, "在歌单中搜索",
            tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * The search box that takes the top bar's place while searching this playlist. It opens focused
 * with the keyboard up; "取消" closes it and brings the whole playlist back.
 */
@Composable
private fun PlaylistSearchBar(query: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    androidx.activity.compose.BackHandler(onBack = onClose)
    Row(
        Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = query,
            onValueChange = onChange,
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clip(CircleShape)
                .background(cs.surfaceContainerHigh)
                .focusRequester(focus),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary),
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                "在歌单中搜索歌名、歌手或专辑",
                                style = MaterialTheme.typography.bodyLarge,
                                color = cs.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                }
            },
        )
        Text(
            "取消",
            style = MaterialTheme.typography.bodyLarge,
            color = cs.primary,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClose)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** One quiet line above the results: what was searched, so "nothing found" is never misread. */
@Composable
private fun SearchNote(search: PlaylistSearchUi, onFill: (() -> Unit)?, onCancelFill: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // The line always takes its height, even before the first answer, so the results never jump
    // up or down when it appears; its text only changes when an answer lands.
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                playlistSearchNote(search).orEmpty(),
                style = MaterialTheme.typography.bodySmall.tabular,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            search.fillError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // A partly read playlist offers to read the rest; while that runs, it can be stopped.
        val action = when {
            search.filling -> "停止" to onCancelFill
            search.canFill && onFill != null -> "载入全部再搜" to onFill
            else -> null
        }
        if (action != null) {
            Text(
                action.first,
                style = MaterialTheme.typography.labelLarge,
                color = cs.primary,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = action.second)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * 112dp cover, name, then one line of numbers, then the platform.
 *
 * The cover is larger than the 48dp it has in the library on purpose: it is the same object held
 * closer, which is where the sense of having gone one level down comes from. It is not a second
 * artwork — there is still exactly one cover on this page.
 *
 * The second line holds the count and, when it applies, the cache note. The song-count total time
 * the direction asked for is not available: durations only exist for the pages that have been read,
 * so printing "2 hours 18 minutes" would describe 40 of 746 tracks.
 */
@Composable
private fun Header(state: TrackListUiState, playlist: PlaylistSummary, dark: Boolean, titleProbe: TitleProbe? = null) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(top = Space.section),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(
            seed = playlist.key.stableId,
            modifier = Modifier.size(112.dp),
            shape = MaterialTheme.shapes.large,
            url = playlist.coverUrl,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                playlist.title,
                style = MaterialTheme.typography.headlineSmall,
                color = cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = if (titleProbe != null) Modifier.largeTitle(titleProbe) else Modifier,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                listOfNotNull(
                    "${playlist.trackCount} 首",
                    freshnessLabel(state.fetchedAtEpochMs, System.currentTimeMillis())
                        .takeIf { state.error == null },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium.tabular,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            PlatformTag(playlist.key.platform, dark)
        }
    }
}

/**
 * 40dp capsule plus one line of progress. Neutral: on this page the accent marks the playing row
 * and nothing else.
 */
@Composable
private fun ActionRow(state: TrackListUiState, onPlayAll: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val total = state.playlist?.trackCount ?: state.items.size
    // A flow, so at large font sizes the progress note wraps under the button instead of being cut.
    FlowRow(
        Modifier.fillMaxWidth().padding(top = Space.section),
        horizontalArrangement = Arrangement.spacedBy(Space.sibling),
        verticalArrangement = Arrangement.spacedBy(RowSpec.vertical),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onPlayAll,
            enabled = state.items.isNotEmpty(),
            modifier = Modifier.height(40.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = cs.surfaceContainerHigh,
                contentColor = cs.onSurface,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("播放全部", style = MaterialTheme.typography.labelLarge)
        }
        if (state.items.size < total) {
            Text(
                "已载入 ${state.items.size} / $total",
                style = MaterialTheme.typography.bodySmall.tabular,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
}

/**
 * One track.
 *
 * The number is a label for the position, not a heading: it is smaller than the title, quieter than
 * the subtitle, and sits on the title's baseline. Centring it in a 56dp row was the version that
 * made it hang between the two text lines, belonging to neither.
 */
@Composable
private fun TrackLine(
    index: Int,
    song: SongSummary,
    style: TrackRowStyle,
    numberStyle: NumberStyle,
    digits: Int,
    playing: Boolean,
    preparing: Boolean = false,
    onClick: () -> Unit,
    onMore: (() -> Unit)? = null,
    menu: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val slot = numberStyle.slot(digits)
    // The number grows with the font, so "10" is not cut to "1" at 200%; never below its dp width.
    val slotWidth = slot.width * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val lines = listLines()
    val accent = nowPlayingAccent()
    // A PlainRow like every list row: the content plus the shared padding, 56dp at least, so the row
    // grows with the font instead of clipping its two lines.
    PlainRow(onClick = onClick) {
        if (style == TrackRowStyle.COVER) {
            Cover(
                seed = song.album.ifBlank { song.key.stableId },
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.small,
                url = song.coverUrl,
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Row {
                if (style == TrackRowStyle.NUMBERED) {
                    if (playing) {
                        // Same slot, same left edge: the signal replaces the number instead of
                        // being added next to it.
                        Box(
                            Modifier.width(slotWidth).height(20.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            PlayingBars(accent)
                        }
                    } else {
                        Text(
                            numberStyle.label(index, digits),
                            // Baseline, not vertical centre: the number reads as the start of the
                            // title's line instead of floating between the two lines.
                            modifier = Modifier.width(slotWidth).alignByBaseline(),
                            style = MaterialTheme.typography.labelMedium.tabular.copy(
                                fontWeight = FontWeight.Normal,
                            ),
                            color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Start,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    song.title,
                    modifier = Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.titleSmall,
                    // A cover row has no index slot to replace, so the playing signal is the one
                    // change that adds no element: the title, already where the eye lands.
                    color = if (playing && style == TrackRowStyle.COVER) accent else cs.onSurface,
                    maxLines = lines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (preparing) "正在准备…" else songSubtitle(song),
                style = MaterialTheme.typography.bodySmall,
                color = if (preparing) accent else cs.onSurfaceVariant,
                maxLines = lines,
                overflow = TextOverflow.Ellipsis,
                modifier = if (style == TrackRowStyle.NUMBERED) {
                    Modifier.padding(start = slotWidth + 12.dp)
                } else {
                    Modifier
                },
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            formatDuration(song.durationMs),
            style = MaterialTheme.typography.bodySmall.tabular,
            color = cs.onSurfaceVariant,
        )
        if (onMore != null) {
            // Same button as a search row; the menu is its sibling so it anchors to the button.
            Box {
                IconButton(onClick = onMore, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.MoreVert, contentDescription = "更多操作",
                        tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp),
                    )
                }
                menu?.invoke()
            }
        }
    }
}

@Composable
private fun LoadingBlock() {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
        Text("正在读取曲目…", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
}

/** The header still names the playlist; only the list area explains and offers the way out. */
@Composable
private fun FailureBlock(onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("网络异常，没能读取曲目", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            "刷新一下试试，或者稍后再来",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("重新读取", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * The end of the list, or the next page.
 *
 * "Load more" is a tap, not an automatic trigger, to match the library and search pages: a list
 * that appends by itself while the user is reading is harder to trust than one that waits.
 */
@Composable
private fun Footer(state: TrackListUiState, onLoadMore: () -> Unit, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val total = state.playlist?.trackCount
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
        when {
            state.error != null && state.items.isNotEmpty() -> Text(
                "第 ${state.items.size} 首之后读取失败 · 重试",
                style = MaterialTheme.typography.labelMedium.tabular,
                color = cs.onSurface,
                modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onRetry)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            state.canLoadMore || (state.loading && state.items.isNotEmpty()) ->
                LoadMorePill(loading = state.loading, onClick = onLoadMore)
            state.loading -> Text(
                "正在读取…",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            state.items.isNotEmpty() -> Text(
                if (total != null) "共 $total 首" else "已载入 ${state.items.size} 首",
                style = MaterialTheme.typography.labelMedium.tabular,
                color = cs.onSurfaceVariant,
            )
        }
    }
}
