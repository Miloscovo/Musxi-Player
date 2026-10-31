package tech.musxi.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import tech.musxi.loginpoc.*

/** Isolated debug proof. No production login, queue or favorite writes. */
class KugouWebLoginValidationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var browser: KugouWebLoginWindow? = null
    private lateinit var status: TextView
    private lateinit var report: JSONObject
    private var verifying = false
    private fun write() {
        report.put("updatedAt", System.currentTimeMillis())
        File(filesDir, "kugou-web-validation.json").writeText(report.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getLongExtra("revision", -1) != revision) { finish(); return }
        current?.finish(); current = this
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        report = JSONObject().put("status", "OPENING").put("timestamp", System.currentTimeMillis())
        status = TextView(this).apply { text = "正在验证酷狗网页备选登录，现有账号保留。"; textSize = 18f }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 50, 24, 24)
            addView(status)
            addView(Button(this@KugouWebLoginValidationActivity).apply {
                text = "结束验证并返回"; setOnClickListener { finish() }
            })
        })
        write()
        browser = KugouWebLoginWindow(this, object : KugouWebLoginWindow.Listener {
            override fun stage(stage: String, host: String) {
                val events = report.optJSONArray("stages") ?: JSONArray().also { report.put("stages", it) }
                if (events.length() < 50) events.put(JSONObject().put("stage", stage).put("host", host))
                report.put("status", stage).put("host", host); write()
            }
            override fun session(session: KugouApi.Session) {
                browser = null; verifying = true
                status.text = "网页登录会话已返回，正在核对音乐身份与曲库…"
                report.put("status", "VERIFYING"); write()
                scope.launch {
                    var checkStage = "COOKIE_CAPTURED"
                    try {
                        val checks = withContext(Dispatchers.IO) {
                            val checks = verifyCandidate(this@KugouWebLoginValidationActivity, session) { checkStage = it }
                            ensureActive()
                            checkStage = "ISOLATED_SESSION_SAVE"
                            SessionStore(probeContext(this@KugouWebLoginValidationActivity)).save(session)
                            checks.put("isolatedSessionSaved", true)
                        }
                        report.put("status", "VERIFIED").put("checks", checks); write()
                        status.text = "网页会话验证通过，可读取曲库。原账号保留，未切换登录。"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        report.put("status", "FAILED").put("category", safeCategory(error)).put("checkStage", checkStage); write()
                        status.text = "网页会话尚未验证通过，原账号保留。"
                    }
                }
            }
            override fun cancelled() {
                browser = null
                if (!verifying) {
                    report.put("status", "CANCELLED"); write()
                    status.text = "本次网页登录已结束，原账号保留。"
                }
            }
        })
    }

    override fun onDestroy() {
        scope.cancel(); browser?.close(); browser = null
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        private var revision = 0L
        private var current: KugouWebLoginValidationActivity? = null
        private fun probeContext(context: Context) = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = File(super.getNoBackupFilesDir(), "kugou-web-validation").apply { mkdirs() }
        }
        private fun verifyCandidate(context: Context, candidate: KugouApi.Session,
            onStage: (String) -> Unit = {}): JSONObject {
            val store = SessionStore(context)
            onStage("DEVICE_ID")
            val api = KugouApi(KugouApi.Device(store.deviceMid()))
            onStage("PROFILE_VERIFY")
            val verified = api.verify(candidate)
            onStage("ORIGINAL_SESSION_READ")
            val original = store.load()
            check(original == null || original.userId == verified.userId) { "identity" }
            onStage("PLAYLIST_READ")
            val lists = KugouLibraryApi(api).playlists(verified, 0, 30)
            return JSONObject().put("profileAccepted", true)
                .put("profileAccountIdEchoed", verified.accountIdEchoed)
                .put("existingAccountPresent", original != null)
                .put("sameAsExistingUserId", original?.userId == verified.userId)
                .put("playlistReadSucceeded", true).put("playlistCount", lists.items.size)
        }
        fun launch(context: Context) {
            val token = ++revision
            context.startActivity(Intent(context, KugouWebLoginValidationActivity::class.java)
                .putExtra("revision", token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        fun close() { ++revision; current?.finish() }
        fun restartCheck(app: MusxiApplication) {
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                val result = JSONObject().put("status", "RUNNING").put("timestamp", System.currentTimeMillis())
                val file = File(app.filesDir, "kugou-web-restart-validation.json")
                file.writeText(result.toString())
                try {
                    val checks = withContext(Dispatchers.IO) {
                        val saved = SessionStore(probeContext(app)).load() ?: error("no_probe_session")
                        verifyCandidate(app, saved)
                    }
                    result.put("status", "VERIFIED").put("checks", checks)
                } catch (error: Exception) { result.put("status", "FAILED").put("category", safeCategory(error)) }
                file.writeText(result.toString())
            }
        }
        /** Retry the current WebView cookie without asking the user to log in again. */
        fun retryCookieCheck(app: MusxiApplication) {
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                val file = File(app.filesDir, "kugou-web-retry-validation.json")
                val result = JSONObject().put("status", "RUNNING").put("timestamp", System.currentTimeMillis())
                file.writeText(result.toString())
                var checkStage = "COOKIE_READ"
                try {
                    val checks = withContext(Dispatchers.IO) {
                        val header = android.webkit.CookieManager.getInstance().getCookie("https://www.kugou.com/")
                        val candidate = KugouWebLoginPolicy.parseCookies(header)
                        checkStage = "COOKIE_PARSED"
                        val store = SessionStore(app)
                        val api = KugouApi(KugouApi.Device(store.deviceMid()))
                        val checks = JSONObject().put("webCookiePresent", true)
                        checkStage = "ORIGINAL_SESSION_READ"
                        val original = store.load()
                        checks.put("existingAccountPresent", original != null)
                        checks.put("sameAsExistingUserId", original?.userId == candidate.userId)
                        check(original == null || original.userId == candidate.userId) { "identity" }
                        if (original != null) {
                            checkStage = "ORIGINAL_PROFILE_CONTROL"
                            try {
                                api.verify(original)
                                checks.put("originalProfileAccepted", true)
                            } catch (error: Exception) {
                                checks.put("originalProfileAccepted", false).put("originalProfileCategory", safeCategory(error))
                            }
                        }
                        checkStage = "PROFILE_VERIFY"
                        val profile = try {
                            api.verify(candidate).also {
                                checks.put("profileAccepted", true).put("profileAccountIdEchoed", it.accountIdEchoed)
                            }
                        } catch (error: Exception) {
                            checks.put("profileAccepted", false).put("profileCategory", safeCategory(error))
                            null
                        }
                        // Separate read-only compatibility check: a web token may have a
                        // different scope from the app profile endpoint. Never promote it
                        // to a production session merely because it parses as a cookie.
                        checkStage = "PLAYLIST_READ"
                        try {
                            val lists = KugouLibraryApi(api).playlists(candidate, 0, 30)
                            checks.put("playlistReadSucceeded", true).put("playlistCount", lists.items.size)
                        } catch (error: Exception) {
                            checks.put("playlistReadSucceeded", false).put("playlistCategory", safeCategory(error))
                        }
                        if (profile != null && checks.optBoolean("playlistReadSucceeded")) {
                            checkStage = "ISOLATED_SESSION_SAVE"
                            SessionStore(probeContext(app)).save(profile)
                            checks.put("isolatedSessionSaved", true)
                        } else checks.put("isolatedSessionSaved", false)
                        checks
                    }
                    result.put("status", if (checks.optBoolean("isolatedSessionSaved")) "VERIFIED" else "FAILED")
                        .put("checks", checks).put("checkStage", checkStage)
                } catch (error: Exception) {
                    result.put("status", "FAILED").put("category", safeCategory(error)).put("checkStage", checkStage)
                }
                file.writeText(result.toString())
            }
        }
        private fun safeCategory(error: Exception): String =
            (error as? KugouApi.ApiException)?.code?.takeIf { it in setOf(
                "network", "timeout", "expired", "risk", "rate", "protocol", "identity", "credentials", "http") }
                ?: (error as? KugouApi.ApiException)?.code?.takeIf { it in setOf("platform", "redirect", "endpoint") }
                ?: if (error is java.io.IOException) "network_or_storage" else "check_failed"
    }
}
