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
    private var owner: LibraryCacheIdentity? = null
    private var queryOwner: String? = null
    private val mutable = MutableStateFlow(LibraryBrowseState())
    val state = mutable.asStateFlow()

    fun playlists(offset: Int = 0, refresh: Boolean = true) = open(LibraryQuery(offset = offset), refresh)
    fun tracks(playlist: MediaKey, offset: Int = 0, refresh: Boolean = true) = open(LibraryQuery(playlist, offset), refresh)
    fun refresh() = open(state.value.query, true)

    /** Does not modify account state or erase disk metadata. */
    fun cancel() = synchronized(gate) { ++revision; mutable.value = mutable.value.copy(refreshing = false) }

    /** Called for account state changes, including disconnect in a different Activity. */
    fun accountChanged() {
        val next = identity()
        val previous = synchronized(gate) { owner }
        if (next == previous) return
        val query = synchronized(gate) {
            if (next != null && queryOwner != null && queryOwner != next.owner) LibraryQuery() else state.value.query
        }
        open(query, refresh = next?.verified == true)
    }

    private fun open(query: LibraryQuery, refresh: Boolean) {
        require(query.playlist == null || query.playlist.platform == platform)
        val expected = identity()
        val token = synchronized(gate) {
            val keep = expected?.sameAttempt(owner) == true && mutable.value.query == query
            owner = expected
            if (expected != null) queryOwner = expected.owner
            mutable.value = if (keep) mutable.value.copy(refreshing = refresh && expected!!.verified,
                accountVerified = expected.verified, error = null, storageError = false)
                else LibraryBrowseState(query = query, refreshing = refresh && expected?.verified == true,
                    accountVerified = expected?.verified == true, error = if (expected == null) "account_unavailable" else null)
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
                mutable.value = mutable.value.copy(content = cached ?: mutable.value.content,
                    source = if (cached != null) LibrarySource.CACHE else mutable.value.source,
                    fetchedAtEpochMs = savedAt ?: mutable.value.fetchedAtEpochMs,
                    accountVerified = identity()?.verified == true, storageError = storageFailed)
                if (refresh && identity()?.verified == true) fetchPage(token, expected, query)
                else mutable.value = mutable.value.copy(refreshing = false,
                    error = if (identity()?.verified != true) "account_unverified" else null)
            }
        }
    }

    private fun fetchPage(token: Long, expected: LibraryCacheIdentity, query: LibraryQuery) {
        fun accept(response: Result<LibraryContent>) {
            if (!current(token, expected)) return
            response.onSuccess { content ->
                val fetchedAt = now().coerceAtLeast(0)
                io {
                    var storageFailed = false
                    if (!current(token, expected)) return@io
                    try {
                        cacheAccess(expected) { store -> when (content) {
                            is LibraryContent.Playlists -> store.writePlaylists(expected.owner, query.offset, SavedLibraryPage(content.page, fetchedAt))
                            is LibraryContent.Tracks -> store.writeTracks(expected.owner, requireNotNull(query.playlist), query.offset, SavedLibraryPage(content.page, fetchedAt))
                        } }
                    } catch (_: Exception) { storageFailed = true }
                    deliver {
                        if (current(token, expected)) mutable.value = LibraryBrowseState(query, content, LibrarySource.NETWORK,
                            fetchedAt, accountVerified = identity()?.verified == true, storageError = storageFailed)
                    }
                }
            }.onFailure { error ->
                if (current(token, expected)) mutable.value = mutable.value.copy(refreshing = false,
                    error = (error as? LibraryPageFailure)?.category?.takeIf { it in safeErrors } ?: "unclassified")
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

    private fun current(token: Long, expected: LibraryCacheIdentity): Boolean =
        token == revision && expected.sameAttempt(identity())

    private fun copyPlaylists(page: Page<PlaylistSummary>) = page.copy(items = Collections.unmodifiableList(page.items.toList()))
    private fun copyTracks(page: Page<SongSummary>) = page.copy(items = Collections.unmodifiableList(page.items.map {
        it.copy(artists = Collections.unmodifiableList(it.artists.toList()))
    }))

    companion object {
        private val safeErrors = setOf("expired", "network", "risk", "rate", "protocol", "identity", "limit",
            "incomplete", "permission", "unavailable", "endpoint", "platform", "http", "timeout")
    }
}

open class LibraryPageFailure(val category: String) : Exception("Music library operation failed")
