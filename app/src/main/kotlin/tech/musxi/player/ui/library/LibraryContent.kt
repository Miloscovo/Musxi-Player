package tech.musxi.player.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.musxi.core.account.Platform
import tech.musxi.core.library.PlaylistKind
import tech.musxi.core.library.PlaylistSummary
import tech.musxi.core.library.SongSummary
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.components.CoverOutline
import tech.musxi.player.ui.components.LoadMorePill
import tech.musxi.player.ui.components.PlainRow
import tech.musxi.player.ui.components.PlatformSegmented
import tech.musxi.player.ui.components.RowDivider
import tech.musxi.player.ui.components.RowSpec
import tech.musxi.player.ui.components.RowText
import tech.musxi.player.ui.components.SectionCard
import tech.musxi.player.ui.components.SectionTitle
import tech.musxi.player.ui.components.pressableRow
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.displayName
import tech.musxi.player.ui.theme.tabular

/**
 * The library tab. Three modules sit in cards, each under its own heading: a way back into what was
 * playing (继续播放), the liked-songs playlist (我喜欢) and a strip of recent plays (最近播放). Below
 * them the playlists are a long list, so they lie on the page colour as rows led by a 48dp cover,
 * with a divider that starts at the text. See `Sections.kt` for the rule behind that split.
 *
 * The list is the default. The header button switches to a 3-column or 2-column grid of covers
 * ([LibraryDensity]), which is opt-in because the owner of the product prefers the list. Hierarchy
 * comes from type size, white space and cover size, not from colour blocks.
 *
 * Horizontal page margins are applied per item rather than on the whole column, and the recent
 * strip is padded by its own `contentPadding` inside its card, see [RecentStrip].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryContent(
    state: PlaylistUiState,
    onSelectFilter: (LibraryFilter) -> Unit,
    onLoadMore: () -> Unit = {},
    onOpenPlaylist: (PlaylistSummary) -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onRefresh: () -> Unit = {},
    /** Pull-to-refresh: re-read the playlists from the platform. */
    onPullRefresh: () -> Unit = {},
    /** Room for the floating dock: content scrolls under it, so the last row must clear it. */
    bottomInset: Dp = 0.dp,
    /** Hoisted so tapping the library tab again can scroll back to the top. */
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    /** Account name, the saved queue to continue and recent plays for the selected platform. */
    home: LibraryHomeUi = LibraryHomeUi(),
    /** The song playing now, so its cover on the recent strip can say so. */
    nowPlayingId: String? = null,
    onResume: () -> Unit = {},
    onPlayRecent: (SongSummary) -> Unit = {},
    /** Long press on a recent cover: take the song off the recent strip. */
    onRemoveRecent: (SongSummary) -> Unit = {},
    /** Starting density. Null reads the saved choice; tests pass a value to skip the preferences. */
    initialDensity: LibraryDensity? = null,
) {
    val cs = MaterialTheme.colorScheme
    val pageInset = Modifier.padding(horizontal = Space.page)
    val context = LocalContext.current
    val densityStore = remember(context) { LibraryDensityStore(libraryDensityPrefs(context)) }
    var density by remember { mutableStateOf(initialDensity ?: densityStore.load()) }
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(Space.section))
        Row(
            pageInset.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "曲库", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface,
                modifier = Modifier.weight(1f),
            )
            // Tapping walks list, compact grid, large grid. The label names the current layout.
            IconButton(onClick = {
                density = density.next()
                densityStore.save(density)
            }) {
                Icon(
                    density.icon(),
                    contentDescription = "歌单显示：${density.label}，点按切换",
                    tint = cs.onSurfaceVariant,
                )
            }
        }
        val status = statusLine(state, System.currentTimeMillis())
        Text(
            if (home.accountName != null) "${home.accountName} · $status" else status,
            style = MaterialTheme.typography.bodySmall.tabular,
            color = cs.onSurfaceVariant,
            modifier = pageInset,
        )
        Spacer(Modifier.height(12.dp))

        // Only real platforms: `ALL` has no platform and can never load a page, so it used to
        // render as a permanently disabled chip that read as a broken option.
        val platformFilters = LibraryFilter.entries.filter { it.platform != null }
        PlatformSegmented(
            platforms = platformFilters.mapNotNull { it.platform },
            selected = state.filter.platform ?: Platform.NETEASE,
            onSelect = { platform ->
                platformFilters.firstOrNull { it.platform == platform }?.let(onSelectFilter)
            },
            modifier = pageInset,
        )
        // Room for the first heading below, the same gap the headings have above them.
        Spacer(Modifier.height(Space.sibling))
        // A failed refresh with nothing cached says it once, inside the empty state.
        if (state.error != null && state.items.isNotEmpty() && !isAccountError(state.error)) {
            Text(
                "刷新失败：${state.error}",
                style = MaterialTheme.typography.bodySmall, color = cs.error,
                modifier = pageInset,
            )
        }
        // Pull down anywhere on the list, including the empty state, to re-read from the platform.
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onPullRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.items.isEmpty() && !state.loading) {
                LibraryEmpty(
                    failed = state.error != null && !isAccountError(state.error),
                    connected = state.accountVerified,
                    platformName = state.filter.platform?.displayName ?: "平台",
                    onOpenAccount = onOpenAccount,
                    onRefresh = onRefresh,
                    bottomInset = bottomInset,
                )
            } else {
                // The liked list is a module of its own above the playlists; the rest is the list.
                val featured = remember(state.items) { state.items.firstOrNull(::isLikedPlaylist) }
                val rest = remember(state.items, featured) {
                    if (featured == null) state.items else state.items.filter { it.key != featured.key }
                }
                val sections = remember(rest) { groupPlaylists(rest) }
                val hasModules = home.resume != null || featured != null || home.recent.isNotEmpty()
                // With nothing above it the page is already clear: a heading would only label the one
                // list there is. A titled section names itself.
                val playlistsTitle = hasModules && rest.isNotEmpty() && sections.firstOrNull()?.title == null
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = bottomInset),
                ) {
                    // One item that is always present, empty or not. Its fixed key stays the list's
                    // first item, so when the journal arrives after the playlists the new rows grow
                    // inside it instead of being inserted above a playlist the list is anchored to,
                    // which would scroll them off the top.
                    item(key = "home") {
                        Column(pageInset) {
                            // Modules that belong together sit in cards, each under its own heading.
                            home.resume?.let { resume ->
                                HomeModule("继续播放") { ResumeRow(resume, onResume) }
                            }
                            featured?.let { liked ->
                                HomeModule(LIKED_HEADING) { LikedPlaylistRow(liked) { onOpenPlaylist(liked) } }
                            }
                            if (home.recent.isNotEmpty()) {
                                HomeModule("最近播放", padded = false) {
                                    RecentStrip(home.recent, nowPlayingId, onPlayRecent, onRemoveRecent)
                                }
                            }
                            if (playlistsTitle) SectionTitle("歌单")
                        }
                    }
                    // Sections are plain items in the same list, so "load more" below stays last.
                    sections.forEachIndexed { sectionIndex, section ->
                        // A heading is there when the section has its own or the "歌单" one leads the list.
                        val headed = section.title != null || (sectionIndex == 0 && playlistsTitle)
                        section.title?.let { title ->
                            item(key = "section-$title") {
                                // The first heading follows the segmented control or a module, each of
                                // which leaves its own gap; a later section needs one.
                                val top = if (sectionIndex > 0) Space.section else 0.dp
                                SectionTitle(title, pageInset.padding(top = top))
                            }
                        }
                        if (density == LibraryDensity.LIST) {
                            itemsIndexed(section.items, key = { _, it -> it.key.stableId }) { index, playlist ->
                                Column(pageInset) {
                                    PlaylistRow(playlist) { onOpenPlaylist(playlist) }
                                    // The line starts at the text, not under the cover.
                                    if (index < section.items.lastIndex) {
                                        RowDivider(start = RowSpec.textStart(RowSpec.cover))
                                    }
                                }
                            }
                        } else {
                            // Grid rows are plain items too, so the load-more pill stays the last item.
                            val rows = section.items.chunked(density.columns)
                            itemsIndexed(rows, key = { _, row -> "row-${row.first().key.stableId}" }) { index, row ->
                                // Rows space themselves from below; the first one also takes the padding a
                                // list row has above its cover when no heading sits above it.
                                if (index == 0 && !headed) Spacer(Modifier.height(RowSpec.vertical))
                                PlaylistGridRow(row, density.columns, pageInset) { onOpenPlaylist(it) }
                            }
                        }
                    }
                    item {
                        if (state.canLoadMore || (state.loading && state.items.isNotEmpty())) {
                            LoadMorePill(loading = state.loading, onClick = onLoadMore)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Empty library. Three reasons look identical unless they are told apart: no account, a genuine
 * zero, and a failed refresh. Each gets its own sentence and its own next step. "No account" gets a
 * card of its own, since connecting is the one thing that fixes it. A failed read still outranks
 * "not connected", as it did before: for a network error the honest next step is to retry.
 */
@Composable
private fun LibraryEmpty(
    failed: Boolean,
    connected: Boolean,
    platformName: String,
    onOpenAccount: () -> Unit,
    onRefresh: () -> Unit,
    bottomInset: Dp,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.page),
    ) {
        if (!failed && !connected) {
            Spacer(Modifier.height(8.dp))
            ConnectCard(platformName, onOpenAccount)
            Spacer(Modifier.height(24.dp + bottomInset))
            return@Column
        }
        Spacer(Modifier.height(40.dp))
        CoverOutline(Modifier.size(96.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                failed -> "网络异常，没能读取歌单"
                else -> "这个平台还没有歌单"
            },
            style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                failed -> "刷新一下试试，或者稍后再来"
                else -> "在平台上新建歌单后，也会出现在这里"
            },
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        // A button only when there is something to do. Zero playlists on a connected account is not
        // a problem to fix, and offering "connect" there would be advice for a different state.
        if (failed) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRefresh) {
                Text("重新读取", style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.height(24.dp + bottomInset))
    }
}

/**
 * No account on this platform yet. A card like every other module, with one filled button:
 * connecting is the only thing that fills this tab, so the card says what connecting gives and
 * offers it once.
 */
@Composable
private fun ConnectCard(platformName: String, onOpenAccount: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    SectionCard {
        Column(Modifier.padding(vertical = Space.inner)) {
            Text("连接$platformName", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "连接后同步你的歌单、收藏和播放记录",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.inner))
            Button(onClick = onOpenAccount) {
                Text("去连接", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * One row of a grid: [columns] tiles share the width, and a short last row keeps its tiles at the
 * same width by filling the rest with empty weighted slots.
 */
@Composable
private fun PlaylistGridRow(
    row: List<PlaylistSummary>,
    columns: Int,
    pageInset: Modifier,
    onOpen: (PlaylistSummary) -> Unit,
) {
    Row(
        pageInset.fillMaxWidth().padding(bottom = Space.inner),
        horizontalArrangement = Arrangement.spacedBy(Space.sibling),
    ) {
        row.forEach { playlist ->
            PlaylistTile(playlist, Modifier.weight(1f)) { onOpen(playlist) }
        }
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** A grid cell: a square cover, then the title on one line and the count on the next. */
@Composable
private fun PlaylistTile(playlist: PlaylistSummary, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.pressableRow(onClick = onClick)) {
        Cover(
            seed = playlist.key.stableId,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            shape = MaterialTheme.shapes.medium,
            url = playlist.coverUrl,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            playlist.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${playlist.trackCount} 首",
            style = MaterialTheme.typography.bodySmall.tabular, color = cs.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The header icon shows the density that is currently on screen. */
private fun LibraryDensity.icon(): ImageVector = when (this) {
    LibraryDensity.LIST -> Icons.Outlined.ViewList
    LibraryDensity.GRID_COMPACT -> Icons.Outlined.ViewModule
    LibraryDensity.GRID_LARGE -> Icons.Outlined.GridView
}

/**
 * One playlist: 48dp cover, title, then a tabular count. The row has no card (a list this long lies
 * on the page); its height is the cover plus the shared row padding, and the divider under it, drawn
 * by the caller, starts at the text.
 */
@Composable
private fun PlaylistRow(playlist: PlaylistSummary, onClick: () -> Unit) {
    PlainRow(onClick = onClick) {
        Cover(
            seed = playlist.key.stableId,
            modifier = Modifier.size(RowSpec.cover),
            shape = MaterialTheme.shapes.small,
            url = playlist.coverUrl,
        )
        Spacer(Modifier.width(RowSpec.leadGap))
        RowText(playlist.title, subtitle = "${playlist.trackCount} 首", subtitleStyle = MaterialTheme.typography.bodySmall.tabular)
    }
}

/**
 * One module of the home area: its heading, then its card, then the gap before the next module.
 * A [padded] false card lets a horizontal strip supply its own inset.
 */
@Composable
private fun HomeModule(title: String, padded: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    SectionTitle(title)
    SectionCard(padded = padded, content = content)
    Spacer(Modifier.height(Space.section))
}

internal fun statusLine(state: PlaylistUiState, nowEpochMs: Long): String {
    // A failed read means the count is unknown, so it must not claim "0 playlists".
    val parts = if (state.error != null && state.items.isEmpty() && !isAccountError(state.error)) {
        mutableListOf("读取失败")
    } else {
        // The platform is already named by the selected chip; repeating it said the same thing twice.
        mutableListOf("${state.items.size} 个歌单")
    }
    // A failed read with nothing cached has no cache to speak of; claiming one contradicted itself.
    if (state.error == null) freshnessLabel(state.fetchedAtEpochMs, nowEpochMs)?.let { parts += it }
    if (!state.accountVerified && state.error == null) parts += "待验证"
    return parts.joinToString(" · ")
}

/**
 * "No account" is reported through the same error slot as a failed request. It is not a network
 * problem, and saying "网络异常" for a platform the user never connected sent them to retry forever.
 */
internal fun isAccountError(error: String?): Boolean =
    error == "account_unavailable" || error == "account_unverified"

/** A run of playlists under one heading. A null [title] means the run has no heading. */
internal data class PlaylistSection(val title: String?, val items: List<PlaylistSummary>)

/**
 * Splits the non-featured playlists into "创建的歌单" and "收藏的歌单", but only when both kinds are
 * present. Otherwise the list comes back as one untitled section in its original order.
 *
 * Created playlists keep their order at the top of the first group. OTHER (and any other kind that is
 * not COLLECTED) follows them at the end of that group.
 */
internal fun groupPlaylists(playlists: List<PlaylistSummary>): List<PlaylistSection> {
    val hasCreated = playlists.any { it.kind == PlaylistKind.CREATED }
    val hasCollected = playlists.any { it.kind == PlaylistKind.COLLECTED }
    if (!hasCreated || !hasCollected) return listOf(PlaylistSection(null, playlists))
    val created = playlists.filter { it.kind == PlaylistKind.CREATED } +
        playlists.filter { it.kind != PlaylistKind.CREATED && it.kind != PlaylistKind.COLLECTED }
    val collected = playlists.filter { it.kind == PlaylistKind.COLLECTED }
    return listOf(PlaylistSection("创建的歌单", created), PlaylistSection("收藏的歌单", collected))
}
