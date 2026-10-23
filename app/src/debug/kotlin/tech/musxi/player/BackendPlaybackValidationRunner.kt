package tech.musxi.player

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import tech.musxi.playback.MusxiPlaybackService
import tech.musxi.playback.PlaybackConnectionValidation
import java.util.concurrent.Executor

/** Real controller failure injection through the public playSong use case; no platform writes. */
internal object BackendPlaybackValidationRunner {
    private val main = Handler(Looper.getMainLooper())
    private var keeper: MediaController? = null
    private var token = 0L
    private var phase = "NOT_RUN"
    private var originalIds = emptyList<String>()
    private var beforeSubmits = 0
    private var beforeResolves = 0
    private var immediateFailure = false
    private var started = 0L

    fun run(app: MusxiApplication, step: String): Boolean {
        if (step !in setOf("bp_disconnected_play", "bp_reconnect", "bp_snapshot", "bp_release")) return false
        when (step) {
            "bp_disconnected_play" -> {
                if (keeper != null || app.playback.state.value.playing || !app.playback.state.value.connected) {
                    phase = "PRECONDITION"; snapshot(app); return true
                }
                val song = app.playback.queueState.value.songs.firstOrNull()
                if (song == null) { phase = "NO_QUEUE"; snapshot(app); return true }
                val epoch = ++token
                started = SystemClock.elapsedRealtime(); phase = "CONNECTING_KEEPER"
                val future = MediaController.Builder(app, SessionToken(app,
                    ComponentName(app, MusxiPlaybackService::class.java))).buildAsync()
                future.addListener({ runCatching { future.get() }.fold({ controller ->
                    if (epoch != token) { controller.release(); return@fold }
                    keeper = controller
                    originalIds = (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId }
                    beforeSubmits = PlaybackConnectionValidation.snapshot(app.playback).queueCommandSubmitCount
                    beforeResolves = (app as DebugMusxiApplication).validationSources.resolveCount
                    PlaybackConnectionValidation.blockConnections(app.playback, true)
                    app.queuePlayback.playSong(song)
                    immediateFailure = !app.queuePlayback.state.value.loading && app.queuePlayback.state.value.error == "player"
                    phase = "DISCONNECTED_PLAY_REJECTED"
                    snapshot(app)
                }, { phase = "KEEPER_FAILED"; snapshot(app) }) }, Executor { main.post(it) })
            }
            "bp_reconnect" -> {
                if (keeper == null) { phase = "PRECONDITION"; snapshot(app); return true }
                PlaybackConnectionValidation.allowConnections(app.playback)
                app.playback.retryConnection(); phase = "RECONNECT_REQUESTED"
                val epoch = token
                main.postDelayed({ if (epoch == token) { phase = "AFTER_RECONNECT"; snapshot(app) } }, 3000)
            }
            "bp_release" -> { ++token; keeper?.release(); keeper = null; phase = "RELEASED" }
        }
        snapshot(app)
        return true
    }

    private fun snapshot(app: MusxiApplication) {
        val k = keeper
        val p = app.playback.state.value
        val c = PlaybackConnectionValidation.snapshot(app.playback)
        val ids = k?.let { (0 until it.mediaItemCount).map { i -> it.getMediaItemAt(i).mediaId } }
        val unchanged = ids != null && ids == originalIds
        val submits = c.queueCommandSubmitCount - beforeSubmits
        val resolves = (app as DebugMusxiApplication).validationSources.resolveCount - beforeResolves
        val settled = !app.queuePlayback.state.value.loading
        app.getSharedPreferences("backend-playback-validation", Context.MODE_PRIVATE).edit().clear()
            .putString("phase", phase).putLong("elapsedMs", SystemClock.elapsedRealtime() - started)
            .putBoolean("injected", true).putBoolean("keeper", k != null)
            .putBoolean("immediateFailure", immediateFailure).putBoolean("loadSettled", settled)
            .putString("loadError", app.queuePlayback.state.value.error ?: "NONE")
            .putBoolean("serviceQueueUnchanged", unchanged).putInt("queueSize", k?.mediaItemCount ?: -1)
            .putBoolean("servicePlaying", k?.isPlaying == true).putBoolean("serviceWanted", k?.playWhenReady == true)
            .putInt("newQueueSubmits", submits).putInt("newResolves", resolves)
            .putInt("pendingAcks", c.pendingCommandCount).putString("connection", p.connectionPhase.name)
            .putBoolean("pass", immediateFailure && settled && unchanged && submits == 0 && resolves == 0 &&
                c.pendingCommandCount == 0 && k != null && !k.playWhenReady &&
                (phase == "DISCONNECTED_PLAY_REJECTED" || phase == "AFTER_RECONNECT" && p.connected))
            .apply()
    }
}
