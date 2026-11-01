package tech.musxi.core.library

import java.util.Collections
import tech.musxi.core.account.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface LibraryPageFetch {
    fun playlists(offset: Int = 0, result: (Result<Page<PlaylistSummary>>) -> Unit)
    fun tracks(playlist: MediaKey, offset: Int = 0, result: (Result<Page<SongSummary>>) -> Unit)
}

/** Digest identifies metadata ownership; generation rejects work from an earlier login attempt. */
data class LibraryCacheIdentity(val owner: String, val generation: Long, val verified: Boolean) {
    override fun toString() = "LibraryCacheIdentity(redacted, verified=$verified)"
    fun sameAttempt(other: LibraryCacheIdentity?) = other != null && owner == other.owner && generation == other.generation
}

data class LibraryQuery(val playlist: MediaKey? = null, val offset: Int = 0) {
    init { require(offset in 0..20_000 && (playlist == null || playlist.platform in setOf(Platform.NETEASE, Platform.KUGOU, Platform.QQ))) }
}

sealed interface LibraryContent {
    class Playlists(val page: Page<PlaylistSummary>) : LibraryContent
    class Tracks(val page: Page<SongSummary>) : LibraryContent
}

enum class LibrarySource { CACHE, NETWORK }

data class LibraryBrowseState(
    val query: LibraryQuery = LibraryQuery(),
    val content: LibraryContent? = null,
    val source: LibrarySource? = null,
    val fetchedAtEpochMs: Long? = null,
    val refreshing: Boolean = false,
    val accountVerified: Boolean = false,
    val error: String? = null,
    val storageError: Boolean = false,
    val invalidationRevision: Long = 0,
    val stale: Boolean = false,
)

private data class FreshQuery(
    val owner: String,
    val generation: Long,
    val query: LibraryQuery,
)

private data class NetworkQuery(
    val owner: String,
    val generation: Long,
    val query: LibraryQuery,
)

/** Process-owned cache-first page reader. All entrypoints/delivery run on the UI executor.
 * The separate IO executor never needs to wait for an account network verification.
 */
class LibraryBrowser(
    private val identity: () -> LibraryCacheIdentity?,
    private val cacheAccess: (LibraryCacheIdentity, (LibraryPageStore) -> Unit) -> Unit,
    private val fetch: LibraryPageFetch,
    private val io: (() -> Unit) -> Unit,
    private val deliver: (() -> Unit) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val platform: Platform = Platform.NETEASE,
) {
    init { require(platform in setOf(Platform.NETEASE, Platform.KUGOU, Platform.QQ)) }
    private val gate = Any()
    @Volatile private var revision = 0L
    @Volatile private var invalidationRevision = 0L
    private var owner: LibraryCacheIdentity? = null
    private var queryOwner: String? = null
    private var invalidatedOwner: String? = null
    private val freshQueries = mutableMapOf<FreshQuery, Long>()
    private val networkCompletions = mutableMapOf<NetworkQuery, Long>()
    private var pendingEmpty: Pair<NetworkQuery, Long>? = null
    private val mutable = MutableStateFlow(LibraryBrowseState())
    val state = mutable.asStateFlow()

    fun playlists(offset: Int = 0, refresh: Boolean = true) = open(LibraryQuery(offset = offset), refresh)
    fun tracks(playlist: MediaKey, offset: Int = 0, refresh: Boolean = true) = open(LibraryQuery(playlist, offset), refresh)
    fun refresh() = open(state.value.query, refresh = true, bypassRecentSuccess = true)
    fun retry() = open(state.value.query, refresh = true, bypassRecentSuccess = true)

    /** Does not modify account state or erase disk metadata. */
    fun cancel() = synchronized(gate) { ++revision; mutable.value = mutable.value.copy(refreshing = false) }

    /** For explicit cache maintenance: fence old responses, without starting another fetch. */
    fun clearCachedPages() = synchronized(gate) {
        ++revision
        ++invalidationRevision
        freshQueries.clear()
        networkCompletions.clear()
        pendingEmpty = null
        invalidatedOwner = owner?.owner
        mutable.value = LibraryBrowseState(query = mutable.value.query,
            accountVerified = identity()?.verified == true, invalidationRevision = invalidationRevision)
    }

    /**
     * Marks the current platform's pages stale. A favorite burst can defer the read until the
     * next ordinary page open, keeping its writes ahead of artwork and library requests.
     * Call on the UI executor after a verified mutation.
     */
    fun invalidate(expected: LibraryCacheIdentity, refreshNow: Boolean = true) {
        val latest = identity()
        if (latest == null || !expected.verified || !expected.sameAttempt(latest) || !latest.verified) return
        val query = synchronized(gate) {
            val nextInvalidation = ++invalidationRevision
            ++revision
            invalidatedOwner = latest.owner
            freshQueries.clear()
            networkCompletions.clear()
            pendingEmpty = null
            // Account collection can still be queued on main; never retain another owner's page.
            if (!latest.sameAttempt(owner)) {
                mutable.value = LibraryBrowseState(
                    query = if (queryOwner == latest.owner) mutable.value.query else LibraryQuery())
            }
            owner = latest
            queryOwner = latest.owner
            val query = mutable.value.query
            mutable.value = mutable.value.copy(
                refreshing = refreshNow,
                accountVerified = true,
                error = null,
                storageError = false,
                invalidationRevision = nextInvalidation,
                stale = true,
            )
            query
        }
        if (!refreshNow) return
        fetchPage(
            synchronized(gate) { revision },
            latest,
            query,
        )
    }

    /** Called for account state changes, including disconnect in a different Activity. */
    fun accountChanged() {
        val next = identity()
        val previous = synchronized(gate) { owner }
        if (next == previous) return
        val query = synchronized(gate) {
            if (next != null && queryOwner != null && queryOwner != next.owner) LibraryQuery() else state.value.query
        }
        open(query, refresh = next?.verified == true, bypassRecentSuccess = true)
    }

    private fun open(
        query: LibraryQuery,
        refresh: Boolean,
        bypassRecentSuccess: Boolean = false,
    ) {
        require(query.playlist == null || query.playlist.platform == platform)
        val expected = identity()
        val token = synchronized(gate) {
            val keep = expected?.sameAttempt(owner) == true && mutable.value.query == query
            val previous = mutable.value
            if (!keep) freshQueries.clear()
            if (expected?.sameAttempt(owner) != true) { networkCompletions.clear(); pendingEmpty = null }
            owner = expected
            if (expected != null) queryOwner = expected.owner
            mutable.value = if (keep) previous.copy(refreshing = refresh && expected!!.verified,
                accountVerified = expected.verified, error = null, storageError = false,
                invalidationRevision = invalidationRevision, stale = previous.stale)
                else LibraryBrowseState(query = query, refreshing = refresh && expected?.verified == true,
                    accountVerified = expected?.verified == true, error = if (expected == null) "account_unavailable" else null,
                    invalidationRevision = invalidationRevision,
                    stale = expected?.let { !isFresh(query, it) } ?: false)
            ++revision
        }
        if (expected == null) return
        io {
            var cached: LibraryContent? = null
            var savedAt: Long? = null
            var storageFailed = false
            if (!current(token, expected)) return@io
            try {
                cacheAccess(expected) { store ->
                    if (query.playlist == null) store.readPlaylists(expected.owner, query.offset)?.let {
                        cached = LibraryContent.Playlists(copyPlaylists(it.page)); savedAt = it.fetchedAtEpochMs
                    } else store.readTracks(expected.owner, query.playlist, query.offset)?.let {
                        cached = LibraryContent.Tracks(copyTracks(it.page)); savedAt = it.fetchedAtEpochMs
                    }
                }
            } catch (_: Exception) { storageFailed = true }
            deliver {
                if (!current(token, expected)) return@deliver
                val pageIsStale = cached != null && !isFresh(query, expected)
                mutable.value = mutable.value.copy(content = cached ?: mutable.value.content,
                    source = if (cached != null) LibrarySource.CACHE else mutable.value.source,
                    fetchedAtEpochMs = savedAt ?: mutable.value.fetchedAtEpochMs,
                    accountVerified = identity()?.verified == true, storageError = storageFailed,
                    invalidationRevision = invalidationRevision,
                    stale = mutable.value.stale || pageIsStale)
                val verified = identity()?.verified == true
                if (refresh && verified &&
                    (bypassRecentSuccess || !hasRecentNetworkSuccess(expected, query))) {
                    fetchPage(token, expected, query, confirmEmpty = bypassRecentSuccess)
                } else {
                    mutable.value = mutable.value.copy(
                        refreshing = false,
                        error = if (!verified) "account_unverified" else null,
                    )
                }
            }
        }
    }

    private fun fetchPage(
        token: Long,
        expected: LibraryCacheIdentity,
        query: LibraryQuery,
        confirmEmpty: Boolean = false,
    ) {
        fun accept(response: Result<LibraryContent>) {
            if (!current(token, expected)) return
            response.onSuccess { content ->
                val completedAt = now().coerceAtLeast(0)
                val previousContent = mutable.value.content
                val previousFetchedAt = mutable.value.fetchedAtEpochMs
                io {
                    var storageFailed = false
                    if (!current(token, expected)) return@io
                    var retained = (previousContent as? LibraryContent.Playlists)?.takeIf { it.page.items.isNotEmpty() }
                    var retainedAt = previousFetchedAt
                    val emptyHome = platform == Platform.KUGOU && query.playlist == null && query.offset == 0 &&
                        content is LibraryContent.Playlists && content.page.items.isEmpty()
                    var rejectEmpty = false
                    try {
                        cacheAccess(expected) { store ->
                            if (emptyHome) {
                                store.readPlaylists(expected.owner, 0)?.takeIf { it.page.items.isNotEmpty() }?.let {
                                    retained = LibraryContent.Playlists(copyPlaylists(it.page)); retainedAt = it.fetchedAtEpochMs
                                }
                                if (retained != null) synchronized(gate) {
                                    val key = NetworkQuery(expected.owner, expected.generation, query)
                                    val previous = pendingEmpty
                                    val confirmed = confirmEmpty && previous?.first == key &&
                                        completedAt - previous.second in 0L..EMPTY_CONFIRMATION_WINDOW_MS
                                    rejectEmpty = !confirmed
                                    pendingEmpty = if (confirmed) null else key to completedAt
                                }
                            } else synchronized(gate) { pendingEmpty = null }
                            if (!rejectEmpty) when (content) {
                                is LibraryContent.Playlists -> store.writePlaylists(expected.owner, query.offset, SavedLibraryPage(content.page, completedAt))
                                is LibraryContent.Tracks -> store.writeTracks(expected.owner, requireNotNull(query.playlist), query.offset, SavedLibraryPage(content.page, completedAt))
                            }
                        }
                    } catch (_: Exception) { storageFailed = true }
                    deliver {
                        if (current(token, expected)) {
                            // A failed cache read must not bypass the non-empty visible-page guard.
                            if (rejectEmpty || (emptyHome && retained != null && storageFailed)) {
                                synchronized(gate) {
                                    networkCompletions.remove(NetworkQuery(expected.owner, expected.generation, query))
                                }
                                mutable.value = mutable.value.copy(content = retained, refreshing = false,
                                    fetchedAtEpochMs = retainedAt, stale = true, error = "incomplete", storageError = storageFailed)
                                return@deliver
                            }
                            // A failed write leaves the old disk page stale on the next open.
                            synchronized(gate) {
                                if (current(token, expected)) {
                                    networkCompletions[
                                        NetworkQuery(expected.owner, expected.generation, query)
                                    ] = completedAt
                                    if (invalidationRevision > 0 && !storageFailed) {
                                        freshQueries[FreshQuery(expected.owner, expected.generation, query)] =
                                            invalidationRevision
                                    }
                                }
                            }
                            mutable.value = LibraryBrowseState(query, content, LibrarySource.NETWORK,
                                completedAt, accountVerified = identity()?.verified == true,
                                storageError = storageFailed, invalidationRevision = invalidationRevision,
                                stale = false)
                        }
                    }
                }
            }.onFailure { error ->
                if (current(token, expected)) {
                    synchronized(gate) { pendingEmpty = null }
                    mutable.value = mutable.value.copy(refreshing = false,
                        error = (error as? LibraryPageFailure)?.category?.takeIf { it in safeErrors } ?: "unclassified")
                }
            }
        }
        try {
            if (query.playlist == null) fetch.playlists(query.offset) { result ->
                accept(result.map { LibraryContent.Playlists(copyPlaylists(it)) })
            } else fetch.tracks(query.playlist, query.offset) { result ->
                accept(result.map { LibraryContent.Tracks(copyTracks(it)) })
            }
        } catch (_: Exception) { accept(Result.failure(LibraryPageFailure("unclassified"))) }
    }

    private fun hasRecentNetworkSuccess(expected: LibraryCacheIdentity, query: LibraryQuery): Boolean =
        synchronized(gate) {
            val completedAt = networkCompletions[
                NetworkQuery(expected.owner, expected.generation, query)
            ] ?: return@synchronized false
            val elapsed = now() - completedAt
            elapsed in 0L until RECENT_NETWORK_SUCCESS_WINDOW_MS
        }

    private fun isFresh(query: LibraryQuery, expected: LibraryCacheIdentity): Boolean = synchronized(gate) {
        expected.owner != invalidatedOwner || invalidationRevision == 0L ||
            freshQueries[FreshQuery(expected.owner, expected.generation, query)] == invalidationRevision
    }

    private fun current(token: Long, expected: LibraryCacheIdentity): Boolean =
        token == revision && expected.sameAttempt(identity())

    private fun copyPlaylists(page: Page<PlaylistSummary>) = page.copy(items = Collections.unmodifiableList(page.items.toList()))
    private fun copyTracks(page: Page<SongSummary>) = page.copy(items = Collections.unmodifiableList(page.items.map {
        it.copy(artists = Collections.unmodifiableList(it.artists.toList()))
    }))

    companion object {
        private const val RECENT_NETWORK_SUCCESS_WINDOW_MS = 30_000L
        private const val EMPTY_CONFIRMATION_WINDOW_MS = 60_000L
        private val safeErrors = setOf("expired", "network", "risk", "rate", "protocol", "identity", "limit",
            "incomplete", "permission", "unavailable", "endpoint", "platform", "http", "timeout")
    }
}

open class LibraryPageFailure(val category: String) : Exception("Music library operation failed")
