package tech.musxi.player

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.concurrent.Executor
import tech.musxi.playback.MusxiPlaybackService
import tech.musxi.playback.PlaybackRepeatMode
import tech.musxi.playback.PlaybackState

/** Fixed mode checks. Queue keys/metadata remain in memory; diagnostics contain comparisons only. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object P1ValidationRunner {
    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var queueKeys: List<String>? = null
    private var watched: PlaybackState? = null
    private var revision = 0L

    fun run(app: MusxiApplication, step: String): Boolean {
        if (!step.startsWith("p1_")) return false
        val diag = app.getSharedPreferences("p1-validation", Context.MODE_PRIVATE)
        val token = ++revision
        fun capture(player: MediaController) {
            if (token != revision) return
            val keys = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
            if (step == "p1_watch") {
                queueKeys = keys
                watched = app.playback.state.value
            }
            when (step) {
                "p1_repeat_off" -> app.playback.setRepeatMode(PlaybackRepeatMode.OFF)
                "p1_repeat_one" -> app.playback.setRepeatMode(PlaybackRepeatMode.ONE)
                "p1_repeat_all" -> app.playback.setRepeatMode(PlaybackRepeatMode.ALL)
                "p1_shuffle_on" -> app.playback.setShuffleEnabled(true)
                "p1_shuffle_off" -> app.playback.setShuffleEnabled(false)
            }
            // Read back controller-owned state after MediaSession delivers its command.
            main.postDelayed({
                if (token == revision) {
                    val state = app.playback.state.value
                    val before = watched
                    val currentKeys = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
                    diag.edit().putString("step", step).putLong("revision", token)
                        .putBoolean("connected", state.connected).putBoolean("canSetModes", state.canSetPlaybackModes)
                        .putString("repeatMode", state.repeatMode.name).putBoolean("shuffleEnabled", state.shuffleEnabled)
                        .putInt("queueSize", state.queueSize).putInt("queueIndex", state.queueIndex)
                        .putBoolean("queueOrderMatches", queueKeys != null && queueKeys == currentKeys)
                        .putBoolean("watchedKeyMatches", before != null && before.mediaId == state.mediaId)
                        .putBoolean("watchedSourceMatches", before != null && before.sourcePlatform == state.sourcePlatform)
                        .putBoolean("watchedMetadataMatches", before != null && before.title == state.title &&
                            before.artist == state.artist && before.album == state.album && before.coverUrl == state.coverUrl)
                        .putString("queuePlatform", state.queuePlatform?.name ?: "NONE")
                        .putString("sourcePlatform", state.sourcePlatform?.name ?: "NONE")
                        .putBoolean("playing", state.playing).putBoolean("wanted", state.playWhenReady)
                        .putBoolean("ended", state.ended).putLong("position", state.positionMs)
                        .putInt("automaticTransitions", state.automaticTransitions)
                        .putString("failure", state.failure?.name ?: "NONE")
                        .putString("selectionPhase", app.sourceSelection.state.value.phase.name)
                        .apply()
                }
            }, 150)
        }
        val existing = controller
        if (existing != null) capture(existing)
        else {
            val future = MediaController.Builder(app, SessionToken(app,
                ComponentName(app, MusxiPlaybackService::class.java))).buildAsync()
            future.addListener({
                try {
                    val built = future.get()
                    if (controller == null) controller = built else built.release()
                    capture(controller!!)
                } catch (_: Exception) {
                    if (token == revision) diag.edit().putString("step", step).putLong("revision", token)
                        .putBoolean("connected", false).apply()
                }
            }, Executor { main.post(it) })
        }
        return true
    }
}
