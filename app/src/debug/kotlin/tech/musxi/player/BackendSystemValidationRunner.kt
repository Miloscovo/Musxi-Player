package tech.musxi.player

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import tech.musxi.core.library.MediaKey
import java.util.Locale

/** Invokes the app's actual notification PendingIntent. No coordinate input or global settings. */
internal object BackendSystemValidationRunner {
    private val main = Handler(Looper.getMainLooper())
    private var command = "NONE"
    private var revision = 0L
    private var status = "NONE"
    private var started = 0L
    private var startPosition = 0L
    private var startTransitions = 0
    private var source: MediaKey? = null
    private var queue = emptyList<MediaKey>()
    private var actionSent = false

    fun run(app: MusxiApplication, step: String): Boolean {
        if (step !in setOf("ss_snapshot", "ss_long_begin", "ss_notification_play", "ss_notification_pause",
                "ss_foreground_hold", "ss_foreground_fast_timeout", "ss_foreground_reset")) return false
        if (step != "ss_snapshot") {
            command = step; ++revision; status = "REQUESTED"; actionSent = false
        }
        when (step) {
            "ss_foreground_hold", "ss_foreground_fast_timeout", "ss_foreground_reset" -> {
                status = if (tech.musxi.playback.PlaybackServiceValidation.foreground(
                        hold = step == "ss_foreground_hold", fastTimeout = step == "ss_foreground_fast_timeout"))
                    "INJECTION_SET" else "NO_SERVICE"
            }
            "ss_long_begin" -> {
                val p = app.playback.state.value
                started = SystemClock.elapsedRealtime(); startPosition = p.positionMs
                startTransitions = p.automaticTransitions; source = p.sourceKey
                queue = app.playback.queueState.value.songs.map { it.key }
                status = if (p.playing && source != null) "OBSERVING" else "PRECONDITION"
            }
            "ss_notification_play", "ss_notification_pause" -> {
                val play = step == "ss_notification_play"
                val notification = notifications(app).firstOrNull { it.id == 1001 }?.notification
                val actions = notification?.actions.orEmpty()
                val labels = if (play) setOf("play", "播放") else setOf("pause", "暂停")
                val action = actions.firstOrNull {
                    it.title?.toString()?.trim()?.lowercase(Locale.ROOT) in labels
                }
                if (action == null || action.actionIntent?.creatorPackage != app.packageName) status = "NO_ACTION"
                else {
                    status = try { action.actionIntent.send(); actionSent = true; "SENT" }
                    catch (_: Exception) { "SEND_FAILED" }
                }
            }
        }
        snapshot(app)
        val token = revision
        main.postDelayed({ if (token == revision) snapshot(app) }, 1000)
        return true
    }

    private fun notifications(app: MusxiApplication) =
        app.getSystemService(NotificationManager::class.java).activeNotifications.filter { it.packageName == app.packageName }

    private fun snapshot(app: MusxiApplication) {
        val p = app.playback.state.value
        val power = app.getSystemService(PowerManager::class.java)
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        val notifications = notifications(app)
        val foreground = tech.musxi.playback.PlaybackServiceValidation.foregroundState()
        val edit = app.getSharedPreferences("backend-system-validation", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("serviceForeground", foreground?.first == true)
            .putBoolean("foregroundGatePending", foreground?.second == true)
            .putString("command", command).putLong("revision", revision).putString("status", status)
            .putLong("snapshotElapsedMs", SystemClock.elapsedRealtime())
            .putBoolean("actionSent", actionSent).putInt("notificationCount", notifications.size)
            .putInt("notificationActions", notifications.firstOrNull { it.id == 1001 }?.notification?.actions?.size ?: 0)
            .putBoolean("interactive", power.isInteractive).putBoolean("deviceIdle", power.isDeviceIdleMode)
            .putBoolean("keyguardLocked", keyguard.isKeyguardLocked).putBoolean("deviceLocked", keyguard.isDeviceLocked)
            .putBoolean("deviceSecure", keyguard.isDeviceSecure).putBoolean("playing", p.playing)
            .putBoolean("wanted", p.playWhenReady).putBoolean("buffering", p.buffering)
            .putString("suppression", p.suppression.name).putString("failure", p.failure?.name ?: "NONE")
            .putString("connection", p.connectionPhase.name).putString("sourcePlatform", p.sourcePlatform?.name ?: "NONE")
            .putInt("queueSize", p.queueSize).putLong("positionMs", p.positionMs)
            .putLong("durationMs", p.durationMs).putString("repeatMode", p.repeatMode.name)
        if (started > 0) edit.putLong("observedMs", SystemClock.elapsedRealtime() - started)
            .putLong("startPositionMs", startPosition).putInt("newAutomaticTransitions", p.automaticTransitions - startTransitions)
            .putBoolean("sameSource", source == p.sourceKey)
            .putBoolean("sameQueue", queue == app.playback.queueState.value.songs.map { it.key })
        edit.apply()
    }
}
