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
    }, playback::playQueue, { kugouLibrary.cancelQueueLoading(); qqLibrary.cancelQueueLoading(); playback.stop() }) }
    val queuePlayback get() = queuePlaybackHolder.value
    override fun onPlaybackStopRequested() {
        kugouLibrary.cancelQueueLoading()
        qqLibrary.cancelQueueLoading()
        if (queuePlaybackHolder.isInitialized()) queuePlaybackHolder.value.cancelPending()
    }

    override fun onCreate() {
        super.onCreate()
        // Dedicated app-owned WebView jar. No other platform uses this browser profile.
        if (Build.VERSION.SDK_INT >= 28) WebView.setDataDirectorySuffix("netease_login")
        accounts = AccountRepository(this)
    }
}
