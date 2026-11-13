package tech.musxi.player.lockscreen

import android.os.Bundle
import android.content.Intent
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import tech.musxi.player.MusxiApplication

/**
 * Lifecycle-only host for a future front-end subclass. It never dismisses the system keyguard,
 * turns the display on, keeps it awake, starts playback, or opens the main application.
 * Declare the concrete host non-exported, singleTask, in its own task and excluded from recents.
 */
abstract class LockScreenLyricsHostActivity : ComponentActivity() {
    private val controller get() = (application as MusxiApplication).lockScreenLyrics
    private var requestId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        requestId = intent.getLongExtra(LockScreenLyricsController.REQUEST_ID, -1L)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = dismissToSystemLockScreen()
        })
    }

    override fun onStart() {
        super.onStart()
        if (!controller.attach(this, requestId)) finishAndRemoveTask()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestId = intent.getLongExtra(LockScreenLyricsController.REQUEST_ID, -1L)
        if (!controller.attach(this, requestId)) finishAndRemoveTask()
        else if (hasWindowFocus() && !controller.displayed(this, requestId)) finishAndRemoveTask()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isFinishing && !controller.displayed(this, requestId)) finishAndRemoveTask()
    }

    /** Bind the four-direction swipe and accessibility close action to this method. */
    fun dismissToSystemLockScreen() { controller.dismiss(requestId) }

    override fun onDestroy() {
        controller.detach(this)
        super.onDestroy()
    }
}
