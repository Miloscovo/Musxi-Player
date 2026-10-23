package tech.musxi.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import android.content.ComponentName
import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibraryContent
import tech.musxi.core.library.SongSummary
import tech.musxi.playback.MusxiPlaybackService
import tech.musxi.playback.PlaybackConnectionValidation
import java.util.concurrent.Executor

/** Fixed commands; platform favorite writes require the explicitly armed session below. */
internal object BackendEveningValidationRunner {
    private val main = Handler(Looper.getMainLooper())
    private var keeper: MediaController? = null
    private var command = "NONE"
    private var revision = 0L
    private var callbacks = 0
    private var callbackFailures = 0
    private var savedQueue: List<SongSummary> = emptyList()
    private var savedSource: tech.musxi.core.library.MediaKey? = null
    private var savedPosition = 0L
    private var savedResolve = 0
    private var savedQueueRevision = 0L
    private var serviceQueueLossAccepted = false
    private data class FavoriteCheck(val song: SongSummary, val keys: Set<tech.musxi.core.library.MediaKey>,
        val identity: tech.musxi.core.library.LibraryCacheIdentity, var submitted: Boolean = false)
    private val favoriteChecks = mutableMapOf<Platform, FavoriteCheck>()

    fun run(app: MusxiApplication, step: String): Boolean {
        if (!step.startsWith("be_")) return false
        if (step != "be_snapshot") { command = step; revision++ }
        val favoritePlatform = Platform.entries.firstOrNull { step.endsWith("_${it.name.lowercase()}") }
        if (favoritePlatform != null) {
            val platform = favoritePlatform
            val browser = when (platform) { Platform.NETEASE -> app.libraryBrowser; Platform.QQ -> app.qqLibraryBrowser; Platform.KUGOU -> app.kugouLibraryBrowser }
            val state = app.favorites.state.value.getValue(platform)
            val owner = app.accounts.playbackIdentity(platform)
            val debug = app as DebugMusxiApplication
            val fetch = when (platform) { Platform.NETEASE -> debug.validationLibrary; Platform.QQ -> debug.validationQqLibrary; Platform.KUGOU -> debug.validationKugouLibrary }
            when {
                step.startsWith("be_fav_sync_") -> app.favorites.refresh(platform)
                step.startsWith("be_fav_prepare_") -> {
                    if (favoriteChecks[platform]?.let { it.submitted && state.keys != it.keys } == true) {
                        snapshot(app); return true
                    }
                    val songs = (browser.state.value.content as? LibraryContent.Tracks)?.page?.items
                    val song = songs?.firstOrNull { it.key in state.keys } ?: songs?.firstOrNull()
                        ?: app.playbackJournal.state.value.getValue(platform).queue?.songs?.firstOrNull()
                    if (song != null && owner?.verified == true && state.synced && !state.loading && state.pending == null) {
                        val check = FavoriteCheck(song, state.keys.toSet(), owner)
                        favoriteChecks[platform] = check
                        // Recovery stays inside the app sandbox; never copied into evidence.
                        val json = org.json.JSONObject().put("platform", platform.name).put("song", song.key.id)
                            .put("title", song.title).put("album", song.album).put("duration", song.durationMs)
                            .put("artists", org.json.JSONArray(song.artists)).put("owner", owner.owner).put("generation", owner.generation)
                            .put("keys", org.json.JSONArray(state.keys.map { it.id }))
                        java.io.File(app.noBackupFilesDir, "evening-favorite-${platform.name}.json").writeText(json.toString())
                    }
                }
                step.startsWith("be_fav_toggle_") -> favoriteChecks[platform]?.let { check ->
                    if (!check.submitted && owner?.sameAttempt(check.identity) == true && state.synced && !state.loading && state.keys == check.keys) {
                        check.submitted = true
                        app.favorites.setLiked(check.song, check.song.key !in check.keys)
                    }
                }
                step.startsWith("be_fav_restore_") -> favoriteChecks[platform]?.let { check ->
                    if (check.submitted && owner?.sameAttempt(check.identity) == true && state.synced && !state.loading && state.pending == null)
                        app.favorites.setLiked(check.song, check.song.key in check.keys)
                }
                step.startsWith("be_fav_idempotent_") -> favoriteChecks[platform]?.let { check ->
                    if (owner?.sameAttempt(check.identity) == true && state.synced && !state.loading)
                        app.favorites.setLiked(check.song, check.song.key in state.keys)
                }
                step.startsWith("be_page_off_") -> fetch.offline = true
                step.startsWith("be_page_on_") -> fetch.offline = false
                step.startsWith("be_page_refresh_") -> browser.refresh()
                step.startsWith("be_invalidate_") -> owner?.let(browser::invalidate)
            }
        }
        when (step) {
            "be_baseline" -> {
                savedQueue = app.playback.queueState.value.songs.toList()
                savedSource = app.playback.state.value.sourceKey
                savedPosition = app.playback.state.value.positionMs
                savedQueueRevision = app.playback.queueState.value.revision
                savedResolve = (app as DebugMusxiApplication).validationSources.resolveCount
                callbacks = 0; callbackFailures = 0
            }
            "be_keeper" -> {
                keeper?.release(); keeper = null
                val token = revision
                val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, MusxiPlaybackService::class.java))).buildAsync()
                future.addListener({ runCatching { future.get() }.onSuccess {
                    if (token == revision) keeper = it else it.release()
                    snapshot(app)
                } }, Executor { main.post(it) })
            }
            "be_keeper_release" -> { keeper?.release(); keeper = null }
            "be_clear_service_queue" -> {
                serviceQueueLossAccepted = tech.musxi.playback.PlaybackServiceValidation.loseQueue()
            }
            "be_resume" -> app.playback.resume()
            "be_pause" -> app.playback.pause()
            "be_seek_end" -> app.playback.state.value.durationMs.takeIf { it > 10_000 }?.let { app.playback.seekTo(it - 2_000) }
            "be_next" -> app.playback.next()
            "be_repeat_all" -> app.playback.setRepeatMode(tech.musxi.playback.PlaybackRepeatMode.ALL)
            "be_repeat_off" -> app.playback.setRepeatMode(tech.musxi.playback.PlaybackRepeatMode.OFF)
            "be_disconnect" -> PlaybackConnectionValidation.disconnect(app.playback)
            "be_block_fail" -> PlaybackConnectionValidation.blockConnections(app.playback, false)
            "be_block_timeout" -> PlaybackConnectionValidation.blockConnections(app.playback, true)
            "be_allow" -> PlaybackConnectionValidation.allowConnections(app.playback)
            "be_retry" -> app.playback.retryConnection()
            "be_retry_burst" -> repeat(10) { app.playback.retryConnection() }
            "be_ack_arm" -> PlaybackConnectionValidation.lateAckDisconnect(app.playback)
            "be_edit_noop" -> {
                val q = app.playback.queueState.value
                val i = app.playback.state.value.queueIndex
                app.playback.moveQueueItem(i, i, q.revision) { callbacks++; if(it.isFailure) callbackFailures++; snapshot(app) }
            }
            "be_snapshot" -> Unit
        }
        snapshot(app)
        main.postDelayed({ snapshot(app) }, 700)
        return true
    }

    private fun snapshot(app: MusxiApplication) {
        val p = app.playback.state.value
        val q = app.playback.queueState.value
        val edit = app.getSharedPreferences("backend-evening-validation", Context.MODE_PRIVATE).edit()
            .putString("step", command).putLong("revision", revision).putLong("elapsedMs", SystemClock.elapsedRealtime())
            .putString("phase", p.connectionPhase.name).putInt("attempt", p.connectionAttempt)
            .putBoolean("connected", p.connected).putBoolean("canRetry", p.canRetryConnection)
            .putBoolean("playing", p.playing).putBoolean("wanted", p.playWhenReady)
            .putBoolean("buffering", p.buffering).putBoolean("ended", p.ended)
            .putString("suppression", p.suppression.name).putString("failure", p.failure?.name ?: "NONE")
            .putBoolean("serviceQueueLossAccepted", serviceQueueLossAccepted)
            .putInt("queueSize", p.queueSize).putInt("queueIndex", p.queueIndex).putLong("positionMs", p.positionMs)
            .putLong("durationMs", p.durationMs).putLong("queueRevision", q.revision)
            .putInt("automaticTransitions", p.automaticTransitions).putBoolean("keeper", keeper != null)
            .putBoolean("queueMatches", savedQueue == q.songs).putBoolean("sourceMatches", savedSource == p.sourceKey)
            .putBoolean("positionMatches", savedPosition == p.positionMs).putBoolean("revisionMatches", savedQueueRevision == q.revision)
            .putInt("baselineResolve", savedResolve).putInt("resolveCount", (app as DebugMusxiApplication).validationSources.resolveCount)
            .putInt("injectedFailures", app.validationSources.injectedCount)
            .putString("queuePlatform", p.queuePlatform?.name ?: "NONE").putString("sourcePlatform", p.sourcePlatform?.name ?: "NONE")
            .putInt("callbacks", callbacks).putInt("callbackFailures", callbackFailures)
        val connection = PlaybackConnectionValidation.snapshot(app.playback)
        edit.putString("gate", connection.gate.name).putInt("controllersCreated", connection.controllerCreatedCount)
            .putInt("controllersReleased", connection.controllerReleasedCount).putInt("queueSubmits", connection.queueCommandSubmitCount)
            .putInt("pendingAcks", connection.pendingCommandCount).putBoolean("ackArmed", connection.ackDelayArmed)
            .putString("connectionEvents", connection.events.joinToString(";") { "${it.phase}:${it.attempt}:${it.elapsedMs}" })
        keeper?.let { k -> edit.putBoolean("keeperPlaying", k.isPlaying).putBoolean("keeperWanted", k.playWhenReady)
            .putInt("keeperQueueSize", k.mediaItemCount).putLong("keeperPosition", k.currentPosition) }
        Platform.entries.forEach { platform ->
            val browser = when (platform) { Platform.NETEASE -> app.libraryBrowser; Platform.QQ -> app.qqLibraryBrowser; Platform.KUGOU -> app.kugouLibraryBrowser }
            val s = browser.state.value
            val f = app.favorites.state.value.getValue(platform)
            edit.putString("${platform}_phase", app.accounts.states.value.getValue(platform).phase.name)
                .putLong("${platform}_invalidation", s.invalidationRevision).putBoolean("${platform}_stale", s.stale)
                .putBoolean("${platform}_refreshing", s.refreshing).putString("${platform}_libraryError", s.error ?: "NONE")
                .putBoolean("${platform}_storageError", s.storageError).putBoolean("${platform}_content", s.content != null)
                .putInt("${platform}_offset", s.query.offset).putString("${platform}_source", s.source?.name ?: "NONE")
                .putInt("${platform}_favorites", f.keys.size).putBoolean("${platform}_synced", f.synced)
                .putBoolean("${platform}_favoriteBusy", f.loading || f.pending != null).putString("${platform}_favoriteError", f.error ?: "NONE")
            favoriteChecks[platform]?.let { check ->
                edit.putBoolean("${platform}_favoritePrepared", true).putBoolean("${platform}_favoriteSubmitted", check.submitted)
                    .putBoolean("${platform}_favoriteRestored", f.synced && !f.loading && f.pending == null && f.keys == check.keys)
                    .putBoolean("${platform}_favoriteToggled", f.synced && !f.loading && f.pending == null &&
                        (check.song.key in f.keys) != (check.song.key in check.keys) &&
                        (f.keys - check.keys).all { it == check.song.key } && (check.keys - f.keys).all { it == check.song.key })
            }
        }
        edit.apply()
    }
}
