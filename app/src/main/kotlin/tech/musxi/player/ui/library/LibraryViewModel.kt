package tech.musxi.player.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibraryBrowseState
import tech.musxi.core.library.LibraryContent
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.PlaylistChangeResult
import tech.musxi.core.library.PlaylistChangeStatus
import tech.musxi.core.library.PlaylistSummary
import tech.musxi.core.library.SongSummary
import tech.musxi.player.MusxiApplication

/** A confirmed change moves its playlist's count by one; nothing else is guessed. */
private fun PlaylistSummary.counted(results: List<PlaylistChangeResult>): PlaylistSummary {
    val change = results.firstOrNull { it.playlist == key && it.status == PlaylistChangeStatus.APPLIED } ?: return this
    return copy(trackCount = (trackCount + if (change.requestedPresent) 1 else -1).coerceAtLeast(0))
}

internal fun PlaylistUiState.afterMembership(results: List<PlaylistChangeResult>): PlaylistUiState =
    if (items.none { p -> results.any { it.playlist == p.key && it.status == PlaylistChangeStatus.APPLIED } }) this
    else copy(items = items.map { it.counted(results) })

/**
 * The open playlist after a membership save, and whether its first page must be read again.
 *
 * A confirmed removal drops the row now and does not re-read: every write makes the browser fetch
 * the page it holds again (the last page this list loaded), and [splicePage] merges that page in,
 * which also brings the next offset back in line after the removal shifted it by one. Re-reading
 * the first page instead would collapse a long list the user has scrolled into, and show the
 * pre-write cached page for a moment. A confirmed addition lands at a position only the server
 * knows, and an unconfirmed result may have gone either way: both re-read.
 */
internal fun TrackListUiState.afterMembership(song: MediaKey, results: List<PlaylistChangeResult>): Pair<TrackListUiState, Boolean> {
    val open = playlist ?: return this to false
    val mine = results.firstOrNull { it.playlist == open.key } ?: return this to false
    return when (mine.status) {
        PlaylistChangeStatus.APPLIED -> if (mine.requestedPresent) {
            copy(playlist = open.counted(results)) to true
        } else {
            copy(playlist = open.counted(results), items = items.filterNot { it.key == song }) to false
        }
        PlaylistChangeStatus.UNKNOWN -> this to true
        else -> this to false
    }
}

/**
 * Puts a page delivered for [offset] in place. The first page replaces the list. A later page is
 * added to what is there, skipping songs already shown: the browser delivers its current page again
 * after every write and after a cache hit, and that page may be shifted by one if a song before it
 * was just removed. Appending blindly would double rows (a doubled key makes the list throw), and
 * replacing from the offset would drop the song that moved up across the page boundary.
 */
internal fun <T, K> splicePage(current: List<T>, offset: Int, page: List<T>, key: (T) -> K): List<T> =
    if (offset == 0) page.distinctBy(key) else (current + page).distinctBy(key)

/** Which platform's library the user is looking at. `ALL` is a filter, never a merged page. */
enum class LibraryFilter(val label: String, val platform: Platform?) {
    ALL("全部", null),
    NETEASE("网易云", Platform.NETEASE),
    QQ("QQ音乐", Platform.QQ),
    KUGOU("酷狗", Platform.KUGOU),
}

/**
 * Accumulates playlist pages for the selected filter. The backend owns one page at a time;
 * aggregation lives here so a refresh or filter change can drop stale pages in one place.
 */
data class PlaylistUiState(
    val filter: LibraryFilter = LibraryFilter.NETEASE,
    val items: List<PlaylistSummary> = emptyList(),
    val nextOffset: Int? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val fromCache: Boolean = false,
    val fetchedAtEpochMs: Long? = null,
    val accountVerified: Boolean = false,
    val storageError: Boolean = false,
    /** A pull-to-refresh the user started is still running. Load-more never sets this. */
    val refreshing: Boolean = false,
) {
    val canLoadMore: Boolean get() = nextOffset != null && !loading
    val isEmpty: Boolean get() = items.isEmpty() && !loading && error == null
}

/** Song page for one playlist, with the same cache-first semantics as the playlist grid. */
data class TrackListUiState(
    val playlist: PlaylistSummary? = null,
    val items: List<SongSummary> = emptyList(),
    val nextOffset: Int? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val fromCache: Boolean = false,
    val fetchedAtEpochMs: Long? = null,
    val accountVerified: Boolean = false,
    val storageError: Boolean = false,
    val refreshing: Boolean = false,
) {
    val canLoadMore: Boolean get() = nextOffset != null && !loading
}

/**
 * Library screen state. It subscribes to the process-owned browsers and never keeps a second
 * copy of account or playback truth; `nextOffset` is always passed back exactly as received.
 */
class LibraryViewModel(private val app: MusxiApplication) : ViewModel() {

    private val _playlists = MutableStateFlow(PlaylistUiState())
    val playlists: StateFlow<PlaylistUiState> = _playlists.asStateFlow()

    private val _tracks = MutableStateFlow(TrackListUiState())
    val tracks: StateFlow<TrackListUiState> = _tracks.asStateFlow()

    private val _playlistSearch = MutableStateFlow(PlaylistSearchUi())
    /** Search inside the open playlist; reset whenever another playlist opens or this one closes. */
    val playlistSearch: StateFlow<PlaylistSearchUi> = _playlistSearch.asStateFlow()
    private var searchJob: kotlinx.coroutines.Job? = null
    /** Bumped on every query and every playlist change, so a late answer cannot overwrite a newer one. */
    private var searchGeneration = 0L

    /** Platform currently feeding the grid. */
    private var browsingPlatform: Platform = Platform.NETEASE
    /** Set once the user taps a chip; from then on start-up no longer picks the platform. */
    private var userChosePlatform = false

    init {
        // Saved accounts are restored off the main thread, so the first account state may not have
        // a profile yet. Until the user picks a chip, follow the first signed-in platform; each
        // browser loads its own playlists when its account appears.
        viewModelScope.launch {
            app.accounts.states.collect { states ->
                if (userChosePlatform) return@collect
                val preferred = listOf(Platform.KUGOU, Platform.NETEASE, Platform.QQ)
                    .firstOrNull { states.getValue(it).profile != null } ?: return@collect
                if (preferred != browsingPlatform) {
                    browsingPlatform = preferred
                    _playlists.value = PlaylistUiState(filter = filterOf(preferred))
                    merge(browserFor(preferred).state.value)
                }
            }
        }
        viewModelScope.launch {
            app.libraryBrowser.state.collect { if (browsingPlatform == Platform.NETEASE) merge(it) }
        }
        viewModelScope.launch {
            app.kugouLibraryBrowser.state.collect { if (browsingPlatform == Platform.KUGOU) merge(it) }
        }
        viewModelScope.launch {
            app.qqLibraryBrowser.state.collect { if (browsingPlatform == Platform.QQ) merge(it) }
        }
    }

    private fun browserFor(platform: Platform) = when (platform) {
        Platform.NETEASE -> app.libraryBrowser
        Platform.KUGOU -> app.kugouLibraryBrowser
        Platform.QQ -> app.qqLibraryBrowser
    }

    private fun filterOf(platform: Platform) = LibraryFilter.entries.first { it.platform == platform }

    fun selectFilter(filter: LibraryFilter) {
        val platform = filter.platform ?: return
        userChosePlatform = true
        if (platform != browsingPlatform) {
            browserFor(browsingPlatform).cancel()
            browsingPlatform = platform
            _playlists.value = PlaylistUiState(filter = filter)
            _tracks.value = TrackListUiState()
        } else {
            _playlists.value = _playlists.value.copy(filter = filter)
        }
        loadPlaylists(reset = true)
    }

    fun loadPlaylists(reset: Boolean = false) {
        val browser = browserFor(browsingPlatform)
        val offset = if (reset) 0 else _playlists.value.nextOffset ?: return
        if (reset) {
            _playlists.value = _playlists.value.copy(items = emptyList(), nextOffset = null, error = null)
        }
        _playlists.value = _playlists.value.copy(loading = true)
        browser.playlists(offset)
    }

    /**
     * Songs a confirmed save took out of the open playlist. A cached or pre-write copy of a page can
     * still hold them until the server's own first page arrives, so they stay hidden until then.
     */
    private val removedSongs = mutableSetOf<MediaKey>()

    fun openPlaylist(playlist: PlaylistSummary) {
        resetPlaylistSearch()
        removedSongs.clear()
        _tracks.value = TrackListUiState(playlist = playlist, loading = true)
        browserFor(playlist.key.platform).tracks(playlist.key, 0)
        trackPlatform = playlist.key.platform
    }

    fun loadMoreTracks() {
        val offset = _tracks.value.nextOffset ?: return
        val playlist = _tracks.value.playlist ?: return
        _tracks.value = _tracks.value.copy(loading = true)
        browserFor(playlist.key.platform).tracks(playlist.key, offset)
    }

    private var trackPlatform: Platform? = null

    fun closePlaylist() {
        resetPlaylistSearch()
        trackPlatform?.let { browserFor(it).cancel() }
        trackPlatform = null
        removedSongs.clear()
        _tracks.value = TrackListUiState()
    }

    /** Merges one page delivery, dropping pages that belong to a superseded query. */
    private fun merge(state: LibraryBrowseState) {
        // A pull-to-refresh ends with whatever the browser settles on, including a failure with no
        // page at all; otherwise the spinner would wait for content that never comes.
        if (!state.refreshing) {
            if (state.query.playlist == null && _playlists.value.refreshing) {
                _playlists.value = _playlists.value.copy(refreshing = false, loading = false, error = state.error)
            } else if (state.query.playlist != null && state.query.playlist == _tracks.value.playlist?.key &&
                _tracks.value.refreshing) {
                _tracks.value = _tracks.value.copy(refreshing = false, loading = false, error = state.error)
            }
        }
        val content = state.content
        if (content is LibraryContent.Playlists) {
            if (state.query.playlist != null) return
            val page = content.page
            val current = _playlists.value
            val accumulated = splicePage(current.items, state.query.offset, page.items) { it.key }
            _playlists.value = current.copy(
                items = accumulated,
                nextOffset = page.nextOffset,
                loading = state.refreshing,
                error = state.error,
                fromCache = state.source == tech.musxi.core.library.LibrarySource.CACHE,
                fetchedAtEpochMs = state.fetchedAtEpochMs,
                accountVerified = state.accountVerified,
                storageError = state.storageError,
                refreshing = current.refreshing && state.refreshing,
            )
        } else if (content is LibraryContent.Tracks) {
            val playlist = _tracks.value.playlist ?: return
            if (content.page.items.firstOrNull()?.key?.platform != playlist.key.platform &&
                state.query.playlist != playlist.key
            ) return
            if (state.query.playlist != playlist.key) return
            val page = content.page
            val current = _tracks.value
            // A fresh network copy of the first page is the server's word: nothing left to hide.
            if (state.source == tech.musxi.core.library.LibrarySource.NETWORK && !state.stale && state.query.offset == 0) {
                removedSongs.clear()
            }
            val delivered = if (removedSongs.isEmpty()) page.items else page.items.filterNot { it.key in removedSongs }
            val accumulated = splicePage(current.items, state.query.offset, delivered) { it.key }
            _tracks.value = current.copy(
                items = accumulated,
                nextOffset = page.nextOffset,
                loading = state.refreshing,
                error = state.error,
                fromCache = state.source == tech.musxi.core.library.LibrarySource.CACHE,
                fetchedAtEpochMs = state.fetchedAtEpochMs,
                accountVerified = state.accountVerified,
                storageError = state.storageError,
                refreshing = current.refreshing && state.refreshing,
            )
            // A new page is in the cache now: an open search covers it too.
            if (accumulated.size != current.items.size) refreshPlaylistSearch()
        }
    }

    /** Retry is always explicit; a failure never turns into an empty success. */
    fun retryPlaylists() = loadPlaylists(reset = _playlists.value.items.isEmpty())
    fun retryTracks() = _tracks.value.playlist?.let { browserFor(it.key.platform).refresh() }

    /**
     * Pull-to-refresh: re-reads the first page from the platform and starts the list again. The old
     * rows stay visible until the new page lands, so the page never flashes empty.
     */
    fun refreshPlaylists() {
        _playlists.value = _playlists.value.copy(refreshing = true, loading = true, error = null)
        browserFor(browsingPlatform).playlists(0)
    }

    fun refreshTracks() {
        val playlist = _tracks.value.playlist ?: return
        _tracks.value = _tracks.value.copy(refreshing = true, loading = true, error = null)
        browserFor(playlist.key.platform).tracks(playlist.key, 0)
    }

    fun songKeyOf(song: SongSummary): MediaKey = song.key

    fun openPlaylistSearch() {
        _playlistSearch.value = _playlistSearch.value.copy(open = true)
    }

    /** Closes the box and drops the query: the page shows the whole playlist again. */
    fun closePlaylistSearch() = resetPlaylistSearch()

    /**
     * Searches the open playlist's cached pages for [query], 250ms after the last keystroke. The
     * backend reads disk, so typing a word would otherwise queue one read per letter.
     */
    fun searchPlaylist(query: String) {
        val playlist = _tracks.value.playlist ?: return
        val generation = ++searchGeneration
        searchJob?.cancel()
        if (query.isBlank()) {
            _playlistSearch.value = PlaylistSearchUi(query = query, open = true)
            return
        }
        // Results and note of the previous query stay until the new answer replaces them.
        _playlistSearch.value = _playlistSearch.value.copy(query = query, open = true, searching = true, failed = false)
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MS)
            app.accounts.searchCachedPlaylist(playlist.key, query.trim()) { result ->
                // A newer query, or another playlist, already took over.
                if (generation != searchGeneration || _tracks.value.playlist?.key != playlist.key) return@searchCachedPlaylist
                _playlistSearch.value = _playlistSearch.value.with(query, result)
            }
        }
    }

    /** Re-runs the current query, e.g. after more pages arrived. */
    private fun refreshPlaylistSearch() {
        val current = _playlistSearch.value
        if (current.active) searchPlaylist(current.query)
    }

    private var fillJob: kotlinx.coroutines.Job? = null

    /**
     * Reads the rest of the open playlist into the cache so the search covers all of it. Each page
     * that lands re-runs the search; the backend paces the requests and stops on any trouble.
     */
    fun fillPlaylistForSearch() {
        val playlist = _tracks.value.playlist ?: return
        val browser = browserFor(playlist.key.platform)
        fillJob?.cancel()
        _playlistSearch.value = _playlistSearch.value.copy(filling = true, fillError = null)
        fillJob = viewModelScope.launch {
            var lastRevision = -1L
            browser.fillPlaylist(playlist.key).collect { fill ->
                if (fill.playlist != playlist.key || _tracks.value.playlist?.key != playlist.key) return@collect
                if (fill.pagesRevision != lastRevision) {
                    lastRevision = fill.pagesRevision
                    refreshPlaylistSearch()
                }
                val current = _playlistSearch.value
                _playlistSearch.value = current.copy(
                    filling = fill.running,
                    searched = maxOf(current.searched, fill.loadedCount),
                    total = fill.total ?: current.total,
                    fillError = if (fill.running || fill.cancelled) null else playlistFillErrorText(fill.error),
                )
                if (!fill.running) fillJob?.cancel()
            }
        }
    }

    /** The user stopped the fill; what was read stays in the cache and in the results. */
    fun cancelPlaylistFill() {
        stopFill()
        _playlistSearch.value = _playlistSearch.value.copy(filling = false)
    }

    private fun stopFill() {
        fillJob?.cancel()
        fillJob = null
        trackPlatform?.let { browserFor(it).cancelFill() }
    }

    private fun resetPlaylistSearch() {
        stopFill()
        searchGeneration++
        searchJob?.cancel()
        searchJob = null
        _playlistSearch.value = PlaylistSearchUi()
    }

    /**
     * Shows the result of a playlist membership save in the lists on screen. Only what the server
     * confirmed is applied locally: a confirmed removal drops the row from the open playlist at once
     * (and keeps it hidden from older copies of the page), and every confirmed change moves its
     * playlist's count unless the grid was read after the save began. An addition to the open
     * playlist, or an unconfirmed result, re-reads its first page. [savedAtEpochMs] is when the save
     * started. The play queue is never touched: it is the playback service's, not this page's.
     */
    fun applyMembership(song: MediaKey, results: List<PlaylistChangeResult>, savedAtEpochMs: Long? = null) {
        if (results.isEmpty()) return
        // A grid read after the save began already carries the server's counts; moving them again
        // would count the change twice.
        val grid = _playlists.value
        if (savedAtEpochMs == null || (grid.fetchedAtEpochMs ?: 0L) < savedAtEpochMs) {
            _playlists.value = grid.afterMembership(results)
        }
        val open = _tracks.value.playlist
        results.firstOrNull { it.playlist == open?.key && it.status == PlaylistChangeStatus.APPLIED }?.let {
            if (it.requestedPresent) removedSongs -= song else removedSongs += song
        }
        val (tracks, reread) = _tracks.value.afterMembership(song, results)
        _tracks.value = tracks
        if (reread) refreshTracks()
    }
}

/** Typing pause before a playlist search reads the cache. */
private const val SEARCH_DEBOUNCE_MS = 250L
