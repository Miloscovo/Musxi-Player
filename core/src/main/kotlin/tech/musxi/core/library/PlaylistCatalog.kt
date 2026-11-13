package tech.musxi.core.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.musxi.core.account.Platform

data class PlaylistCatalogState(
    val platform: Platform,
    val items: List<PlaylistSummary> = emptyList(),
    val revision: Long = 0,
    val identity: LibraryCacheIdentity? = null,
    val nextOffset: Int? = null,
    val complete: Boolean = false,
    val loading: Boolean = false,
    val fromCache: Boolean = false,
    val customOrder: Boolean = false,
    val storageError: Boolean = false,
    val error: String? = null,
    val savingOrder: Boolean = false,
)

/** IO and completion are serialized by the account repository; UI never handles the file. */
fun interface PlaylistOrderAccess {
    fun access(expected: LibraryCacheIdentity, change: ((List<MediaKey>) -> List<MediaKey>)?,
        result: (Result<List<MediaKey>>) -> Unit)
}

/** One account/platform's accumulated playlist pages and durable Musxi-only display order.
 * All entrypoints and callbacks run on the UI executor. Raw offsets never depend on display order.
 */
class PlaylistCatalog(
    val platform: Platform,
    private val browser: LibraryBrowser,
    private val identity: () -> LibraryCacheIdentity?,
    private val storage: PlaylistOrderAccess,
) {
    private val mutable = MutableStateFlow(PlaylistCatalogState(platform))
    val state = mutable.asStateFlow()
    private val pages = sortedMapOf<Int, Page<PlaylistSummary>>()
    private var order = emptyList<MediaKey>()
    private var owner: LibraryCacheIdentity? = null
    @Volatile private var version = 0L
    private var epoch = 0L
    private var opened = false
    private var storageReady = false
    private var saving = false
    private var invalidation = -1L
    private val serverChanges = ArrayDeque<() -> Unit>()

    fun open(refresh: Boolean = true) {
        opened = true
        ensureOwner()
        pages.clear(); ++version
        publish()
        browser.playlists(0, refresh)
        accept(browser.state.value)
    }
    fun refresh() {
        open(false)
        browser.refresh()
        accept(browser.state.value)
    }
    fun loadMore() {
        ensureOwner()
        val next = state.value.nextOffset ?: return
        if (!state.value.loading) {
            browser.playlists(next)
            accept(browser.state.value)
        }
    }
    fun cancel() {
        opened = false
        browser.cancel()
        mutable.value = mutable.value.copy(loading = false)
    }
    fun accountChanged() {
        if (ensureOwner() && opened) {
            browser.playlists(0)
            accept(browser.state.value)
        }
    }
    fun invalidate(expected: LibraryCacheIdentity) {
        if (expected != identity()) return
        pages.clear(); ++version; publish()
        browser.invalidate(expected, refreshNow = false)
        if (opened) {
            browser.playlists(0)
            accept(browser.state.value)
        }
    }
    fun clearCachedPages() {
        pages.clear(); ++version; publish()
        browser.clearCachedPages()
        accept(browser.state.value)
    }

    /** Collected from this catalog's own browser, not the song-detail browser. */
    fun accept(value: LibraryBrowseState) {
        ensureOwner()
        val expected = owner ?: return
        if (!opened || value !== browser.state.value || value.query.playlist != null || !browser.represents(expected)) return
        if (invalidation != value.invalidationRevision) {
            invalidation = value.invalidationRevision
            pages.clear(); ++version
        }
        val content = value.content as? LibraryContent.Playlists
        if (content != null && !value.stale) {
            val page = content.page
            val offset = value.query.offset
            val previous = pages[offset]
            val end = offset + page.items.size
            if (page.items.size > FilePlaylistOrderStore.MAX_ITEMS || end > FilePlaylistOrderStore.MAX_ITEMS ||
                page.items.any { it.key.platform != platform } ||
                page.items.map { it.key }.distinct().size != page.items.size ||
                (page.nextOffset != null && (page.items.isEmpty() || page.nextOffset != end)) ||
                (page.total != null && (page.total !in end..FilePlaylistOrderStore.MAX_ITEMS ||
                    (page.nextOffset == null && end != page.total) ||
                    (page.nextOffset != null && end >= page.total))) ||
                (offset > 0 && pages.filterKeys { it < offset }.values.any {
                    (it.total != null && page.total != null && it.total != page.total) ||
                        it.items.any { row -> page.items.any { next -> next.key == row.key } }
                })) {
                mutable.value = state.value.copy(loading = false, error = "protocol")
                return
            }
            if (previous != page) {
                // A changed prefix invalidates every later page, including cached continuation pages.
                pages.keys.filter { it >= offset }.forEach(pages::remove)
                pages[offset] = page.copy(items = page.items.toList())
                ++version
            }
        }
        publish(value.refreshing, value.source == LibrarySource.CACHE, value.error)
    }

    /** Move before an already-loaded ordinary/collected row. Null means end; requires complete data. */
    fun move(key: MediaKey, before: MediaKey?, expectedRevision: Long, result: (Result<Unit>) -> Unit) {
        ensureOwner()
        val loaded = rawItems().filter { it.kind != PlaylistKind.LIKED }.map { it.key }
        if (key !in loaded || before == key || (before != null && before !in loaded) || key.platform != platform)
            return result(Result.failure(LibraryPageFailure("invalid_request")))
        if (before == null && !state.value.complete)
            return result(Result.failure(LibraryPageFailure("incomplete")))
        update(expectedRevision, { saved ->
            val merged = (saved + loaded).distinct().toMutableList()
            merged.remove(key)
            merged.add(before?.let { merged.indexOf(it) } ?: merged.size, key)
            merged
        }, result)
    }
    fun moveToStart(key: MediaKey, expectedRevision: Long, result: (Result<Unit>) -> Unit) {
        ensureOwner()
        if (state.value.items.none { it.key == key && it.kind != PlaylistKind.LIKED })
            return result(Result.failure(LibraryPageFailure("invalid_request")))
        val loaded = rawItems().filter { it.kind != PlaylistKind.LIKED }.map { it.key }
        update(expectedRevision, { saved ->
            listOf(key) + (saved + loaded).distinct().filter { it != key }
        }, result)
    }
    fun resetOrder(expectedRevision: Long, result: (Result<Unit>) -> Unit) =
        update(expectedRevision, { emptyList() }, result)

    /** Server-created IDs are inserted at the front without mistaking a storage failure for failed creation. */
    fun created(key: MediaKey, result: (Result<Unit>) -> Unit = {}) {
        ensureOwner()
        if (key.platform != platform) return result(Result.failure(LibraryPageFailure("invalid_request")))
        update(null, { saved -> listOf(key) + saved.filter { it != key } }, result)
    }
    fun deleted(key: MediaKey, result: (Result<Unit>) -> Unit = {}) {
        ensureOwner()
        if (key.platform != platform) return result(Result.failure(LibraryPageFailure("invalid_request")))
        update(null, { saved -> saved.filter { it != key } }, result)
    }

    private fun ensureOwner(): Boolean {
        val latest = identity()
        if (latest == owner) return false
        owner = latest; ++epoch; ++version; saving = false; storageReady = false
        val abandoned = serverChanges.toList()
        serverChanges.clear()
        pages.clear(); order = emptyList(); invalidation = -1
        mutable.value = PlaylistCatalogState(platform, revision = version, identity = latest)
        abandoned.forEach { it() }
        if (latest == null) return true
        val token = epoch
        storage.access(latest, null) { response ->
            if (token != epoch || latest != identity()) return@access
            storageReady = response.isSuccess
            order = response.getOrDefault(emptyList()).toList()
            ++version
            publish(storageError = response.isFailure)
        }
        return true
    }

    private fun update(expectedRevision: Long?, change: (List<MediaKey>) -> List<MediaKey>, result: (Result<Unit>) -> Unit) {
        ensureOwner()
        val expected = owner ?: return result(Result.failure(LibraryPageFailure("account_unavailable")))
        if (!storageReady && expectedRevision != null) return result(Result.failure(LibraryPageFailure("storage")))
        if (saving) {
            if (expectedRevision != null) return result(Result.failure(LibraryPageFailure("busy")))
            serverChanges += {
                if (expected != identity()) result(Result.failure(LibraryPageFailure("account_changed")))
                else update(null, change, result)
            }
            return
        }
        if (expectedRevision != null && expectedRevision != version)
            return result(Result.failure(LibraryPageFailure("conflict")))
        val token = epoch
        val startedVersion = version
        saving = true
        publish()
        storage.access(expected, { saved ->
            // The storage executor rechecks account ownership; collections are never caller-owned.
            if (expectedRevision != null && version != startedVersion) throw LibraryPageFailure("conflict")
            change(saved.toList()).toList()
        }) { response ->
            if (token != epoch || expected != identity()) {
                result(Result.failure(LibraryPageFailure("account_changed"))); return@access
            }
            saving = false
            response.onSuccess { order = it.toList(); storageReady = true; ++version }
            publish(storageError = response.isFailure)
            // A remote refresh may arrive during the local atomic write. The saved order remains valid,
            // but the frontend must take the latest revision before its next drag.
            try { result(response.map { Unit }) } finally {
                if (!saving && serverChanges.isNotEmpty()) serverChanges.removeFirst().invoke()
            }
        }
    }

    private fun rawItems(): List<PlaylistSummary> {
        val values = mutableListOf<PlaylistSummary>()
        var offset: Int? = 0
        val visited = mutableSetOf<Int>()
        while (offset != null && visited.add(offset)) {
            val page = pages[offset] ?: break
            values += page.items
            offset = page.nextOffset
        }
        return values.distinctBy { it.key }
    }
    private fun publish(loading: Boolean = state.value.loading, fromCache: Boolean = state.value.fromCache,
        error: String? = null, storageError: Boolean = state.value.storageError) {
        val raw = rawItems()
        var next: Int? = 0
        val visited = mutableSetOf<Int>()
        while (next != null && visited.add(next) && pages.containsKey(next)) next = pages.getValue(next).nextOffset
        val rank = order.withIndex().associate { it.value to it.index }
        val fixed = raw.filter { it.kind == PlaylistKind.LIKED }
        val ordinary = raw.filter { it.kind != PlaylistKind.LIKED }.sortedBy { rank[it.key] ?: Int.MAX_VALUE }
        mutable.value = PlaylistCatalogState(platform, (fixed + ordinary).toList(), version, owner,
            next, next == null && pages.containsKey(0), loading, fromCache, order.isNotEmpty(), storageError, error, saving)
    }
}
