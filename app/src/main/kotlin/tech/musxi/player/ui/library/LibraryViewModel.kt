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
    val filter: LibraryFilter = LibraryFilter.ALL,
    val items: List<PlaylistSummary> = emptyList(),
    val nextOffset: Int? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val fromCache: Boolean = false,
    val fetchedAtEpochMs: Long? = null,
    val accountVerified: Boolean = false,
    val storageError: Boolean = false,
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

    /** Platform currently feeding the grid; `null` means the ALL tab shows one platform at a time. */
    private var browsingPlatform: Platform = Platform.NETEASE

    init {
        viewModelScope.launch {
            app.libraryBrowser.state.collect { merge(it) }
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

    fun selectFilter(filter: LibraryFilter) {
        val platform = filter.platform ?: return
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
            )
        }
    }

    /** Retry is always explicit; a failure never turns into an empty success. */
    fun retryPlaylists() = loadPlaylists(reset = _playlists.value.items.isEmpty())
    fun retryTracks() = _tracks.value.playlist?.let { browserFor(it.key.platform).refresh() }

    fun songKeyOf(song: SongSummary): MediaKey = song.key
}
