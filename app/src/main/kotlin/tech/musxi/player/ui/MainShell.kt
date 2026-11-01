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
import tech.musxi.player.ui.player.rememberCoverColor
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import tech.musxi.player.SleepTimer
import tech.musxi.player.formatRemaining
import tech.musxi.playback.displayName
import tech.musxi.player.ui.player.QueuePanel
import tech.musxi.player.ui.player.QueueRowUi
import tech.musxi.player.ui.player.QueueUi
import tech.musxi.player.ui.player.defaultTranslationEnabled
import tech.musxi.player.ui.player.lyricsUiOf
import tech.musxi.player.ui.player.miniPlayerUi
import tech.musxi.player.ui.player.playerUiOf
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
    // The library tab's page stack (root list, then a playlist) and the root's scroll position,
    // hoisted so both survive switching tabs.
    val libraryNav = rememberNavStackState(root = "library", maxDepth = 2)
    val libraryList = rememberLazyListState()
    // The "我的" tab's stack: the page itself, then one sub-page (深色模式 or a platform's account).
    val meNav = rememberNavStackState(root = MeRootKey, maxDepth = 2)
    // The "我的" root page's scroll, hoisted like the library list so reselecting the tab can reach it.
    val meScroll = androidx.compose.foundation.rememberScrollState()
    val shellScope = rememberCoroutineScope()
    // A restored stack can outlive the view model's playlist (process death): drop the empty page.
    LaunchedEffect(Unit) {
        if (libraryNav.depth > 1 && libraryViewModel.tracks.value.playlist == null) libraryNav.popToRoot()
    }
    // Closed only once the pop has finished, so the leaving page keeps its rows during the gesture.
    LaunchedEffect(libraryNav.depth) {
        if (libraryNav.depth == 1) libraryViewModel.closePlaylist()
    }
    val playback by app.playback.state.collectAsStateWithLifecycle()
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
    val queueUi = remember(queue, playback.queueIndex, queueBusy, playback.connected, accounts) {
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
    val playerUi = remember(playback, swapSearching) { playerUiOf(playback, swapSearching) }
    // Neighbour covers for the cover swipe. Under shuffle the next song is not the next row of the
    // queue, so none is drawn there and the swipe only returns the cover.
    val previousCover = remember(queue, playback.queueIndex, playerUi.shuffle) {
        if (playerUi.shuffle) null else queue.songs.getOrNull(playback.queueIndex - 1)?.let { CoverRef(it.title, it.coverUrl) }
    }
    val nextCover = remember(queue, playback.queueIndex, playerUi.shuffle) {
        if (playerUi.shuffle) null else queue.songs.getOrNull(playback.queueIndex + 1)?.let { CoverRef(it.title, it.coverUrl) }
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
    var miniRect by remember { mutableStateOf<Rect?>(null) }
    // The player closes on the artwork, not on the lyrics of the song that will not be showing next
    // time. Reset once the page has finished going down, so the cover does not swap in under it.
    LaunchedEffect(playerActive) {
        if (!playerActive) {
            lyricsMode = false
            lyricsBrowsing = false
        }
    }
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
    // The backend owns check, download and install; the shell only maps its state to the page.
    val updateState by app.updates.state.collectAsStateWithLifecycle()
    val aboutUpdate = remember(updateState) { aboutUpdateUiOf(updateState) }
    val updateActions = remember(app, context) {
        UpdateActions(
            onCheck = { app.updates.check() },
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
    LaunchedEffect(showQuality, currentQueueTicket, playback.mediaId, playback.sourceKey, queue.queueEpoch) {
        if (!showQuality) return@LaunchedEffect
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
    // The sleep timer outlives the player page, so only its deadline is observed here. The clock is
    // read once a second and only while the overflow menu is showing the time left.
    val sleepDeadline by SleepTimer.deadline.collectAsStateWithLifecycle()
    var sleepNow by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(showMore, sleepDeadline) {
        while (showMore && sleepDeadline != null) {
            sleepNow = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val sleepRemaining = sleepDeadline?.let { formatRemaining(it - sleepNow) }
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
    fun startSleepTimer(minutes: Int) {
        SleepTimer.start(minutes) { app.playback.pause() }
        notice.show("将在 $minutes 分钟后停止播放")
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
                SleepTimer.cancel()
                notice.show("已关闭定时停止")
            }
            MoreAction.SLEEP_15 -> startSleepTimer(15)
            MoreAction.SLEEP_30 -> startSleepTimer(30)
            MoreAction.SLEEP_45 -> startSleepTimer(45)
            MoreAction.SLEEP_60 -> startSleepTimer(60)
            MoreAction.SLEEP_90 -> startSleepTimer(90)
            MoreAction.SEARCH_ARTIST ->
                searchFromPlayer(playback.artist.split('、', '/', ',').firstOrNull().orEmpty())
            MoreAction.SEARCH_ALBUM -> searchFromPlayer(playback.album)
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
                    context.startActivity(android.content.Intent.createChooser(send, "分享歌曲"))
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
        onSetFollowCover = { on -> saveSetting { app.settingsRepository.setLyricsColorMode(lyricsColorModeOf(on)) } },
        onSetShowTranslation = { on ->
            saveSetting { app.settingsRepository.setLyricsTranslationPreference(translationPreferenceOf(on)) }
        },
        onSetTransparency = { on -> saveSetting { app.settingsRepository.setTransparencyEnabled(on) } },
        onSetThemeMode = { mode -> saveSetting { app.settingsRepository.setThemeMode(mode) } },
        // MeTab has already asked. A clear that fails for one kind still clears the others, so the
        // sentence is built from what the backend reports instead of always saying it worked.
        onClearCache = { app.cache.clear { result -> notice.show(cacheClearNotice(result)) } },
        onComingSoon = { notice.show("即将推出") },
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
    // The accent has no stored field yet (AppSettings has none), so the page shows the theme's own.
    val accent = LocalMusxi.current.accent
    val meUi = remember(accounts, settings, accent, cacheState) {
        meUiOf(
            // Netease, QQ, Kugou: the order of the old account screen, not the enum's.
            accounts = listOf(Platform.NETEASE, Platform.QQ, Platform.KUGOU).mapNotNull { accounts[it] },
            settings = settings,
            accent = accent,
            cache = cacheState,
        )
    }
    // Opening the tab measures the cache again, so the storage row is never an old number.
    LaunchedEffect(tab == ShellTab.ME) {
        if (tab == ShellTab.ME) app.cache.refresh()
    }
    fun submitQueueCommand(command: ((Result<Unit>) -> Unit) -> Unit) {
        if (!queueUi.editing) return
        queueBusy = true
        command { result ->
            queueBusy = false
            if (result.isFailure) notice.show("队列操作未完成，请确认连接后重试")
        }
    }
    // Search results join the current queue only when it is the same platform: the backend refuses
    // a mixed-platform queue, so a clear sentence beats a silent no.
    fun enqueueFromSearch(song: tech.musxi.core.library.SongSummary, playNext: Boolean) {
        val queuePlatform = queue.songs.firstOrNull()?.key?.platform
        if (queue.songs.isEmpty()) { app.queuePlayback.playSong(song); return }
        if (queuePlatform != song.key.platform) {
            notice.show("当前队列来自${queuePlatform?.displayName ?: "其他平台"}，不能混入${song.key.platform.displayName}的歌曲"); return
        }
        app.playback.enqueue(song, queue.revision, playNext) { result ->
            notice.show(if (result.isSuccess) (if (playNext) "将在下一首播放" else "已加入播放队列") else "没能加入队列，请稍后重试")
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
            notice.show(if (result.isSuccess) (if (liked) "已取消收藏" else "已收藏") else "收藏未生效，请稍后重试")
        }
    }
    fun addSongToPlaylist(song: tech.musxi.core.library.SongSummary, target: tech.musxi.core.library.EditablePlaylist) {
        app.playlists.addToPlaylist(song, target) { result ->
            notice.show(if (result.isSuccess) "已加入「${target.playlist.title}」" else "没能加入歌单，请稍后重试")
        }
    }

    // The shell's page colour is behind the bars unless the player covers it; the player then
    // reports its own backdrop, composed later, and wins.
    if (!playerCovering) SystemBarIcons(MaterialTheme.colorScheme.background)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // The dock floats over the content: the page is full height and scrolls underneath it,
        // so it no longer sits on a strip of bare background. The inset is measured, not
        // guessed, because the dock changes height when the mini player appears.
        var dockHeight by remember { mutableIntStateOf(0) }
        val contentInset = with(LocalDensity.current) { dockHeight.toDp() } + 16.dp
        // The pages are what the glass records and blurs; the dock, player, sheets and notice are
        // the host's overlay (spec section 3). The overlay is composed after the pages, so its back
        // handlers keep winning over the pages' own, as they did in the one Box before.
        val pages: @Composable BoxScope.() -> Unit = {
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
                        // Pages are pushed with the shared transition and leave with the system back
                        // gesture; the player's own back handling wins while it is open.
                        ShellTab.LIBRARY -> NavStackHost(libraryNav, backEnabled = !playerActive) { key -> if (key == "playlist") {
                            TrackListContent(
                                state = tracks,
                                dark = dark,
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
                            )
                        } else {
                            val libraryJournals by app.playbackJournal.state.collectAsStateWithLifecycle()
                            LibraryContent(
                                state = playlists,
                                onSelectFilter = libraryViewModel::selectFilter,
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
                                home = remember(libraryJournals, accounts, queue.songs.isNotEmpty(), playlists.filter) {
                                    val platform = playlists.filter.platform
                                    libraryHomeUiOf(
                                        journal = platform?.let { libraryJournals[it] },
                                        accountName = platform?.let { accounts[it]?.profile?.displayName },
                                        playingQueue = queue.songs.isNotEmpty(),
                                    )
                                },
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
                            loadPlaylists = { platform, done -> app.playlists.listWritable(platform, done) },
                            onAddToPlaylist = ::addSongToPlaylist,
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
                }
            }
        }
        val chrome: @Composable BoxScope.() -> Unit = {
            CompositionLocalProvider(LocalNowPlayingAccent provides nowPlaying) {
                // One dock instead of two stacked capsules: the chrome shares the page's margins,
                // and its hairline is the same separator the lists use.
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(horizontal = Space.page)
                        // The dock must clear the gesture bar; without this it sits under it on
                        // every phone with gesture navigation.
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .onSizeChanged { dockHeight = it.height }
                        .fadesWithPlayer(playerProgress),
                ) {
                    // Built once: the notice's action depends on which notice is showing.
                    val dockNotice = dockNoticeOf(
                        selection.phase, selection.mode, playerOpen = showPlayer,
                        // Why the original did not play: the original platform's own answer first.
                        reason = selection.original?.key?.platform?.let { selection.failureReasons[it] }
                            ?: playback.failureReason?.reason,
                        playbackFailed = playerUi.status == tech.musxi.player.ui.player.PlayerStatus.FAILED && !swapSearching,
                    )
                    ShellDock(
                        mini = miniPlayer,
                        selected = tab,
                        // Tapping the current tab again: back to its root page, or to the top of it.
                        onSelect = { picked ->
                            if (picked != tab) {
                                tab = picked
                            } else if (picked == ShellTab.LIBRARY) {
                                libraryNav.reselect { shellScope.launch { libraryList.animateScrollToItem(0) } }
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
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            // The queue sheet is the topmost overlay: back closes it before it touches the player.
            BackHandler(enabled = showQueue) { showQueue = false }
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
            val playerLayer: @Composable BoxScope.() -> Unit = {
                // The full player covers the shell rather than navigating to a new destination: it is a
                // layer over the same state, and pressing back must return to the tab underneath.
                PlayerSurface(
                    progress = playerProgress,
                    mini = miniPlayer,
                    ui = playerUi,
                    dark = dark,
                    miniRect = miniRect,
                    nowPlaying = nowPlaying,
                    previous = previousCover,
                    next = nextCover,
                    coverShown = !lyricsMode,
                    onPlayPause = { app.playback.toggle() },
                    onPrevious = { app.playback.previous() },
                    onNext = { app.playback.next() },
                ) { surface ->
                    FullPlayer(
                        ui = playerUi,
                        dark = dark,
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
                        qualityMenu = if (showQuality) {
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
                                sleepRemaining = sleepRemaining,
                            )
                        } else null,
                        sleepTimerActive = sleepDeadline != null,
                        onShowMore = {
                            // Read the clock now so the first frame of the menu is not a stale countdown.
                            sleepNow = SystemClock.elapsedRealtime()
                            showMore = true
                        },
                        onDismissMore = { showMore = false },
                        onMoreAction = ::handleMoreAction,
                        // The song is the queue original, the same one the heart acts on. Without a
                        // current song the menu has no "加入歌单" row.
                        loadPlaylists = if (currentSong != null) {
                            { done -> app.playlists.listWritable(currentSong.key.platform, done) }
                        } else null,
                        onAddToPlaylist = if (currentSong != null) {
                            { target -> addSongToPlaylist(currentSong, target) }
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
                    // The queue sits above the player for the same reason the source panel does: it is
                    // an interruption of the page, not a destination of its own.
                    if (showQueue) {
                        Box(
                            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                                .clickable(interactionSource = null, indication = null) { showQueue = false },
                        ) {
                            Column(
                                Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Bottom,
                            ) {
                                QueuePanel(
                                    ui = queueUi,
                                    // The sheet itself must swallow taps, or every touch would reach
                                    // the scrim behind it and close the queue.
                                    modifier = Modifier.clickable(interactionSource = null, indication = null) {},
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
                    if (showSongInfo) {
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
        }
        // "透明效果" off, battery saver and Android below 12 make every glass surface solid.
        GlassHost(enabled = settings.transparencyEnabled, overlay = chrome, content = pages)
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
