package tech.musxi.player.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import tech.musxi.player.ui.shell.swipeSwitch
import tech.musxi.player.ui.shell.swipeSwitchPage
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
import tech.musxi.player.ui.player.nextBookmarkName
import tech.musxi.player.ui.player.skipTarget
import tech.musxi.player.ui.player.LocalBookmarkSheet
import tech.musxi.player.ui.player.LocalPlayerActions
import tech.musxi.player.ui.player.speedLabel
import tech.musxi.player.ui.player.playerPalette
import tech.musxi.player.ui.player.rememberCoverColor
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.LocalNowPlayingAccent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import tech.musxi.player.ui.nav.NavStackHost
import tech.musxi.player.ui.nav.rememberNavStackState
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibrarySource
import tech.musxi.core.playback.SourceSelectionPhase
import tech.musxi.accounts.NeteaseWebLogin
import tech.musxi.player.MusxiApplication
import tech.musxi.player.ui.account.AccountPageActions
import tech.musxi.player.ui.account.AccountViewModel
import tech.musxi.player.ui.components.GlassHost
import tech.musxi.player.ui.components.GlassLayer
import tech.musxi.player.ui.me.lyricsColorModeOf
import tech.musxi.player.ui.me.meUiOf
import tech.musxi.player.ui.me.translationPreferenceOf
import tech.musxi.player.ui.shell.MeRootKey
import tech.musxi.player.ui.shell.MeTab
import tech.musxi.player.ui.shell.MeTabActions
import tech.musxi.player.ui.shell.cacheClearNotice
import tech.musxi.player.ui.account.UpdateActions
import tech.musxi.player.ui.account.aboutUpdateUiOf
import tech.musxi.player.ui.account.isTrustedReleasePage
import tech.musxi.player.ui.account.justUpdatedNotice
import tech.musxi.player.ui.components.OpticallyCentredText
import tech.musxi.player.ui.library.LibraryContent
import tech.musxi.player.ui.library.LibraryFilter
import tech.musxi.player.ui.library.LibraryViewModel
import tech.musxi.player.ui.library.libraryHomeUiOf
import tech.musxi.player.ui.library.PlaylistUiState
import tech.musxi.player.ui.library.TrackListContent
import tech.musxi.player.ui.player.CoverRef
import tech.musxi.player.ui.player.FullPlayer
import tech.musxi.player.ui.player.PlayerSurface
import tech.musxi.player.ui.player.fadesWithPlayer
import tech.musxi.player.ui.player.rememberPlayerProgress
import tech.musxi.player.ui.player.LyricsMatchSheet
import tech.musxi.player.ui.player.MiniPlayer
import tech.musxi.player.ui.player.lyricsMatchUiOf
import tech.musxi.player.ui.player.SourcePanel
import tech.musxi.player.ui.playlist.MembershipEditor
import tech.musxi.player.ui.playlist.MembershipSource
import tech.musxi.player.ui.playlist.PlaylistMembershipSheet
import tech.musxi.player.ui.account.UpdateSheet
import tech.musxi.player.ui.player.SourcePhase
import tech.musxi.player.ui.player.FavoriteIntent
import tech.musxi.player.ui.player.FavoriteStep
import tech.musxi.player.ui.player.favoriteUiOf
import tech.musxi.player.ui.player.nextFavoriteStep
import tech.musxi.player.ui.player.MenuChoice
import tech.musxi.player.ui.player.MoreAction
import tech.musxi.player.ui.player.MoreMenuUi
import tech.musxi.player.ui.player.OutputMenuUi
import tech.musxi.player.ui.player.QualityMenuUi
import tech.musxi.player.ui.player.SongInfoDialog
import tech.musxi.player.ui.player.menuIcon
import tech.musxi.player.ui.model.formatDuration
import tech.musxi.player.sleepStartedNotice
import tech.musxi.player.sleepStatusText
import tech.musxi.playback.displayName
import tech.musxi.player.ui.player.QueueRowActions
import tech.musxi.player.ui.player.QueueSheet
import tech.musxi.player.ui.player.QueueRowUi
import tech.musxi.player.ui.player.QueueUi
import tech.musxi.player.ui.player.translationShown
import tech.musxi.player.ui.player.isLocalPlayback
import tech.musxi.player.ui.player.localAudioInfoRows
import tech.musxi.player.ui.player.localFolderNameOf
import tech.musxi.player.ui.player.localNeighbourCover
import tech.musxi.player.ui.player.localQueueUiOf
import tech.musxi.player.ui.player.lyricsUiOf
import tech.musxi.player.ui.player.miniPlayerUi
import tech.musxi.player.ui.player.playerUiOf
import tech.musxi.player.ui.player.PlaybackProgress
import tech.musxi.player.ui.player.lyricsWithoutWordProgress
import tech.musxi.player.ui.player.positionOnly
import tech.musxi.player.ui.player.playbackProgressOf
import tech.musxi.player.ui.player.withoutPosition
import tech.musxi.player.ui.player.slowPlaybackStateOf
import tech.musxi.player.ui.player.withoutWordProgress
import tech.musxi.player.ui.player.sourcePanelUiOf
import tech.musxi.player.ui.search.RecentSearches
import tech.musxi.player.ui.search.SearchScreen
import tech.musxi.player.ui.search.SearchViewModel
import tech.musxi.player.ui.shell.BarShape
import tech.musxi.player.ui.shell.ShellBottomBar
import tech.musxi.player.ui.shell.NoticePill
import tech.musxi.player.ui.shell.NoticeState
import tech.musxi.player.ui.shell.ShellDock
import tech.musxi.player.ui.shell.ShellTab
import tech.musxi.player.ui.shell.dockNoticeOf
import tech.musxi.player.ui.theme.LocalMusxi
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.SystemBarIcons
import tech.musxi.player.ui.theme.displayName

/**
 * Shell for the real Compose front end: content area, then the mini player, then the tab bar.
 * All three tabs are wired to live backend state; the player itself is still to come.
 *
 * The chrome is laid out here rather than inside each screen for one reason: before this, `tab`
 * was never changed by anything, so the app could only ever show the library.
 */
@Composable
fun MainShell(app: MusxiApplication, dark: Boolean) {
    // Non-blocking feedback is shown as an in-app pill, not a system Toast.
    val notice = remember { NoticeState() }
    val accounts by app.accounts.states.collectAsStateWithLifecycle()
    // Persisted preferences and the cache measurement behind the "我的" page. MainActivity reads the
    // theme mode from the same settings flow; the transparency switch is read here for the glass.
    val settings by app.settings.collectAsStateWithLifecycle()
    val cacheState by app.cache.state.collectAsStateWithLifecycle()
    val libraryViewModel: LibraryViewModel = viewModel { LibraryViewModel(app) }
    val accountViewModel: AccountViewModel = viewModel { AccountViewModel(app.accounts) }
    val searchViewModel: SearchViewModel = viewModel {
        SearchViewModel(
            app.searchBrowser,
            RecentSearches(app.getSharedPreferences("search_history", android.content.Context.MODE_PRIVATE)),
        )
    }
    val accountState by accountViewModel.state.collectAsStateWithLifecycle()
    val searchState by searchViewModel.state.collectAsStateWithLifecycle()
    val playlists by libraryViewModel.playlists.collectAsStateWithLifecycle()
    // An empty search page follows the platform the library is showing, so a Kugou listener does not
    // land on 网易云 every time. Once there are keywords or results, the search keeps its own choice.
    LaunchedEffect(playlists.filter.platform) {
        val platform = playlists.filter.platform ?: return@LaunchedEffect
        val search = searchViewModel.state.value
        if (search.phase == tech.musxi.player.ui.search.SearchPhase.IDLE && search.keywords.isBlank()) {
            searchViewModel.selectPlatform(platform)
        }
    }
    val tracks by libraryViewModel.tracks.collectAsStateWithLifecycle()
    val playlistSearch by libraryViewModel.playlistSearch.collectAsStateWithLifecycle()
    // The library tab's page stack (root list, then a playlist) and the root's scroll position,
    // hoisted so both survive switching tabs.
    val libraryNav = rememberNavStackState(root = "library", maxDepth = 2)
    val libraryList = rememberLazyListState()
    // The 本地音频 home's own list, hoisted for the same reason: reselecting the tab scrolls it.
    val localHomeList = rememberLazyListState()
    // The "我的" tab's stack: the page itself, then one sub-page (深色模式 or a platform's account).
    val meNav = rememberNavStackState(root = MeRootKey, maxDepth = 2)
    // The "我的" root page's scroll, hoisted like the library list so reselecting the tab can reach it.
    val meScroll = androidx.compose.foundation.rememberScrollState()
    val shellScope = rememberCoroutineScope()
    // The library's fourth segment, 本地音频, and the local folder page it pushes. The folder id is
    // saved with the stack; null opens "未归入文件夹". While the folder page is selecting, its action
    // bar takes the dock's place.
    val localController = remember(app) { tech.musxi.player.ui.localaudio.AppLocalAudioController(app) }
    // One way to change a recording's cover, from its row's menu or a long press on the playing cover.
    val coverCropHost = tech.musxi.player.ui.localaudio.rememberCoverCropHost(localController, notice::show)
    var librarySegmentLocal by rememberSaveable { mutableStateOf(false) }
    var localFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var localSelecting by remember { mutableStateOf(false) }
    // A restored stack can outlive the view model's playlist (process death): drop the empty page.
    // A restored local folder page reads the library itself, so it stays.
    LaunchedEffect(Unit) {
        if (libraryNav.stack.top == "playlist" && libraryViewModel.tracks.value.playlist == null) libraryNav.popToRoot()
    }
    // The recommendation list has nothing to show once the switch is off (its lists are cleared).
    LaunchedEffect(settings.recommendationsEnabled) {
        if (!settings.recommendationsEnabled && libraryNav.stack.top == "recommendations") libraryNav.popToRoot()
    }
    // Closed only once the pop has finished, so the leaving page keeps its rows during the gesture.
    LaunchedEffect(libraryNav.depth) {
        if (libraryNav.depth == 1) libraryViewModel.closePlaylist()
    }
    // Spec section 12: the service republishes the whole state twice a second while playing, and a
    // shell that read it would recompose every page on each report. So the state is split in two.
    // `playback` is the state with the position taken out: it only changes when something other
    // than the position does, and it is what this function reads. `playbackProgress` carries the
    // position and is handed down unread, as a State or a lambda, to the few leaves that draw it.
    // Its .value must never be read in this function's body.
    val playbackFlows = remember(app) {
        val source = app.playback.state
        PlaybackFlows(
            slow = source.withoutPosition(),
            progress = source.positionOnly(),
            initialSlow = slowPlaybackStateOf(source.value),
            initialProgress = playbackProgressOf(source.value),
        )
    }
    val playback by playbackFlows.slow.collectAsStateWithLifecycle(playbackFlows.initialSlow)
    val playbackProgress = playbackFlows.progress.collectAsStateWithLifecycle(playbackFlows.initialProgress)
    val miniProgress: () -> Float = remember(playbackProgress) { { playbackProgress.value.fraction } }
    val livePlayerProgress: () -> PlaybackProgress = remember(playbackProgress) { { playbackProgress.value } }
    // A recording from the local library is playing: no platform, source, quality or lyrics apply,
    // and the queue is the local one. Platform songs never reach the branches this guards.
    val localPlaying = isLocalPlayback(playback)
    val outputs by app.playback.outputs.collectAsStateWithLifecycle()
    val selection by app.sourceSelection.state.collectAsStateWithLifecycle()
    val miniPlayer = remember(playback) { miniPlayerUi(playback) }
    // Favourites are per platform; the song the button acts on is the *queue original*, not the
    // swapped source: the platform that owns the song is where "liked" is stored.
    val favorites by app.favorites.state.collectAsStateWithLifecycle()
    // The heart can only write after the collection has been read once. Nothing else asks for that
    // read, so a connected platform with an unsynced collection is synced here. A failed write or an
    // unverified write also drops the collection back to unsynced, so this re-runs whenever the
    // favourites change, at most twice per (platform, account attempt) until a sync succeeds: the
    // browser itself rejects a request while another one is loading.
    val connectedGenerations = Platform.entries.associateWith { platform ->
        accounts[platform]?.takeIf { it.phase == AccountPhase.CONNECTED }?.generation
    }
    val resyncCounts = remember { mutableMapOf<Pair<Platform, Long>, Int>() }
    LaunchedEffect(connectedGenerations, favorites) {
        connectedGenerations.forEach { (platform, generation) ->
            val state = app.favorites.state.value[platform] ?: return@forEach
            if (state.synced) {
                resyncCounts.keys.removeAll { it.first == platform }
                return@forEach
            }
            if (generation == null || state.loading || state.pending != null) return@forEach
            val slot = platform to generation
            val used = resyncCounts[slot] ?: 0
            if (used < 2) {
                resyncCounts[slot] = used + 1
                app.favorites.refresh(platform)
            }
        }
    }
    val queue by app.playback.queueState.collectAsStateWithLifecycle()
    val queuedLocal by app.playback.localQueueState.collectAsStateWithLifecycle()
    // The library is read only while a recording plays, so a platform-only session never opens it.
    // The queue's items are a snapshot from when it was built; whether a file can still be read is
    // updated in the library as playback finds out, so rows and the info dialog take that instead.
    val localLibrary = if (localPlaying) app.localAudio.state.collectAsStateWithLifecycle().value else null
    val localQueue = remember(queuedLocal, localLibrary) {
        val fresh = localLibrary?.items?.associateBy { it.id }.orEmpty()
        if (fresh.isEmpty()) queuedLocal else queuedLocal.copy(items = queuedLocal.items.map { fresh[it.id] ?: it })
    }
    val localFolderName = localFolderNameOf(localQueue.folderId, localLibrary?.folders.orEmpty())
    val queueLoad by app.queuePlayback.state.collectAsStateWithLifecycle()
    // The song the user tapped, shown as "preparing" until the queue lands or fails.
    var pendingSongId by remember { mutableStateOf<String?>(null) }
    // Cleared when the tapped song is the one playing, or when the load settles with an error. A
    // plain "loading went false" check could fire in the same frame as the tap and clear it early.
    LaunchedEffect(pendingSongId, playback.mediaId, queueLoad.error, queueLoad.loading) {
        val pending = pendingSongId ?: return@LaunchedEffect
        if (playback.mediaId == pending || (!queueLoad.loading && queueLoad.error != null)) pendingSongId = null
    }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var showBookmarks by rememberSaveable { mutableStateOf(false) }
    var showPlayMode by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showOutput by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showSongInfo by remember { mutableStateOf(false) }
    var showLyricsMatch by remember { mutableStateOf(false) }
    var qualityLoading by remember { mutableStateOf(false) }
    var qualityOptions by remember { mutableStateOf<tech.musxi.core.playback.QualityOptions?>(null) }
    var qualityError by remember { mutableStateOf(false) }
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
    // What the user asked the heart to become. The backend confirms a write by re-reading the whole
    // collection, which takes seconds, so the tap lives here as an intent: the heart shows it at
    // once, and the effect below hands it to the backend when the backend can take it.
    var favoriteIntent by remember { mutableStateOf<FavoriteIntent?>(null) }
    var favoriteFailures by remember { mutableIntStateOf(0) }
    val favorite = remember(currentSong, favorites, favoriteIntent, favoriteFailures) {
        favoriteUiOf(favorites[currentSong?.key?.platform], currentSong, favoriteIntent)
            ?.copy(failures = favoriteFailures)
    }
    // Re-asks every second while waiting so the 20 second limit can fire; a state change restarts
    // the effect at once. An intent is submitted once: the submitted copy has a new value, which
    // restarts this effect on the "already written" branch of nextFavoriteStep.
    LaunchedEffect(favoriteIntent, favorites) {
        val intent = favoriteIntent ?: return@LaunchedEffect
        while (true) {
            val state = app.favorites.state.value[intent.song.key.platform] ?: return@LaunchedEffect
            when (val step = nextFavoriteStep(state, intent, SystemClock.elapsedRealtime())) {
                FavoriteStep.Wait -> delay(1_000)
                is FavoriteStep.Submit -> {
                    favoriteIntent = step.intent
                    app.favorites.setLiked(step.intent.song, step.intent.liked)
                    return@LaunchedEffect
                }
                FavoriteStep.Done -> {
                    favoriteIntent = null
                    return@LaunchedEffect
                }
                is FavoriteStep.Fail -> {
                    favoriteIntent = null
                    favoriteFailures++
                    notice.show(step.message)
                    return@LaunchedEffect
                }
            }
        }
    }
    // A recording's cover as the player shows it: the picked picture, or the file's own while
    // 读取本地封面 is on. The setting is a key below, so switching it redraws the queue at once.
    val localArtworkMode = settings.localArtworkMode
    val localCover: (tech.musxi.core.localaudio.LocalAudioItem) -> String? = remember(localArtworkMode) {
        { item -> app.localAudio.displayArtworkFile(item)?.let { android.net.Uri.fromFile(it).toString() } }
    }
    val queueUi = remember(queue, localQueue, localPlaying, playback.queueIndex, queueBusy, playback.connected, accounts, localCover) {
        // The local queue belongs to no account: signing out of a platform leaves it editable.
        if (localPlaying) return@remember localQueueUiOf(localQueue, editing = !queueBusy && playback.connected, cover = localCover)
        QueueUi(
            items = queue.songs.map { song ->
                QueueRowUi(
                    key = song.key.stableId,
                    title = song.title,
                    artist = song.artists.joinToString(" / ").ifBlank { "未知歌手" },
                    durationMs = song.durationMs,
                    coverUrl = song.coverUrl,
                )
            },
            currentIndex = playback.queueIndex,
            editing = !queueBusy && playback.connected && queue.songs.firstOrNull()?.let {
                accounts[it.key.platform]?.phase == AccountPhase.CONNECTED
            } == true,
            revision = queue.revision,
            upcoming = queue.upcoming,
        )
    }
    // The player page needs one number the shell does not otherwise watch: whether a swap is still
    // searching, which changes the wording of the failed state.
    val swapSearching = selection.phase == SourceSelectionPhase.SEARCHING
    val playerUi = remember(playback, swapSearching, localFolderName) {
        playerUiOf(playback, swapSearching, localFolderName)
    }
    // Neighbour covers for the cover swipe. Under shuffle the next song is not the next row of the
    // queue, so none is drawn there and the swipe only returns the cover.
    // A local queue has no artwork: its neighbours are placeholders from the local rows.
    val previousCover = remember(queue, localQueue, localPlaying, playback.queueIndex, playerUi.shuffle, localCover) {
        if (localPlaying) localNeighbourCover(localQueue, playback.queueIndex, -1, playerUi.shuffle, localCover)
        else if (playerUi.shuffle) null else queue.songs.getOrNull(playback.queueIndex - 1)?.let { CoverRef(it.title, it.coverUrl) }
    }
    val nextCover = remember(queue, localQueue, localPlaying, playback.queueIndex, playerUi.shuffle, localCover) {
        if (localPlaying) localNeighbourCover(localQueue, playback.queueIndex, 1, playerUi.shuffle, localCover)
        else if (playerUi.shuffle) null else queue.songs.getOrNull(playback.queueIndex + 1)?.let { CoverRef(it.title, it.coverUrl) }
    }
    // One accent for the whole shell: the colour of the track that is playing, or the theme's own
    // accent when nothing is. Taken from the same cover sample as the player page, so the two agree.
    val nowPlayingCover = rememberCoverColor(playback.coverUrl)
    val nowPlaying = remember(playback.title, dark, nowPlayingCover) {
        if (playback.title.isBlank()) null else playerPalette(playback.title, dark, nowPlayingCover).accent
    }

    val sourcePanel = remember(selection) { sourcePanelUiOf(selection, SystemClock.elapsedRealtime()) }

    var tab by rememberSaveable { mutableStateOf(ShellTab.LIBRARY) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }

    // 每日推荐 / 猜你喜欢 on the library home. Shown for the platform segment in view, only while the
    // switch in 我的 is on; 本地音频 has none.
    val recommendationStates by app.recommendations.state.collectAsStateWithLifecycle()
    val recommendationPlatform = playlists.filter.platform
        .takeIf { settings.recommendationsEnabled && !librarySegmentLocal }
    // Keyed on the phase so a login finishing (or a re-check) opens again; the backend answers an
    // unverified account from its cache, marked stale, without a request.
    val recommendationAccountPhase = recommendationPlatform?.let { accounts[it]?.phase }
    // The contract asks for open on entering the page and on coming back to the foreground; there is
    // no timer. The platform's fresh cache answers without a request, so reopening is cheap. Turning
    // the switch back on lands here too, once the save has gone through and the settings flow says so.
    // Leaving does not cancel: a cancelled read still uses up the 30-second interval, so coming back
    // within it would show 刚刷新过 instead of the list the finished read would have cached.
    LifecycleStartEffect(recommendationPlatform, recommendationAccountPhase, tab == ShellTab.LIBRARY) {
        if (tab == ShellTab.LIBRARY) recommendationPlatform?.let(app.recommendations::open)
        onStopOrDispose { }
    }
    // The list page belongs to the platform it was opened from.
    var recommendationListPlatform by rememberSaveable { mutableStateOf<Platform?>(null) }
    // A refresh the user asked for (pull on the list, 刷新): its outcome gets a notice once settled.
    var recommendationRefreshAsked by remember { mutableStateOf<Platform?>(null) }
    LaunchedEffect(recommendationRefreshAsked, recommendationStates) {
        val platform = recommendationRefreshAsked ?: return@LaunchedEffect
        val state = recommendationStates[platform] ?: return@LaunchedEffect
        if (state.loading) return@LaunchedEffect
        recommendationRefreshAsked = null
        tech.musxi.player.ui.library.recommendationRefreshNotice(state)?.let { notice.show(it) }
    }
    fun refreshRecommendations(platform: Platform) {
        recommendationRefreshAsked = platform
        app.recommendations.refresh(platform)
    }
    // Plays the list as shown: the revision is the one on screen, so a list refreshed in between is
    // refused (stale_revision) instead of starting a different song at the same position.
    fun playRecommendations(platform: Platform, index: Int, songId: String? = null) {
        val revision = recommendationStates[platform]?.revision ?: return
        if (songId != null) pendingSongId = songId
        app.recommendations.play(platform, index, revision) { result ->
            result.onFailure {
                if (songId != null && pendingSongId == songId) pendingSongId = null
                notice.show(tech.musxi.player.ui.library.recommendationPlayFailureText(it))
            }
        }
    }
    // Lyrics are a mode of the player page, not a destination: the cover's slot changes and every
    // control stays usable. `browsing` is the user having taken the scroll over.
    var lyricsMode by rememberSaveable { mutableStateOf(false) }
    var lyricsBrowsing by remember { mutableStateOf(false) }
    var lyricsTouchedAt by remember { mutableLongStateOf(0L) }
    // The mini row and the full page are one surface driven by one progress value. `showPlayer`
    // stays the saved "is it open" flag; a tap, a release or a back press changes both together, and
    // a change made here (search from the player, relogin) runs the surface to match.
    fun collapsePlayer() {
        showPlayer = false
        showQueue = false
        showLyricsMatch = false
        showPlayMode = false
        showQuality = false
        showOutput = false
        showMore = false
    }
    val playerProgress = rememberPlayerProgress(
        expanded = showPlayer,
        onExpandedChange = { open -> if (open) showPlayer = true else collapsePlayer() },
    )
    // Open, or still moving: back, the page below and the dock notice treat the player as present.
    val playerActive = showPlayer || playerProgress.busy
    val playerCovering = playerProgress.covering
    // The two wallpapers (11-12): one host each for its 外观 row, frame sheet and preview bar. The
    // player's is judged on the open player; the interface's on the library home, the page with the
    // most covers and cards, and it goes back to 我的 afterwards.
    val wallpaperHost = tech.musxi.player.ui.wallpaper.rememberWallpaperHost(
        kind = tech.musxi.player.ui.wallpaper.WallpaperKind.PLAYER,
        repo = app.wallpaper,
        onNotice = notice::show,
        onOpenPreview = { showPlayer = true },
        onClosePreview = { playerProgress.collapse() },
    )
    val interfaceWallpaperHost = tech.musxi.player.ui.wallpaper.rememberWallpaperHost(
        kind = tech.musxi.player.ui.wallpaper.WallpaperKind.INTERFACE,
        repo = app.interfaceWallpaper,
        onNotice = notice::show,
        onOpenPreview = {
            if (showPlayer) playerProgress.collapse()
            tab = ShellTab.LIBRARY
            libraryNav.popToRoot()
        },
        onClosePreview = { tab = ShellTab.ME },
    )
    val wallpaperHosts = remember(wallpaperHost, interfaceWallpaperHost) {
        wallpaperHost.other = interfaceWallpaperHost
        interfaceWallpaperHost.other = wallpaperHost
        tech.musxi.player.ui.wallpaper.WallpaperHosts(interfaceWallpaperHost, wallpaperHost)
    }
    val wallpaperState by app.wallpaper.state.collectAsStateWithLifecycle()
    val interfaceWallpaperState by app.interfaceWallpaper.state.collectAsStateWithLifecycle()
    // A player preview left by pulling the player down or switching away is dropped, never half-applied.
    LaunchedEffect(playerActive) { if (!playerActive) wallpaperHost.previewLeft() }
    var miniRect by remember { mutableStateOf<Rect?>(null) }
    // The player closes on the artwork, not on the lyrics of the song that will not be showing next
    // time. Reset once the page has finished going down, so the cover does not swap in under it.
    LaunchedEffect(playerActive) {
        if (!playerActive) {
            lyricsMode = false
            lyricsBrowsing = false
        }
    }
    // A local recording has no lyrics and no quality choice: a page left in lyrics mode returns to
    // the cover, and menus left open by the previous song close.
    LaunchedEffect(localPlaying) {
        if (localPlaying) {
            lyricsMode = false
            lyricsBrowsing = false
            showQuality = false
            showLyricsMatch = false
        }
    }
    // Read for this frame too: the effect above lands one frame after the switch.
    val lyricsShown = lyricsMode && !localPlaying
    // The browser re-samples the active word with every playback report; the page draws the word
    // sweep from the playhead below, so the shell drops those two fields and only sees a new line.
    val lyricsFlow = remember(app) { app.lyrics.state.withoutWordProgress() }
    val lyricsState by lyricsFlow.collectAsStateWithLifecycle(remember(app) { lyricsWithoutWordProgress(app.lyrics.state.value) })
    // The 我的 setting decides every song's default; the chip on the lyrics page changes the current
    // song only. The choice is keyed by source and by the setting, so the next song (or a change of
    // the setting) starts from the setting again, while rotation keeps the choice.
    var translationOverride by rememberSaveable(lyricsState.sourceKey?.stableId, settings.lyricsTranslationPreference) {
        mutableStateOf<Boolean?>(null)
    }
    val showTranslation = remember(
        lyricsState.lines, lyricsState.plainText, translationOverride, settings.lyricsTranslationPreference,
    ) {
        translationShown(settings.lyricsTranslationPreference, translationOverride, lyricsState.lines, lyricsState.plainText)
    }
    val lyricsUi = remember(lyricsState, lyricsBrowsing, showTranslation) {
        lyricsUiOf(lyricsState, lyricsBrowsing, showTranslation)
    }
    // The word sweep's clock: the sampled position carried forward between samples while the
    // backend says it may be. Fed from an effect, not composition: a recomposition is not a sample,
    // and reading the position here would recompose the shell on every report.
    val lyricsPlayhead = remember { tech.musxi.player.ui.player.LyricsPlayhead() }
    LaunchedEffect(playbackProgress) {
        snapshotFlow { playbackProgress.value }.collect { sample ->
            lyricsPlayhead.update(sample.positionMs, sample.sampledAtElapsedMs, sample.speed, sample.canExtrapolate)
        }
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
    // 锁定平台 (我的 › 曲库首页): the library opens on it, and a swipe on the library walks the tabs.
    val libraryLockStore = remember(context) {
        tech.musxi.player.ui.library.LibraryLockStore(tech.musxi.player.ui.library.libraryLockPrefs(context))
    }
    var libraryLock by remember { mutableStateOf(libraryLockStore.load()) }
    // Applied once per process and on each change, not again on a configuration change: applying
    // it reloads page one. Always applied at start, so the start-up pick of the first signed-in
    // platform does not take the library away from the locked one.
    var appliedLock by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(libraryLock) {
        val lock = libraryLock
        if (appliedLock == lock.name) return@LaunchedEffect
        appliedLock = lock.name
        val platform = lock.platform
        when {
            lock == tech.musxi.player.ui.library.LibraryLock.LOCAL -> librarySegmentLocal = true
            platform != null -> {
                librarySegmentLocal = false
                libraryViewModel.selectFilter(LibraryFilter.entries.first { it.platform == platform })
            }
        }
    }
    // The backend owns check, download and install; the shell only maps its state to the page.
    val updateState by app.updates.state.collectAsStateWithLifecycle()
    val aboutUpdate = remember(updateState) { aboutUpdateUiOf(updateState) }
    // The release details live in a sheet; the 关于 row only says that there is one and where it is.
    var showUpdateSheet by rememberSaveable { mutableStateOf(false) }
    val updateActions = remember(app, context) {
        UpdateActions(
            onCheck = { app.updates.check() },
            onShowRelease = { showUpdateSheet = true },
            onOpenRelease = { url ->
                if (isTrustedReleasePage(url)) runCatching {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                }
            },
            onIgnore = { version -> app.updates.ignore(version) },
            onClearIgnore = { app.updates.clearIgnore() },
            onDownload = { version -> app.updates.startDownload(version) },
            onCancelDownload = { app.updates.cancelDownload() },
            onInstall = { app.updates.install() },
            onOpenInstallSettings = { app.updates.openInstallPermissionSettings() },
            onSetAutoCheck = { enabled -> app.updates.setAutoCheck(enabled) },
        )
    }
    // First start after an update: say so once, then clear it so the next start stays quiet.
    LaunchedEffect(updateState.justUpdatedFrom) {
        if (updateState.justUpdatedFrom != null) {
            notice.show(justUpdatedNotice(updateState.current.versionName))
            app.updates.consumeJustUpdated()
        }
    }
    val appVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val currentQueueTicket = app.playback.currentQueueTicket()
    LaunchedEffect(showQuality, localPlaying, currentQueueTicket, playback.mediaId, playback.sourceKey, queue.queueEpoch) {
        // A local file has no quality to ask the service about.
        if (!showQuality || localPlaying) return@LaunchedEffect
        qualityLoading = true; qualityError = false; qualityOptions = null
        val expectedKey = playback.sourceKey
        val expectedEpoch = queue.queueEpoch
        val expectedTicket = currentQueueTicket
        app.playback.qualities(expectedKey) { result ->
            if (showQuality && app.playback.currentQueueTicket() == expectedTicket &&
                app.playback.state.value.sourceKey == expectedKey && app.playback.queueState.value.queueEpoch == expectedEpoch) {
                qualityLoading = false
                qualityOptions = result.getOrNull()
                qualityError = result.isFailure
            }
        }
    }
    fun openOutputSwitcher() {
        if (!app.playback.openOutputSwitcher(context)) notice.show("无法打开系统输出设置")
    }
    // The sleep timer outlives the player page, so only its state is observed here. The clock is
    // read once a second and only while the overflow menu is showing the time left.
    val sleepTimer by app.playback.sleepTimer.collectAsStateWithLifecycle()
    val sleepDeadline = sleepTimer.deadlineElapsedMs
    var sleepNow by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(showMore, sleepDeadline, sleepTimer.phase) {
        while (showMore && sleepTimer.phase == tech.musxi.core.playback.SleepTimerPhase.COUNTING) {
            sleepNow = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    // A recording is not a song: the end-of-track wording names what is actually playing.
    val trackNoun = if (localPlaying) "音频" else "歌曲"
    val sleepStatus = sleepStatusText(sleepTimer, sleepNow, trackNoun)
    // "到点后播完当前…" is a standing preference for the minute rows, kept across launches.
    val sleepPrefs = remember(context) {
        context.getSharedPreferences("sleep-timer-ui-v1", android.content.Context.MODE_PRIVATE)
    }
    var sleepFinishTrack by remember { mutableStateOf(sleepPrefs.getBoolean("finish-track", false)) }
    // Leaves the player for the search tab with the artist or album already typed in.
    fun searchFromPlayer(keywords: String) {
        val term = keywords.trim()
        if (term.isEmpty()) return
        showMore = false
        showPlayer = false
        lyricsMode = false
        lyricsBrowsing = false
        tab = ShellTab.SEARCH
        searchViewModel.onKeywordsChange(term)
        val platform = playback.queuePlatform
        // Changing platform already searches when keywords are typed; otherwise search directly.
        if (platform != null && searchViewModel.state.value.platform != platform) {
            searchViewModel.selectPlatform(platform)
        } else {
            searchViewModel.search()
        }
    }
    // Success is said only after the service accepted the command; it rejects an end-of-track
    // timer when nothing is ready to finish.
    fun startSleepTimer(minutes: Int) {
        val finish = sleepFinishTrack
        app.playback.startSleepTimer(minutes, finishTrack = finish) { result ->
            notice.show(if (result.isSuccess) sleepStartedNotice(minutes, finish, trackNoun) else "定时停止设置失败，请重试")
        }
    }
    fun stopAfterCurrentTrack() {
        app.playback.stopAfterCurrentTrack { result ->
            notice.show(
                if (result.isSuccess) sleepStartedNotice(null, finishTrack = true, noun = trackNoun)
                else "当前没有可以播完的$trackNoun，请开始播放后再试",
            )
        }
    }
    // Manual search of the other platforms for the current queue slot. Results arrive in the source
    // selection state, so the source panel and the dock notice take it from there.
    fun findSources() {
        app.playback.findAlternativeSources { result ->
            if (result.isFailure) notice.show("现在不能换源，请稍后再试")
        }
    }

    fun handleMoreAction(action: MoreAction) {
        showMore = false
        val songText = listOf(playback.title, playback.artist).filter { it.isNotBlank() }.joinToString(" - ")
        when (action) {
            MoreAction.SLEEP_OFF -> {
                app.playback.cancelSleepTimer { result ->
                    notice.show(if (result.isSuccess) "已关闭定时停止" else "关闭定时停止失败，请重试")
                }
            }
            MoreAction.SLEEP_15 -> startSleepTimer(15)
            MoreAction.SLEEP_30 -> startSleepTimer(30)
            MoreAction.SLEEP_45 -> startSleepTimer(45)
            MoreAction.SLEEP_60 -> startSleepTimer(60)
            MoreAction.SLEEP_90 -> startSleepTimer(90)
            MoreAction.SLEEP_END_OF_TRACK -> stopAfterCurrentTrack()
            // The menu hides both searches for a local recording; its author is not a platform artist.
            MoreAction.SEARCH_ARTIST -> if (!localPlaying) {
                searchFromPlayer(playback.artist.split('、', '/', ',').firstOrNull().orEmpty())
            }
            MoreAction.SEARCH_ALBUM -> if (!localPlaying) searchFromPlayer(playback.album)
            MoreAction.COPY -> if (songText.isNotEmpty()) {
                context.getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("song", songText))
                // From Android 13 the system confirms a copy itself.
                if (android.os.Build.VERSION.SDK_INT < 33) {
                    notice.show("已复制")
                }
            }
            MoreAction.SHARE -> if (songText.isNotEmpty()) {
                // Text only: no link, so nothing about the signed stream or the account is shared.
                runCatching {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, songText)
                    context.startActivity(android.content.Intent.createChooser(send, if (localPlaying) "分享" else "分享歌曲"))
                }
            }
            MoreAction.INFO -> showSongInfo = true
        }
    }
    // Queue loading errors leave the previous playback intact and still need visible feedback.
    LaunchedEffect(queueLoad.error) {
        val message = queueLoadMessage(queueLoad.error) ?: return@LaunchedEffect
        notice.show(message)
    }
    // Netease logs in through the official web page in an in-app window. The window belongs to this
    // Activity, so it is held here and closed with it.
    val activity = remember(context) { context.findActivity() }
    var neteaseWeb by remember { mutableStateOf<NeteaseWebLogin?>(null) }
    fun closeNeteaseWeb() {
        neteaseWeb?.close()
        neteaseWeb = null
    }
    DisposableEffect(activity) { onDispose { closeNeteaseWeb() } }
    fun connect(platform: Platform) {
        if (platform == Platform.NETEASE) {
            val host = activity?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
            closeNeteaseWeb()
            neteaseWeb = NeteaseWebLogin.open(host, app.accounts, closed = { neteaseWeb = null })
            if (neteaseWeb == null) {
                notice.show("网易云登录正在进行，请先完成或取消")
            }
        } else {
            // The official login apps need a real Activity to launch their intent.
            accountViewModel.connect(platform) { intent -> runCatching { context.startActivity(intent) } }
        }
    }
    var reconnectAfterClear by remember { mutableStateOf<Platform?>(null) }
    LaunchedEffect(reconnectAfterClear, accounts) {
        val platform = reconnectAfterClear ?: return@LaunchedEffect
        if (accounts[platform]?.phase == AccountPhase.DISCONNECTED) {
            reconnectAfterClear = null
            connect(platform)
        }
    }
    // Settings are written on the IO thread, one at a time in the order they were tapped. The page
    // shows the stored value and keeps no copy, so a save that fails leaves the switch where it was;
    // the notice says why nothing changed.
    val settingsWrites = remember { Mutex() }
    fun saveSetting(change: () -> Unit) {
        shellScope.launch {
            val saved = settingsWrites.withLock {
                withContext(Dispatchers.IO) { runCatching { change() }.isSuccess }
            }
            if (!saved) notice.show("设置没能保存，请稍后重试")
        }
    }
    val meTabActions = MeTabActions(
        onSetAutoSwitchSource = { on -> saveSetting { app.settingsRepository.setAutoSwitchSource(on) } },
        onSetRelaxDuration = { on -> saveSetting { app.settingsRepository.setRelaxSourceDuration(on) } },
        // Off clears the lists and their cache in the backend. On needs nothing here: the library
        // home opens the platform in view once the settings flow says the switch is on.
        onSetRecommendations = { on -> saveSetting { app.settingsRepository.setRecommendationsEnabled(on) } },
        onSelectLibraryLock = { lock ->
            libraryLockStore.save(lock)
            libraryLock = lock
        },
        onSetFollowCover = { on -> saveSetting { app.settingsRepository.setLyricsColorMode(lyricsColorModeOf(on)) } },
        onSetShowTranslation = { on ->
            saveSetting { app.settingsRepository.setLyricsTranslationPreference(translationPreferenceOf(on)) }
        },
        onSetTransparency = { on -> saveSetting { app.settingsRepository.setTransparencyEnabled(on) } },
        onSetThemeMode = { mode -> saveSetting { app.settingsRepository.setThemeMode(mode) } },
        onSetDefaultQuality = { quality -> saveSetting { app.settingsRepository.setDefaultQuality(quality) } },
        onSetLyricsFontSize = { size -> saveSetting { app.settingsRepository.setLyricsFontSize(size) } },
        onSetMediaCardLyrics = { on -> saveSetting { app.settingsRepository.setMediaCardLyrics(on) } },
        // Saved by name; MainActivity builds the theme from it, so the whole app follows at once.
        onSetAccent = { accent -> saveSetting { app.settingsRepository.setAccent(accent.name) } },
        // Switching only hides or shows covers already read; it never deletes or reads anything.
        onSetLocalArtwork = { on ->
            saveSetting { app.settingsRepository.setLocalArtworkMode(if (on) "AUTO" else "OFF") }
        },
        onFillLocalArtwork = { fillLocalArtwork(app, notice) },
        // MeTab has already asked. A clear that fails for one kind still clears the others, so the
        // sentence is built from what the backend reports instead of always saying it worked.
        onClearCache = { app.cache.clear { result -> notice.show(cacheClearNotice(result)) } },
    )
    // The account sub-page keeps the flows the account list had: official-app connect, the Netease
    // web window, re-login after clearing, re-verify and sign-out.
    val accountPageActions = AccountPageActions(
        onConnect = ::connect,
        onCancel = { platform ->
            // Cancelling only moves the account state; the web window must be
            // closed too, or it stays on screen with nothing behind it.
            if (platform == Platform.NETEASE) closeNeteaseWeb()
            accountViewModel.cancel(platform)
        },
        onRetry = accountViewModel::retry,
        onRelogin = { platform ->
            // An expired session is cleared first, then a new login starts by
            // itself once the clearing has landed; one tap, not two.
            if (platform == Platform.NETEASE) closeNeteaseWeb()
            reconnectAfterClear = platform
            accountViewModel.relogin(platform)
        },
        onReverify = accountViewModel::reverify,
        onSignOut = { platform ->
            if (platform == Platform.NETEASE) closeNeteaseWeb()
            accountViewModel.disconnect(platform)
        },
        onConnectNeteaseScanner = if (android.os.Build.VERSION.SDK_INT >= 29) {
            {
                accountViewModel.connectNeteaseScanner { intent ->
                    runCatching { context.startActivity(intent) }
                }
                notice.show("登录二维码已存入相册，请在网易云扫一扫里从相册选择")
            }
        } else null,
    )
    // The theme is built from the saved accent (MainActivity), so the page shows the one in use.
    val accent = LocalMusxi.current.accent
    val artworkFill by app.localAudio.artworkFill.collectAsStateWithLifecycle()
    val artworkFillText = localArtworkFillText(artworkFill, localArtworkFillActive)
    val meUi = remember(accounts, settings, accent, cacheState, artworkFillText, libraryLock) {
        meUiOf(
            // Netease, QQ, Kugou: the order of the old account screen, not the enum's.
            accounts = listOf(Platform.NETEASE, Platform.QQ, Platform.KUGOU).mapNotNull { accounts[it] },
            settings = settings,
            accent = accent,
            cache = cacheState,
            localArtworkFill = artworkFillText,
        ).copy(libraryLock = libraryLock)
    }
    // Opening the tab measures the cache again, so the storage row is never an old number.
    LaunchedEffect(tab == ShellTab.ME) {
        if (tab == ShellTab.ME) app.cache.refresh()
    }
    fun submitQueueCommand(command: ((Result<Unit>) -> Unit) -> Unit) {
        if (!queueUi.editing) return
        queueBusy = true
        // The local queue needs no account connection, so its failure does not ask for one.
        val failure = if (localPlaying) "队列操作未完成，请重试" else "队列操作未完成，请确认连接后重试"
        command { result ->
            queueBusy = false
            if (result.isFailure) notice.show(failure)
        }
    }
    // Search results join the current queue only when it is the same platform: the backend refuses
    // a mixed-platform queue, so a clear sentence beats a silent no.
    fun enqueueFromSearch(song: tech.musxi.core.library.SongSummary, playNext: Boolean) {
        // The platform queue is empty while a recording plays; falling through would replace the
        // local queue with this one song when the user only asked to add it.
        if (localPlaying) {
            notice.show("正在播放本地音频，不能混入平台歌曲", Haptic.REJECT)
            return
        }
        val queuePlatform = queue.songs.firstOrNull()?.key?.platform
        if (queue.songs.isEmpty()) { app.queuePlayback.playSong(song); return }
        if (queuePlatform != song.key.platform) {
            notice.show("当前队列来自${queuePlatform?.displayName ?: "其他平台"}，不能混入${song.key.platform.displayName}的歌曲", Haptic.REJECT); return
        }
        app.playback.enqueue(song, queue.revision, playNext) { result ->
            if (result.isSuccess) notice.show(if (playNext) "将在下一首播放" else "已加入播放队列", Haptic.CONFIRM)
            else notice.show("没能加入队列，请稍后重试")
        }
    }
    // What the song menu can offer for one song. "Liked" is the confirmed state; the backend never
    // updates it optimistically, and a platform whose collection is unsynced or read-only cannot take
    // a write, so the row is shown but disabled.
    fun songActionsFor(song: tech.musxi.core.library.SongSummary): tech.musxi.player.ui.components.SongActionsUi {
        val state = favorites[song.key.platform]
        return tech.musxi.player.ui.components.SongActionsUi(
            liked = state?.keys?.contains(song.key) == true,
            canFavorite = state != null && state.synced && state.canWrite,
            favoriteNote = when {
                state == null || !state.synced -> "收藏同步中"
                !state.canWrite -> "当前账号不能修改收藏"
                else -> null
            },
        )
    }
    // The favourites browser and the playlist repository deliver their results on the main thread
    // (AccountRepository posts every session reply to the main looper), so a notice can be shown here.
    fun toggleFavoriteFromMenu(song: tech.musxi.core.library.SongSummary) {
        val liked = favorites[song.key.platform]?.keys?.contains(song.key) == true
        app.favorites.enqueueLiked(song, !liked) { result ->
            // From a menu, not the heart button: nothing buzzed on the tap, so the result does.
            if (result.isSuccess) notice.show(if (liked) "已取消收藏" else "已收藏", if (liked) Haptic.UNLIKE else Haptic.LIKE)
            else notice.show("收藏未生效，请稍后重试")
        }
    }
    // The playlist sheet is the shell's, not a menu's: a save still running when the sheet closes, or
    // when the user leaves the page, reports here. Rows of the lists on screen follow only what the
    // server confirmed; the queue is left alone.
    val membership = remember {
        MembershipEditor(
            source = object : MembershipSource {
                override fun read(
                    song: tech.musxi.core.library.MediaKey,
                    result: (Result<tech.musxi.core.library.SongPlaylistMembership>) -> Unit,
                ) = app.playlists.membershipFor(song, result)
                override fun update(
                    song: tech.musxi.core.library.SongSummary,
                    snapshot: tech.musxi.core.library.SongPlaylistMembership,
                    desired: Map<tech.musxi.core.library.MediaKey, Boolean>,
                    result: (Result<tech.musxi.core.library.PlaylistMembershipResult>) -> Unit,
                ) = app.playlists.updateMembership(song, snapshot, desired, result)
                override fun canCreate(platform: Platform): Boolean = app.playlists.capabilities(platform).create
                override fun create(
                    song: tech.musxi.core.library.SongSummary,
                    name: String,
                    result: (Result<tech.musxi.core.library.CreatedPlaylistSongResult>) -> Unit,
                ) = app.playlists.createPlaylistAndAdd(song, name, result)
            },
            onSettled = { settled ->
                // A save made for an account that has since changed says what happened, but must not
                // edit the new account's lists.
                val platform = settled.song.key.platform
                val sameAccount = settled.accountGeneration == null ||
                    app.accounts.states.value[platform]?.generation == settled.accountGeneration
                if (sameAccount) {
                    libraryViewModel.applyMembership(settled.song.key, settled.results, settled.startedAtMs)
                }
                // A save where every list took the change confirms; a partial one only explains.
                val allSaved = settled.results.isNotEmpty() && settled.results.all {
                    it.status == tech.musxi.core.library.PlaylistChangeStatus.APPLIED ||
                        it.status == tech.musxi.core.library.PlaylistChangeStatus.UNCHANGED
                }
                settled.notice?.let { notice.show(it, if (allSaved) Haptic.CONFIRM else null) }
            },
        )
    }
    // An account change on the open song's platform makes its snapshot the old account's: reread.
    // Losing the connection counts too, and so does coming back: a read that failed while the account
    // was away is tried again once it returns.
    val membershipPlatform = membership.state?.song?.key?.platform
    val membershipGeneration = membershipPlatform?.let { connectedGenerations[it] }
    val seenGeneration = remember { arrayOfNulls<Long>(1) }
    val seenPlatform = remember { arrayOfNulls<Platform>(1) }
    LaunchedEffect(membershipPlatform, membershipGeneration) {
        if (membershipPlatform == null) {
            seenGeneration[0] = null
            seenPlatform[0] = null
        } else {
            val samePlatform = seenPlatform[0] == membershipPlatform
            val before = seenGeneration[0]
            seenGeneration[0] = membershipGeneration
            seenPlatform[0] = membershipPlatform
            // The generation counter is per platform; a sheet opened for another platform's song is a
            // new sheet, not an account change.
            if (samePlatform && before != membershipGeneration) membership.accountChanged()
        }
    }
    // Always the original song (its own platform), never the source a swap is playing from.
    fun managePlaylists(song: tech.musxi.core.library.SongSummary, from: tech.musxi.core.library.MediaKey? = null) {
        membership.open(song, pinned = from)
    }

    // The shell's page colour is behind the bars unless the player covers it; the player then
    // reports its own backdrop, composed later, and wins.
    if (!playerCovering) SystemBarIcons(MaterialTheme.colorScheme.background)
    // Every layer below (pages, dock, player, panels over the player) learns here whether sound is
    // playing, so the "now playing" bars move wherever they are drawn.
    CompositionLocalProvider(
        tech.musxi.player.ui.components.LocalPlaybackActive provides playback.playing,
        tech.musxi.player.ui.localaudio.LocalCoverCropHost provides coverCropHost,
        tech.musxi.player.ui.wallpaper.LocalWallpaperHosts provides wallpaperHosts,
        tech.musxi.player.ui.components.LocalPageBackdrop provides
            tech.musxi.player.ui.wallpaper.rememberInterfaceBackdrop(interfaceWallpaperHost, interfaceWallpaperState),
    ) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // The dock floats over the content: the page is full height and scrolls underneath it,
        // so it no longer sits on a strip of bare background. The inset is measured, not
        // guessed, because the dock changes height when the mini player appears.
        var dockHeight by remember { mutableIntStateOf(0) }
        // Scroll linkage (spec section 10): the pages' scrolling folds the dock and names the top bar.
        val scrollChrome = remember { tech.musxi.player.ui.shell.ScrollChrome() }
        scrollChrome.density = LocalDensity.current.density
        // A new tab or page starts with the whole dock.
        LaunchedEffect(tab, libraryNav.depth, meNav.depth, librarySegmentLocal) { scrollChrome.expand() }
        val dockFolded = scrollChrome.collapsed && miniPlayer.visible && !showPlayer
        // The pages keep the room of the whole dock: a list whose bottom padding shrank as the dock
        // folded would jump under the finger.
        var dockFullHeight by remember { mutableIntStateOf(0) }
        val contentInset = with(LocalDensity.current) { dockFullHeight.toDp() } + 16.dp
        // A local folder page that is selecting puts its action bar where the dock was. The mini row
        // is drawn by the player surface, not the dock, so it has to step aside as well: without the
        // dock's glass it would float over the list and still catch taps. The 界面壁纸 preview bar
        // takes the same place while it is up.
        val dockHidden = (localSelecting && tab == ShellTab.LIBRARY) || interfaceWallpaperHost.bar
        // The pages are what the glass records and blurs; the dock, player, sheets and notice are
        // the host's overlay (spec section 3). The overlay is composed after the pages, so its back
        // handlers keep winning over the pages' own, as they did in the one Box before.
        // Menus opened from the pages are drawn by the shell, over the dock, as glass (spec section 3).
        val menuOverlay = remember { tech.musxi.player.ui.components.MenuOverlayState() }
        // Task 1: a sideways swipe walks the library's segments (网易云 / QQ / 酷狗 / 本地音频) and,
        // past the last one, the tabs. Only on a tab's root page, with no keyboard, selection bar
        // or player in the way.
        val swipe = tech.musxi.player.ui.shell.rememberSwipeSwitchState()
        val swipeHaptics = tech.musxi.player.ui.theme.rememberHaptics()
        val platformFilters = remember { LibraryFilter.entries.filter { it.platform != null } }
        val librarySegment = if (librarySegmentLocal) platformFilters.size else
            platformFilters.indexOfFirst { it.platform == (playlists.filter.platform ?: Platform.NETEASE) }.coerceAtLeast(0)
        val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        swipe.enabled = !playerActive && !imeUp && !dockHidden && when (tab) {
            ShellTab.LIBRARY -> libraryNav.depth == 1
            ShellTab.SEARCH -> true
            ShellTab.ME -> meNav.depth == 1
        }
        swipe.reduced = tech.musxi.player.ui.theme.rememberReducedMotion()
        // The dock only walks the tabs, and so does the page with a platform locked; the dock is
        // off while it is hidden or the player is open.
        swipe.dockEnabled = !playerActive && !dockHidden
        val locked = libraryLock != tech.musxi.player.ui.library.LibraryLock.NONE
        fun swipeTo(dir: tech.musxi.player.ui.shell.SwipeDir) = tech.musxi.player.ui.shell.swipeTarget(
            tab, librarySegment, platformFilters.size + 1, dir, tabsOnly = swipe.tabsOnly || locked,
        )
        swipe.canGo = { dir -> swipeTo(dir) != null }
        swipe.leavesTab = { dir -> swipeTo(dir) is tech.musxi.player.ui.shell.SwipeTarget.Tab }
        swipe.onCommit = { swipeHaptics(tech.musxi.player.ui.theme.Haptic.TICK) }
        swipe.go = { dir ->
            when (val to = swipeTo(dir)) {
                is tech.musxi.player.ui.shell.SwipeTarget.Tab -> tab = to.tab
                is tech.musxi.player.ui.shell.SwipeTarget.Segment ->
                    if (to.index >= platformFilters.size) {
                        librarySegmentLocal = true
                    } else {
                        // As a tap on the segment: back from 本地音频 to the platform loaded keeps it as it was.
                        val picked = platformFilters[to.index]
                        val fromLocal = librarySegmentLocal
                        librarySegmentLocal = false
                        if (!fromLocal || picked.platform != playlists.filter.platform) libraryViewModel.selectFilter(picked)
                    }
                null -> Unit
            }
        }
        val pages: @Composable BoxScope.() -> Unit = {
            // Everything below the shell inherits the accent of the track that is playing.
            CompositionLocalProvider(
                LocalNowPlayingAccent provides nowPlaying,
                tech.musxi.player.ui.components.LocalMenuOverlay provides menuOverlay,
                tech.musxi.player.ui.shell.LocalScrollChrome provides scrollChrome,
            ) {
                Box(
                    Modifier.fillMaxWidth().fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .nestedScroll(scrollChrome.connection)
                        .swipeSwitch(swipe),
                ) {
                  Box(Modifier.fillMaxSize().swipeSwitchPage(swipe)) {
                    when (tab) {
                        // A playlist opens inside the library tab rather than over the shell: the
                        // dock has to stay visible, because tapping a track is the moment the user
                        // needs to see that something started playing.
                        // Pages are pushed with the shared transition and leave with the system back
                        // gesture; the player's own back handling wins while it is open.
                        ShellTab.LIBRARY -> NavStackHost(libraryNav, backEnabled = !playerActive) { key -> if (key == "local-folder") {
                            tech.musxi.player.ui.localaudio.LocalAudioFolderRoute(
                                controller = localController,
                                folderId = localFolderId,
                                bottomInset = contentInset,
                                nowPlayingAudioId = if (localPlaying) playback.localAudio?.id else null,
                                onBack = { libraryNav.pop() },
                                onNotice = notice::show,
                                onSelectionChange = { localSelecting = it },
                            )
                        } else if (key == "recommendations") {
                            // The recommendation list on the playlist page. No search, no load-more: the
                            // snapshot is the whole list. A tap plays the list from that song.
                            val platform = recommendationListPlatform ?: playlists.filter.platform ?: Platform.NETEASE
                            val recTracks = remember(platform, recommendationStates) {
                                tech.musxi.player.ui.library.recommendationTracksOf(platform, recommendationStates[platform])
                            }
                            TrackListContent(
                                state = recTracks,
                                dark = dark,
                                onBack = { libraryNav.pop() },
                                onPlayAll = { playRecommendations(platform, 0) },
                                onSongClick = { song ->
                                    val index = recTracks.items.indexOfFirst { it.key == song.key }
                                    if (index >= 0) playRecommendations(platform, index, song.key.stableId)
                                },
                                onLoadMore = {},
                                onRetry = { app.recommendations.open(platform) },
                                onPullRefresh = { refreshRecommendations(platform) },
                                nowPlayingId = playback.mediaId,
                                pendingSongId = pendingSongId,
                                bottomInset = contentInset,
                                songActions = ::songActionsFor,
                                onPlayNext = { song -> enqueueFromSearch(song, playNext = true) },
                                onAddToQueue = { song -> enqueueFromSearch(song, playNext = false) },
                                onToggleFavorite = ::toggleFavoriteFromMenu,
                                onManagePlaylists = { song -> managePlaylists(song) },
                            )
                        } else if (key == "playlist") {
                            TrackListContent(
                                state = tracks,
                                dark = dark,
                                search = playlistSearch,
                                onOpenSearch = libraryViewModel::openPlaylistSearch,
                                onSearch = libraryViewModel::searchPlaylist,
                                onCloseSearch = libraryViewModel::closePlaylistSearch,
                                onFillForSearch = libraryViewModel::fillPlaylistForSearch,
                                onCancelFill = libraryViewModel::cancelPlaylistFill,
                                onBack = { libraryNav.pop() },
                                onPlayAll = {
                                    val playlist = tracks.playlist
                                    val first = tracks.items.firstOrNull()
                                    if (playlist != null && first != null) {
                                        app.queuePlayback.playPlaylist(
                                            playlist.key,
                                            libraryViewModel.songKeyOf(first),
                                            tracks.items,
                                        )
                                    }
                                },
                                onSongClick = { song ->
                                    val playlist = tracks.playlist
                                    if (playlist != null) {
                                        pendingSongId = song.key.stableId
                                        app.queuePlayback.playPlaylist(
                                            playlist.key,
                                            libraryViewModel.songKeyOf(song),
                                            tracks.items,
                                        )
                                    }
                                },
                                onLoadMore = libraryViewModel::loadMoreTracks,
                                onRetry = libraryViewModel::retryTracks,
                                onPullRefresh = libraryViewModel::refreshTracks,
                                nowPlayingId = playback.mediaId,
                                pendingSongId = pendingSongId,
                                bottomInset = contentInset,
                                songActions = ::songActionsFor,
                                onPlayNext = { song -> enqueueFromSearch(song, playNext = true) },
                                onAddToQueue = { song -> enqueueFromSearch(song, playNext = false) },
                                onToggleFavorite = ::toggleFavoriteFromMenu,
                                onManagePlaylists = { song -> managePlaylists(song, from = tracks.playlist?.key) },
                            )
                        } else {
                            val libraryJournals by app.playbackJournal.state.collectAsStateWithLifecycle()
                            LibraryContent(
                                state = playlists,
                                onSelectFilter = { picked ->
                                    // Back from 本地音频 to the platform already loaded: show it as it was.
                                    // Selecting it again would reload page one and lose the scroll.
                                    val fromLocal = librarySegmentLocal
                                    librarySegmentLocal = false
                                    if (!fromLocal || picked.platform != playlists.filter.platform) libraryViewModel.selectFilter(picked)
                                },
                                localSelected = librarySegmentLocal,
                                onSelectLocal = { librarySegmentLocal = true },
                                localStatus = tech.musxi.player.ui.localaudio.rememberLocalLibraryStatus(localController),
                                localContent = { header ->
                                    tech.musxi.player.ui.localaudio.LocalAudioHomeRoute(
                                        header = header,
                                        controller = localController,
                                        bottomInset = contentInset,
                                        nowPlayingFolderId = if (localPlaying) localQueue.folderId else null,
                                        onOpenFolder = { id -> if (libraryNav.push("local-folder")) localFolderId = id },
                                        onOpenUnfiled = { if (libraryNav.push("local-folder")) localFolderId = null },
                                        onNotice = notice::show,
                                        localPlaying = localPlaying,
                                        listState = localHomeList,
                                    )
                                },
                                onLoadMore = { libraryViewModel.loadPlaylists() },
                                // The detail page is pushed onto the library tab's stack.
                                onOpenPlaylist = { p -> if (libraryNav.push("playlist")) libraryViewModel.openPlaylist(p) },
                                // "No account yet" is a dead end unless the button actually takes
                                // the user to the tab where connecting happens.
                                onOpenAccount = { tab = ShellTab.ME },
                                onRefresh = libraryViewModel::retryPlaylists,
                                onPullRefresh = libraryViewModel::refreshPlaylists,
                                bottomInset = contentInset,
                                // Remembered so playback ticks do not hand the tab a fresh object
                                // every recomposition.
                                home = remember(libraryJournals, accounts, queue.songs.isNotEmpty(), playlists.filter, recommendationPlatform, recommendationStates) {
                                    val platform = playlists.filter.platform
                                    libraryHomeUiOf(
                                        journal = platform?.let { libraryJournals[it] },
                                        accountName = platform?.let { accounts[it]?.profile?.displayName },
                                        playingQueue = queue.songs.isNotEmpty(),
                                    ).copy(
                                        recommendation = recommendationPlatform?.let {
                                            tech.musxi.player.ui.library.recommendationCardOf(recommendationStates[it])
                                        },
                                    )
                                },
                                onOpenRecommendation = {
                                    val platform = recommendationPlatform
                                    if (platform != null && libraryNav.push("recommendations")) recommendationListPlatform = platform
                                },
                                onPlayRecommendation = { recommendationPlatform?.let { playRecommendations(it, 0) } },
                                // A failed first read: open again, which the backend turns into a request
                                // (or "rate" while the last one is under 30 seconds old). The card itself
                                // shows the outcome, so no notice on top.
                                onRetryRecommendation = { recommendationPlatform?.let(app.recommendations::open) },
                                nowPlayingId = playback.mediaId,
                                onResume = {
                                    playlists.filter.platform?.let { app.playbackJournal.restore(it) }
                                    showPlayer = true
                                },
                                onPlayRecent = { song -> app.queuePlayback.playSong(song) },
                                // Recents belong to the journal of the platform being shown.
                                onRemoveRecent = { song ->
                                    playlists.filter.platform?.let { app.playbackJournal.removeRecent(it, song.key) }
                                },
                                listState = libraryList,
                            )
                        }
                        } // NavStackHost
                        ShellTab.SEARCH -> SearchScreen(
                            state = searchState,
                            onKeywordsChange = searchViewModel::onKeywordsChange,
                            onPlatformSelect = searchViewModel::selectPlatform,
                            onSearch = searchViewModel::search,
                            onLoadMore = searchViewModel::loadMore,
                            onRetry = searchViewModel::retry,
                            onSongClick = { song -> app.queuePlayback.playSong(song) },
                            bottomInset = contentInset,
                            onRecentClick = searchViewModel::searchRecent,
                            onRecentRemove = searchViewModel::removeRecent,
                            onRecentClear = searchViewModel::clearRecent,
                            nowPlayingId = playback.mediaId,
                            onPlayNext = { song -> enqueueFromSearch(song, playNext = true) },
                            onAddToQueue = { song -> enqueueFromSearch(song, playNext = false) },
                            songActions = ::songActionsFor,
                            onToggleFavorite = ::toggleFavoriteFromMenu,
                            onManagePlaylists = { song -> managePlaylists(song) },
                        )
                        // The "我的" page and what it opens (深色模式, a platform's account) live on this
                        // tab's own stack, like the library's playlist.
                        ShellTab.ME -> MeTab(
                            nav = meNav,
                            scrollState = meScroll,
                            ui = meUi,
                            accountRows = accountState.rows,
                            dark = dark,
                            actions = meTabActions,
                            accountActions = accountPageActions,
                            appVersion = appVersion,
                            update = aboutUpdate,
                            updateActions = updateActions,
                            bottomInset = contentInset,
                            backEnabled = !playerActive,
                        )
                    }
                  } // swipe page
                }
            }
        }
        val chrome: @Composable BoxScope.() -> Unit = {
            // The top bar is glass over the page, so it lives here and not in the page.
            tech.musxi.player.ui.shell.ShellTopBar(
                scrollChrome,
                Modifier.align(Alignment.TopCenter).fadesWithPlayer(playerProgress),
            )
            CompositionLocalProvider(LocalNowPlayingAccent provides nowPlaying) {
                // One dock instead of two stacked capsules: the chrome shares the page's margins,
                // and its hairline is the same separator the lists use.
                if (!dockHidden) Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(horizontal = Space.page)
                        // The dock must clear the gesture bar; without this it sits under it on
                        // every phone with gesture navigation.
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        // A sideways swipe on the tab bar or the folded tab walks the tabs only.
                        .swipeSwitch(swipe, tabsOnly = true)
                        .onSizeChanged {
                            dockHeight = it.height
                            if (!dockFolded) dockFullHeight = it.height
                        }
                        .fadesWithPlayer(playerProgress),
                ) {
                    // Built once: the notice's action depends on which notice is showing.
                    // Why the original did not play: the original platform's own answer first.
                    val dockReason = selection.original?.key?.platform?.let { selection.failureReasons[it] }
                        ?: playback.failureReason?.reason
                    val dockFailed = playerUi.status == tech.musxi.player.ui.player.PlayerStatus.FAILED && !swapSearching
                    val dockNotice = dockNoticeOf(
                        phase = selection.phase,
                        mode = selection.mode,
                        playerOpen = showPlayer,
                        reason = dockReason,
                        playbackFailed = dockFailed,
                        local = localPlaying,
                    )
                    ShellDock(
                        mini = miniPlayer,
                        selected = tab,
                        // Tapping the current tab again: back to its root page, or to the top of it.
                        onSelect = { picked ->
                            if (picked != tab) {
                                tab = picked
                            } else if (picked == ShellTab.LIBRARY) {
                                libraryNav.reselect {
                                    shellScope.launch { (if (librarySegmentLocal) localHomeList else libraryList).animateScrollToItem(0) }
                                }
                            } else if (picked == ShellTab.ME) {
                                // On a sub-page: back to the root page. On the root page: back to
                                // its top. The scroll position is kept in between.
                                meNav.reselect { shellScope.launch { meScroll.animateScrollTo(0) } }
                            }
                        },
                        badges =if (aboutUpdate.badge) setOf(ShellTab.ME) else emptySet(),
                        // The mini row itself is drawn by the player surface, one layer up.
                        onMiniBounds = { bounds -> if (miniRect != bounds) miniRect = bounds },
                        onExpand = { showPlayer = true },
                        onPlayPause = { app.playback.toggle() },
                        onPrevious = { app.playback.previous() },
                        onNext = { app.playback.next() },
                        notice = dockNotice,
                        onNoticeAction = {
                            if (dockNotice?.findsSources == true) {
                                findSources()
                            } else {
                                showPlayer = true
                            }
                        },
                        collapsed = dockFolded,
                        onExpandTabs = scrollChrome::expand,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            // The queue sheet takes the back key itself while it is on screen (MusxiSheet).
            BackHandler(enabled = showLyricsMatch) { showLyricsMatch = false }
            // Back must peel one layer at a time, outermost first. Before this existed the back
            // gesture left the app from the player, which is the one screen a listener is most
            // likely to be holding.
            BackHandler(enabled = playerActive) {
                when {
                    // The anchored menus are focusable popups and take the back key themselves, so
                    // they have no branch here.
                    showQueue -> showQueue = false
                    // This handler is registered after the sheet's own, so it runs first: the
                    // lyrics match sheet has to be closed here or back would collapse the player.
                    showLyricsMatch -> showLyricsMatch = false
                    // The source sheet takes the back key itself while it is on screen.
                    // Leaving lyrics returns to the cover first: it is a mode of this page, and a
                    // single back press should not skip the layer the user just entered.
                    lyricsMode && showPlayer -> lyricsMode = false
                    else -> playerProgress.collapse()
                }
            }
            // Registered after the player's handler, so it runs first: back on a preview cancels it.
            BackHandler(enabled = playerActive && wallpaperHost.bar && wallpaperHost.framing == null) {
                wallpaperHost.cancelBar()
            }
            // The interface preview sits on the library home; back there cancels it too.
            BackHandler(enabled = !playerActive && interfaceWallpaperHost.bar && interfaceWallpaperHost.framing == null) {
                interfaceWallpaperHost.cancelBar()
            }
            val wallpaperLook = tech.musxi.player.ui.wallpaper.rememberWallpaperLook(wallpaperHost, wallpaperState)
            val playerLayer: @Composable BoxScope.() -> Unit = {
                // The full player covers the shell rather than navigating to a new destination: it is a
                // layer over the same state, and pressing back must return to the tab underneath.
                // An open or moving player is never torn down; only the resting mini row steps aside.
                if (!dockHidden || playerActive) PlayerSurface(
                    progress = playerProgress,
                    mini = miniPlayer,
                    ui = playerUi,
                    dark = dark,
                    miniRect = miniRect,
                    miniCompact = dockFolded,
                    sideSwipe = Modifier.swipeSwitch(swipe, tabsOnly = true),
                    nowPlaying = nowPlaying,
                    previous = previousCover,
                    next = nextCover,
                    coverShown = !lyricsShown,
                    onPlayPause = { app.playback.toggle() },
                    onPrevious = { app.playback.previous() },
                    onNext = { app.playback.next() },
                    miniProgress = miniProgress,
                    // Long press on a local recording's cover: change it, or go back to the file's own.
                    coverActions = playback.localAudio?.id?.takeIf { localPlaying }?.let { id ->
                        tech.musxi.player.ui.player.CoverActions(
                            custom = localLibrary?.items?.firstOrNull { it.id == id }?.artworkCustom == true,
                            onChange = { coverCropHost.pick(id) },
                            onRestore = { coverCropHost.restore(id) },
                        )
                    },
                    wallpaper = wallpaperLook,
                ) { surface ->
                    // The live position now, carried forward from the last sample while it plays: a
                    // skip or a bookmark must land where the listener hears, not up to 0.5 s behind.
                    fun positionNow(): Long {
                        val p = playbackProgress.value
                        if (!p.canExtrapolate || p.sampledAtElapsedMs <= 0L) return p.positionMs
                        val elapsed = android.os.SystemClock.elapsedRealtime() - p.sampledAtElapsedMs
                        return p.positionMs + (elapsed * p.speed).toLong().coerceAtLeast(0L)
                    }
                    val localItemId = playback.localAudio?.id
                    val localBookmarks = localLibrary?.bookmarks?.filter { it.audioId == localItemId }.orEmpty()
                    FullPlayer(
                        ui = playerUi,
                        dark = dark,
                        liveProgress = livePlayerProgress,
                        localActions = if (!localPlaying || localItemId == null) null else LocalPlayerActions(
                            onSkip = { delta ->
                                app.playback.seekTo(skipTarget(positionNow(), delta, playback.durationMs))
                            },
                            // Saved per recording, then sent to the player; the button shows the
                            // speed the player reports, so a failed save never shows a false value.
                            onSelectSpeed = { value ->
                                app.playback.setPlaybackSpeed(value) { r ->
                                    if (r.isSuccess) notice.show("这一条以后都用 ${speedLabel(value)} 播放", Haptic.CONFIRM)
                                    else notice.show("播放速度没能设置，请重试")
                                }
                            },
                            speed = playback.playbackSpeed,
                            onAddBookmark = {
                                val at = positionNow()
                                app.localAudio.addBookmark(localItemId, at, nextBookmarkName(localBookmarks.size)) { r ->
                                    notice.show(
                                        if (r.isSuccess) "已在 ${tech.musxi.player.ui.model.formatDuration(at)} 加书签" else "书签没能保存，请重试",
                                        if (r.isSuccess) Haptic.CONFIRM else null,
                                    )
                                }
                            },
                            onShowBookmarks = { showBookmarks = true },
                            bookmarkCount = localBookmarks.size,
                        ),
                        // Going down also closes the sheets; the lyrics mode is reset once docked.
                        onCollapse = { playerProgress.collapse() },
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
                                if (!ui.enabled) {
                                    notice.show("当前账号不能修改收藏")
                                } else {
                                    val now = SystemClock.elapsedRealtime()
                                    val existing = favoriteIntent
                                    // A second tap on the same song replaces the first: the user's last
                                    // word wins, with a fresh attempt budget and a fresh time limit.
                                    favoriteIntent = if (existing != null && existing.song.key == song.key) {
                                        existing.copy(liked = !ui.liked, attempts = 0, startedAtMs = now)
                                    } else {
                                        FavoriteIntent(song, !ui.liked, startedAtMs = now)
                                    }
                                }
                            }
                        },
                        onShowQueue = { showQueue = true },
                        // Long press on the heart: "收藏到" with 我喜欢 first, the playlists, and 新建歌单.
                        onCollectTo = currentSong?.let { song ->
                            {
                                if (favorite?.enabled == false) notice.show("当前账号不能修改收藏")
                                else membership.open(song, mode = tech.musxi.player.ui.playlist.MembershipMode.COLLECT)
                            }
                        },
                        lyrics = if (lyricsShown) lyricsUi else null,
                        lyricsAccentCurrent = settings.lyricsColorMode == tech.musxi.core.settings.LyricsColorMode.COVER,
                        lyricsFontSize = settings.lyricsFontSize,
                        lyricsPlayhead = lyricsPlayhead,
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
                        onShowPlayMode = { showPlayMode = true },
                        onShowQuality = {
                            showQuality = true
                        },
                        onShowOutput = { showOutput = true },
                        playModeMenuOpen = showPlayMode,
                        onDismissPlayMode = { showPlayMode = false },
                        onSelectPlayMode = { mode ->
                            app.playback.setShuffleEnabled(mode.shuffle)
                            app.playback.setRepeatMode(mode.repeat)
                            showPlayMode = false
                        },
                        qualityMenu = if (showQuality && !localPlaying) {
                            QualityMenuUi(
                                choices = qualityOptions?.options.orEmpty().map {
                                    MenuChoice(it.id, it.name, it.id == qualityOptions?.current)
                                },
                                message = when {
                                    qualityLoading -> "正在获取可用音质…"
                                    qualityError -> "获取音质失败，请稍后重试"
                                    qualityOptions?.options.isNullOrEmpty() -> "暂无可确认的可用音质"
                                    else -> null
                                },
                                loading = qualityLoading,
                            )
                        } else null,
                        onDismissQuality = { showQuality = false },
                        onSelectQuality = { id ->
                            showQuality = false
                            app.playback.setQuality(id) { result ->
                                if (result.isFailure) notice.show("音质切换失败，已保留原音质")
                            }
                        },
                        outputMenu = if (showOutput) {
                            OutputMenuUi(
                                choices = outputs.devices.map {
                                    MenuChoice(it.id, it.displayName(), it.selected, it.type.menuIcon())
                                },
                                message = if (outputs.devices.isEmpty()) "暂无可确认的输出设备" else null,
                            )
                        } else null,
                        onDismissOutput = { showOutput = false },
                        onSelectOutput = { id ->
                            app.playback.selectOutput(id) { result ->
                                val error = result.exceptionOrNull() as? tech.musxi.playback.OutputSelectionException
                                if (error?.category == tech.musxi.playback.OutputFailure.SYSTEM_SWITCHER_REQUIRED) {
                                    showOutput = false
                                    openOutputSwitcher()
                                } else if (result.isFailure) {
                                    notice.show("输出切换未完成，请使用系统媒体输出")
                                }
                            }
                        },
                        onOpenSystemOutput = {
                            showOutput = false
                            openOutputSwitcher()
                        },
                        moreMenu = if (showMore) {
                            MoreMenuUi(
                                artist = playback.artist,
                                album = playback.album,
                                sleepStatus = sleepStatus,
                                sleepEndOfTrack = sleepTimer.mode == tech.musxi.core.playback.SleepTimerMode.END_OF_TRACK &&
                                    sleepTimer.active,
                                sleepFinishTrack = sleepFinishTrack,
                                trackNoun = trackNoun,
                                local = localPlaying,
                            )
                        } else null,
                        onSleepFinishTrackChange = { value ->
                            sleepFinishTrack = value
                            sleepPrefs.edit().putBoolean("finish-track", value).apply()
                        },
                        sleepTimerActive = sleepTimer.active,
                        onShowMore = {
                            // Read the clock now so the first frame of the menu is not a stale countdown.
                            sleepNow = SystemClock.elapsedRealtime()
                            showMore = true
                        },
                        onDismissMore = { showMore = false },
                        onMoreAction = ::handleMoreAction,
                        // The song is the queue original, the same one the heart acts on. Without a
                        // current song the menu has no "加入歌单" row.
                        onManagePlaylists = if (currentSong != null) {
                            { managePlaylists(currentSong) }
                        } else null,
                        onMatchLyrics = if (currentSong != null) {
                            { showLyricsMatch = true }
                        } else null,
                        onFindSources = if (currentSong != null) {
                            { findSources() }
                        } else null,
                        surface = surface,
                    )
                }
            }
            val overPlayer: @Composable BoxScope.() -> Unit = {
                if (playerActive) {
                    // Same layering as the queue: a sheet over the player, closed by the scrim or back.
                    if (showLyricsMatch) {
                        val match by app.lyricsMatch.state.collectAsStateWithLifecycle()
                        val matchUi = lyricsMatchUiOf(
                            match,
                            title = currentSong?.title ?: playback.title,
                            artist = currentSong?.artists?.joinToString(" / ") ?: playback.artist,
                        )
                        Box(
                            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                                .clickable(interactionSource = null, indication = null) { showLyricsMatch = false },
                        ) {
                            Column(Modifier.fillMaxSize().imePadding(), verticalArrangement = Arrangement.Bottom) {
                                LyricsMatchSheet(
                                    ui = matchUi,
                                    modifier = Modifier.clickable(interactionSource = null, indication = null) {},
                                    onSearch = { _, platform, keywords -> app.lyricsMatch.search(platform, keywords, 1) },
                                    onPreview = app.lyricsMatch::preview,
                                    onBind = { original, candidate -> app.lyricsMatch.bind(original, candidate, 0L) },
                                    onUnbind = app.lyricsMatch::reset,
                                    onDismiss = { showLyricsMatch = false },
                                )
                            }
                        }
                    }
                    val localItem = playback.localAudio?.let { item -> localQueue.items.firstOrNull { it.id == item.id } ?: item }
                    if (showSongInfo && localPlaying) {
                        // Public metadata of the file only; no path or document URI exists in this state.
                        SongInfoDialog(
                            title = playback.title,
                            rows = if (localItem != null) {
                                localAudioInfoRows(localItem, playback.durationMs, localFolderName)
                            } else {
                                listOf(
                                    "作者" to playback.artist,
                                    "时长" to if (playback.durationMs > 0) formatDuration(playback.durationMs) else "",
                                ).filter { it.second.isNotBlank() }
                            },
                            onDismiss = { showSongInfo = false },
                            heading = "音频信息",
                        )
                    } else if (showSongInfo) {
                        SongInfoDialog(
                            title = playback.title,
                            rows = listOf(
                                "歌手" to playback.artist,
                                "专辑" to playback.album,
                                "时长" to if (playback.durationMs > 0) formatDuration(playback.durationMs) else "",
                                "歌单平台" to playback.queuePlatform?.displayName.orEmpty(),
                                "实际音源" to playback.sourcePlatform?.displayName.orEmpty(),
                                "音质" to playback.qualityName.orEmpty(),
                            ).filter { it.second.isNotBlank() },
                            onDismiss = { showSongInfo = false },
                        )
                    }
                    // The source panel sits above the player: an interruption, not a destination. It stays in
                    // the tree so the sheet can slide out once the search is over, draws its own scrim and
                    // takes the back key while it is on screen.
                    // A local file is never swapped for a platform song, so a selection left over from a
                    // platform track must not surface over it.
                    if (!localPlaying) {
                        SourcePanel(
                            ui = sourcePanel,
                            dark = dark,
                            onPick = { index -> app.sourceSelection.select(selection.revision, index) },
                            onCancel = { app.sourceSelection.cancel(selection.revision) },
                            onSkip = { app.playback.next() },
                            // A search the user started has nothing to resume: retrying it is searching again.
                            onRetry = { if (sourcePanel.manual) findSources() else app.playback.resume() },
                        )
                    }
                }
                // The current recording's bookmarks: tap one to jump there, swipe it away to delete.
                val bookmarkItemId = playback.localAudio?.id
                LocalBookmarkSheet(
                    visible = showBookmarks && playerActive && localPlaying,
                    bookmarks = localLibrary?.bookmarks?.filter { it.audioId == bookmarkItemId }
                        ?.sortedBy { it.positionMs }.orEmpty(),
                    onDismiss = { showBookmarks = false },
                    onJump = { bookmark ->
                        app.playback.seekTo(bookmark.positionMs)
                        showBookmarks = false
                    },
                    onDelete = { bookmark ->
                        app.localAudio.removeBookmark(bookmark.id) { r ->
                            if (r.isFailure) notice.show("书签没能删除，请重试")
                        }
                    },
                    onRename = { bookmark, name, done ->
                        app.localAudio.renameBookmark(bookmark.id, name) { r ->
                            done(r.isSuccess)
                            if (r.isFailure) notice.show("书签名称没能保存，请重试")
                        }
                    },
                )
                // The queue: the app's sheet over the player (half height, flicks to full, pulls down
                // to close, takes back while shown). Always in the tree, so it slides out with what it
                // showed. Removals wait out their undo time inside the sheet before reaching here.
                QueueSheet(
                    visible = showQueue && playerActive,
                    ui = queueUi,
                    onDismiss = { showQueue = false },
                    onSelect = { index -> submitQueueCommand { done ->
                        // A local row resumes as the folder's continue preference says.
                        if (localPlaying) {
                            app.playback.jumpToLocalQueueIndex(index, localQueue.revision, fromStart = false, callback = done)
                        } else {
                            app.playback.jumpToQueueIndex(index, queue.revision, done)
                        }
                    } },
                    onRemove = { index ->
                        // The revision travels with the command: a stale edit is rejected by the
                        // service rather than applied to a queue that has moved on.
                        submitQueueCommand { done ->
                            // Removing a local row leaves the file and its folders alone.
                            if (localPlaying) app.playback.removeLocalQueueItem(index, localQueue.revision, done)
                            else app.playback.removeQueueItem(index, queue.revision, done)
                        }
                    },
                    onMove = { from, to, expectedRevision ->
                        submitQueueCommand { done ->
                            if (localPlaying) app.playback.moveLocalQueueItem(from, to, expectedRevision, done)
                            else app.playback.moveQueueItem(from, to, expectedRevision, done)
                        }
                    },
                    onClear = { submitQueueCommand { done ->
                        if (localPlaying) {
                            // An emptied local queue ends local playback; the sheet would otherwise
                            // fall back to an empty platform queue.
                            app.playback.clearLocalQueue(localQueue.revision) { result ->
                                if (result.isSuccess) showQueue = false
                                done(result)
                            }
                        } else {
                            app.playback.clearQueue(queue.revision, done)
                        }
                    } },
                    actions = QueueRowActions(
                        // "下一首播放" moves the row right after the current one.
                        onPlayNext = { index ->
                            val target = if (index < queueUi.currentIndex) queueUi.currentIndex else queueUi.currentIndex + 1
                            if (target != index && queueUi.currentIndex >= 0) {
                                submitQueueCommand { done ->
                                    if (localPlaying) app.playback.moveLocalQueueItem(index, target, queueUi.revision, done)
                                    else app.playback.moveQueueItem(index, target, queueUi.revision, done)
                                }
                            }
                        },
                        // A local recording is not a platform song: no playlist to add it to.
                        onAddToPlaylist = if (localPlaying) null else ({ index ->
                            queue.songs.getOrNull(index)?.let { song -> managePlaylists(song) }
                        }),
                    ),
                )
                // One playlist sheet for every entry (search, a playlist's tracks, the player), above
                // the player and the tabs alike. It draws its own scrim and takes back while shown.
                PlaylistMembershipSheet(
                    ui = membership.state,
                    dark = dark,
                    onToggle = membership::toggle,
                    onSave = membership::save,
                    onRetry = membership::retry,
                    onDismiss = membership::close,
                    onStartCreate = membership::startCreate,
                    onCancelCreate = membership::cancelCreate,
                    onCreate = membership::create,
                )
                // Always in the tree, like the playlist sheet: it slides out with what it showed.
                UpdateSheet(
                    visible = showUpdateSheet,
                    release = aboutUpdate.release,
                    actions = updateActions,
                    onDismiss = { showUpdateSheet = false },
                )
                // The cover frame a 本地音频 row asks for; a sheet, so it lives here, not in the page.
                tech.musxi.player.ui.localaudio.CoverCropOverlay(coverCropHost)
                // The wallpaper's preview bar on the open player, and its frame sheet over everything.
                if (wallpaperHost.bar && playerCovering) {
                    val ratio = tech.musxi.player.ui.wallpaper.windowRatio()
                    val preferDark = tech.musxi.player.ui.wallpaper.rememberPrefersDark(wallpaperLook, ratio)
                    tech.musxi.player.ui.wallpaper.WallpaperPreviewBar(
                        strength = wallpaperHost.liveStrength ?: wallpaperState.effectivePreferences.strength,
                        preferDark = preferDark && !dark,
                        working = wallpaperHost.working,
                        onStrength = { wallpaperHost.liveStrength = it },
                        onStrengthDone = wallpaperHost::commitStrength,
                        onAdjust = wallpaperHost::adjustFromBar,
                        onCancel = wallpaperHost::cancelBar,
                        onApply = wallpaperHost::apply,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                tech.musxi.player.ui.wallpaper.WallpaperFrameOverlay(wallpaperHost)
                // The 界面壁纸's preview bar sits where the dock was, over the library home it is
                // judged on; the dock steps aside for it (dockHidden).
                if (interfaceWallpaperHost.bar && !playerActive) {
                    val interfaceLook = tech.musxi.player.ui.wallpaper.rememberWallpaperLook(
                        interfaceWallpaperHost, interfaceWallpaperState,
                    )
                    val ratio = tech.musxi.player.ui.wallpaper.windowRatio()
                    val preferDark = tech.musxi.player.ui.wallpaper.rememberPrefersDark(
                        interfaceLook, ratio, tech.musxi.player.ui.wallpaper.WallpaperKind.INTERFACE,
                    )
                    tech.musxi.player.ui.wallpaper.WallpaperPreviewBar(
                        strength = interfaceWallpaperHost.liveStrength ?: interfaceWallpaperState.effectivePreferences.strength,
                        preferDark = preferDark && !dark,
                        working = interfaceWallpaperHost.working,
                        onStrength = { interfaceWallpaperHost.liveStrength = it },
                        onStrengthDone = interfaceWallpaperHost::commitStrength,
                        onAdjust = interfaceWallpaperHost::adjustFromBar,
                        onCancel = interfaceWallpaperHost::cancelBar,
                        onApply = interfaceWallpaperHost::apply,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                tech.musxi.player.ui.wallpaper.WallpaperFrameOverlay(interfaceWallpaperHost)
                // Drawn after the player and its overlays, so queue and source scrims cannot cover it.
                NoticePill(
                    state = notice,
                    // Page margins keep a long two-line notice off the screen edges.
                    modifier = if (playerCovering) {
                        Modifier.align(Alignment.TopCenter)
                            .padding(horizontal = Space.page)
                            .windowInsetsPadding(WindowInsets.statusBars)
                            // Below the player's 56dp top bar, clear of the playback controls.
                            .padding(top = 64.dp)
                    } else {
                        Modifier.align(Alignment.BottomCenter)
                            .padding(horizontal = Space.page)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            // dockHeight includes the dock's 8dp bottom spacer, so the dock's top edge is at dockHeight.
                            .padding(bottom = with(LocalDensity.current) { dockHeight.toDp() } + 8.dp)
                    },
                )
            }
            // The sheets and the notice over the player sample the player, not the tab under it;
            // the dock keeps sampling the tab. Recorded only while the player is open or moving.
            GlassLayer(active = playerActive, overlay = overPlayer, content = playerLayer)
            // Last, so a page's menu sits over the dock and its back handler runs first.
            tech.musxi.player.ui.components.MenuOverlayLayer(menuOverlay)
        }
        // "透明效果" off, battery saver and Android below 12 make every glass surface solid.
        GlassHost(enabled = settings.transparencyEnabled, overlay = chrome, content = pages)
    }
    }
}

/** One sentence per queue-load failure; null when there is nothing to say. */
internal fun queueLoadMessage(error: String?): String? = when (error) {
    null -> null
    "account_unavailable", "account_changed", "expired" -> "账号未连接或已过期，请到「我的」重新连接"
    "network", "http", "timeout" -> "网络异常，没能开始播放"
    "rate", "risk", "limit" -> "请求太频繁，稍后再试"
    "player" -> "播放服务暂不可用，请稍后重试"
    else -> "没能开始播放，请重试"
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The two halves of the playback state the shell collects, built once per app (see MainShell). */
private class PlaybackFlows(
    val slow: kotlinx.coroutines.flow.Flow<tech.musxi.playback.PlaybackState>,
    val progress: kotlinx.coroutines.flow.Flow<PlaybackProgress>,
    val initialSlow: tech.musxi.playback.PlaybackState,
    val initialProgress: PlaybackProgress,
)

/**
 * True from the tap on 为已导入的条目补取封面 until the last batch answers: the backend's own state
 * only covers one batch, and between two batches the row must not offer a second run.
 */
private var localArtworkFillActive by androidx.compose.runtime.mutableStateOf(false)

/** "补取中 12/40" while a batch runs, "补取中…" between batches, null when idle. */
private fun localArtworkFillText(state: tech.musxi.player.localaudio.LocalAudioArtworkState, active: Boolean): String? = when {
    state.running && state.total > 0 -> "补取中 ${state.processed}/${state.total}"
    state.running || active -> "补取中…"
    else -> null
}

/**
 * Fills in covers for recordings that have none, in batches the backend accepts (at most
 * [tech.musxi.core.localaudio.LocalAudioLimits.MAX_BATCH]), each with the newest revision. Progress
 * saves move the revision while something plays, so one STALE_STATE per batch is retried; a second
 * ends the run. Recordings with a picked cover are skipped, as the backend would skip them anyway.
 */
private fun fillLocalArtwork(app: tech.musxi.player.MusxiApplication, notice: tech.musxi.player.ui.shell.NoticeState) {
    if (localArtworkFillActive) return
    val repo = app.localAudio
    val missing = repo.state.value.items
        .filter { !it.artworkCustom && (it.metadata.artworkKey == null || repo.artworkFile(it.metadata.artworkKey!!) == null) }
        .map { it.id }
    if (missing.isEmpty()) { notice.show("已导入的条目都有封面了"); return }
    localArtworkFillActive = true
    val batches = missing.chunked(tech.musxi.core.localaudio.LocalAudioLimits.MAX_BATCH)
    var found = 0
    fun finish(text: String) { localArtworkFillActive = false; notice.show(text) }
    fun run(index: Int, retried: Boolean) {
        if (index >= batches.size) {
            finish(if (found == 0) "没有找到新的封面" else "补到 $found 张封面")
            return
        }
        // Only ids still in the library: a recording deleted meanwhile would fail the whole batch.
        val present = repo.state.value.items.mapTo(HashSet()) { it.id }
        val batch = batches[index].filter { it in present }
        if (batch.isEmpty()) { run(index + 1, false); return }
        repo.fillArtwork(batch, repo.state.value.revision) { result ->
            val error = (result.exceptionOrNull() as? tech.musxi.core.localaudio.LocalAudioFailure)?.error
            when {
                result.isSuccess -> {
                    found += result.getOrThrow().count { it.error == null && it.artworkKey != null }
                    run(index + 1, false)
                }
                error == tech.musxi.core.localaudio.LocalAudioError.STALE_STATE && !retried -> run(index, true)
                error == tech.musxi.core.localaudio.LocalAudioError.INVALID_TARGET -> finish("读取本地封面已关闭，补取已停止")
                else -> finish(if (found == 0) "补取没能完成，请稍后重试" else "补到 $found 张封面，其余没能完成")
            }
        }
    }
    run(0, false)
}
