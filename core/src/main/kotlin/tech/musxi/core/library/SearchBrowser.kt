package tech.musxi.core.library

import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.AccountState
import tech.musxi.core.account.Platform

data class SearchQuery(val platform: Platform, val keywords: String, val offset: Int = 0)
data class SearchBrowseState(
    val query: SearchQuery? = null,
    val page: Page<SongSummary>? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

fun interface SearchPageFetch {
    /** Check isCurrent before queued network work and before delivering a response. */
    fun search(query: SearchQuery, isCurrent: () -> Boolean, result: (Result<Page<SongSummary>>) -> Unit)
}

/** Main-thread commands/callbacks. Pagination belongs to a single explicit platform and account. */
class SearchBrowser(
    private val account: (Platform) -> AccountState,
    private val fetch: SearchPageFetch,
) {
    private val mutable = MutableStateFlow(SearchBrowseState())
    val state = mutable.asStateFlow()
    @Volatile private var revision = 0L
    private var expected: AccountState? = null

    fun search(platform: Platform, keywords: String, offset: Int = 0) {
        val query = SearchQuery(platform, keywords.trim(), offset)
        val token = ++revision
        val identity = account(platform)
        val keep = mutable.value.query == query && identity.phase == AccountPhase.CONNECTED &&
            expected?.generation == identity.generation && expected?.profile?.id == identity.profile?.id
        expected = identity
        mutable.value = SearchBrowseState(query, if (keep) mutable.value.page else null, loading = true)
        if (query.keywords.length !in 1..120 || query.keywords.any { it.isISOControl() } ||
            offset !in 0..MAX_OFFSET || offset % PAGE_SIZE != 0) {
            mutable.value = SearchBrowseState(query, error = "request"); return
        }
        if (identity.phase != AccountPhase.CONNECTED) {
            mutable.value = SearchBrowseState(query, error = "account_unavailable"); return
        }
        fun current(): Boolean {
            val latest = account(platform)
            return token == revision && latest.phase == AccountPhase.CONNECTED && latest.generation == identity.generation &&
                latest.profile?.id == identity.profile?.id
        }
        val callback: (Result<Page<SongSummary>>) -> Unit = callback@{ response ->
            if (!current()) {
                if (token == revision) accountChanged()
                return@callback
            }
            response.fold({ page ->
                if (page.items.size > PAGE_SIZE || page.items.any { it.key.platform != platform } ||
                    page.total?.let { it < 0 || offset + page.items.size > it } == true ||
                    (page.total != null && page.nextOffset == null && offset + page.items.size < page.total) ||
                    page.nextOffset?.let { it != offset + PAGE_SIZE || it > MAX_OFFSET || page.items.isEmpty() ||
                        (page.total != null && it >= page.total) } == true) {
                    mutable.value = mutable.value.copy(loading = false, error = "protocol")
                } else {
                    val songs = Collections.unmodifiableList(page.items.map { song ->
                        song.copy(artists = Collections.unmodifiableList(song.artists.toList()),
                            coverUrl = ArtworkPolicy.sanitize(platform, song.coverUrl))
                    })
                    mutable.value = SearchBrowseState(query, page.copy(items = songs))
                }
            }, { error ->
                mutable.value = mutable.value.copy(loading = false,
                    error = (error as? LibraryPageFailure)?.category?.takeIf { it in safeErrors } ?: "unclassified")
            })
        }
        try { fetch.search(query, ::current, callback) }
        catch (_: Exception) { callback(Result.failure(LibraryPageFailure("unclassified"))) }
    }

    /** Retains the last displayed page; running HTTP may finish but cannot publish or start queued stale work. */
    fun cancel() { ++revision; mutable.value = mutable.value.copy(loading = false) }
    fun refresh() { state.value.query?.let { search(it.platform, it.keywords, it.offset) } }
    fun next() { state.value.let { value -> if (!value.loading) value.page?.nextOffset?.let { offset ->
        value.query?.let { search(it.platform, it.keywords, offset) }
    } } }

    /** Invalidate and remove results immediately on disconnect, revalidation or account replacement. */
    fun accountChanged() {
        val previous = expected ?: return
        val latest = account(previous.platform)
        if (latest.generation != previous.generation || latest.profile?.id != previous.profile?.id ||
            latest.phase != AccountPhase.CONNECTED) {
            ++revision; expected = null
            mutable.value = SearchBrowseState(query = mutable.value.query, error = "account_changed")
        }
    }

    companion object {
        const val PAGE_SIZE = 30
        const val MAX_OFFSET = 19_980
        private val safeErrors = setOf("expired", "network", "risk", "rate", "protocol", "identity", "limit",
            "incomplete", "permission", "unavailable", "endpoint", "platform", "http", "timeout", "request")
    }
}
