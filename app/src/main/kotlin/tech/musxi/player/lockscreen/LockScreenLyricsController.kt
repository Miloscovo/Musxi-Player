package tech.musxi.player.lockscreen

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tech.musxi.core.settings.LockScreenLyricsMode
import tech.musxi.player.MusxiApplication

/** Android boundary for the policy. The front end supplies a private Activity subclass in a later change. */
class LockScreenLyricsController(
    private val app: MusxiApplication,
    private val hostClass: Class<out LockScreenLyricsHostActivity>?,
) {
    private val session = LockScreenLyricsSession()
    private val mutable = MutableStateFlow(session.state)
    val state = mutable.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private var host = WeakReference<LockScreenLyricsHostActivity>(null)
    private var collection: Job? = null
    private var registered = false
    private var pendingLaunch: Runnable? = null
    private var timeout: Runnable? = null
    private val keyguard get() = app.getSystemService(KeyguardManager::class.java)
    private val power get() = app.getSystemService(PowerManager::class.java)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> update(LockScreenLyricsEvent.SCREEN_OFF)
                Intent.ACTION_SCREEN_ON -> scheduleScreenOn()
                Intent.ACTION_USER_PRESENT -> update(LockScreenLyricsEvent.USER_PRESENT)
            }
        }
    }
    private val checkVisible = object : Runnable {
        override fun run() {
            update()
            if (host.get() != null) main.postDelayed(this, 1_000)
        }
    }

    fun start(scope: CoroutineScope) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (collection != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(receiver, filter)
        registered = true
        collection = scope.launch {
            val availability = app.playback.state.map {
                listOf(it.connected, it.mediaId, it.ended, it.stopped, it.error, it.localError, it.playing)
            }.distinctUntilChanged()
            combine(app.settings, availability) { _, _ -> Unit }.collect { update() }
        }
        update()
    }

    /** Call on returning from system settings; no app-op or device setting is changed here. */
    fun refreshPermissions() { update() }

    fun overlayPermissionIntent(): Intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${app.packageName}"))

    fun applicationSettingsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${app.packageName}"))

    private fun environment(): LockScreenLyricsEnvironment {
        val playback = app.playback.state.value
        return LockScreenLyricsEnvironment(
            enabled = app.settings.value.effectiveLockScreenLyricsMode == LockScreenLyricsMode.PAGE,
            hostAvailable = hostClass?.let { klass ->
                runCatching {
                    @Suppress("DEPRECATION")
                    val info = app.packageManager.getActivityInfo(ComponentName(app, klass), 0)
                    info.enabled && !info.exported && info.applicationInfo.enabled
                }.getOrDefault(false)
            } ?: false,
            overlayGranted = Settings.canDrawOverlays(app),
            interactive = power?.isInteractive == true,
            locked = keyguard?.isKeyguardLocked == true,
            hasMedia = playback.connected && playback.mediaId != null && !playback.ended && !playback.stopped &&
                playback.error == null && playback.localError == null,
            playing = playback.playing,
        )
    }

    private fun scheduleScreenOn() {
        pendingLaunch?.let(main::removeCallbacks)
        // Keyguard state may follow SCREEN_ON. One bounded delay, never an automatic retry loop.
        pendingLaunch = Runnable {
            pendingLaunch = null
            update(LockScreenLyricsEvent.SCREEN_ON)
        }.also { main.postDelayed(it, 300) }
    }

    private fun update(event: LockScreenLyricsEvent = LockScreenLyricsEvent.STATE_CHANGED) {
        if (event == LockScreenLyricsEvent.SCREEN_OFF || event == LockScreenLyricsEvent.USER_PRESENT) {
            pendingLaunch?.let(main::removeCallbacks)
            pendingLaunch = null
        }
        when (session.update(environment(), event)) {
            LockScreenLyricsEffect.LAUNCH -> launch(requireNotNull(session.state.requestId))
            LockScreenLyricsEffect.CLOSE -> closeHost()
            LockScreenLyricsEffect.NONE -> Unit
        }
        mutable.value = session.state
    }

    private fun launch(requestId: Long) {
        val klass = hostClass ?: return
        try {
            app.startActivity(Intent(app, klass)
                .putExtra(REQUEST_ID, requestId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            timeout?.let(main::removeCallbacks)
            timeout = Runnable {
                if (session.timedOut(requestId)) { closeHost(); mutable.value = session.state }
            }.also { main.postDelayed(it, 3_000) }
        } catch (_: RuntimeException) {
            session.failed(requestId)
        }
    }

    internal fun attach(activity: LockScreenLyricsHostActivity, requestId: Long): Boolean {
        update()
        if (!session.accepts(requestId)) return false
        if (host.get() !== activity) host.get()?.finish()
        host = WeakReference(activity)
        main.removeCallbacks(checkVisible)
        main.postDelayed(checkVisible, 1_000)
        return true
    }

    internal fun displayed(activity: LockScreenLyricsHostActivity, requestId: Long): Boolean {
        update()
        if (host.get() !== activity || !session.displayed(requestId)) return false
        timeout?.let(main::removeCallbacks)
        timeout = null
        mutable.value = session.state
        return true
    }

    internal fun dismiss(requestId: Long) {
        if (session.dismiss(requestId)) { closeHost(); mutable.value = session.state }
    }

    internal fun detach(activity: LockScreenLyricsHostActivity) {
        if (host.get() !== activity) return
        host.clear()
        main.removeCallbacks(checkVisible)
        val requestId = session.state.requestId ?: return
        if (!activity.isChangingConfigurations) {
            session.dismiss(requestId)
            mutable.value = session.state
        }
    }

    private fun closeHost() {
        timeout?.let(main::removeCallbacks)
        timeout = null
        main.removeCallbacks(checkVisible)
        host.get()?.finishAndRemoveTask()
        host.clear()
    }

    internal fun close() {
        collection?.cancel(); collection = null
        if (registered) { app.unregisterReceiver(receiver); registered = false }
        pendingLaunch?.let(main::removeCallbacks); pendingLaunch = null
        closeHost()
    }

    internal companion object { const val REQUEST_ID = "lockscreen_request_id" }
}
