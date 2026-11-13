package tech.musxi.player

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Fixed local-only observation, no instrumentation wake loop, settings changes or platform writes. */
internal object LocalAudioBackgroundValidationRunner {
    private val main = Handler(Looper.getMainLooper())
    private var epoch = 0L
    private var started = 0L
    private var previousPosition = -1L
    private var observation = JSONObject()
    private var expectedId: String? = null
    private var screenOffSince: Long? = null
    private var screenReceiver: BroadcastReceiver? = null

    fun run(app: MusxiApplication, step: String): Boolean {
        if (step !in setOf("local_followup_background_begin", "local_followup_background_snapshot", "local_followup_background_abort")) return false
        when (step) {
            "local_followup_background_begin" -> {
                val token = ++epoch
                stopScreenMonitor(app)
                started = SystemClock.elapsedRealtime()
                observation = JSONObject().put("startedAtMs", System.currentTimeMillis()).put("status", "starting")
                    .put("samples", JSONArray()).put("screenEvents", JSONArray()).put("instrumentationRunning", false)
                screenOffSince = null
                screenReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (token != epoch) return
                        val now = SystemClock.elapsedRealtime()
                        screenOffSince = if (intent.action == Intent.ACTION_SCREEN_OFF) now else null
                        observation.getJSONArray("screenEvents").put(JSONObject()
                            .put("elapsedMs", now - started).put("action", intent.action))
                        write(app)
                    }
                }.also { receiver ->
                    val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
                    if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    else app.registerReceiver(receiver, filter)
                }
                previousPosition = -1
                val marker = marker(app)
                val saved = if (marker.isFile) JSONObject(marker.readText()) else null
                expectedId = saved?.optString("backgroundId")?.takeIf { it.isNotBlank() }
                val id = expectedId
                if (id == null) { fail(app, "missing_test_marker"); return true }
                observation.put("audioId", id)
                beginWhenConnected(app, token, id, 0)
            }
            "local_followup_background_snapshot" -> write(app)
            "local_followup_background_abort" -> { ++epoch; fail(app, "aborted") }
        }
        return true
    }

    private fun beginWhenConnected(app: MusxiApplication, token: Long, id: String, tries: Int) {
        if (epoch != token) return
        if (app.localAudio.state.value.ready && app.playback.state.value.connected) {
                app.localAudioPlayback.play(null, id, fromStart = true) { result ->
                    if (token != epoch) return@play
                    if (result.isFailure) {
                        val error = result.exceptionOrNull()
                        observation.put("playError", (error as? tech.musxi.core.localaudio.LocalAudioFailure)?.error?.name ?: error?.javaClass?.simpleName)
                        fail(app, "play_rejected"); return@play
                    }
                    waitReady(app, token, 0)
                }
        } else if (tries >= 150) fail(app, "connection_or_library_not_ready")
        else main.postDelayed({ beginWhenConnected(app, token, id, tries + 1) }, 100)
    }

    private fun waitReady(app: MusxiApplication, token: Long, tries: Int) {
        if (epoch != token) return
        if (app.playback.state.value.let { it.playing && it.localAudio?.id == expectedId }) {
            started = SystemClock.elapsedRealtime()
            screenOffSince = screenOffSince?.coerceAtLeast(started)
            if (app.playback.state.value.durationMs < 850_000) { fail(app, "test_audio_too_short"); return }
            observation.put("status", "observing")
            sample(app, token)
        } else if (tries >= 100) fail(app, "not_playing")
        else main.postDelayed({ waitReady(app, token, tries + 1) }, 100)
    }

    private fun sample(app: MusxiApplication, token: Long) {
        if (epoch != token) return
        val state = app.playback.state.value
        val elapsed = SystemClock.elapsedRealtime() - started
        val interactive = app.getSystemService(PowerManager::class.java).isInteractive
        if (interactive) screenOffSince = null
        else if (screenOffSince == null) screenOffSince = SystemClock.elapsedRealtime()
        val screenOffDuration = screenOffSince?.let { SystemClock.elapsedRealtime() - it.coerceAtLeast(started) } ?: 0L
        val locked = app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        observation.getJSONArray("samples").put(JSONObject().put("elapsedMs", elapsed).put("positionMs", state.positionMs)
            .put("playing", state.playing).put("playWhenReady", state.playWhenReady).put("interactive", interactive)
            .put("keyguardLocked", locked).put("timerActive", app.playback.sleepTimer.value.active)
            .put("durationMs", state.durationMs).put("deviceIdleMode", app.getSystemService(PowerManager::class.java).isDeviceIdleMode)
            .put("error", state.localError?.name ?: state.error ?: JSONObject.NULL))
        if (state.localAudio?.id != expectedId || state.error != null || state.localError != null) { fail(app, "playback_identity_or_error"); return }
        if (!observation.has("timerStartedElapsedMs")) {
            if (!state.playing || state.positionMs <= previousPosition) { fail(app, "background_stopped_or_rewound"); return }
            previousPosition = state.positionMs
            if (elapsed > 900_000) { fail(app, "screen_off_observation_timeout"); return }
            if (screenOffDuration < 300_000) {
                write(app)
                main.postDelayed({ sample(app, token) }, 30_000)
                return
            }
            observation.put("fiveMinuteScreenOffPlayback", true)
                .put("continuousOffStartedElapsedMs", requireNotNull(screenOffSince) - started)
            app.playback.startSleepTimer(1, false) { result ->
                if (token != epoch) return@startSleepTimer
                if (result.isFailure) { fail(app, "timer_rejected"); return@startSleepTimer }
                observation.put("timerStartedElapsedMs", SystemClock.elapsedRealtime() - started)
                write(app)
                main.postDelayed({ sample(app, token) }, 5_000)
            }
        } else {
            val timerElapsed = elapsed - observation.getLong("timerStartedElapsedMs")
            if (interactive || screenOffSince?.minus(started) != observation.getLong("continuousOffStartedElapsedMs")) {
                fail(app, "screen_interrupted_during_timer"); return
            }
            if (state.playWhenReady) {
                if (!state.playing || state.positionMs <= previousPosition) { fail(app, "timer_playback_stalled"); return }
                if (timerElapsed > 80_000) { fail(app, "timer_stop_late"); return }
                previousPosition = state.positionMs
                write(app)
                main.postDelayed({ sample(app, token) }, 5_000)
                return
            }
            if (timerElapsed !in 55_000..80_000 || app.playback.sleepTimer.value.active) { fail(app, "timer_stop_outside_window"); return }
            val rows = observation.getJSONArray("samples")
            val offRows = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { !it.getBoolean("interactive") }
            observation.put("timerStopObservedAfterMs", timerElapsed)
            observation.put("screenOffObservedMs", screenOffDuration).put("screenOffMinuteTimer", true)
                .put("keyguardObserved", offRows.any { it.getBoolean("keyguardLocked") }).put("status", "passed")
            stopScreenMonitor(app)
            write(app)
        }
    }

    private fun fail(app: MusxiApplication, reason: String) {
        observation.put("status", "failed").put("reason", reason)
        if (app.playback.state.value.localAudio?.id == expectedId) {
            app.playback.pause()
            if (observation.has("timerStartedElapsedMs")) app.playback.cancelSleepTimer()
        }
        stopScreenMonitor(app)
        write(app)
    }
    private fun stopScreenMonitor(app: MusxiApplication) {
        screenReceiver?.let(app::unregisterReceiver)
        screenReceiver = null
    }
    private fun marker(app: MusxiApplication) = File(app.filesDir, "local-audio-followup-20261110.json")
    private fun write(app: MusxiApplication) {
        observation.put("updatedAtMs", System.currentTimeMillis())
        File(app.filesDir, "local-audio-followup-background-20261110.json").writeText(observation.toString(2))
    }
}
