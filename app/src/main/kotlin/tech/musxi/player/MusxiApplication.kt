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
    lateinit var accounts: AccountRepository
        private set
    val playback by lazy { PlaybackConnection(this) }
    val library by lazy { NeteaseLibraryRepository(accounts) }
    val kugouLibrary by lazy { KugouLibraryRepository(accounts) }
    val qqLibrary by lazy { QqLibraryRepository(accounts) }
    private val favoritesRepository by lazy { tech.musxi.accounts.FavoritesRepository(accounts, ::onFavoriteDiagnostic) }
    protected open fun onFavoriteDiagnostic(platform: tech.musxi.core.account.Platform, operation: String, category: String) = Unit
    val favorites by lazy { tech.musxi.core.library.FavoritesBrowser(accounts::playbackIdentity,
        tech.musxi.core.library.FavoritesFetch(favoritesRepository::fetch),
        tech.musxi.core.library.FavoritesWrite(favoritesRepository::setLiked)) }
    val playbackJournal: tech.musxi.core.playback.PlaybackJournalBrowser by lazy { tech.musxi.core.playback.PlaybackJournalBrowser(
        accounts::playbackIdentity, accounts::playbackJournal, { saved, result -> queuePlayback.restore(saved, result) }) }
    private val lyricsRepository by lazy { tech.musxi.accounts.LyricsRepository(accounts) }
    val lyrics by lazy { tech.musxi.core.lyrics.LyricsBrowser(accounts::playbackIdentity, lyricsRepository::fetch) }
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
    }, playback::playQueue, { kugouLibrary.cancelQueueLoading(); qqLibrary.cancelQueueLoading(); playback.stop() }, { key ->
        accounts.states.value.getValue(key.platform).takeIf {
            it.phase == tech.musxi.core.account.AccountPhase.CONNECTED
        }?.generation
    }, playback::restoreQueue, { playbackJournal.cancelRestore() }) }
    val queuePlayback get() = queuePlaybackHolder.value
    override fun onPlaybackStopRequested() {
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

    override fun onCreate() {
        super.onCreate()
        // Dedicated app-owned WebView jar. No other platform uses this browser profile.
        if (Build.VERSION.SDK_INT >= 28) WebView.setDataDirectorySuffix("netease_login")
        accounts = AccountRepository(this)
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
