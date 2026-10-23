package tech.musxi.player.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.musxi.core.library.PlaylistSummary
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.components.CoverOutline
import tech.musxi.player.ui.components.OpticallyCentredText
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.tabular

/**
 * The library tab: a list of rows, each led by a 48dp cover.
 *
 * A two-column grid of square tiles was tried here and reverted — it looked worse than this list, and
 * the owner of the product preferred the list. The same lesson as the cover pattern: a layout that
 * reads as "more designed" is not automatically better.
 */
@Composable
fun LibraryContent(
    state: PlaylistUiState,
    onSelectFilter: (LibraryFilter) -> Unit,
    onLoadMore: () -> Unit = {},
    onOpenPlaylist: (PlaylistSummary) -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onRefresh: () -> Unit = {},
    /** Room for the floating dock: content scrolls under it, so the last row must clear it. */
    bottomInset: Dp = 0.dp,

) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = Space.page)) {
        Spacer(Modifier.height(Space.section))
        Text("曲库", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
        Text(
            statusLine(state),
            style = MaterialTheme.typography.bodySmall.tabular,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Only real platforms: `ALL` has no platform and can never load a page, so it used to
            // render as a permanently disabled chip that read as a broken option.
            LibraryFilter.entries.filter { it.platform != null }.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { onSelectFilter(filter) },
                    label = { OpticallyCentredText(filter.label, MaterialTheme.typography.labelMedium) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // A failed refresh with nothing cached says it once, inside the empty state.
        if (state.error != null && state.items.isNotEmpty()) {
            Text(
                "刷新失败：${state.error}",
                style = MaterialTheme.typography.bodySmall, color = cs.error,
            )
        }
        if (state.items.isEmpty() && !state.loading) {
            LibraryEmpty(
                failed = state.error != null,
                connected = state.accountVerified,
                onOpenAccount = onOpenAccount,
                onRefresh = onRefresh,
                bottomInset = bottomInset,
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomInset),
            ) {
                items(state.items, key = { it.key.stableId }) { playlist ->
                    PlaylistRow(playlist) { onOpenPlaylist(playlist) }
                    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.45f))
                }
                item {
                    if (state.canLoadMore) {
                        Text(
                            "加载更多", style = MaterialTheme.typography.labelLarge, color = cs.primary,
                            modifier = Modifier.fillMaxWidth().clickable(onClick = onLoadMore)
                                .padding(vertical = 16.dp),
                        )
                    }
                    if (state.loading) {
                        Text(
                            "正在读取…", style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Empty library. Three reasons look identical unless they are told apart: no account, a genuine
 * zero, and a failed refresh. Each gets its own sentence and its own next step.
 */
@Composable
private fun LibraryEmpty(
    failed: Boolean,
    connected: Boolean,
    onOpenAccount: () -> Unit,
    onRefresh: () -> Unit,
    bottomInset: Dp,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 40.dp)) {
        CoverOutline(Modifier.size(96.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                failed -> "网络异常，没能读取歌单"
                !connected -> "还没有同步到歌单"
                else -> "这个平台还没有歌单"
            },
            style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                failed -> "刷新一下试试，或者稍后再来"
                !connected -> "先在「我的」里连接平台，歌单会同步到这里"
                else -> "在平台上新建歌单后，也会出现在这里"
            },
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        // A button only when there is something to do. Zero playlists on a connected account is not
        // a problem to fix, and offering "connect" there would be advice for a different state.
        when {
            failed -> {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRefresh) {
                    Text("重新读取", style = MaterialTheme.typography.labelLarge)
                }
            }
            !connected -> {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpenAccount) {
                    Text("去「我的」连接", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Spacer(Modifier.height(24.dp + bottomInset))
    }
}

/** One playlist: 48dp cover, title, then a tabular count. The hairline is the app-wide separator. */
@Composable
private fun PlaylistRow(playlist: PlaylistSummary, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(64.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(
            seed = playlist.key.stableId,
            modifier = Modifier.size(48.dp),
            shape = MaterialTheme.shapes.small,
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

internal fun statusLine(state: PlaylistUiState): String {
    // A failed read means the count is unknown, so it must not claim "0 playlists".
    val parts = if (state.error != null && state.items.isEmpty()) {
        mutableListOf("读取失败")
    } else {
        // The platform is already named by the selected chip; repeating it said the same thing twice.
        mutableListOf("${state.items.size} 个歌单")
    }
    // A failed read with nothing cached has no cache to speak of; claiming one contradicted itself.
    if (state.fromCache && state.error == null) parts += "本机缓存"
    if (!state.accountVerified && state.error == null) parts += "待验证"
    return parts.joinToString(" · ")
}
