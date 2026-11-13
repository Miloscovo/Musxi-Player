package tech.musxi.core.library

import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.musxi.core.account.Platform

data class PlaylistFillState(
    val playlist: MediaKey? = null,
    val running: Boolean = false,
    /** Distinct songs in every retained page, with the same meaning as cached search. */
    val loadedCount: Int = 0,
    val total: Int? = null,
    val error: String? = null,
    val complete: Boolean = false,
    val cancelled: Boolean = false,
    /** Changes only after a page is committed and read back; re-search on this value. */
    val pagesRevision: Long = 0,
    val limit: Int = 5_000,
)

/** Cache-only progress is independent of the visible page. Entry points/callbacks use UI;
 * cache work uses the same serial IO executor as ordinary paging and erasure.
 */
internal class PlaylistCacheFiller(
    private val identity: () -> LibraryCacheIdentity?,
    private val cacheAccess: (LibraryCacheIdentity, (LibraryPageStore) -> Unit) -> Unit,
    private val fetch: LibraryPageFetch,
    private val io: (() -> Unit) -> Unit,
    private val deliver: (() -> Unit) -> Unit,
    private val now: () -> Long,
    private val platform: Platform,
) {
    private val mutable = MutableStateFlow(PlaylistFillState())
    val state = mutable.asStateFlow()
    @Volatile private var revision = 0L
    private var expectedOwner: LibraryCacheIdentity? = null

    fun start(playlist: MediaKey) = state.also {
        require(playlist.platform == platform)
        if (mutable.value.running && mutable.value.playlist == playlist) return@also
        val token = ++revision
        val expected = identity()
        expectedOwner = expected
        mutable.value = PlaylistFillState(playlist = playlist, running = expected?.verified == true,
            error = when { expected == null -> "account_unavailable"; !expected.verified -> "account_unverified"; else -> null },
            pagesRevision = mutable.value.pagesRevision)
        if (expected?.verified == true) inspect(token, expected, playlist)
    }

    fun cancel(error: String? = null) {
        ++revision
        if (mutable.value.running) mutable.value = mutable.value.copy(running = false, error = error,
            cancelled = error == null, complete = false)
    }

    fun accountChanged() {
        if (mutable.value.running && expectedOwner != identity()) cancel("account_changed")
    }

    private fun current(token: Long, expected: LibraryCacheIdentity): Boolean =
        token == revision && identity() == expected && expected.verified

    private fun active(token: Long, expected: LibraryCacheIdentity): Boolean {
        if (token != revision) return false
        if (!current(token, expected)) { cancel("account_changed"); return false }
        return true
    }

    private fun fail(token: Long, expected: LibraryCacheIdentity, error: String) {
        if (active(token, expected)) mutable.value = mutable.value.copy(running = false, complete = false, error = error)
    }

    private data class Coverage(val search: CachedPlaylistSearchResult, val next: Int?, val count: Int)

    private fun coverage(pages: List<CachedTrackPage>): Coverage {
        val search = CachedPlaylistSearch.search(pages, "")
        val byOffset = pages.associateBy { it.offset }
        var offset = 0
        var count = 0
        val seen = mutableSetOf<Int>()
        val firstTotal = byOffset[0]?.saved?.page?.total
        while (true) {
            val page = byOffset[offset]?.saved?.page ?: return Coverage(search, offset, count)
            if (!seen.add(offset) || page.total != firstTotal) throw LibraryPageFailure("incomplete")
            count += page.items.size
            val next = page.nextOffset ?: return Coverage(search, null, count)
            if (page.items.isEmpty() || next <= offset || next > 20_000) throw LibraryPageFailure("incomplete")
            offset = next
        }
    }

    private fun inspect(token: Long, expected: LibraryCacheIdentity, playlist: MediaKey) {
        io {
            if (!current(token, expected)) { deliver { active(token, expected) }; return@io }
            val result = runCatching {
                var pages = emptyList<CachedTrackPage>()
                cacheAccess(expected) { store -> pages = store.readTrackPages(expected.owner, playlist) }
                coverage(pages)
            }
            deliver {
                if (!active(token, expected)) return@deliver
                result.fold({ coverage ->
                    updateProgress(coverage)
                    val next = coverage.next
                    val size = if (platform == Platform.NETEASE) 50 else 30
                    val remaining = coverage.search.total?.let { (it - coverage.count).coerceAtLeast(0) } ?: size
                    when {
                        coverage.search.complete -> mutable.value = mutable.value.copy(running = false, complete = true)
                        next == null -> fail(token, expected, "incomplete")
                        coverage.search.total?.let { next >= it } == true -> fail(token, expected, "incomplete")
                        coverage.count + minOf(size, remaining) > state.value.limit ||
                            coverage.search.loadedCount + minOf(size, remaining) > state.value.limit -> fail(token, expected, "fill_limit")
                        else -> fetch(token, expected, playlist, next, coverage)
                    }
                }, { error -> fail(token, expected, safeError(error, "storage")) })
            }
        }
    }

    private fun updateProgress(coverage: Coverage) {
        mutable.value = mutable.value.copy(loadedCount = coverage.search.loadedCount, total = coverage.search.total)
    }

    private fun fetch(token: Long, expected: LibraryCacheIdentity, playlist: MediaKey, offset: Int, previous: Coverage) {
        try {
            fetch.tracks(playlist, offset, { current(token, expected) }) { response ->
                if (!active(token, expected)) return@tracks
                response.fold({ page -> save(token, expected, playlist, offset, previous, page) },
                    { error -> fail(token, expected, safeError(error)) })
            }
        } catch (_: Exception) { fail(token, expected, "unclassified") }
    }

    private fun save(token: Long, expected: LibraryCacheIdentity, playlist: MediaKey, offset: Int,
        previous: Coverage, page: Page<SongSummary>) {
        io {
            if (!current(token, expected)) { deliver { active(token, expected) }; return@io }
            val saved = runCatching {
                val next = page.nextOffset
                if (page.items.size > (if (platform == Platform.NETEASE) 50 else 30) ||
                    page.items.any { it.key.platform != platform } ||
                    (page.total != null && page.total !in 0..20_000) ||
                    (previous.search.total != null && previous.search.total != page.total) ||
                    (next != null && (page.items.isEmpty() || next != offset + page.items.size || next > 20_000 ||
                        (page.total != null && next >= page.total))) ||
                    (page.total != null && (offset + page.items.size > page.total ||
                        (next == null && offset + page.items.size != page.total)))) throw LibraryPageFailure("incomplete")
                if (previous.count + page.items.size > state.value.limit) throw LibraryPageFailure("fill_limit")
                var after: Coverage? = null
                cacheAccess(expected) { store ->
                    // A manual refresh, cache eviction or another writer cannot splice old pages into a new head.
                    val before = coverage(store.readTrackPages(expected.owner, playlist))
                    if (before != previous) throw LibraryPageFailure("cache_changed")
                    if (!current(token, expected)) throw LibraryPageFailure("cancelled")
                    val snapshot = page.copy(items = Collections.unmodifiableList(page.items.map {
                        it.copy(artists = Collections.unmodifiableList(it.artists.toList()),
                            artistIds = Collections.unmodifiableList(it.artistIds.toList()))
                    }))
                    if (!store.writeTracksRetainingPages(expected.owner, playlist, offset,
                            SavedLibraryPage(snapshot, now().coerceAtLeast(0)))) throw LibraryPageFailure("cache_limit")
                    after = coverage(store.readTrackPages(expected.owner, playlist))
                }
                requireNotNull(after)
            }
            deliver {
                if (!active(token, expected)) return@deliver
                saved.fold({ after ->
                    updateProgress(after)
                    mutable.value = mutable.value.copy(pagesRevision = mutable.value.pagesRevision + 1)
                    if (after.count < previous.count + page.items.size) fail(token, expected, "cache_limit")
                    else inspect(token, expected, playlist)
                }, { error -> fail(token, expected, safeError(error, "storage")) })
            }
        }
    }

    private fun safeError(error: Throwable, fallback: String = "unclassified"): String =
        (error as? LibraryPageFailure)?.category?.takeIf { it in safeErrors } ?: fallback

    companion object {
        private val safeErrors = setOf("expired", "network", "risk", "rate", "protocol", "identity", "limit",
            "incomplete", "permission", "unavailable", "endpoint", "platform", "http", "timeout", "request",
            "cancelled", "account_changed", "storage", "fill_limit", "cache_limit", "cache_changed")
    }
}
