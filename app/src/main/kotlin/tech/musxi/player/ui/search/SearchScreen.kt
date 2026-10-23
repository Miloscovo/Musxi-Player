package tech.musxi.player.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import tech.musxi.core.account.Platform
import tech.musxi.core.library.SongSummary
import tech.musxi.player.ui.components.OpticallyCentredText
import tech.musxi.player.ui.components.ProgressArc
import tech.musxi.player.ui.components.SongRow
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.displayName

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
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = Space.page)) {
        Spacer(Modifier.height(Space.section))
        Text("搜索", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            "在单个平台内搜索，结果不跨平台合并",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        SearchField(state.keywords, onKeywordsChange, onSearch)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            searchPlatforms.forEach { platform ->
                FilterChip(
                    selected = state.platform == platform,
                    onClick = { onPlatformSelect(platform) },
                    label = {
                        OpticallyCentredText(
                            platform.displayName,
                            MaterialTheme.typography.labelMedium,
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // The state area owns the remaining height; each state fills it on its own terms.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (state.phase) {
                // The platform is already chosen, so the hint names it instead of asking for a choice.
                SearchPhase.IDLE -> Hint("输入歌名、歌手或专辑，搜索${state.platform.displayName}")
                SearchPhase.LOADING -> Loading("正在搜索…")
                SearchPhase.EMPTY -> EmptyResult()
                SearchPhase.ERROR -> Failure(state.errorText.orEmpty(), onRetry)
                SearchPhase.RESULTS -> ResultList(state, onLoadMore, onSongClick)
            }
        }
    }
}

@Composable
private fun SearchField(keywords: String, onChange: (String) -> Unit, onSearch: () -> Unit) {
    OutlinedTextField(
        value = keywords,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("歌名、歌手或专辑", style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Icons.Outlined.Search, null, modifier = Modifier.size(20.dp)) },
        trailingIcon = if (keywords.isEmpty()) null else {
            {
                IconButton(onClick = { onChange("") }) {
                    Icon(
                        Icons.Outlined.Close, "清除",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

@Composable
private fun ResultList(state: SearchUiState, onLoadMore: () -> Unit, onSongClick: (SongSummary) -> Unit) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.items, key = { it.key.stableId }) { song ->
            SongRow(song) { onSongClick(song) }
            // The hairline is the app-wide separator: account, library and search all use it.
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
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
                Text(
                    "加载更多", style = MaterialTheme.typography.labelLarge, color = cs.primary,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onLoadMore)
                        .padding(vertical = 16.dp),
                )
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

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Text(
            text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun EmptyResult() {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("未找到相关歌曲", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                "试试其他关键词，或切换平台",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Loading(text: String) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier.padding(top = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProgressArc(modifier = Modifier.size(14.dp), color = cs.onSurfaceVariant, size = 14.dp)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
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
