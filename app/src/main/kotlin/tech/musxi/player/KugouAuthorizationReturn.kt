package tech.musxi.player

import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform

/** Visible, temporary authorization status using an already granted overlay permission. */
internal class KugouAuthorizationReturn(private val app: MusxiApplication) {
    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var generation = -1L
    private val windows get() = app.getSystemService(WindowManager::class.java)
    private val observe = object : Runnable {
        override fun run() {
            val state = app.accounts.states.value.getValue(Platform.KUGOU)
            // The repository's completion delivery is already queued on main after publishing CONNECTED.
            if (state.generation == generation && state.phase == AccountPhase.CONNECTED) {
                main.postDelayed({ close() }, 1000); return
            }
            if (state.generation != generation || state.phase !in setOf(AccountPhase.WAITING_CONFIRMATION, AccountPhase.VERIFYING)) {
                close(); return
            }
            main.postDelayed(this, 500)
        }
    }
    fun start(token: Long) {
        close()
        if (!Settings.canDrawOverlays(app)) return
        generation = token
        val density = app.resources.displayMetrics.density
        val label = TextView(app).apply {
            text = "等待酷狗授权 · 返回 Musxi"
            textSize = 13f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0xE629333B.toInt())
            val pad = (12 * density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            setOnClickListener {
                app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                close()
            }
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (72 * density).toInt()
        }
        try {
            windows.addView(label, params); view = label
            main.post(observe)
            main.postDelayed({ close() }, 175_000)
        } catch (_: Exception) { close() }
    }
    fun close() {
        main.removeCallbacksAndMessages(null)
        view?.let { runCatching { windows.removeView(it) } }
        view = null
    }
    fun isArmed(token: Long) = view != null && generation == token
}
