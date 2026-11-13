package tech.musxi.player.ui.library

import tech.musxi.core.library.CachedPlaylistSearchResult
import tech.musxi.core.library.SongSummary

/**
 * Search inside one open playlist. The backend only searches pages already in the cache (no network
 * request), so the result says how much it covered and the page must never present an empty result
 * on a partly read playlist as "this playlist has no such song".
 */
data class PlaylistSearchUi(
    /** What the user typed, as shown in the field. */
    val query: String = "",
    /**
     * The query the shown [results] answer. While a newer query is on its way the old results and
     * their note stay up unchanged, so typing does not make the list and the line below flicker.
     */
    val answered: String = "",
    /** The search box is open on the page (it may still be empty). */
    val open: Boolean = false,
    val searching: Boolean = false,
    val results: List<SongSummary> = emptyList(),
    /** How many tracks the search could look at, and of how many. */
    val searched: Int = 0,
    val total: Int? = null,
    /** Every track of the playlist was in the cache: the result is final. */
    val complete: Boolean = false,
    val failed: Boolean = false,
    /** The rest of the playlist is being read into the cache so the search can cover it. */
    val filling: Boolean = false,
    /** Why the last fill stopped short, already in words; null when it did not. */
    val fillError: String? = null,
) {
    /** "载入全部再搜" is offered: a search shows, the playlist is not all read, and no fill runs. */
    val canFill: Boolean get() = active && answered.isNotBlank() && !complete && !filling
    /** A search is showing: the list below is the results, not the playlist. */
    val active: Boolean get() = open && query.isNotBlank()
}

/** Folds one backend answer into the page state for [query]. */
internal fun PlaylistSearchUi.with(query: String, result: Result<CachedPlaylistSearchResult>): PlaylistSearchUi =
    result.fold(
        onSuccess = { r ->
            copy(
                query = query, answered = query, searching = false, failed = false,
                results = r.items, searched = r.loadedCount, total = r.total, complete = r.complete,
            )
        },
        onFailure = { copy(query = query, answered = query, searching = false, failed = true, results = emptyList()) },
    )

/**
 * The line under the search box. On a partly read playlist it says how much was searched, so an
 * empty result reads as "not among these", never as "not in the playlist".
 */
internal fun playlistSearchNote(ui: PlaylistSearchUi): String? = when {
    !ui.active -> null
    ui.filling -> "正在载入全部歌曲 · ${ui.searched}" + (ui.total?.let { " / $it" } ?: "") + " 首"
    // Nothing answered yet for this search: say nothing rather than flash a "searching" line that
    // is replaced a quarter of a second later. Later keystrokes keep the previous answer's note.
    ui.answered.isBlank() -> null
    ui.failed -> "暂时搜不了，请稍后再试"
    ui.complete && ui.results.isEmpty() -> "这个歌单里没有找到"
    ui.complete -> "找到 ${ui.results.size} 首"
    ui.results.isEmpty() -> "在已载入的 ${ui.searched} 首里没有找到" + totalSuffix(ui.total)
    else -> "在已载入的 ${ui.searched} 首里找到 ${ui.results.size} 首" + totalSuffix(ui.total)
}

private fun totalSuffix(total: Int?): String = if (total != null) "（共 $total 首）" else ""

/** Words for a fill that stopped before the end; null for one that finished or was cancelled. */
internal fun playlistFillErrorText(error: String?): String? = when (error) {
    null -> null
    "fill_limit" -> "歌单太长，只能载入前 5000 首"
    "cache_limit" -> "手机空间不足，没能载入全部"
    "rate", "risk" -> "请求太频繁，稍后再试"
    "network", "timeout", "http" -> "网络异常，没能载入全部"
    "account_unavailable", "account_unverified", "account_changed", "expired" -> "账号未连接或已过期"
    else -> "没能载入全部，请稍后再试"
}
