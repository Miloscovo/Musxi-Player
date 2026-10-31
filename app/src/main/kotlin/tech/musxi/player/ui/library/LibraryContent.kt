package tech.musxi.player.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.musxi.core.account.Platform
import tech.musxi.core.library.PlaylistSummary
import tech.musxi.core.library.SongSummary
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.components.CoverOutline
import tech.musxi.player.ui.components.LoadMorePill
import tech.musxi.player.ui.components.PlatformSegmented
import tech.musxi.player.ui.components.pressableRow
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.displayName
import tech.musxi.player.ui.theme.tabular

/**
 * The library tab: a way back into what was playing, a strip of recent plays, then the playlists as
 * a list of rows, each led by a 48dp cover. The liked-songs playlist leads that list in a larger row.
 *
 * A two-column grid of square tiles was tried here and reverted — it looked worse than this list, and
 * the owner of the product preferred the list. The same lesson as the cover pattern: a layout that
 * reads as "more designed" is not automatically better. Hierarchy here comes from type size, white
 * space and cover size, not from colour blocks.
 *
 * Horizontal page margins are applied per item rather than on the whole column, so [RecentRow] can
 * scroll to the screen edges. See its KDoc for why that is not done with a negative margin.
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
    /** Account name, the saved queue to continue and recent plays for the selected platform. */
    home: LibraryHomeUi = LibraryHomeUi(),
    /** The song playing now, so its cover on the recent strip can say so. */
    nowPlayingId: String? = null,
    onResume: () -> Unit = {},
    onPlayRecent: (SongSummary) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val pageInset = Modifier.padding(horizontal = Space.page)
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(Space.section))
        Text(
            "曲库", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface,
            modifier = pageInset,
        )
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
        Spacer(Modifier.height(8.dp))
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
                // The liked list leads in a larger row; every other playlist keeps the plain row.
                val featured = remember(state.items) { state.items.firstOrNull(::isLikedPlaylist) }
                val rest = remember(state.items, featured) {
                    if (featured == null) state.items else state.items.filter { it.key != featured.key }
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottomInset),
                ) {
                    // One item that is always present, empty or not. Its fixed key stays the list's
                    // first item, so when the journal arrives after the playlists the new rows grow
                    // inside it instead of being inserted above a playlist the list is anchored to,
                    // which would scroll them off the top.
                    item(key = "home") {
                        Column {
                            home.resume?.let { resume ->
                                Column(pageInset) {
                                    ResumeCard(resume, onResume)
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                            if (home.recent.isNotEmpty()) {
                                RecentRow(home.recent, nowPlayingId, onPlayRecent)
                                Spacer(Modifier.height(16.dp))
                            }
                            // With nothing above it the page is already clear: a heading would only
                            // label the one list there is.
                            if (home.resume != null || home.recent.isNotEmpty()) {
                                Text(
                                    "歌单",
                                    style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
                                    modifier = pageInset.padding(top = 8.dp, bottom = 4.dp),
                                )
                            }
                        }
                    }
                    if (featured != null) {
                        item(key = featured.key.stableId) {
                            Column(pageInset) {
                                FeaturedPlaylistCard(featured) { onOpenPlaylist(featured) }
                                if (rest.isNotEmpty()) {
                                    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.45f))
                                }
                            }
                        }
                    }
                    itemsIndexed(rest, key = { _, it -> it.key.stableId }) { index, playlist ->
                        Column(pageInset) {
                            PlaylistRow(playlist) { onOpenPlaylist(playlist) }
                            if (index < rest.lastIndex) {
                                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.45f))
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
 * No account on this platform yet. A tonal card with one filled button: connecting is the only thing
 * that fills this tab, so the card says what connecting gives and offers it once.
 */
@Composable
private fun ConnectCard(platformName: String, onOpenAccount: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(cs.surfaceContainerHigh)
            .padding(20.dp),
    ) {
        Text("连接$platformName", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            "连接后同步你的歌单、收藏和播放记录",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenAccount) {
            Text("去连接", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** One playlist: 48dp cover, title, then a tabular count. The hairline is the app-wide separator. */
@Composable
private fun PlaylistRow(playlist: PlaylistSummary, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(64.dp).pressableRow(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(
            seed = playlist.key.stableId,
            modifier = Modifier.size(48.dp),
            shape = MaterialTheme.shapes.small,
            url = playlist.coverUrl,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                playlist.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${playlist.trackCount} 首",
                style = MaterialTheme.typography.bodySmall.tabular, color = cs.onSurfaceVariant,
            )
        }
    }
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
