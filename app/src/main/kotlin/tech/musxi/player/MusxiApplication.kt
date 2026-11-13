package tech.musxi.player

import android.app.Application
import android.os.Build
import android.webkit.WebView
import tech.musxi.accounts.AccountRepository
import tech.musxi.accounts.NeteaseLibraryRepository
import tech.musxi.accounts.KugouLibraryRepository
import tech.musxi.accounts.QqLibraryRepository
import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.SongSummary
import tech.musxi.core.library.LibraryPageFetch
import tech.musxi.playback.PlaybackConnection
import tech.musxi.playback.PlaybackHost
import tech.musxi.playback.PlaybackSourceProvider
import tech.musxi.playback.PlatformPlaybackSources
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

open class MusxiApplication : Application(), PlaybackHost {
    val settingsRepository get() = accounts.settingsRepository
    val settings get() = settingsRepository.settings
    override val mediaCardLyrics by lazy {
        combine(playback.state, lyrics.state, settings, sourceSelection.state) { playback, lyrics, settings, selection ->
            val waiting = selection.phase in setOf(tech.musxi.core.playback.SourceSelectionPhase.SEARCHING,
                tech.musxi.core.playback.SourceSelectionPhase.CHOOSING, tech.musxi.core.playback.SourceSelectionPhase.FAILED)
            tech.musxi.playback.MediaCardLyricsState.from(
                settings.effectiveLockScreenLyricsMode == tech.musxi.core.settings.LockScreenLyricsMode.CARD,
                playback, lyrics, waiting)
        }.distinctUntilChanged()
    }
    /** Personalization files are durable local settings, independent of account and content caches. */
    private val wallpaperPool by lazy { tech.musxi.player.wallpaper.AppWallpaperPool.get(this) }
    val wallpaper by lazy { wallpaperPool.player }
    val interfaceWallpaper by lazy { wallpaperPool.interfaceWallpaper }
    /** Local audio survives platform logout and is never part of ordinary cache cleanup. */
    val localAudio by lazy { tech.musxi.player.localaudio.AppLocalAudioRepository(this, settings) }
    override val localAudioPlayback by lazy {
        tech.musxi.player.localaudio.AppLocalAudioPlaybackAdapter(localAudio,
            { queue, playWhenReady, result -> playback.playLocalQueue(queue, playWhenReady, result) },
            beforePrepare = { playback.checkpointBeforeInstall(it) },
            journalStore = tech.musxi.core.localaudio.FileLocalAudioPlaybackJournalStore(
                java.io.File(filesDir, "local-audio-playback-v1")),
            onNewIntent = ::cancelPlatformPlaybackWork)
    }
    val cache by lazy { AppCacheRepository(this) { kind ->
        when (kind) {
            tech.musxi.core.cache.CacheKind.LYRICS -> { lyrics.clearCache(); lyricsRepository.clearCache() }
            tech.musxi.core.cache.CacheKind.LIBRARY_PAGES -> {
                listOf(libraryBrowserCache, kugouLibraryBrowserCache, qqLibraryBrowserCache)
                    .filter { it.isInitialized() }.forEach { it.value.clearCachedPages() }
                if (recommendationsHolder.isInitialized()) recommendationsHolder.value.clearCachedResults()
                playlistCatalogs.values.forEach { it.clearCachedPages() }
            }
            tech.musxi.core.cache.CacheKind.COVERS -> Unit
        }
    } }
    fun sourceSwitchPreferences() = settings.value.let {
        tech.musxi.core.playback.SourceSwitchPreferences(it.autoSwitchSource, it.relaxSourceDuration)
    }
    override val sourcePreferences get() = settings.map {
        tech.musxi.core.playback.SourceSwitchPreferences(it.autoSwitchSource, it.relaxSourceDuration)
    }
    override fun currentSourcePreferences() = sourceSwitchPreferences()
    override val sourceSelection by lazy { tech.musxi.core.playback.SourceSelection(android.os.SystemClock::elapsedRealtime, ::sourceSwitchPreferences) }
    private val alternativeSearch by lazy { CrossPlatformSourceSearch(this) }
    override fun findAlternativeSources(original: tech.musxi.core.library.SongSummary,
        result: (Result<tech.musxi.core.playback.AlternativeSources>) -> Unit): kotlinx.coroutines.Job =
        alternativeSearch.find(original, result)
    override fun findAlternativeSources(original: tech.musxi.core.library.SongSummary, excluded: Set<String>,
        result: (Result<tech.musxi.core.playback.AlternativeSources>) -> Unit): kotlinx.coroutines.Job =
        alternativeSearch.find(original, result, excluded)
    override fun onAlternativeSourcePlayed(original: tech.musxi.core.library.SongSummary, generation: Long,
        source: tech.musxi.core.playback.SourceCandidate) = alternativeSearch.remember(original, generation, source)
    override fun findAlternativeSources(original: tech.musxi.core.library.SongSummary, excluded: Set<String>,
        mode: tech.musxi.core.playback.SourceSelectionMode,
        progress: tech.musxi.core.playback.SourceSearchProgressListener,
        result: (Result<tech.musxi.core.playback.AlternativeSources>) -> Unit): kotlinx.coroutines.Job =
        alternativeSearch.find(original, result, excluded, mode = mode, onProgress = progress)
    override fun onAlternativeSourceFailed(original: tech.musxi.core.library.MediaKey, source: tech.musxi.core.library.MediaKey) =
        alternativeSearch.forget(original, source)
    /** On-demand entitlement inspection. Search results alone do not establish available quality. */
    fun inspectAlternativeSource(revision: Long, index: Int,
        result: (Result<tech.musxi.core.playback.QualityOptions>) -> Unit) {
        val selection = sourceSelection.state.value
        val candidate = selection.candidates.getOrNull(index)
        if (selection.revision != revision || selection.phase != tech.musxi.core.playback.SourceSelectionPhase.CHOOSING || candidate == null)
            return result(Result.failure(tech.musxi.core.library.LibraryPageFailure("stale_revision")))
        val key = candidate.song.key
        fun current() = sourceSelection.state.value.revision == revision &&
            accounts.states.value.getValue(key.platform).let {
                it.phase == tech.musxi.core.account.AccountPhase.CONNECTED && it.generation == candidate.accountGeneration
            }
        if (!current()) return result(Result.failure(tech.musxi.core.library.LibraryPageFailure("account_changed")))
        sourceSelection.candidateAudio(revision, key, candidate.audio.copy(loading = true, error = null))
        val callback: (Result<tech.musxi.core.playback.QualityOptions>) -> Unit = callback@{ response ->
            if (!current()) { result(Result.failure(tech.musxi.core.library.LibraryPageFailure("account_changed"))); return@callback }
            val options = response.getOrNull()?.options.orEmpty()
            val level = when {
                options.any { it.id.endsWith(":lossless") || it.id.endsWith(":flac") || it.id == "kugou:high" || it.id == "netease:hires" } -> tech.musxi.core.playback.SourceAudioQuality.LOSSLESS
                options.any { it.id.endsWith(":high") || it.id.endsWith(":higher") || it.id.endsWith(":exhigh") || it.id.endsWith(":320") } -> tech.musxi.core.playback.SourceAudioQuality.HIGH
                options.isNotEmpty() -> tech.musxi.core.playback.SourceAudioQuality.STANDARD
                else -> tech.musxi.core.playback.SourceAudioQuality.UNKNOWN
            }
            val category = (response.exceptionOrNull() as? tech.musxi.core.library.LibraryPageFailure)?.category
                ?.takeIf { it in setOf("network", "timeout", "permission", "membership", "copyright", "preview", "expired", "http", "unavailable") }
                ?: if (options.isEmpty()) "unavailable" else null
            sourceSelection.candidateAudio(revision, key, tech.musxi.core.playback.SourceAudioMetadata(
                quality = level, qualities = options,
                playability = if (options.isNotEmpty()) tech.musxi.core.playback.SourcePlayability.AVAILABLE else tech.musxi.core.playback.SourcePlayability.UNKNOWN,
                error = category))
            result(response)
        }
        when (key.platform) {
            Platform.NETEASE -> library.qualities(key, callback)
            Platform.QQ -> qqLibrary.qualities(key, callback)
            Platform.KUGOU -> kugouLibrary.qualities(key, callback)
        }
    }
    lateinit var accounts: AccountRepository
        private set
    val playback by lazy { PlaybackConnection(this) }
    /** Lifecycle/permission backend only; release has no concrete page until the front end connects it. */
    val lockScreenLyrics by lazy { tech.musxi.player.lockscreen.LockScreenLyricsController(this,
        lockScreenLyricsHostClass()) }
    protected open fun lockScreenLyricsHostClass(): Class<out tech.musxi.player.lockscreen.LockScreenLyricsHostActivity>? = null
    /** Shared public content, with the applied player wallpaper; drafts never leak onto the lock screen. */
    val lockScreenLyricsContent by lazy {
        combine(playback.state, lyrics.state, sourceSelection.state, wallpaper.state) { playback, lyrics, selection, wallpaper ->
            val waiting = selection.phase in setOf(tech.musxi.core.playback.SourceSelectionPhase.SEARCHING,
                tech.musxi.core.playback.SourceSelectionPhase.CHOOSING, tech.musxi.core.playback.SourceSelectionPhase.FAILED)
            tech.musxi.player.lockscreen.LockScreenLyricsContent.from(playback, lyrics, waiting, wallpaper.saved)
        }.distinctUntilChanged()
    }
    val library by lazy { NeteaseLibraryRepository(accounts) }
    val updates by lazy { tech.musxi.accounts.AppUpdateRepository(this,
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0",
        releaseBuild = BuildConfig.BUILD_TYPE == "release", beforeInstall = playback::checkpointBeforeInstall) }
    val kugouLibrary by lazy { KugouLibraryRepository(accounts) }
    val qqLibrary by lazy { QqLibraryRepository(accounts) }
    private val recommendationsHolder = lazy { AppRecommendations(accounts.createRecommendationsBrowser()) {
            queue, result -> queuePlayback.playSnapshot(queue, result)
        } }
    val recommendations get() = recommendationsHolder.value
    /** Metadata only: artist/album reads use the same account lanes and page bounds as library reads. */
    val catalog = tech.musxi.core.library.CatalogTrackFetch { key, offset, result ->
        val entity = tech.musxi.core.library.MediaKey(key.platform, key.id)
        val artist = key.kind == tech.musxi.core.library.CatalogKind.ARTIST
        when (key.platform) {
            Platform.NETEASE -> if (artist) library.artistTracks(entity, offset, result) else library.albumTracks(entity, offset, result)
            Platform.QQ -> if (artist) qqLibrary.artistTracks(entity, offset, result) else qqLibrary.albumTracks(entity, offset, result)
            Platform.KUGOU -> if (artist) kugouLibrary.artistTracks(entity, offset, result) else kugouLibrary.albumTracks(entity, offset, result)
        }
    }
    private val favoritesRepository by lazy { tech.musxi.accounts.FavoritesRepository(accounts, ::onFavoriteDiagnostic) }
    val playlists by lazy { tech.musxi.accounts.PlaylistRepository(accounts,
        favoriteWrite = ::writePlaylistFavorite,
        changed = { platform, identity ->
            libraryFor(platform).invalidate(identity)
            playlistCatalogs[platform]?.invalidate(identity)
        }, catalogFor = ::playlistCatalog, onDeleted = { key ->
            if (queuePlaybackHolder.isInitialized()) queuePlaybackHolder.value.playlistDeleted(key)
        }) }
    private val playlistCatalogs = mutableMapOf<Platform, tech.musxi.core.library.PlaylistCatalog>()
    private fun playlistCatalog(platform: Platform) = playlistCatalogs.getOrPut(platform) {
        accounts.createPlaylistCatalog(when (platform) {
            Platform.NETEASE -> createLibraryFetch()
            Platform.KUGOU -> createKugouLibraryFetch()
            Platform.QQ -> createQqLibraryFetch()
        }, platform)
    }
    private fun writePlaylistFavorite(song: SongSummary, liked: Boolean, result: (Result<Unit>) -> Unit) {
        val platform = song.key.platform
        val owner = accounts.playbackIdentity(platform)
        appScope.launch {
            val ready = kotlinx.coroutines.withTimeoutOrNull(90_000L) {
                favorites.state.first { it.getValue(platform).let { state ->
                    !state.loading && state.pending == null && state.queuedKeys.isEmpty()
                } }
                if (accounts.playbackIdentity(platform) != owner) return@withTimeoutOrNull false
                // A membership sheet can be opened before any favorites UI has initialized its browser.
                favorites.refresh(platform)
                favorites.state.first { !it.getValue(platform).loading }
                val state = favorites.state.value.getValue(platform)
                state.synced && state.canWrite && state.error == null
            }
            when {
                accounts.playbackIdentity(platform) != owner -> result(Result.failure(LibraryPageFailure("account_changed")))
                ready == null -> result(Result.failure(LibraryPageFailure("timeout")))
                !ready -> result(Result.failure(LibraryPageFailure("not_synced")))
                else -> favorites.enqueueLiked(song, liked, result)
            }
        }
    }

    private fun libraryFor(platform: Platform) = when (platform) {
        Platform.NETEASE -> libraryBrowser
        Platform.KUGOU -> kugouLibraryBrowser
        Platform.QQ -> qqLibraryBrowser
    }
    protected open fun onFavoriteDiagnostic(platform: tech.musxi.core.account.Platform, operation: String, category: String) = Unit
    val favorites by lazy { tech.musxi.core.library.FavoritesBrowser(accounts::playbackIdentity,
        tech.musxi.core.library.FavoritesFetch(favoritesRepository::fetch),
        tech.musxi.core.library.FavoritesWrite(favoritesRepository::setLiked),
        verify = tech.musxi.core.library.FavoritesVerify(favoritesRepository::verifyLiked),
        onVerifiedChange = { platform, identity ->
            when (platform) {
                Platform.NETEASE -> libraryBrowser
                Platform.KUGOU -> kugouLibraryBrowser
                Platform.QQ -> qqLibraryBrowser
            }.invalidate(identity, refreshNow = false)
        }) }
    val playbackJournal: tech.musxi.core.playback.PlaybackJournalBrowser by lazy { tech.musxi.core.playback.PlaybackJournalBrowser(
        accounts::playbackIdentity, accounts::playbackJournal, { saved, result ->
            localAudioPlayback.cancelPendingPlay()
            queuePlayback.restore(saved, result)
        }) }
    private val lyricsRepository by lazy { tech.musxi.accounts.LyricsRepository(accounts) }
    val lyrics by lazy { tech.musxi.core.lyrics.LyricsBrowser(accounts::playbackIdentity,
        lyricsRepository::fetchOriginal, ::fetchLyricsTranslation,
        lyricsRepository::fetchOriginal, lyricsRepository::releaseOriginal,
        lyricsRepository::fetchForOriginal, allowCachedUnverified = true) }
    val lyricsMatch by lazy { tech.musxi.core.lyrics.LyricsMatchBrowser(accounts::playbackIdentity,
        search = { platform, keywords, page, result ->
            when (platform) {
                Platform.NETEASE -> library.searchPage(keywords, (page - 1) * 30, result)
                Platform.QQ -> qqLibrary.searchPage(keywords, (page - 1) * 30, result)
                Platform.KUGOU -> kugouLibrary.searchPage(keywords, (page - 1) * 30, result)
            }
        }, previewFetch = lyricsRepository::preview, bindingAccess = accounts::lyricsBinding,
        onBindingChanged = { _, _ -> lyrics.refresh() }) }
    protected open fun fetchLyricsTranslation(key: tech.musxi.core.library.MediaKey,
        original: tech.musxi.core.lyrics.ParsedLyrics, refresh: Boolean,
        result: (Result<tech.musxi.core.lyrics.ParsedLyrics>) -> Unit) =
        lyricsRepository.fetchTranslation(key, original, refresh, result)
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    val searchBrowser by lazy { accounts.createSearchBrowser(tech.musxi.core.library.SearchPageFetch { query, current, result ->
        when (query.platform) {
            Platform.NETEASE -> library.searchPage(query.keywords, query.offset, result, current)
            Platform.QQ -> qqLibrary.searchPage(query.keywords, query.offset, result, current)
            Platform.KUGOU -> kugouLibrary.searchPage(query.keywords, query.offset, result, current)
        }
    }) }
    private val libraryBrowserCache = lazy { accounts.createLibraryBrowser(createLibraryFetch()) }
    val libraryBrowser by libraryBrowserCache
    protected open fun createLibraryFetch(): LibraryPageFetch = library
    private val kugouLibraryBrowserCache = lazy { accounts.createLibraryBrowser(createKugouLibraryFetch(), Platform.KUGOU) }
    val kugouLibraryBrowser by kugouLibraryBrowserCache
    protected open fun createKugouLibraryFetch(): LibraryPageFetch = kugouLibrary
    private val qqLibraryBrowserCache = lazy { accounts.createLibraryBrowser(createQqLibraryFetch(), Platform.QQ) }
    val qqLibraryBrowser by qqLibraryBrowserCache
    protected open fun createQqLibraryFetch(): LibraryPageFetch = qqLibrary
    override val playbackSources: PlaybackSourceProvider by lazy { createPlaybackSources() }
    protected open fun createPlaybackSources(): PlaybackSourceProvider = PlatformPlaybackSources(mapOf(
        "netease" to NeteasePlaybackProvider(accounts, library), "kugou" to KugouPlaybackProvider(accounts, kugouLibrary),
        "qq" to QqPlaybackProvider(accounts, qqLibrary)))
    private val queuePlaybackHolder = lazy { NeteasePlaybackUseCase({ playlist, song, result ->
        when (playlist.platform) {
            Platform.NETEASE -> library.queue(playlist, song, result)
            Platform.KUGOU -> kugouLibrary.queue(playlist, song, result)
            Platform.QQ -> qqLibrary.queue(playlist, song, result)
            else -> result(Result.failure(tech.musxi.core.library.LibraryPageFailure("unavailable")))
        }
    }, playback::playQueue, { library.cancelQueueLoading(); kugouLibrary.cancelQueueLoading(); qqLibrary.cancelQueueLoading(); playback.stop() }, { key ->
        accounts.states.value.getValue(key.platform).takeIf {
            it.phase == tech.musxi.core.account.AccountPhase.CONNECTED
        }?.generation
    }, playback::restoreQueue, { playbackJournal.cancelRestore(); localAudioPlayback.cancelPendingPlay() },
        loadQueueHead = { playlist, song, known, result ->
            when (playlist.platform) {
                Platform.NETEASE -> library.queueHead(playlist, song, known, result)
                Platform.KUGOU -> kugouLibrary.queueHead(playlist, song, known, result)
                Platform.QQ -> qqLibrary.queueHead(playlist, song, known, result)
            }
        },
        enqueueBatch = { songs, at, batchId, epoch, result -> playback.enqueueBatch(songs, at, batchId, epoch, result) },
        currentQueueEpoch = { playback.queueState.value.queueEpoch }) }
    val queuePlayback get() = queuePlaybackHolder.value
    override fun onPlatformPlaybackRequested() {
        localAudioPlayback.cancelPendingPlay()
        localAudioPlayback.deactivateQueue()
    }
    override fun onLocalPlaybackRequested() { cancelPlatformPlaybackWork() }
    override fun onPlaybackStopRequested() {
        localAudioPlayback.cancelPendingPlay()
        localAudioPlayback.deactivateQueue()
        cancelPlatformPlaybackWork()
    }
    private fun cancelPlatformPlaybackWork() {
        library.cancelQueueLoading()
        kugouLibrary.cancelQueueLoading()
        qqLibrary.cancelQueueLoading()
        if (queuePlaybackHolder.isInitialized()) queuePlaybackHolder.value.cancelPending()
        playbackJournal.cancelRestore()
    }

    override fun onPlaybackCheckpoint(generation: Long, snapshot: tech.musxi.core.playback.SavedPlaybackQueue,
        recent: tech.musxi.core.playback.RecentPlay?) {
        playbackJournal.save(snapshot.songs.first().key.platform, generation, snapshot, recent)
    }
    override fun onPlaybackQueueCleared(platform: Platform, generation: Long) {
        playbackJournal.save(platform, generation, null)
    }
    override fun onPlaybackCheckpointSaved(generation: Long, snapshot: tech.musxi.core.playback.SavedPlaybackQueue,
        recent: tech.musxi.core.playback.RecentPlay?, completed: (Result<Unit>) -> Unit) {
        playbackJournal.save(snapshot.songs.first().key.platform, generation, snapshot, recent, completed)
    }

    override fun onPlaybackQualityChanged(platform: Platform, qualityId: String) {
        accounts.rememberPreferredQuality(platform, qualityId)
    }

    override fun onCreate() {
        super.onCreate()
        // Dedicated app-owned WebView jar. No other platform uses this browser profile.
        if (Build.VERSION.SDK_INT >= 28) WebView.setDataDirectorySuffix("netease_login")
        accounts = AccountRepository(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private val started = mutableSetOf<android.app.Activity>()
            override fun onActivityStarted(activity: android.app.Activity) {
                if (activity !is tech.musxi.player.lockscreen.LockScreenLyricsHostActivity) started.add(activity)
                accounts.setForeground(started.isNotEmpty())
                lockScreenLyrics.refreshPermissions()
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                started.remove(activity)
                accounts.setForeground(started.isNotEmpty())
            }
            override fun onActivityDestroyed(activity: android.app.Activity) { started.remove(activity) }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
        })
        lockScreenLyrics.start(appScope)
        updates.start()
        appScope.launch { accounts.states.collect { lyricsRepository.accountChanged() } }
        appScope.launch { accounts.states.collect { lyricsMatch.accountChanged() } }
        appScope.launch { accounts.states.collect { playbackJournal.accountChanged() } }
        appScope.launch { accounts.states.collect { favorites.accountChanged() } }
        appScope.launch {
            combine(playback.state, accounts.states, sourceSelection.state) { state, _, selection ->
                val queue = state.mediaId?.let { id ->
                    state.queuePlatform?.let { tech.musxi.core.library.MediaKey(it, id.substringAfter(':')) }
                }
                val waiting = selection.phase in setOf(tech.musxi.core.playback.SourceSelectionPhase.SEARCHING,
                    tech.musxi.core.playback.SourceSelectionPhase.CHOOSING, tech.musxi.core.playback.SourceSelectionPhase.FAILED)
                lyrics.follow(queue, state.sourceKey.takeUnless { waiting }, state.positionMs)
                if (queue == null) lyricsMatch.cancel() else lyricsMatch.follow(queue)
            }.collect { }
        }
    }
}
