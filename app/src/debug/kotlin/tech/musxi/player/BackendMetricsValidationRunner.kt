package tech.musxi.player

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import tech.musxi.core.account.Platform
import tech.musxi.core.diagnostics.RequestMetricSummary
import tech.musxi.core.diagnostics.RequestOperation
import tech.musxi.core.library.FavoritesState
import tech.musxi.core.library.LibraryBrowseState
import tech.musxi.core.library.LibraryBrowser
import tech.musxi.core.library.LibraryContent
import tech.musxi.core.library.SongSummary

/** Fixed read-only B3 probes with bounded, redacted diagnostics. */
internal object BackendMetricsValidationRunner {
    private const val PREFERENCES = "backend-metrics-validation"
    private const val SEARCH_QUERY = "周杰伦"

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var operationToken = 0L
    @Volatile private var currentStep: String? = null
    private var cancelledPage: Any? = null

    fun run(app: MusxiApplication, step: String): Boolean {
        if (!step.startsWith("bm_")) return false
        if (step == "bm_snapshot") {
            snapshot(app)
            return true
        }
        if (!isKnownCommand(step)) return true

        val token = ++operationToken
        currentStep = step
        beginCommand(app, step)
        try {
            when {
                step == "bm_clear" -> clearWindow(app)
                step == "bm_cancel" -> {
                    app.searchBrowser.search(Platform.NETEASE, SEARCH_QUERY)
                    app.searchBrowser.cancel()
                    cancelledPage = app.searchBrowser.state.value.page
                    setString(app, "commandStatus", "CANCELLED")
                    snapshot(app)
                }
                step == "bm_clear_inflight" -> {
                    app.searchBrowser.search(Platform.NETEASE, SEARCH_QUERY)
                    main.postDelayed({ if (token == operationToken) {
                        val before = app.accounts.requestTimingSnapshot()
                        val count = before.sumOf { it.count }
                        val completed = before.sumOf { it.completed }
                        clearWindow(app)
                        preferences(app).edit().putLong("beforeClearCount", count)
                            .putLong("beforeClearCompleted", completed).apply()
                    } }, 50)
                }
                step.startsWith("bm_lyrics_") -> {
                    val platform = Platform.valueOf(step.substringAfter("bm_lyrics_").uppercase())
                    val song = (browser(app, platform).state.value.content as? LibraryContent.Tracks)?.page?.items?.firstOrNull()
                    if (song == null) setString(app, "commandStatus", "NO_SONG")
                    else tech.musxi.accounts.LyricsRepository(app.accounts).fetch(song.key) { response ->
                        if (token == operationToken) { setString(app, "commandStatus", if (response.isSuccess) "OK" else "FAILED"); snapshot(app) }
                    }
                }
                step.startsWith("bm_audio_") -> {
                    val platform = Platform.valueOf(step.substringAfter("bm_audio_").uppercase())
                    val song = (browser(app, platform).state.value.content as? LibraryContent.Tracks)?.page?.items?.firstOrNull()
                    if (song == null) setString(app, "commandStatus", "NO_SONG")
                    else {
                        fun complete(ok: Boolean) { if (token == operationToken) {
                            setString(app, "commandStatus", if (ok) "OK" else "FAILED"); snapshot(app)
                        } }
                        when (platform) {
                            Platform.NETEASE -> app.library.resolveAudio(song.key) { complete(it.isSuccess) }
                            Platform.QQ -> app.qqLibrary.resolveAudio(song.key) { complete(it.isSuccess) }
                            Platform.KUGOU -> app.kugouLibrary.resolveAudio(song.key) { complete(it.isSuccess) }
                        }
                    }
                }
                step == "bm_favorites_read_netease" -> readFavorites(app, Platform.NETEASE)
                step == "bm_favorites_read_qq" -> readFavorites(app, Platform.QQ)
                step == "bm_favorites_read_kugou" -> readFavorites(app, Platform.KUGOU)
                step == "bm_search_netease" -> search(app, Platform.NETEASE, token)
                step == "bm_search_qq" -> search(app, Platform.QQ, token)
                step == "bm_search_kugou" -> search(app, Platform.KUGOU, token)
                step == "bm_playlists_netease" -> playlists(app, Platform.NETEASE)
                step == "bm_playlists_qq" -> playlists(app, Platform.QQ)
                step == "bm_playlists_kugou" -> playlists(app, Platform.KUGOU)
                step == "bm_tracks_netease" -> tracks(app, Platform.NETEASE)
                step == "bm_tracks_qq" -> tracks(app, Platform.QQ)
                step == "bm_tracks_kugou" -> tracks(app, Platform.KUGOU)
                step == "bm_next_netease" -> nextTracks(app, Platform.NETEASE)
                step == "bm_next_qq" -> nextTracks(app, Platform.QQ)
                step == "bm_next_kugou" -> nextTracks(app, Platform.KUGOU)
            }
        } catch (_: Throwable) {
            setString(app, "commandStatus", "FAILED")
            snapshot(app)
        }
        lateSnapshot(app, token)
        return true
    }

    private fun clearWindow(app: MusxiApplication) {
        app.accounts.clearRequestTimings()
        preferences(app).edit()
            .putLong("windowStartedElapsedMs", SystemClock.elapsedRealtime())
            .putString("commandStatus", "CLEARED")
            .apply()
        snapshot(app)
    }

    private fun search(app: MusxiApplication, platform: Platform, token: Long) {
        setSearchResult(app, platform, "REQUESTED", -1)
        snapshot(app)
        val callback: (Result<List<SongSummary>>) -> Unit = { response ->
            if (token == operationToken) {
                response.fold(
                    { songs -> setSearchResult(app, platform, "OK", songs.size) },
                    { setSearchResult(app, platform, "FAILED", -1) },
                )
                snapshot(app)
            }
        }
        when (platform) {
            Platform.NETEASE -> app.library.search(SEARCH_QUERY, callback)
            Platform.QQ -> app.qqLibrary.search(SEARCH_QUERY, callback)
            Platform.KUGOU -> app.kugouLibrary.search(SEARCH_QUERY, callback)
        }
    }

    private fun playlists(app: MusxiApplication, platform: Platform) {
        browser(app, platform).playlists(refresh = true)
        setString(app, "commandStatus", "REQUESTED")
        snapshot(app)
    }

    private fun tracks(app: MusxiApplication, platform: Platform) {
        val state = browser(app, platform).state.value
        val playlist = (state.content as? LibraryContent.Playlists)?.page?.items
            ?.firstOrNull { it.trackCount > 0 }
        if (playlist == null) {
            setString(app, "commandStatus", "NO_PLAYLIST")
            snapshot(app)
            return
        }
        browser(app, platform).tracks(playlist.key, refresh = true)
        setString(app, "commandStatus", "REQUESTED")
        snapshot(app)
    }

    private fun nextTracks(app: MusxiApplication, platform: Platform) {
        val state = browser(app, platform).state.value
        val playlist = state.query.playlist
        val nextOffset = (state.content as? LibraryContent.Tracks)?.page?.nextOffset
        if (playlist == null || nextOffset == null) {
            setString(app, "commandStatus", "NO_NEXT_PAGE")
            snapshot(app)
            return
        }
        browser(app, platform).tracks(playlist, nextOffset, refresh = true)
        setString(app, "commandStatus", "REQUESTED")
        snapshot(app)
    }

    private fun readFavorites(app: MusxiApplication, platform: Platform) {
        app.favorites.refresh(platform)
        setString(app, "commandStatus", "REQUESTED")
        snapshot(app)
    }

    private fun beginCommand(app: MusxiApplication, step: String) {
        val prefs = preferences(app)
        prefs.edit()
            .putString("step", step)
            .putLong("revision", prefs.getLong("revision", 0L) + 1L)
            .putLong("commandStartedElapsedMs", SystemClock.elapsedRealtime())
            .putString("commandStatus", "RUNNING")
            .putString("searchPlatform", "NONE")
            .putString("searchStatus", "NONE")
            .putInt("searchCount", -1)
            .apply()
    }

    private fun lateSnapshot(app: MusxiApplication, token: Long) {
        main.postDelayed({
            if (token == operationToken) snapshot(app)
        }, 4_000L)
    }

    private fun snapshot(app: MusxiApplication) {
        val prefs = preferences(app)
        val now = SystemClock.elapsedRealtime()
        val step = currentStep ?: prefs.getString("step", "NONE") ?: "NONE"
        val revision = prefs.getLong("revision", 0L)
        val commandStarted = prefs.getLong("commandStartedElapsedMs", now)
        val windowStarted = prefs.getLong("windowStartedElapsedMs", -1L)
        val edit = prefs.edit().clear()

        edit.putString("step", step)
            .putBoolean("searchBrowserLoading", app.searchBrowser.state.value.loading)
            .putBoolean("searchBrowserHasPage", app.searchBrowser.state.value.page != null)
            .putBoolean("cancelPageUnchanged", app.searchBrowser.state.value.page === cancelledPage)
            .putLong("beforeClearCount", prefs.getLong("beforeClearCount", -1))
            .putLong("beforeClearCompleted", prefs.getLong("beforeClearCompleted", -1))
            .putLong("revision", revision)
            .putLong("commandStartedElapsedMs", commandStarted)
            .putLong("elapsedMs", elapsed(now, commandStarted))
            .putLong("windowStartedElapsedMs", windowStarted)
            .putLong("windowElapsedMs", if (windowStarted >= 0L) elapsed(now, windowStarted) else -1L)
            .putString("commandStatus", prefs.getString("commandStatus", "NONE") ?: "NONE")
            .putString("searchPlatform", prefs.getString("searchPlatform", "NONE") ?: "NONE")
            .putString("searchStatus", prefs.getString("searchStatus", "NONE") ?: "NONE")
            .putInt("searchCount", prefs.getInt("searchCount", -1))

        val metrics = app.accounts.requestTimingSnapshot().associateBy {
            it.platform to it.operation
        }
        Platform.entries.forEach { platform ->
            RequestOperation.entries.forEach { operation ->
                putMetric(edit, platform, operation, metrics[platform to operation])
            }
        }
        Platform.entries.forEach { platform ->
            putLibrary(edit, platform, browser(app, platform).state.value)
            putFavorites(
                edit,
                platform,
                app.favorites.state.value[platform] ?: FavoritesState(),
            )
        }
        edit.apply()
    }

    private fun putMetric(
        edit: SharedPreferences.Editor,
        platform: Platform,
        operation: RequestOperation,
        summary: RequestMetricSummary?,
    ) {
        val prefix = "${platform.name}_${operation.name}"
        edit.putLong("${prefix}_count", summary?.count ?: 0L)
            .putLong("${prefix}_completed", summary?.completed ?: 0L)
            .putLong("${prefix}_success", summary?.completedSuccess ?: 0L)
            .putLong("${prefix}_failed", summary?.completedFailed ?: 0L)
            .putLong("${prefix}_rejected", summary?.completedRejected ?: 0L)
            .putLong("${prefix}_stale", summary?.completedStale ?: 0L)
            .putLong("${prefix}_queueTotalMs", summary?.queueTotalMs ?: 0L)
            .putLong("${prefix}_queueMaxMs", summary?.queueMaxMs ?: 0L)
            .putLong("${prefix}_workTotalMs", summary?.workTotalMs ?: 0L)
            .putLong("${prefix}_workMaxMs", summary?.workMaxMs ?: 0L)
            .putLong("${prefix}_deliveryTotalMs", summary?.deliveryTotalMs ?: 0L)
            .putLong("${prefix}_deliveryMaxMs", summary?.deliveryMaxMs ?: 0L)
    }

    private fun putLibrary(
        edit: SharedPreferences.Editor,
        platform: Platform,
        state: LibraryBrowseState,
    ) {
        val prefix = "library_${platform.name}"
        val count = when (val content = state.content) {
            is LibraryContent.Playlists -> content.page.items.size
            is LibraryContent.Tracks -> content.page.items.size
            null -> -1
        }
        val kind = when (state.content) {
            is LibraryContent.Playlists -> "PLAYLISTS"
            is LibraryContent.Tracks -> "TRACKS"
            null -> "NONE"
        }
        edit.putInt("${prefix}_contentCount", count)
            .putString("${prefix}_contentKind", kind)
            .putInt("${prefix}_offset", state.query.offset)
            .putBoolean("${prefix}_refreshing", state.refreshing)
            .putString("${prefix}_error", safeError(state.error))
            .putString("${prefix}_source", state.source?.name ?: "NONE")
            .putBoolean("${prefix}_stale", state.stale)
            .putLong("${prefix}_invalidationRevision", state.invalidationRevision)
            .putBoolean("${prefix}_storageError", state.storageError)
    }

    private fun putFavorites(
        edit: SharedPreferences.Editor,
        platform: Platform,
        state: FavoritesState,
    ) {
        val prefix = "favorites_${platform.name}"
        edit.putInt("${prefix}_count", state.keys.size)
            .putBoolean("${prefix}_loading", state.loading)
            .putBoolean("${prefix}_hasPending", state.pending != null)
            .putString("${prefix}_error", safeError(state.error))
            .putBoolean("${prefix}_synced", state.synced)
    }

    private fun browser(app: MusxiApplication, platform: Platform): LibraryBrowser = when (platform) {
        Platform.NETEASE -> app.libraryBrowser
        Platform.QQ -> app.qqLibraryBrowser
        Platform.KUGOU -> app.kugouLibraryBrowser
    }

    private fun setSearchResult(
        app: MusxiApplication,
        platform: Platform,
        status: String,
        count: Int,
    ) {
        preferences(app).edit()
            .putString("searchPlatform", platform.name)
            .putString("searchStatus", status)
            .putInt("searchCount", count)
            .apply()
    }

    private fun setString(app: MusxiApplication, key: String, value: String) {
        preferences(app).edit().putString(key, value).apply()
    }

    private fun safeError(error: String?): String =
        error?.takeIf { it in safeErrors } ?: if (error == null) "NONE" else "ERROR"

    private fun elapsed(now: Long, started: Long): Long =
        (now - started).coerceAtLeast(0L)

    private fun preferences(app: MusxiApplication): SharedPreferences =
        app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun isKnownCommand(step: String): Boolean = when (step) {
        "bm_clear",
        "bm_cancel",
        "bm_clear_inflight",
        "bm_lyrics_netease",
        "bm_lyrics_qq",
        "bm_lyrics_kugou",
        "bm_audio_netease",
        "bm_audio_qq",
        "bm_audio_kugou",
        "bm_snapshot",
        "bm_search_netease",
        "bm_search_qq",
        "bm_search_kugou",
        "bm_playlists_netease",
        "bm_playlists_qq",
        "bm_playlists_kugou",
        "bm_tracks_netease",
        "bm_tracks_qq",
        "bm_tracks_kugou",
        "bm_next_netease",
        "bm_next_qq",
        "bm_next_kugou",
        "bm_favorites_read_netease",
        "bm_favorites_read_qq",
        "bm_favorites_read_kugou" -> true
        else -> false
    }

    private val safeErrors = setOf(
        "account_unavailable",
        "account_unverified",
        "account_changed",
        "busy",
        "endpoint",
        "expired",
        "http",
        "identity",
        "incomplete",
        "limit",
        "network",
        "not_synced",
        "permission",
        "platform",
        "protocol",
        "rate",
        "request",
        "risk",
        "timeout",
        "unavailable",
        "unclassified",
    )
}
