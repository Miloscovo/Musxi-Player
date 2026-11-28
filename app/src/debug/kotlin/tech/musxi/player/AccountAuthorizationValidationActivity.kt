package tech.musxi.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.CookieManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import tech.musxi.accounts.NeteaseWebLogin
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import org.json.JSONObject

/** Fixed debug entry behind ValidationReceiver's DUMP permission. User performs official login. */
class AccountAuthorizationValidationActivity : Activity() {
    companion object {
        fun launch(context: Context, platform: Platform? = null) = context.startActivity(Intent(context,
            AccountAuthorizationValidationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("platform", platform?.name))
    }
    private val app get() = application as MusxiApplication
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private var browser: NeteaseWebLogin? = null
    private var pending: Platform? = null
    private var originalId: String? = null
    private var previousVerification = 0L
    private var generation = 0L
    private var report = JSONObject()
    private fun save() = File(filesDir, "account-authorization-live.json").writeText(report.toString(2))
    private val observe = object : Runnable {
        override fun run() {
            pending?.let { platform ->
                val state = app.accounts.states.value.getValue(platform)
                val facts = app.accounts.verification.value.getValue(platform)
                status.text = "$platform · ${state.phase}\n校验失败类别：${facts.lastFailure ?: "无"}\n官方确认由使用者操作；完成后返回本页。"
                if (state.generation >= generation && state.phase == AccountPhase.CONNECTED &&
                    (facts.lastVerifiedAtEpochMs ?: 0L) > previousVerification) {
                    val same = originalId == state.profile?.id
                    report.put("result", if (same) "passed" else "different_owner")
                        .put("sameOwner", same).put("verifiedAtEpochMs", facts.lastVerifiedAtEpochMs)
                        .put("finishedAtEpochMs", System.currentTimeMillis())
                    save(); pending = null
                    status.text = "$platform 官方授权已验证并保存；原账号一致：$same"
                } else if (state.generation > generation && state.phase == AccountPhase.CONNECTED && facts.lastFailure != null) {
                    report.put("result", "rolled_back").put("failure", facts.lastFailure?.name)
                    save(); pending = null
                }
            }
            main.postDelayed(this, 500)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 40, 24, 24) }
        status = TextView(this).apply { text = "账号后端实机验证（仅 debug）\n请使用原账号完成官方确认。" }
        layout.addView(status)
        Platform.entries.forEach { platform ->
            layout.addView(Button(this).apply {
                text = "$platform 原位重新授权"
                setOnClickListener { start(platform) }
            })
        }
        layout.addView(Button(this).apply {
            text = "网易云明确退出并检查网页清理"
            setOnClickListener { logoutNetease() }
        })
        layout.addView(Button(this).apply {
            text = "网易云退出后重新登录"
            setOnClickListener {
                if (app.accounts.states.value.getValue(Platform.NETEASE).phase == AccountPhase.DISCONNECTED)
                    browser = NeteaseWebLogin.open(this@AccountAuthorizationValidationActivity, app.accounts)
            }
        })
        setContentView(layout)
        main.post(observe)
        requestedPlatform(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedPlatform(intent)
    }
    private fun requestedPlatform(value: Intent) {
        val platform = Platform.entries.firstOrNull { it.name == value.getStringExtra("platform") } ?: return
        pending?.let { app.accounts.cancel(it) }; pending = null
        main.postDelayed({ start(platform) }, 1000)
    }
    private fun start(platform: Platform) {
        if (pending != null) return
        val state = app.accounts.states.value.getValue(platform)
        if (state.phase != AccountPhase.CONNECTED) { status.text = "请等待账号完成恢复后重试"; return }
        originalId = state.profile?.id
        generation = state.generation
        previousVerification = app.accounts.verification.value.getValue(platform).lastVerifiedAtEpochMs ?: 0L
        report = JSONObject().put("platform", platform.name).put("startedAtEpochMs", System.currentTimeMillis())
            .put("stage", "awaiting_user")
        pending = platform; save()
        if (platform == Platform.NETEASE) browser = NeteaseWebLogin.reauthenticate(this, app.accounts)
        else app.accounts.reauthenticateOfficial(platform) { startActivity(it) }
    }
    private fun logoutNetease() {
        if (pending != null) return
        browser?.close(); browser = null
        app.accounts.disconnect(Platform.NETEASE)
        val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
        val check = object : Runnable {
            override fun run() {
                val state = app.accounts.states.value.getValue(Platform.NETEASE)
                if (state.phase == AccountPhase.DISCONNECTED) {
                    val empty = !CookieManager.getInstance().hasCookies()
                    val pendingClear = getSharedPreferences("netease-browser-lifecycle-v1", MODE_PRIVATE)
                        .getBoolean("pending", true)
                    val result = JSONObject().put("disconnected", true).put("cookieJarEmpty", empty)
                        .put("pendingClear", pendingClear).put("finishedAtEpochMs", System.currentTimeMillis())
                    File(filesDir, "account-logout-live.json").writeText(result.toString(2))
                    status.text = "网易云已退出；Cookie为空：$empty；清理待办：$pendingClear\n请点退出后重新登录恢复原账号。"
                } else if (android.os.SystemClock.elapsedRealtime() < deadline) main.postDelayed(this, 200)
                else status.text = "退出未完成：${state.phase} / ${state.error}"
            }
        }
        main.post(check)
    }
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        browser?.close()
        super.onDestroy()
    }
}
