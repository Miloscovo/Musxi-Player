package tech.musxi.player.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** Rows from the end at which the next page is asked for: early enough that a steady scroll never waits. */
internal const val AutoLoadAheadRows = 6

/**
 * True when the list's last visible row is within [ahead] rows of its end. Pure, so it is tested
 * without Compose. An empty or unmeasured list never asks.
 */
internal fun nearEnd(lastVisibleIndex: Int, totalCount: Int, ahead: Int = AutoLoadAheadRows): Boolean =
    totalCount > 0 && lastVisibleIndex >= 0 && lastVisibleIndex >= totalCount - 1 - ahead

/**
 * Loads the next page by itself as the list nears its end, so "加载更多" is only needed after a
 * failure. It asks once per approach: the trigger fires when the list *becomes* near its end, not
 * on every frame while it stays there, and only while [canLoadMore] (a page exists, none is loading)
 * and there is no [failed] page waiting for a manual retry — retrying a broken page on every scroll
 * would hammer the platform.
 */
@Composable
fun AutoLoadMore(
    listState: LazyListState,
    canLoadMore: Boolean,
    failed: Boolean,
    onLoadMore: () -> Unit,
) {
    val allowed = rememberUpdatedState(canLoadMore && !failed)
    val load = rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            nearEnd(info.visibleItemsInfo.lastOrNull()?.index ?: -1, info.totalItemsCount) && allowed.value
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { load.value() }
    }
}
