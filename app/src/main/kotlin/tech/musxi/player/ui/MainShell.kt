package tech.musxi.player.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import tech.musxi.player.ui.player.playerPalette
import tech.musxi.player.ui.theme.LocalNowPlayingAccent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibrarySource
import tech.musxi.core.playback.SourceSelectionPhase
import tech.musxi.player.MusxiApplication
import tech.musxi.player.ui.account.AccountScreen
import tech.musxi.player.ui.account.AccountViewModel
import tech.musxi.player.ui.components.OpticallyCentredText
import tech.musxi.player.ui.library.LibraryContent
import tech.musxi.player.ui.library.LibraryFilter
import tech.musxi.player.ui.library.LibraryViewModel
import tech.musxi.player.ui.library.PlaylistUiState
import tech.musxi.player.ui.library.TrackListContent
import tech.musxi.player.ui.player.FullPlayer
import tech.musxi.player.ui.player.MiniPlayer
import tech.musxi.player.ui.player.SourcePanel
import tech.musxi.player.ui.player.SourcePhase
import tech.musxi.player.ui.player.FavoriteUi
import tech.musxi.player.ui.player.QueuePanel
import tech.musxi.player.ui.player.QueueRowUi
import tech.musxi.player.ui.player.QueueUi
import tech.musxi.player.ui.player.defaultTranslationEnabled
import tech.musxi.player.ui.player.lyricsUiOf
import tech.musxi.player.ui.player.miniPlayerUi
import tech.musxi.player.ui.player.playerUiOf
import tech.musxi.player.ui.player.sourcePanelUiOf
import tech.musxi.player.ui.search.SearchScreen
import tech.musxi.player.ui.search.SearchViewModel
import tech.musxi.player.ui.shell.BarShape
import tech.musxi.player.ui.shell.ShellBottomBar
import tech.musxi.player.ui.shell.ShellDock
import tech.musxi.player.ui.shell.ShellTab
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.displayName
import tech.musxi.playback.PlaybackRepeatMode

/**
 * Shell for the real Compose front end: content area, then the mini player, then the tab bar.
 * All three tabs are wired to live backend state; the player itself is still to come.
 *
 * The chrome is laid out here rather than inside each screen for one reason: before this, `tab`
 * was never changed by anything, so the app could only ever show the library.
 */
@Composable
fun MainShell(app: MusxiApplication, dark: Boolean) {
    val accounts by app.accounts.states.collectAsStateWithLifecycle()
    val libraryViewModel: LibraryViewModel = viewModel { LibraryViewModel(app) }
    val accountViewModel: AccountViewModel = viewModel { AccountViewModel(app.accounts) }
    val searchViewModel: SearchViewModel = viewModel { SearchViewModel(app.searchBrowser) }
    val accountState by accountViewModel.state.collectAsStateWithLifecycle()
    val searchState by searchViewModel.state.collectAsStateWithLifecycle()
    val playlists by libraryViewModel.playlists.collectAsStateWithLifecycle()
    val tracks by libraryViewModel.tracks.collectAsStateWithLifecycle()
    val playback by app.playback.state.collectAsStateWithLifecycle()
    val selection by app.sourceSelection.state.collectAsStateWithLifecycle()
    val miniPlayer = remember(playback) { miniPlayerUi(playback) }
    // Favourites are per platform; the song the button acts on is the *queue original*, not the
    // swapped source: the platform that owns the song is where "liked" is stored.
    val favorites by app.favorites.state.collectAsStateWithLifecycle()
    val queue by app.playback.queueState.collectAsStateWithLifecycle()
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var queueBusy by remember { mutableStateOf(false) }
    val currentSong = remember(playback) {
        val platform = playback.queuePlatform
        val id = playback.mediaId?.substringAfter(':', missingDelimiterValue = "")
        if (platform == null || id.isNullOrBlank()) {
            null
        } else {
            tech.musxi.core.library.SongSummary(
                key = tech.musxi.core.library.MediaKey(platform, id),
                title = playback.title,
                artists = listOf(playback.artist).filter { it.isNotBlank() },
                album = playback.album,
                durationMs = playback.durationMs,
            )
        }
    }
    val favorite = remember(currentSong, favorites) {
        val song = currentSong ?: return@remember null
        val state = favorites[song.key.platform] ?: return@remember null
        FavoriteUi(
            liked = song.key in state.keys,
            pending = state.pending == song.key,
            enabled = state.canWrite,
        )
    }
    val queueUi = remember(queue, playback.queueIndex, queueBusy, playback.connected, accounts) {
        QueueUi(
            items = queue.songs.map { song ->
                QueueRowUi(
                    key = song.key.stableId,
                    title = song.title,
                    artist = song.artists.joinToString(" / ").ifBlank { "未知歌手" },
                    durationMs = song.durationMs,
                )
            },
            currentIndex = playback.queueIndex,
            editing = !queueBusy && playback.connected && queue.songs.firstOrNull()?.let {
                accounts[it.key.platform]?.phase == AccountPhase.CONNECTED
            } == true,
            revision = queue.revision,
        )
    }
    // The player page needs one number the shell does not otherwise watch: whether a swap is still
    // searching, which changes the wording of the failed state.
    val swapSearching = selection.phase == SourceSelectionPhase.SEARCHING
    val playerUi = remember(playback, swapSearching) { playerUiOf(playback, swapSearching) }
    // One accent for the whole shell: the colour of the track that is playing, or the theme's own
    // accent when nothing is.
    val nowPlaying = remember(playback.title, dark) {
        if (playback.title.isBlank()) null else playerPalette(playback.title, dark).accent
    }

    // Display-only ticker. The service owns the five-second timer (`selection.tick`); this exists
    // purely so the countdown re-renders while that timer runs.
    var nowTick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(selection.phase, selection.deadlineElapsedMs) {
        if (selection.phase == SourceSelectionPhase.CHOOSING) {
            repeat(35) {
                nowTick = SystemClock.elapsedRealtime()
                delay(200)
            }
        }
    }
    val sourcePanel = remember(selection, nowTick) { sourcePanelUiOf(selection, nowTick) }

    var tab by rememberSaveable { mutableStateOf(ShellTab.LIBRARY) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    // Lyrics are a mode of the player page, not a destination: the cover's slot changes and every
    // control stays usable. `browsing` is the user having taken the scroll over.
    var lyricsMode by rememberSaveable { mutableStateOf(false) }
    var lyricsBrowsing by remember { mutableStateOf(false) }
    var lyricsTouchedAt by remember { mutableLongStateOf(0L) }
    val lyricsState by app.lyrics.state.collectAsStateWithLifecycle()
    // null means "use the original script's default"; touching the control records an explicit
    // choice for this source. Keying the saved value by source makes rotation retain the choice and
    // a source switch start from the new lyrics instead. Translation updates never write this value.
    var translationOverride by rememberSaveable(lyricsState.sourceKey?.stableId) {
        mutableStateOf<Boolean?>(null)
    }
    val defaultTranslation = remember(lyricsState.lines, lyricsState.plainText) {
        defaultTranslationEnabled(lyricsState.lines, lyricsState.plainText)
    }
    val showTranslation = translationOverride ?: defaultTranslation
    val lyricsUi = remember(lyricsState, lyricsBrowsing, showTranslation) {
        lyricsUiOf(lyricsState, lyricsBrowsing, showTranslation)
    }
    // A new song or a new source means a new list: keeping "the user has taken over the scroll" from
    // the previous track would hand the next song a scroll position and a paused follow it never
    // asked for.
    LaunchedEffect(lyricsState.sourceKey) {
        lyricsBrowsing = false
    }
    // Following resumes when the finger has been away long enough. There is no separate "picked
    // line" state any more: tapping a line seeks straight to it, so the list never has to hold
    // still waiting for a second press.
    LaunchedEffect(lyricsTouchedAt) {
        if (lyricsTouchedAt > 0) {
            delay(3_000)
            lyricsBrowsing = false
        }
    }
    val context = LocalContext.current
    fun submitQueueCommand(command: ((Result<Unit>) -> Unit) -> Unit) {
        if (!queueUi.editing) return
        queueBusy = true
        command { result ->
            queueBusy = false
            if (result.isFailure) Toast.makeText(
                context, "队列操作未完成，请确认连接后重试", Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            // The dock floats over the content: the page is full height and scrolls underneath it,
            // so it no longer sits on a strip of bare background. The inset is measured, not
            // guessed, because the dock changes height when the mini player appears.
            var dockHeight by remember { mutableIntStateOf(0) }
            val contentInset = with(LocalDensity.current) { dockHeight.toDp() } + 16.dp
            // Everything below the shell inherits the accent of the track that is playing.
            CompositionLocalProvider(LocalNowPlayingAccent provides nowPlaying) {
                Box(
                    Modifier.fillMaxWidth().fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars),
                ) {
                    when (tab) {
                        // A playlist opens inside the library tab rather than over the shell: the
                        // dock has to stay visible, because tapping a track is the moment the user
                        // needs to see that something started playing.
                        ShellTab.LIBRARY -> if (tracks.playlist != null) {
                            TrackListContent(
                                state = tracks,
                                dark = dark,
                                onBack = libraryViewModel::closePlaylist,
                                onPlayAll = {
                                    val playlist = tracks.playlist
                                    val first = tracks.items.firstOrNull()
                                    if (playlist != null && first != null) {
                                        app.queuePlayback.playPlaylist(
                                            playlist.key,
                                            libraryViewModel.songKeyOf(first),
                                        )
                                    }
                                },
                                onSongClick = { song ->
                                    val playlist = tracks.playlist
                                    if (playlist != null) {
                                        app.queuePlayback.playPlaylist(
                                            playlist.key,
                                            libraryViewModel.songKeyOf(song),
                                        )
                                    }
                                },
                                onLoadMore = libraryViewModel::loadMoreTracks,
                                onRetry = libraryViewModel::retryTracks,
                                nowPlayingId = playback.mediaId,
                                bottomInset = contentInset,
                            )
                        } else {
                            LibraryContent(
                                state = playlists,
                                onSelectFilter = libraryViewModel::selectFilter,
                                onLoadMore = { libraryViewModel.loadPlaylists() },
                                onOpenPlaylist = libraryViewModel::openPlaylist,
                                // "No account yet" is a dead end unless the button actually takes
                                // the user to the tab where connecting happens.
                                onOpenAccount = { tab = ShellTab.ME },
                                onRefresh = libraryViewModel::retryPlaylists,
                                bottomInset = contentInset,
                            )
                        }
                        ShellTab.SEARCH -> SearchScreen(
                            state = searchState,
                            onKeywordsChange = searchViewModel::onKeywordsChange,
                            onPlatformSelect = searchViewModel::selectPlatform,
                            onSearch = searchViewModel::search,
                            onLoadMore = searchViewModel::loadMore,
                            onRetry = searchViewModel::retry,
                            onSongClick = { song -> app.queuePlayback.playSong(song) },
                            bottomInset = contentInset,
                        )
                        ShellTab.ME -> AccountScreen(
                            state = accountState,
                            dark = dark,
                            bottomInset = contentInset,
                            // The platform login flow needs a real Activity to launch its intent.
                            onConnect = { platform ->
                                accountViewModel.connect(platform) { intent ->
                                    runCatching { context.startActivity(intent) }
                                }
                            },
                            onCancel = accountViewModel::cancel,
                            onRetry = accountViewModel::retry,
                            onRelogin = accountViewModel::relogin,
                            onManage = accountViewModel::manage,
                        )
                    }
                }
                // One dock instead of two stacked capsules: the chrome shares the page's margins,
                // and its hairline is the same separator the lists use.
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(horizontal = Space.page)
                        // The dock must clear the gesture bar; without this it sits under it on
                        // every phone with gesture navigation.
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .onSizeChanged { dockHeight = it.height },
                ) {
                    ShellDock(
                        mini = miniPlayer,
                        selected = tab,
                        onSelect = { tab = it },
                        onExpand = { showPlayer = true },
                        onPlayPause = { app.playback.toggle() },
                        onPrevious = { app.playback.previous() },
                        onNext = { app.playback.next() },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            // The detail page is the library tab's second level, so back leaves it before it leaves
            // the tab. Composed before the player's handler, which therefore wins while it is open.
            BackHandler(enabled = tracks.playlist != null) {
                libraryViewModel.closePlaylist()
            }
            // The queue sheet is the topmost overlay: back closes it before it touches the player.
            BackHandler(enabled = showQueue) { showQueue = false }
            // Back must peel one layer at a time, outermost first. Before this existed the back
            // gesture left the app from the player, which is the one screen a listener is most
            // likely to be holding.
            BackHandler(enabled = showPlayer) {
                when {
                    sourcePanel.phase != SourcePhase.HIDDEN ->
                        app.sourceSelection.cancel(selection.revision)
                    // Leaving lyrics returns to the cover first: it is a mode of this page, and a
                    // single back press should not skip the layer the user just entered.
                    lyricsMode -> lyricsMode = false
                    else -> showPlayer = false
                }
            }
            // The full player covers the shell rather than navigating to a new destination: it is a
            // layer over the same state, and pressing back must return to the tab underneath.
            if (showPlayer) {
                FullPlayer(
                    ui = playerUi,
                    dark = dark,
                    onCollapse = {
                        showPlayer = false
                        // The player closes on the artwork, not on the lyrics of the song that will
                        // not be showing next time.
                        lyricsMode = false
                        lyricsBrowsing = false
                    },
                    onPlayPause = { app.playback.toggle() },
                    onPrevious = { app.playback.previous() },
                    onNext = { app.playback.next() },
                    onSeek = { fraction ->
                        val duration = playback.durationMs
                        if (duration > 0) {
                            app.playback.seekTo((duration * fraction).toLong())
                        }
                    },
                    onShowLyrics = {
                        if (lyricsMode) {
                            lyricsMode = false
                            lyricsBrowsing = false
                        } else {
                            lyricsMode = true
                        }
                    },
                    favorite = favorite,
                    onToggleFavorite = {
                        val song = currentSong
                        val ui = favorite
                        if (song != null && ui != null) {
                            app.favorites.setLiked(song, !ui.liked)
                        }
                    },
                    onShowQueue = { showQueue = true },
                    lyrics = if (lyricsMode) lyricsUi else null,
                    onSeekLine = { timeMs ->
                        // One step, like the PC build: the tap is the jump. It also ends browsing,
                        // because the user has just said where they want to be.
                        app.playback.seekTo(timeMs)
                        lyricsBrowsing = false
                    },
                    onToggleTranslation = { translationOverride = !showTranslation },
                    onRefreshTranslation = { app.lyrics.refreshTranslation() },
                    onReturnToCurrent = { lyricsBrowsing = false },
                    onRetryLyrics = { app.lyrics.refresh() },
                    onReloginLyrics = {
                        // Passing the account back through the player would leave the user in a
                        // mode that cannot succeed; take them where the fix actually lives.
                        showPlayer = false
                        lyricsMode = false
                        tab = ShellTab.ME
                    },
                    onUserScrollLyrics = {
                        lyricsBrowsing = true
                        lyricsTouchedAt = SystemClock.elapsedRealtime()
                    },
                    onToggleShuffle = { app.playback.setShuffleEnabled(!playback.shuffleEnabled) },
                    onCycleRepeat = {
                        app.playback.setRepeatMode(
                            when (playback.repeatMode) {
                                PlaybackRepeatMode.OFF -> PlaybackRepeatMode.ALL
                                PlaybackRepeatMode.ALL -> PlaybackRepeatMode.ONE
                                PlaybackRepeatMode.ONE -> PlaybackRepeatMode.OFF
                            },
                        )
                    },
                )
                // The queue sits above the player for the same reason the source panel does: it is
                // an interruption of the page, not a destination of its own.
                if (showQueue) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))) {
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            QueuePanel(
                                ui = queueUi,
                                onSelect = { index -> submitQueueCommand { done ->
                                    app.playback.jumpToQueueIndex(index, queue.revision, done)
                                } },
                                onRemove = { index ->
                                    // The revision travels with the command: a stale edit is
                                    // rejected by the service rather than applied to a queue that
                                    // has moved on.
                                    submitQueueCommand { done -> app.playback.removeQueueItem(index, queue.revision, done) }
                                },
                                onMove = { from, to, expectedRevision ->
                                    // Same revision rule as the other edits: if the queue changed
                                    // while the finger was down, the service refuses rather than
                                    // applying a move computed against a list that no longer exists.
                                    submitQueueCommand { done -> app.playback.moveQueueItem(from, to, expectedRevision, done) }
                                },
                                onClear = { submitQueueCommand { done -> app.playback.clearQueue(queue.revision, done) } },
                                onClose = { showQueue = false },
                            )
                        }
                    }
                }
                // The source panel sits above the player: an interruption, not a destination.
                if (sourcePanel.phase != SourcePhase.HIDDEN) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))) {
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            SourcePanel(
                                ui = sourcePanel,
                                dark = dark,
                                onPick = { index -> app.sourceSelection.select(selection.revision, index) },
                                onCancel = { app.sourceSelection.cancel(selection.revision) },
                                onSkip = { app.playback.next() },
                                onRetry = { app.playback.resume() },
                            )
                        }
                    }
                }
            }
        }
    }
}
