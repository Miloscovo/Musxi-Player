package tech.musxi.player

import android.app.Application
import android.os.Build
import android.webkit.WebView
import tech.musxi.accounts.AccountRepository
import tech.musxi.accounts.NeteaseLibraryRepository
import tech.musxi.accounts.KugouLibraryRepository
import tech.musxi.accounts.QqLibraryRepository
import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibraryPageFetch
import tech.musxi.playback.PlaybackConnection
import tech.musxi.playback.PlaybackHost
import tech.musxi.playback.PlaybackSourceProvider
import tech.musxi.playback.PlatformPlaybackSources
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine

open class MusxiApplication : Application(), PlaybackHost {
    override val sourceSelection by lazy { tech.musxi.core.playback.SourceSelection(android.os.SystemClock::elapsedRealtime) }
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
                options.any { it.id.endsWith(":lossless") || it.id.endsWith(":flac") } -> tech.musxi.core.playback.SourceAudioQuality.LOSSLESS
                options.any { it.id.endsWith(":high") || it.id.endsWith(":higher") || it.id.endsWith(":exhigh") || it.id.endsWith(":320") } -> tech.musxi.core.playback.SourceAudioQuality.HIGH
                options.isNotEmpty() -> tech.musxi.core.playback.SourceAudioQuality.STANDARD
                else -> tech.musxi.core.playback.SourceAudioQuality.UNKNOWN
            }
            val category = (response.exceptionOrNull() as? tech.musxi.core.library.LibraryPageFailure)?.category
                ?.takeIf { it in setOf("network", "timeout", "permission", "expired", "http", "unavailable") }
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
    val library by lazy { NeteaseLibraryRepository(accounts) }
    val kugouLibrary by lazy { KugouLibraryRepository(accounts) }
    val qqLibrary by lazy { QqLibraryRepository(accounts) }
    private val favoritesRepository by lazy { tech.musxi.accounts.FavoritesRepository(accounts, ::onFavoriteDiagnostic) }
    val playlists by lazy { tech.musxi.accounts.PlaylistRepository(accounts,
        favoriteWrite = { song, result -> favorites.enqueueLiked(song, true, result) },
        changed = { platform, identity -> libraryFor(platform).invalidate(identity) }) }
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
        accounts::playbackIdentity, accounts::playbackJournal, { saved, result -> queuePlayback.restore(saved, result) }) }
    private val lyricsRepository by lazy { tech.musxi.accounts.LyricsRepository(accounts) }
    val lyrics by lazy { tech.musxi.core.lyrics.LyricsBrowser(accounts::playbackIdentity,
        lyricsRepository::fetchOriginal, ::fetchLyricsTranslation,
        lyricsRepository::fetchOriginal, lyricsRepository::releaseOriginal) }
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
    val libraryBrowser by lazy { accounts.createLibraryBrowser(createLibraryFetch()) }
    protected open fun createLibraryFetch(): LibraryPageFetch = library
    val kugouLibraryBrowser by lazy { accounts.createLibraryBrowser(createKugouLibraryFetch(), Platform.KUGOU) }
    protected open fun createKugouLibraryFetch(): LibraryPageFetch = kugouLibrary
    val qqLibraryBrowser by lazy { accounts.createLibraryBrowser(createQqLibraryFetch(), Platform.QQ) }
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
    }, playback::restoreQueue, { playbackJournal.cancelRestore() },
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
    override fun onPlaybackStopRequested() {
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

    override fun onPlaybackQualityChanged(platform: Platform, qualityId: String) {
        accounts.rememberPreferredQuality(platform, qualityId)
    }

    override fun onCreate() {
        super.onCreate()
        // Dedicated app-owned WebView jar. No other platform uses this browser profile.
        if (Build.VERSION.SDK_INT >= 28) WebView.setDataDirectorySuffix("netease_login")
        accounts = AccountRepository(this)
        appScope.launch { accounts.states.collect { lyricsRepository.accountChanged() } }
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
            }.collect { }
        }
    }
}
