package tech.musxi.player

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.*
import tech.musxi.core.account.*
import tech.musxi.core.library.*
import tech.musxi.accounts.NeteaseWebLogin
import tech.musxi.core.playback.*

/** Temporary execution console for backend acceptance; cc will own the product UI. */
class DeveloperActivity : Activity() {
    private val app get() = application as MusxiApplication
    private val accounts get() = app.accounts
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observation: Job? = null
    private var web: NeteaseWebLogin? = null
    private var resumed = false
    private lateinit var page: LinearLayout
    private lateinit var libraryStatus: TextView
    private lateinit var libraryRows: LinearLayout
    private lateinit var playbackStatus: TextView
    private lateinit var queueStatus: TextView
    private lateinit var kugouStatus: TextView
    private lateinit var qqStatus: TextView
    private lateinit var qqRows: LinearLayout
    private lateinit var kugouRows: LinearLayout
    private lateinit var sourceStatus: TextView
    private var sourceDialog: AlertDialog? = null
    private var sourceDialogRevision: Long? = null
    private var countdown: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Musxi Android · 后端开发"
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 32)
        }
        content.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(32 + insets.systemWindowInsetLeft, 24 + insets.systemWindowInsetTop,
                32 + insets.systemWindowInsetRight, 32 + insets.systemWindowInsetBottom)
            insets
        }
        setContentView(ScrollView(this).apply { addView(content) })
        text(content, "Musxi Android · 开发验收", 24)
        text(content, "此页面用于验证账号、歌单和播放服务；正式前端后续由 cc 接入。", 15)
        sourceStatus = text(content, "原曲不可播时，可选择其他平台来源；候选准备后 5 秒自动尝试首个同名来源。", 15)
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(page)
        text(content, "网易云音乐库", 20)
        libraryStatus = text(content, "连接网易云后可读取自己的歌单。", 15)
        button(content, "我的网易云歌单（先显示缓存）") { app.libraryBrowser.playlists() }
        button(content, "刷新当前列表") { app.libraryBrowser.refresh() }
        libraryRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(libraryRows)
        text(content, "酷狗音乐库", 20)
        kugouStatus = text(content, "连接酷狗后可读取自己的歌单，已有缓存可离线浏览。", 15)
        button(content, "我的酷狗歌单（先显示缓存）") { app.kugouLibraryBrowser.playlists() }
        button(content, "刷新当前酷狗列表") { app.kugouLibraryBrowser.refresh() }
        kugouRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(kugouRows)
        text(content, "QQ 音乐库", 20)
        qqStatus = text(content, "连接 QQ 音乐后可读取自己的歌单，已有缓存可离线浏览。", 15)
        button(content, "我的 QQ 歌单（先显示缓存）") { app.qqLibraryBrowser.playlists() }
        button(content, "刷新当前 QQ 列表") { app.qqLibraryBrowser.refresh() }
        qqRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(qqRows)
        text(content, "播放服务", 20)
        playbackStatus = text(content, "正在连接播放服务…", 15)
        queueStatus = text(content, "", 15)
        button(content, "播放 / 暂停") { app.playback.toggle() }
        button(content, "上一首") { app.playback.previous() }
        button(content, "下一首") { app.playback.next() }
        button(content, "停止并清空播放") { app.queuePlayback.stop() }
    }

    override fun onStart() {
        super.onStart()
        accounts.setForeground(true)
        observation = scope.launch {
            launch { accounts.states.collect { renderAccounts(it) } }
            launch { app.libraryBrowser.state.collect { renderLibrary(it, Platform.NETEASE) } }
            launch { app.kugouLibraryBrowser.state.collect { renderLibrary(it, Platform.KUGOU) } }
            launch { app.qqLibraryBrowser.state.collect { renderLibrary(it, Platform.QQ) } }
            launch { app.sourceSelection.state.collect { renderSources(it) } }
            launch {
                app.queuePlayback.state.collect {
                    queueStatus.text = when {
                        it.loading -> "正在建立完整歌单队列…"
                        it.error != null -> "队列未能建立，请重试或重新读取歌单"
                        else -> ""
                    }
                }
            }
            launch {
                app.playback.state.collect {
                    playbackStatus.text = when {
                        it.error != null -> it.error
                        it.suppression == tech.musxi.playback.PlaybackSuppression.AUDIO_FOCUS -> "播放被其他音频暂时打断"
                        it.suppression == tech.musxi.playback.PlaybackSuppression.OUTPUT -> "当前音频输出暂不可用"
                        it.suppression != tech.musxi.playback.PlaybackSuppression.NONE -> "播放暂被系统打断"
                        it.buffering -> "正在缓冲"
                        it.ended -> "已播放至列表末尾"
                        it.playing -> "正在播放 · ${it.queueIndex + 1} / ${it.queueSize} · ${it.title}"
                        it.mediaId != null -> "已暂停 / 已准备"
                        it.connected -> "播放服务已连接，等待选择歌曲"
                        else -> "正在连接播放服务…"
                    }
                    getSharedPreferences("diagnostics", MODE_PRIVATE).edit()
                        .putBoolean("playbackConnected", it.connected).putBoolean("playing", it.playing)
                        .putBoolean("playbackError", it.error != null).apply()
                }
            }
        }
    }

    private fun renderAccounts(states: Map<Platform, AccountState>) {
        page.removeAllViews()
        val diag = getSharedPreferences("diagnostics", MODE_PRIVATE).edit()
        states.forEach { (platform, state) ->
            diag.putString(platform.name, state.phase.name)
            diag.putString("${platform.name}_error", state.error?.name ?: "NONE")
            val name = when (platform) { Platform.KUGOU -> "酷狗音乐"; Platform.QQ -> "QQ 音乐"; Platform.NETEASE -> "网易云音乐" }
            text(page, name, 20)
            text(page, phaseLabel(state), 15)
            state.profile?.let { text(page, "${it.displayName} · 账号尾号 ${it.id.takeLast(4)}", 15) }
            val active = state.phase in setOf(AccountPhase.PREPARING, AccountPhase.WAITING_CONFIRMATION,
                AccountPhase.VERIFYING, AccountPhase.REVALIDATING)
            if (!active && state.profile == null) {
                button(page, if (platform == Platform.NETEASE) "网易云官方网页登录" else "前往${if (platform == Platform.QQ) "QQ" else "酷狗"}确认") {
                    if (platform == Platform.NETEASE) openWeb()
                    else accounts.connectOfficial(platform) {
                        check(resumed && !isDestroyed)
                        startActivity(it)
                    }
                }
                if (platform == Platform.NETEASE && android.os.Build.VERSION.SDK_INT >= 29)
                    button(page, "备用：保存二维码并打开网易云扫一扫") {
                        accounts.connectNeteaseScanner { check(resumed && !isDestroyed); startActivity(it) }
                    }
            }
            if (state.phase == AccountPhase.WAITING_CONFIRMATION && platform != Platform.NETEASE)
                button(page, "我已确认，继续检查") { accounts.checkNow(platform) }
            if (state.phase == AccountPhase.WAITING_CONFIRMATION && platform == Platform.KUGOU)
                text(page, "在酷狗确认后，请通过系统返回或最近任务回到 Musxi。返回后会自动检查并保存，无需再次发起登录。", 15)
            if (active) button(page, "取消本次连接") { closeWeb(platform); accounts.cancel(platform) }
            if (!active && state.profile != null) button(page, "重新验证已保存登录") { accounts.restore(platform) }
            button(page, "断开此账号（仅清本机）") {
                closeWeb(platform)
                accounts.disconnect(platform)
            }
        }
        diag.apply()
    }

    private fun renderSources(state: SourceSelectionState) {
        val original = state.original
        sourceStatus.text = when (state.phase) {
            SourceSelectionPhase.NONE -> "原曲不可播时，可选择其他平台来源；候选准备后 5 秒自动尝试首个同名来源。"
            SourceSelectionPhase.SEARCHING -> "原曲暂不可播，正在搜索其他已连接平台…"
            SourceSelectionPhase.CHOOSING -> (if (state.partial) "部分平台未返回结果。" else "") +
                "请选择替代来源；未验证的候选可能仍无法播放。"
            SourceSelectionPhase.SELECTED -> "歌单来源：${original?.key?.platform} · 当前音源：${state.selected?.song?.key?.platform} · 标准音质"
            SourceSelectionPhase.FAILED -> "暂未找到可用候选或搜索未完成，可重试原曲或选择下一首。"
        }
        if (state.phase != SourceSelectionPhase.CHOOSING) {
            countdown?.cancel(); countdown = null
            sourceDialog?.dismiss(); sourceDialog = null; sourceDialogRevision = null
            return
        }
        if (sourceDialogRevision == state.revision) return
        sourceDialog?.dismiss(); countdown?.cancel()
        val labels = state.candidates.mapIndexed { index, candidate ->
            val song = candidate.song
            val seconds = song.durationMs / 1000
            "${index + 1}. ${song.title} · ${song.artists.joinToString(" / ")}\n${song.key.platform} · ${song.album} · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} · 待尝试"
        }.toTypedArray()
        sourceDialogRevision = state.revision
        sourceDialog = AlertDialog.Builder(this).setTitle("选择其他来源")
            .setItems(labels) { _, index -> app.sourceSelection.select(state.revision, index) }
            .setNegativeButton("取消自动选择") { _, _ -> app.sourceSelection.cancel(state.revision) }
            .setOnCancelListener { app.sourceSelection.cancel(state.revision) }.create().also { it.show() }
        countdown = scope.launch {
            while (isActive) {
                val seconds = state.deadlineElapsedMs?.let {
                    ((it - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000
                }
                sourceDialog?.setTitle(if (seconds != null)
                    "${seconds} 秒后自动尝试第 ${state.automaticIndex!! + 1} 项同名来源"
                    else "无同名候选，请手动选择")
                delay(250)
            }
        }
    }

    private fun openWeb() {
        web = NeteaseWebLogin.open(this, accounts, environmentChecked = {
            getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putBoolean("webCookies", it).apply()
        }, closed = { web = null })
    }

    private fun closeWeb(platform: Platform) { if (platform == Platform.NETEASE) { web?.close(); web = null } }

    private fun renderLibrary(state: LibraryBrowseState, platform: Platform) {
        val libraryRows = when (platform) { Platform.QQ -> qqRows; Platform.KUGOU -> kugouRows; Platform.NETEASE -> this.libraryRows }
        val libraryStatus = when (platform) { Platform.QQ -> qqStatus; Platform.KUGOU -> kugouStatus; Platform.NETEASE -> this.libraryStatus }
        val browser = when (platform) { Platform.QQ -> app.qqLibraryBrowser; Platform.KUGOU -> app.kugouLibraryBrowser; Platform.NETEASE -> app.libraryBrowser }
        val name = when (platform) { Platform.QQ -> "QQ"; Platform.KUGOU -> "酷狗"; Platform.NETEASE -> "网易云" }
        libraryRows.removeAllViews()
        val details = mutableListOf<String>()
        details += when (state.source) {
            LibrarySource.CACHE -> "本机缓存"
            LibrarySource.NETWORK -> "本次联网读取"
            null -> if (state.refreshing) "正在读取列表…" else "暂无当前账号的本机记录"
        }
        state.fetchedAtEpochMs?.let {
            details += "读取于 ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))}"
        }
        if (!state.accountVerified) details += "登录尚未验证；缓存可浏览，播放需联网验证"
        if (state.refreshing && state.content != null) details += "正在刷新，当前内容保留"
        if (state.error != null) details += when (state.error) {
            "network", "timeout", "http" -> "刷新未完成，请检查网络后重试"
            "account_unverified", "account_unavailable" -> "连接或重新验证${name}后可刷新"
            "expired" -> "登录已失效，请重新连接"
            else -> "本次刷新未完成，请稍后重试"
        }
        if (state.storageError) details += "本机缓存读写未完成；联网结果仍可使用"
        when (val content = state.content) {
            is LibraryContent.Playlists -> {
                details += "歌单本页 ${content.page.items.size} 项 · 总数 ${content.page.total ?: "未知"}"
                content.page.items.forEach { playlist ->
                    button(libraryRows, "${playlist.title} · ${playlist.trackCount} 首") { browser.tracks(playlist.key) }
                }
                content.page.nextOffset?.let { next -> button(libraryRows, "下一页歌单") { browser.playlists(next) } }
            }
            is LibraryContent.Tracks -> {
                details += "歌曲本页 ${content.page.items.size} 项 · 总数 ${content.page.total ?: "未知"}"
                content.page.items.forEach { song ->
                    button(libraryRows, "${song.title} · ${song.artists.joinToString(" / ")}") {
                        state.query.playlist?.let { app.queuePlayback.playPlaylist(it, song.key) }
                    }.isEnabled = state.accountVerified
                }
                content.page.nextOffset?.let { next -> button(libraryRows, "下一页歌曲") {
                    state.query.playlist?.let { browser.tracks(it, next) }
                } }
            }
            null -> Unit
        }
        if (state.query.playlist != null) button(libraryRows, "返回我的${name}歌单") { browser.playlists() }
        if (state.error != null && state.accountVerified) button(libraryRows, "重试刷新") { browser.refresh() }
        if (state.content != null) {
            val next = when (val content = state.content) {
                is LibraryContent.Playlists -> content.page.nextOffset
                is LibraryContent.Tracks -> content.page.nextOffset
                null -> null
            }
            details += if (state.query.offset == 0 && next == null) "此列表已读取完整" else "当前仅为分页内容"
        }
        libraryStatus.text = details.joinToString("\n")
    }

    private fun phaseLabel(state: AccountState): String = when (state.phase) {
        AccountPhase.DISCONNECTED -> "未连接"
        AccountPhase.PREPARING -> "正在准备…"
        AccountPhase.WAITING_CONFIRMATION -> "请在官方页面确认，完成后返回 Musxi"
        AccountPhase.VERIFYING -> "正在验证音乐会话并保存…"
        AccountPhase.CONNECTED -> "已连接"
        AccountPhase.REVALIDATING -> "正在检查已保存的登录…"
        AccountPhase.REAUTH_REQUIRED -> "登录已失效，请断开后重新连接"
        AccountPhase.RETRYABLE_ERROR -> when (state.error) {
            AccountError.OPEN_FAILED -> "未能打开官方应用，请检查安装后重试"
            AccountError.TIMEOUT -> "本次请求已过期，请重新开始"
            AccountError.REJECTED -> "本次确认已取消或被拒绝"
            AccountError.STORAGE -> "本机记录操作未完成，请重试"
            else -> "暂时无法完成验证，请稍后重试；已有记录保留"
        }
    }

    private fun text(parent: LinearLayout, value: String, size: Int) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setPadding(0, 12, 0, 12); parent.addView(this)
    }
    private fun button(parent: LinearLayout, value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; parent.addView(this); setOnClickListener { action() }
    }

    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onStop() {
        observation?.cancel(); countdown?.cancel()
        sourceDialog?.dismiss(); sourceDialog = null; sourceDialogRevision = null
        accounts.setForeground(false); super.onStop()
    }
    override fun onDestroy() {
        web?.close(); web = null
        scope.cancel()
        super.onDestroy()
    }
}
