package tech.musxi.player.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.Page
import tech.musxi.core.library.SearchBrowseState
import tech.musxi.core.library.SearchQuery
import tech.musxi.core.library.SongSummary

/**
 * The fold over browser deliveries: what accumulates, what resets, and which of the five phases a
 * delivery produces. Pure, so no dispatcher or Android runtime is involved.
 */
class SearchReducerTest {

    private fun songs(from: Int, count: Int, platform: Platform = Platform.NETEASE) =
        (from until from + count).map { i ->
            SongSummary(MediaKey(platform, "s$i"), "歌 $i", listOf("歌手"), "专辑", 200_000)
        }

    private fun delivered(
        keywords: String = "夜に駆ける",
        platform: Platform = Platform.NETEASE,
        offset: Int = 0,
        page: Page<SongSummary>? = null,
        loading: Boolean = false,
        error: String? = null,
    ) = SearchBrowseState(
        query = SearchQuery(platform, keywords, offset),
        page = page,
        loading = loading,
        error = error,
    )

    @Test fun noQueryIsIdle() {
        val reduced = reduce(SearchUiState(), null, emptyList(), SearchBrowseState())
        assertEquals(SearchPhase.IDLE, reduced.ui.phase)
        assertTrue(reduced.ui.items.isEmpty())
        assertNull(reduced.key)
    }

    @Test fun firstPageBecomesResults() {
        val reduced = reduce(
            SearchUiState(), null, emptyList(),
            delivered(page = Page(songs(0, 3), nextOffset = 30, total = 40)),
        )
        assertEquals(SearchPhase.RESULTS, reduced.ui.phase)
        assertEquals(3, reduced.ui.items.size)
        assertTrue(reduced.ui.canLoadMore)
        assertFalse(reduced.ui.loading)
        assertEquals(40, reduced.ui.total)
    }

    @Test fun loadingFirstPageIsLoadingNotEmpty() {
        val reduced = reduce(SearchUiState(), null, emptyList(), delivered(loading = true))
        assertEquals(SearchPhase.LOADING, reduced.ui.phase)
        assertTrue(reduced.ui.loading)
        assertFalse(reduced.ui.loadingMore)
    }

    @Test fun nextPageAppends() {
        val first = reduce(
            SearchUiState(), null, emptyList(),
            delivered(page = Page(songs(0, 3), nextOffset = 30, total = 6)),
        )
        val second = reduce(
            first.ui, first.key, first.items,
            delivered(offset = 30, page = Page(songs(3, 3), nextOffset = null, total = 6)),
        )
        assertEquals(6, second.ui.items.size)
        assertEquals(SearchPhase.RESULTS, second.ui.phase)
        assertFalse(second.ui.canLoadMore)
    }

    @Test fun aDifferentQueryStartsAFreshList() {
        val first = reduce(
            SearchUiState(), null, emptyList(),
            delivered(keywords = "a", page = Page(songs(0, 3), nextOffset = 30, total = 40)),
        )
        val second = reduce(
            first.ui, first.key, first.items,
            delivered(keywords = "b", page = Page(songs(0, 2), nextOffset = 30, total = 2)),
        )
        assertEquals(2, second.ui.items.size)
        assertEquals("b", second.ui.keywords)
    }

    @Test fun aDifferentPlatformStartsAFreshList() {
        val first = reduce(
            SearchUiState(), null, emptyList(),
            delivered(page = Page(songs(0, 3), nextOffset = 30, total = 40)),
        )
        val second = reduce(
            first.ui, first.key, first.items,
            delivered(platform = Platform.KUGOU, page = Page(songs(0, 1, Platform.KUGOU), nextOffset = null, total = 1)),
        )
        assertEquals(1, second.ui.items.size)
        assertEquals(Platform.KUGOU, second.ui.platform)
    }

    @Test fun emptyPageIsEmptyNotError() {
        val reduced = reduce(
            SearchUiState(), null, emptyList(),
            delivered(page = Page(emptyList(), nextOffset = null, total = 0)),
        )
        assertEquals(SearchPhase.EMPTY, reduced.ui.phase)
        assertNull(reduced.ui.errorText)
    }

    @Test fun errorIsError() {
        val reduced = reduce(SearchUiState(), null, emptyList(), delivered(error = "network"))
        assertEquals(SearchPhase.ERROR, reduced.ui.phase)
        assertEquals("网络连接异常，请稍后重试", reduced.ui.errorText)
    }

    @Test fun loadingMoreKeepsItemsVisible() {
        val first = reduce(
            SearchUiState(), null, emptyList(),
            delivered(page = Page(songs(0, 3), nextOffset = 30, total = 6)),
        )
        val more = reduce(first.ui, first.key, first.items, delivered(offset = 30, loading = true))
        assertEquals(SearchPhase.RESULTS, more.ui.phase)
        assertEquals(3, more.ui.items.size)
        assertTrue(more.ui.loadingMore)
        assertFalse(more.ui.loading)
    }

    @Test fun errorCategoriesThatNeedADifferentStepDoNotSayRetry() {
        assertEquals("该平台还没登录，先去「我的」连接", errorText("account_unavailable"))
        assertEquals("登录已过期，需要重新登录", errorText("expired"))
        assertEquals("账号状态已变化，请重新搜索", errorText("account_changed"))
        assertEquals("搜索词无效，换个关键词试试", errorText("request"))
        // An unknown category still has to say something actionable.
        assertTrue(errorText("something_new").isNotBlank())
    }
}
