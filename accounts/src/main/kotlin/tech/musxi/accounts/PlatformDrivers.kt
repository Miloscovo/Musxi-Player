package tech.musxi.accounts

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import tech.musxi.loginpoc.KugouApi
import tech.musxi.qqloginpoc.QqMusicApi
import tech.musxi.neteaseloginpoc.NeteaseApi

/** Internal types deliberately do not implement data-class toString (they contain credentials). */
internal class Identity(val id: String, val name: String)
internal class LoginRequest(val opaque: Any, val launch: Intent?)
internal sealed interface PollResult {
    class Complete(val session: Any) : PollResult
    data object Waiting : PollResult
    data object Expired : PollResult
    data object Refused : PollResult
}

internal interface PlatformDriver {
    fun load(): Any?
    fun verify(session: Any): Any
    fun save(session: Any)
    fun clear()
    fun identity(session: Any): Identity
    fun create(): LoginRequest
    fun poll(request: LoginRequest): PollResult
}

internal class KugouDriver(context: Context) : PlatformDriver {
    private val store = tech.musxi.loginpoc.SessionStore(context)
    internal val api by lazy { KugouApi(KugouApi.Device(store.deviceMid())) }
    override fun load(): Any? = store.load()
    override fun verify(session: Any): Any = api.verify(session as KugouApi.Session)
    override fun save(session: Any) = store.save(session as KugouApi.Session)
    override fun clear() = store.clear()
    override fun identity(session: Any): Identity = (session as KugouApi.Session).let { Identity(it.userId, it.nickname) }
    override fun create(): LoginRequest {
        val key = api.createLogin()
        val page = JSONObject().put("url", KugouApi.authorizationUrl(key)).put("title", "扫码结果")
        val payload = JSONObject().put("cmd", "303").put("jsonStr", page).put("type", 9).put("action", 0)
        return LoginRequest(key, Intent(Intent.ACTION_VIEW,
            Uri.parse("kugou://start.weixin?" + Uri.encode(payload.toString()))).setPackage("com.kugou.android"))
    }
    override fun poll(request: LoginRequest): PollResult {
        val result = api.poll(request.opaque as String)
        return when (result.status) {
            4 -> PollResult.Complete(result.session ?: throw java.io.IOException("Missing session"))
            0 -> PollResult.Expired
            1, 2 -> PollResult.Waiting
            else -> throw java.io.IOException("Unsupported login status")
        }
    }
}

internal class QqDriver(context: Context) : PlatformDriver {
    private val store = tech.musxi.qqloginpoc.SessionStore(context)
    internal val api = QqMusicApi()
    override fun load(): Any? = store.load()
    override fun verify(session: Any): Any = api.verify(session as QqMusicApi.Session)
    override fun save(session: Any) = store.save(session as QqMusicApi.Session)
    override fun clear() = store.clear()
    override fun identity(session: Any): Identity = (session as QqMusicApi.Session).let { Identity(it.musicId, it.nickname) }
    override fun create(): LoginRequest {
        val attempt = api.createLogin()
        val uri = Uri.Builder().scheme("wtloginmqq").authority("ptlogin").path("/qlogin")
            .appendQueryParameter("qrcode", attempt.authorizationPayload)
            .appendQueryParameter("schemacallback", "musxiplayer://login-return").build()
        return LoginRequest(attempt, Intent(Intent.ACTION_VIEW, uri).setPackage("com.tencent.mobileqq"))
    }
    override fun poll(request: LoginRequest): PollResult {
        val result = api.poll(request.opaque as QqMusicApi.Attempt)
        return when (result.status) {
            0 -> PollResult.Complete(result.session ?: throw java.io.IOException("Missing session"))
            65 -> PollResult.Expired
            68 -> PollResult.Refused
            66, 67 -> PollResult.Waiting
            else -> throw java.io.IOException("Unsupported login status")
        }
    }
}

internal class NeteaseDriver(context: Context) : PlatformDriver {
    private val store = tech.musxi.neteaseloginpoc.SessionStore(context)
    internal val api = NeteaseApi()
    override fun load(): Any? = store.load()
    override fun verify(session: Any): Any = api.verify(session as NeteaseApi.Session)
    override fun save(session: Any) = store.save(session as NeteaseApi.Session)
    override fun clear() = store.clear()
    override fun identity(session: Any): Identity = (session as NeteaseApi.Session).let { Identity(it.userId, it.nickname) }
    override fun create(): LoginRequest = LoginRequest(api.createLogin(), null)
    override fun poll(request: LoginRequest): PollResult {
        val result = api.poll(request.opaque as NeteaseApi.Attempt)
        return when (result.status) {
            803 -> PollResult.Complete(result.session ?: throw java.io.IOException("Missing session"))
            800 -> PollResult.Expired
            801, 802 -> PollResult.Waiting
            else -> throw java.io.IOException("Unsupported login status")
        }
    }
}

internal fun safeErrorCode(error: Exception): String = when (error) {
    is KugouApi.ApiException -> error.code
    is QqMusicApi.ApiException -> error.code
    is NeteaseApi.ApiException -> error.code
    is java.io.IOException -> "network"
    else -> "storage"
}
