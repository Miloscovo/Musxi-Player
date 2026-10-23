package tech.musxi.player

import tech.musxi.core.playback.PlaybackFailure
import tech.musxi.playback.*
import tech.musxi.core.library.*

/** Fixed synthetic loader failures for device acceptance. Entire class is absent from release. */
class DebugMusxiApplication : MusxiApplication() {
    var translationDelayOnce = false
    var translationFailureOnce = false
    override fun fetchLyricsTranslation(key: MediaKey, original: tech.musxi.core.lyrics.ParsedLyrics,
        refresh: Boolean, result: (Result<tech.musxi.core.lyrics.ParsedLyrics>) -> Unit) {
        val delayed = translationDelayOnce
        val failed = translationFailureOnce
        translationDelayOnce = false; translationFailureOnce = false
        val task = Runnable {
            if (failed) result(Result.failure(LibraryPageFailure("network")))
            else super.fetchLyricsTranslation(key, original, refresh, result)
        }
        if (delayed) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(task, 4_500)
        else task.run()
    }
    override fun onFavoriteDiagnostic(platform: tech.musxi.core.account.Platform, operation: String, category: String) {
        getSharedPreferences("p2-favorite-diagnostic", MODE_PRIVATE).edit()
            .putString(platform.name.lowercase(), "${operation}_$category").apply()
    }
    var validationActivity: android.app.Activity? = null
        private set
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                if (activity is DeveloperActivity) validationActivity = activity
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                if (validationActivity === activity) validationActivity = null
            }
            override fun onActivityDestroyed(activity: android.app.Activity) {
                if (validationActivity === activity) validationActivity = null
            }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
        })
    }
    override fun createPlaybackSources(): PlaybackSourceProvider = ValidationSources(super.createPlaybackSources())
    val validationSources get() = playbackSources as ValidationSources
    val validationLibrary by lazy { ValidationLibraryFetch(library) }
    override fun createLibraryFetch(): LibraryPageFetch = validationLibrary
    val validationKugouLibrary by lazy { ValidationLibraryFetch(kugouLibrary) }
    override fun createKugouLibraryFetch(): LibraryPageFetch = validationKugouLibrary
    val validationQqLibrary by lazy { ValidationLibraryFetch(qqLibrary) }
    override fun createQqLibraryFetch(): LibraryPageFetch = validationQqLibrary
    var afterStopRequestOnce: (() -> Unit)? = null
    override fun onPlaybackStopRequested() {
        super.onPlaybackStopRequested()
        val callback = afterStopRequestOnce
        afterStopRequestOnce = null
        callback?.invoke()
    }
}

/** Metadata-only failures. Does not change login records or the phone's connectivity. */
class ValidationLibraryFetch(private val delegate: LibraryPageFetch) : LibraryPageFetch {
    var offline = false
    var requests = 0
        private set
    override fun playlists(offset: Int, result: (Result<Page<PlaylistSummary>>) -> Unit) {
        requests++
        if (offline) result(Result.failure(LibraryPageFailure("network"))) else delegate.playlists(offset, result)
    }
    override fun tracks(playlist: MediaKey, offset: Int, result: (Result<Page<SongSummary>>) -> Unit) {
        requests++
        if (offline) result(Result.failure(LibraryPageFailure("network"))) else delegate.tracks(playlist, offset, result)
    }
}

class ValidationSources(private val delegate: PlaybackSourceProvider) : PlaybackSourceProvider by delegate {
    private val gate = Any()
    private var remaining = 0
    private var failure = PlaybackFailure.UNKNOWN
    private var delayFailure = false
    var injectedCount = 0; private set
    var resolveCount = 0; private set
    @Volatile var lastPlatform = "NONE"; private set
    @Volatile var lastMetadata: SongSummary? = null; private set

    fun arm(step: String) = synchronized(gate) {
        injectedCount = 0; resolveCount = 0
        lastPlatform = "NONE"
        lastMetadata = null
        delayFailure = step == "fault_permission_delayed"
        when (step) {
            "fault_expired_once" -> { failure = PlaybackFailure.SOURCE_EXPIRED; remaining = 1 }
            "fault_expired_twice" -> { failure = PlaybackFailure.SOURCE_EXPIRED; remaining = 2 }
            "fault_permission_once" -> { failure = PlaybackFailure.PERMISSION; remaining = 1 }
            "fault_permission_delayed" -> { failure = PlaybackFailure.PERMISSION; remaining = 1 }
            "fault_permission_chain" -> { failure = PlaybackFailure.PERMISSION; remaining = 4 }
            "fault_network_once" -> { failure = PlaybackFailure.NETWORK; remaining = 1 }
            else -> remaining = 0
        }
    }

    override fun resolve(mediaId: String, generation: Long): StreamLease {
        val injection = synchronized(gate) {
            resolveCount++
            lastPlatform = when (mediaId.substringBefore(':')) {
                "qq" -> "QQ"; "netease" -> "NETEASE"; "kugou" -> "KUGOU"; else -> "NONE"
            }
            if (remaining > 0) { remaining--; injectedCount++; failure to delayFailure } else null
        }
        injection?.let { (reason, delayed) ->
            if (delayed) try { Thread.sleep(2_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            throw PlaybackSourceException(reason)
        }
        return delegate.resolve(mediaId, generation).also { lastMetadata = it.metadata }
    }
}
