package tech.musxi.player.ui.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.musxi.core.account.Platform
import tech.musxi.core.library.EditablePlaylist
import tech.musxi.core.library.SongSummary
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.components.LoadMorePill
import tech.musxi.player.ui.components.PlainRow
import tech.musxi.player.ui.components.PlatformSegmented
import tech.musxi.player.ui.components.PlayingBars
import tech.musxi.player.ui.components.ProgressArc
import tech.musxi.player.ui.components.RowDivider
import tech.musxi.player.ui.components.RowSpec
import tech.musxi.player.ui.components.SectionTitle
import tech.musxi.player.ui.components.SongActionsMenu
import tech.musxi.player.ui.components.SongActionsUi
import tech.musxi.player.ui.components.SongRow
import tech.musxi.player.ui.components.listLines
import tech.musxi.player.ui.components.songSubtitle
import tech.musxi.player.ui.model.formatDuration
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.displayName
import tech.musxi.player.ui.theme.tabular

/** Platforms this page can search. Search is always one explicit platform, never merged. */
val searchPlatforms = listOf(Platform.NETEASE, Platform.QQ, Platform.KUGOU)

/**
 * Search page. One platform at a time, five result states, and the only primary action on the
 * page is "retry" when a search failed — everything else stays quiet.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onKeywordsChange: (String) -> Unit,
    onPlatformSelect: (Platform) -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onSongClick: (SongSummary) -> Unit = {},
    /** Room for the floating dock: the page scrolls underneath it. */
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    onRecentClick: (String) -> Unit = {},
    onRecentRemove: (String) -> Unit = {},
    onRecentClear: () -> Unit = {},
    /** Stable id of the track that is playing, so its row can say so. */
    nowPlayingId: String? = null,
    /** A row gets its "more" button when at least one of the menu actions below is given. */
    onPlayNext: ((SongSummary) -> Unit)? = null,
    onAddToQueue: ((SongSummary) -> Unit)? = null,
    /** What the menu can do for this song right now: liked or not, and whether favouriting works. */
    songActions: (SongSummary) -> SongActionsUi = { SongActionsUi() },
    onToggleFavorite: ((SongSummary) -> Unit)? = null,
    /** Reads the platform's writable playlists; the page that lists them is the menu's second page. */
    loadPlaylists: ((Platform, (Result<List<EditablePlaylist>>) -> Unit) -> Unit)? = null,
    onAddToPlaylist: ((SongSummary, EditablePlaylist) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = Space.page)) {
        Spacer(Modifier.height(Space.section))
        // Same size as the library title; the one-line note that used to sit under it now lives in
        // the idle state, where it is read once instead of on every result.
        Text("搜索", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
        Spacer(Modifier.height(16.dp))
        SearchField(state.keywords, onKeywordsChange, onSearch)
        Spacer(Modifier.height(12.dp))
        PlatformSegmented(
            platforms = searchPlatforms,
            selected = state.platform,
            onSelect = onPlatformSelect,
        )
        Spacer(Modifier.height(8.dp))
        // The state area owns the remaining height; each state fills it on its own terms.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (state.phase) {
                // The platform is already chosen, so the hint names it instead of asking for a choice.
                SearchPhase.IDLE -> IdlePanel(
                    recent = state.recent,
                    hint = "在${state.platform.displayName}中搜索歌曲，结果按平台分别显示",
                    onRecentClick = onRecentClick,
                    onRecentRemove = onRecentRemove,
                    onRecentClear = onRecentClear,
                )
                SearchPhase.LOADING -> ResultSkeleton()
                SearchPhase.EMPTY -> EmptyResult(
                    otherPlatforms = searchPlatforms.filter { it != state.platform },
                    onPlatformSelect = onPlatformSelect,
                )
                SearchPhase.ERROR -> Failure(state.errorText.orEmpty(), onRetry)
                SearchPhase.RESULTS -> ResultList(
                    state = state,
                    onLoadMore = onLoadMore,
                    onSongClick = onSongClick,
                    bottomInset = bottomInset,
                    nowPlayingId = nowPlayingId,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    songActions = songActions,
                    onToggleFavorite = onToggleFavorite,
                    loadPlaylists = loadPlaylists,
                    onAddToPlaylist = onAddToPlaylist,
                )
            }
        }
    }
}

@Composable
private fun SearchField(keywords: String, onChange: (String) -> Unit, onSearch: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val background by animateColorAsState(
        targetValue = if (focused) cs.surfaceContainerHighest else cs.surfaceContainerHigh,
        label = "searchFieldBackground",
    )
    // Fade the same hue in and out; animating from `Color.Transparent` would pass through grey.
    val outline by animateColorAsState(
        targetValue = cs.primary.copy(alpha = if (focused) 0.35f else 0f),
        label = "searchFieldOutline",
    )
    BasicTextField(
        value = keywords,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(background, CircleShape)
            .border(1.dp, outline, CircleShape)
            .onFocusChanged { focused = it.isFocused },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
        cursorBrush = SolidColor(cs.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            onSearch()
            keyboard?.hide()
        }),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxSize().padding(start = 14.dp, end = if (keywords.isEmpty()) 14.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Search, null,
                    modifier = Modifier.size(20.dp), tint = cs.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (keywords.isEmpty()) {
                        Text(
                            "歌名、歌手或专辑",
                            style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
                if (keywords.isNotEmpty()) {
                    // 24dp is the look; the 32dp circle around it is the part a thumb can hit.
                    Box(
                        Modifier.size(32.dp).clip(CircleShape)
                            .clickable(role = Role.Button) { onChange("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(24.dp)
                                .background(cs.onSurfaceVariant.copy(alpha = 0.18f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Close, "清除",
                                modifier = Modifier.size(14.dp), tint = cs.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ResultList(
    state: SearchUiState,
    onLoadMore: () -> Unit,
    onSongClick: (SongSummary) -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp,
    nowPlayingId: String?,
    onPlayNext: ((SongSummary) -> Unit)?,
    onAddToQueue: ((SongSummary) -> Unit)?,
    songActions: (SongSummary) -> SongActionsUi,
    onToggleFavorite: ((SongSummary) -> Unit)?,
    loadPlaylists: ((Platform, (Result<List<EditablePlaylist>>) -> Unit) -> Unit)?,
    onAddToPlaylist: ((SongSummary, EditablePlaylist) -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    // Stable id of the row whose "more" menu is open, if any.
    var menuFor by remember { mutableStateOf<String?>(null) }
    val best = state.items.firstOrNull()
    val rest = remember(state.items) { state.items.drop(1) }
    val listState = rememberLazyListState()
    // Dragging the list means the user is reading results, so the keyboard gets out of the way.
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) keyboard?.hide()
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = bottomInset),
    ) {
        if (best != null) {
            item(key = "best:${best.key.stableId}") {
                Column {
                    // The heading sits above the row like every other group's; the row itself no
                    // longer carries a label. 8dp under the segmented control plus this is Space.section.
                    SectionTitle("最佳匹配", Modifier.padding(top = Space.sibling))
                    BestMatchCard(
                        song = best,
                        playing = best.key.stableId == nowPlayingId,
                        onClick = {
                            keyboard?.hide()
                            onSongClick(best)
                        },
                    )
                }
            }
        }
        if (rest.isNotEmpty()) {
            item(key = "section-title") {
                SectionTitle(
                    "歌曲 · 共 ${state.total ?: state.items.size} 首",
                    Modifier.padding(top = Space.section),
                )
            }
        }
        itemsIndexed(rest, key = { _, song -> song.key.stableId }) { index, song ->
            val id = song.key.stableId
            // The button is there when the menu has anything to offer; each entry appears only if its
            // callback was given, so a screen that gives none (the design sheets) shows no button.
            val canMore = onPlayNext != null || onAddToQueue != null || onToggleFavorite != null ||
                (loadPlaylists != null && onAddToPlaylist != null)
            SongRow(
                song = song,
                highlight = state.keywords,
                playing = id == nowPlayingId,
                onMore = if (canMore) ({ menuFor = id }) else null,
                menu = if (canMore) ({
                    SongActionsMenu(
                        expanded = menuFor == id,
                        onDismiss = { menuFor = null },
                        ui = songActions(song),
                        onPlayNext = if (onPlayNext != null) ({ onPlayNext(song) }) else null,
                        onAddToQueue = if (onAddToQueue != null) ({ onAddToQueue(song) }) else null,
                        onToggleFavorite = if (onToggleFavorite != null) ({ onToggleFavorite(song) }) else null,
                        loadPlaylists = if (loadPlaylists != null) {
                            { done -> loadPlaylists(song.key.platform, done) }
                        } else null,
                        onAddToPlaylist = if (onAddToPlaylist != null) {
                            { target -> onAddToPlaylist(song, target) }
                        } else null,
                    )
                }) else null,
            ) {
                keyboard?.hide()
                onSongClick(song)
            }
            // The hairline is the app-wide separator: account, library and search all use it. It
            // starts under the title text, not under the cover, and the last row has none.
            if (index < rest.lastIndex) RowDivider(start = RowSpec.textStart(RowSpec.cover))
        }
        item {
            if (state.loadingMore) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProgressArc(color = cs.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text("正在加载…", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            } else if (state.canLoadMore) {
                LoadMorePill(loading = false, onClick = onLoadMore)
            } else if (state.items.isNotEmpty()) {
                Text(
                    "已显示全部 ${state.total ?: state.items.size} 首",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
            }
        }
    }
}

/**
 * The first result, given more room than the rest: a 96dp cover, a larger title and a play button.
 * Hierarchy comes from size and space, not from a coloured block. Its heading ("最佳匹配") is the
 * group title above it, so the row does not repeat it.
 */
@Composable
private fun BestMatchCard(song: SongSummary, playing: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    PlainRow(onClick = onClick) {
        Cover(
            seed = song.album.ifBlank { song.key.stableId },
            modifier = Modifier.size(RowSpec.coverTile),
            shape = MaterialTheme.shapes.medium,
            url = song.coverUrl,
        )
        Spacer(Modifier.width(Space.inner))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, color = cs.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                songSubtitle(song), style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                maxLines = listLines(), overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                formatDuration(song.durationMs),
                style = MaterialTheme.typography.labelSmall.tabular, color = cs.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        // The theme's primary, not the colour of the track that is playing: this button is the one
        // fill on the page and should look the same whatever is on.
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .background(cs.primary)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (playing) {
                PlayingBars(cs.onPrimary)
            } else {
                Icon(
                    Icons.Filled.PlayArrow, contentDescription = "播放",
                    tint = cs.onPrimary, modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * Stand-in for the result list while the first page loads. It is deliberately still: a looping
 * shimmer would never let the screenshot tests go idle.
 */
@Composable
private fun ResultSkeleton() {
    val block = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val shape = MaterialTheme.shapes.small
    Column(Modifier.fillMaxSize().semantics { contentDescription = "正在搜索" }) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp).background(block, shape))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth(0.3f).height(12.dp).background(block, shape))
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth(0.75f).height(20.dp).background(block, shape))
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth(0.5f).height(14.dp).background(block, shape))
            }
        }
        Spacer(Modifier.height(28.dp))
        repeat(6) {
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).background(block, shape))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(0.6f).height(14.dp).background(block, shape))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth(0.4f).height(12.dp).background(block, shape))
                }
            }
        }
    }
}

/**
 * Idle page. With history it leads with the recent terms and puts the scope note underneath; with
 * none, the note alone holds the place the old hint had.
 */
@Composable
private fun IdlePanel(
    recent: List<String>,
    hint: String,
    onRecentClick: (String) -> Unit,
    onRecentRemove: (String) -> Unit,
    onRecentClear: () -> Unit,
) {
    // Declared before the early return: deleting the last term must be able to switch editing off.
    var editing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(recent.isEmpty()) {
        if (recent.isEmpty()) editing = false
    }
    if (recent.isEmpty()) {
        Hint(hint)
        return
    }
    val cs = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    // Ten terms can wrap onto several lines; scrolling keeps the last one reachable above the keyboard.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
        // The group heading every page uses; the text actions sit on its line.
        SectionTitle("最近搜索") {
            if (editing) {
                RecentAction("全部清除", cs.error, onClick = onRecentClear)
                RecentAction("完成", cs.onSurfaceVariant, onClick = { editing = false })
            } else {
                RecentAction("编辑", cs.onSurfaceVariant, onClick = { editing = true })
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            recent.forEach { term ->
                RecentChip(
                    term,
                    editing = editing,
                    onClick = {
                        if (editing) {
                            onRecentRemove(term)
                        } else {
                            keyboard?.hide()
                            onRecentClick(term)
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(hint, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

/**
 * A text button at the right end of the "最近搜索" heading. Padding is only on the inner side, so the
 * last word still ends on the page margin.
 */
@Composable
private fun RecentAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label, style = MaterialTheme.typography.labelLarge, color = color,
        modifier = Modifier.clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
    )
}

/**
 * One past term. Tap searches it again; while [editing], a tap anywhere on the chip removes it and
 * a small cross at its end says so.
 */
@Composable
private fun RecentChip(term: String, editing: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.height(34.dp).clip(CircleShape)
            .background(cs.surfaceContainerHigh)
            .clickable(
                role = Role.Button,
                onClickLabel = if (editing) "删除这条搜索记录" else null,
                onClick = onClick,
            )
            .padding(start = 14.dp, end = if (editing) 9.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A term can be 64 characters, so this must stay on one line and cut with an ellipsis. That
        // rules out `OpticallyCentredText`; the same 1dp CJK lift is applied by hand instead.
        Text(
            term, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).offset(y = (-1).dp),
        )
        if (editing) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(16.dp).background(cs.onSurfaceVariant.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close, contentDescription = null,
                    modifier = Modifier.size(11.dp), tint = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Text(
            text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 48.dp),
        )
    }
}

/**
 * "Searched, found nothing" is a success, not a failure. It gets a plain title and one piece of
 * advice — no red, no warning glyph, no retry button, because retrying the same query is useless.
 * The keyword itself stays in the field above, so it is not repeated here.
 */
@Composable
private fun EmptyResult(otherPlatforms: List<Platform>, onPlatformSelect: (Platform) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("未找到相关歌曲", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                "试试其他关键词，或切换平台",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            if (otherPlatforms.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                // Choosing a platform re-runs the search with the keyword already in the field.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    otherPlatforms.forEach { platform ->
                        Box(
                            Modifier.height(34.dp).clip(CircleShape)
                                .background(cs.surfaceContainerHigh)
                                .clickable(role = Role.Button) { onPlatformSelect(platform) }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "在${platform.displayName}中搜索",
                                style = MaterialTheme.typography.labelLarge, color = cs.onSurface,
                                maxLines = 1,
                                modifier = Modifier.offset(y = (-1).dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Failure(text: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Text("重试", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
