package tech.musxi.player.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.musxi.core.account.Platform
import tech.musxi.core.library.SearchBrowser
import tech.musxi.core.library.SearchBrowseState
import tech.musxi.core.library.SongSummary

/** Which of the five states the result area is in. `IDLE` means nothing has been searched yet. */
enum class SearchPhase { IDLE, LOADING, RESULTS, EMPTY, ERROR }

data class SearchUiState(
    val keywords: String = "",
    val platform: Platform = Platform.NETEASE,
    val items: List<SongSummary> = emptyList(),
    val phase: SearchPhase = SearchPhase.IDLE,
    /** First page is still loading and there is nothing to show yet. */
    val loading: Boolean = false,
    /** A further page is loading under an already visible list. */
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val total: Int? = null,
    val errorText: String? = null,
) {
    val canSearch: Boolean get() = keywords.isNotBlank() && !loading
}

/**
 * Accumulates result pages for one explicit platform.
 *
 * [SearchBrowser] replaces its page on every delivery, so the accumulation lives here — the same
 * shape as `LibraryViewModel`. The browser is also the only owner of account gating and stale
 * result rejection; this class never decides whether a delivery is still current.
 */
class SearchViewModel(private val browser: SearchBrowser) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /** The query the accumulated list belongs to; a new key resets accumulation. */
    private var key: Pair<Platform, String>? = null
    private var items: List<SongSummary> = emptyList()

    init {
        viewModelScope.launch {
            browser.state.collect { incoming ->
                val reduced = reduce(_state.value, key, items, incoming)
                key = reduced.key
                items = reduced.items
                _state.value = reduced.ui
            }
        }
    }

    fun onKeywordsChange(value: String) {
        // Keep the old results visible while typing; only an explicit search replaces them.
        _state.value = _state.value.copy(keywords = value)
    }

    fun selectPlatform(platform: Platform) {
        if (platform == _state.value.platform) return
        _state.value = _state.value.copy(platform = platform, items = emptyList(), phase = SearchPhase.IDLE,
            canLoadMore = false, errorText = null, total = null)
        key = null
        items = emptyList()
        // A platform switch with keywords already typed is a deliberate re-search, not a reset.
        val keywords = _state.value.keywords.trim()
        if (keywords.isNotEmpty()) search()
    }

    fun search() {
        val keywords = _state.value.keywords.trim()
        if (keywords.isEmpty()) {
            _state.value = _state.value.copy(items = emptyList(), phase = SearchPhase.IDLE,
                canLoadMore = false, errorText = null, total = null)
            key = null
            items = emptyList()
            return
        }
        _state.value = _state.value.copy(keywords = keywords)
        browser.search(_state.value.platform, keywords, 0)
    }

    fun loadMore() {
        val current = _state.value
        if (key == null || !current.canLoadMore || current.loadingMore) return
        // `next()` reads the offset from the browser, so this never invents one.
        browser.next()
    }

    fun retry() = search()

    fun cancel() = browser.cancel()
}

/** Result of folding one browser delivery into the accumulated list and the visible state. */
internal data class ReducedSearch(
    val ui: SearchUiState,
    val key: Pair<Platform, String>?,
    val items: List<SongSummary>,
)

/**
 * Pure fold over browser deliveries, so accumulation and phase mapping are testable without a
 * repository, a dispatcher or an Android runtime.
 *
 * [SearchBrowser] replaces its page on every delivery; the accumulation lives here. A delivery
 * whose query key changed starts a fresh list, because a different query's pages must never mix.
 */
internal fun reduce(
    previous: SearchUiState,
    previousKey: Pair<Platform, String>?,
    previousItems: List<SongSummary>,
    incoming: SearchBrowseState,
): ReducedSearch {
    val query = incoming.query
    if (query == null) {
        return ReducedSearch(
            ui = previous.copy(
                items = emptyList(), phase = SearchPhase.IDLE, loading = false, loadingMore = false,
                canLoadMore = false, total = null, errorText = null,
            ),
            key = null,
            items = emptyList(),
        )
    }
    val key = query.platform to query.keywords
    var items = if (key != previousKey) emptyList() else previousItems
    incoming.page?.let { page ->
        items = if (query.offset == 0) page.items else items + page.items
    }
    val error = incoming.error
    val phase = when {
        error != null -> SearchPhase.ERROR
        incoming.loading && items.isEmpty() -> SearchPhase.LOADING
        items.isEmpty() -> SearchPhase.EMPTY
        else -> SearchPhase.RESULTS
    }
    return ReducedSearch(
        ui = previous.copy(
            keywords = query.keywords,
            platform = query.platform,
            items = items,
            phase = phase,
            loading = incoming.loading && items.isEmpty(),
            loadingMore = incoming.loading && items.isNotEmpty(),
            canLoadMore = phase == SearchPhase.RESULTS && incoming.page?.nextOffset != null && !incoming.loading,
            total = incoming.page?.total,
            errorText = error?.let(::errorText),
        ),
        key = key,
        items = items,
    )
}

/**
 * Turns the browser's error category into one sentence. `expired` and `account_unavailable` are
 * the two that need a different next step, so they must not read as "just try again".
 */
internal fun errorText(category: String): String = when (category) {
    "request" -> "搜索词无效，换个关键词试试"
    "account_unavailable" -> "该平台还没登录，先去「我的」连接"
    "account_changed" -> "账号状态已变化，请重新搜索"
    "expired" -> "登录已过期，需要重新登录"
    "network", "http", "timeout" -> "网络连接异常，请稍后重试"
    "rate", "limit", "risk" -> "请求太频繁，稍等一会儿再试"
    "permission" -> "当前账号没有搜索权限"
    "protocol", "incomplete", "endpoint", "platform", "identity" -> "平台返回异常，请重试"
    else -> "搜索失败，请重试"
}
