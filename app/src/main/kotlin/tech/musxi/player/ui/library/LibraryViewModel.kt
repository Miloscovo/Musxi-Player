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
import tech.musxi.core.library.PlaylistSummary
import tech.musxi.core.library.SongSummary
import tech.musxi.player.MusxiApplication

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

    fun openPlaylist(playlist: PlaylistSummary) {
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
        trackPlatform?.let { browserFor(it).cancel() }
        trackPlatform = null
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
            val accumulated = if (state.query.offset == 0) page.items else current.items + page.items
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
            val accumulated = if (state.query.offset == 0) page.items else current.items + page.items
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
}
