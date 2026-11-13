package tech.musxi.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import tech.musxi.core.settings.LockScreenLyricsMode

/** Fixed DUMP-protected steps. Never grants permissions, touches keyguard, or changes playback. */
internal object LockScreenPageProbeValidation {
    private const val PREFS = "lockscreen-page-probe"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private var observer: Job? = null
    private var expiry: Runnable? = null

    fun run(app: MusxiApplication, step: String): Boolean {
        if (step !in setOf("lock_page_arm", "lock_page_status", "lock_page_off")) return false
        when (step) {
            "lock_page_arm" -> arm(app)
            "lock_page_off" -> off(app)
            else -> { app.lockScreenLyrics.refreshPermissions(); report(app) }
        }
        return true
    }

    private fun arm(app: MusxiApplication) {
        val marker = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (marker.getBoolean("armed", false)) { report(app); return }
        val before = app.settings.value
        check(marker.edit().putBoolean("armed", true)
            .putString("previousMode", before.lockScreenLyricsMode?.name)
            .putBoolean("previousCard", before.mediaCardLyrics).commit())
        try {
            app.settingsRepository.setLockScreenLyricsMode(LockScreenLyricsMode.PAGE)
        } catch (error: RuntimeException) {
            marker.edit().putBoolean("armed", false).commit()
            throw error
        }
        observer?.cancel()
        observer = scope.launch { app.lockScreenLyrics.state.collect { report(app) } }
        expiry?.let(main::removeCallbacks)
        expiry = Runnable { off(app) }.also { main.postDelayed(it, 10 * 60_000L) }
        app.lockScreenLyrics.refreshPermissions()
        report(app)
    }

    internal fun recoverAfterProcessStart(app: MusxiApplication) {
        // A killed probe must not leave PAGE selected or automatically re-arm after a restart.
        if (app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("armed", false)) off(app)
    }

    private fun off(app: MusxiApplication) {
        val marker = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (marker.getBoolean("armed", false)) {
            if (app.settings.value.effectiveLockScreenLyricsMode == LockScreenLyricsMode.PAGE) {
                val mode = marker.getString("previousMode", null)?.let { stored ->
                    LockScreenLyricsMode.entries.firstOrNull { it.name == stored }
                }
                val card = marker.getBoolean("previousCard", false)
                app.settingsRepository.update { it.copy(lockScreenLyricsMode = mode, mediaCardLyrics = card) }
            }
            check(marker.edit().putBoolean("armed", false).commit())
        }
        observer?.cancel(); observer = null
        expiry?.let(main::removeCallbacks); expiry = null
        app.lockScreenLyrics.refreshPermissions()
        report(app)
    }

    private fun report(app: MusxiApplication) {
        val state = app.lockScreenLyrics.state.value
        val env = state.environment
        val report = JSONObject().put("sampledAtEpochMs", System.currentTimeMillis())
            .put("phase", state.phase.name).put("mode", app.settings.value.effectiveLockScreenLyricsMode.name)
            .put("armed", app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("armed", false))
            .put("hostAvailable", env.hostAvailable).put("overlayGranted", env.overlayGranted)
            .put("interactive", env.interactive).put("locked", env.locked)
            .put("hasMedia", env.hasMedia).put("playing", env.playing)
            .put("cycle", state.cycle).put("launchAttempts", state.launchAttempts)
            .put("confirmedDisplays", state.confirmedDisplays)
        File(app.filesDir, "lockscreen-page-probe-report.json").writeText(report.toString(2))
        android.util.Log.i("MusxiLockPageProbe", report.toString())
    }
}
