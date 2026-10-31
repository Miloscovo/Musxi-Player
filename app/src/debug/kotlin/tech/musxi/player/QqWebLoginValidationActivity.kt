package tech.musxi.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import tech.musxi.qqloginpoc.*

/** Fixed, isolated login proof: never replaces the connected account or its encrypted session. */
class QqWebLoginValidationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var window: QqWebLoginWindow? = null
    private lateinit var status: TextView
    private lateinit var report: JSONObject
    private var completed = false
    private fun write() { File(filesDir, "qq-web-validation.json").writeText(report.toString()) }
    private fun probeContext() = object : ContextWrapper(this) {
        override fun getNoBackupFilesDir(): File = File(super.getNoBackupFilesDir(), "qq-web-validation").apply { mkdirs() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getLongExtra("revision", -1) != revision) { finish(); return }
        current?.finish(); current = this
        report = JSONObject().put("status", "OPENING").put("timestamp", System.currentTimeMillis())
        status = TextView(this).apply { text = "正在验证 QQ 网页备选登录，现有账号保留。"; textSize = 18f }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 50, 24, 24)
            addView(status)
            addView(Button(this@QqWebLoginValidationActivity).apply {
                text = "结束验证并返回"; setOnClickListener { finish() }
            })
        })
        write()
        window = QqWebLoginWindow(this, object : QqWebLoginWindow.Listener {
            override fun stage(stage: String, host: String) {
                val events = report.optJSONArray("stages") ?: JSONArray().also { report.put("stages", it) }
                if (events.length() < 40) events.put(JSONObject().put("stage", stage).put("host", host))
                report.put("status", stage).put("host", host); write()
            }
            override fun authorization(authorization: QqWebLoginApi.WebAuthorization) {
                window = null
                status.text = "网页授权已返回，正在验证音乐身份与本人曲库…"
                report.put("status", "VERIFYING"); write()
                verify(authorization)
            }
            override fun cancelled() {
                window = null
                if (!completed) { report.put("status", "CANCELLED"); write() }
                status.text = "本次网页登录已取消，原账号保持。"
            }
        })
    }

    private fun verify(authorization: QqWebLoginApi.WebAuthorization) = scope.launch {
        try {
            val result = withContext(Dispatchers.IO) {
                val api = QqMusicApi()
                val candidate = api.verify(QqWebLoginApi(api).exchangeWebAuthorization(authorization))
                val original = SessionStore(this@QqWebLoginValidationActivity).load()
                val same = original == null || candidate.musicId == original.musicId
                check(same) { "identity" }
                val lists = QqLibraryApi(api, { isActive }).playlists(candidate, 0, 30)
                ensureActive()
                SessionStore(probeContext()).save(candidate)
                JSONObject().put("sameAsExisting", same).put("playlistCount", lists.items.size)
                    .put("musicIdentityVerified", true).put("isolatedSessionSaved", true)
            }
            completed = true
            report.put("checks", result).put("status", "VERIFIED"); write()
            status.text = "网页登录成功：音乐身份与本人曲库验证通过，原账号保留。"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            report.put("status", "FAILED").put("category", safeCategory(error)); write()
            status.text = "网页登录尚未验证通过，原账号保留。"
        }
    }

    override fun onDestroy() {
        scope.cancel(); window?.close(); window = null
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        private var revision = 0L
        private var current: QqWebLoginValidationActivity? = null
        fun launch(context: Context) {
            val token = ++revision
            context.startActivity(Intent(context, QqWebLoginValidationActivity::class.java)
                .putExtra("revision", token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        fun close() { ++revision; current?.finish() }
        private fun safeCategory(error: Exception): String =
            (error as? QqMusicApi.ApiException)?.code?.takeIf { it in setOf(
                "network", "timeout", "expired", "risk", "rate", "protocol", "identity", "credentials", "authorize", "request") }
                ?: if (error is java.io.IOException) "network_or_storage" else "check_failed"

        fun restartCheck(app: MusxiApplication) {
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                val report = JSONObject().put("status", "RUNNING").put("timestamp", System.currentTimeMillis())
                val file = File(app.filesDir, "qq-web-restart-validation.json")
                file.writeText(report.toString())
                try {
                    val result = withContext(Dispatchers.IO) {
                        val context = object : ContextWrapper(app) {
                            override fun getNoBackupFilesDir(): File = File(super.getNoBackupFilesDir(), "qq-web-validation")
                        }
                        val session = SessionStore(context).load() ?: error("no_probe_session")
                        val verified = QqMusicApi().verify(session)
                        val original = SessionStore(app).load()
                        check(original == null || original.musicId == verified.musicId)
                        QqLibraryApi(QqMusicApi(), { isActive }).playlists(verified, 0, 30).items.size
                    }
                    report.put("status", "VERIFIED").put("playlistCount", result).put("sameAsExisting", true)
                } catch (error: Exception) { report.put("status", "FAILED").put("category", safeCategory(error)) }
                file.writeText(report.toString())
            }
        }
    }
}
