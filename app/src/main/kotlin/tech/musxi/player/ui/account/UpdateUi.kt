package tech.musxi.player.ui.account

import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToLong
import tech.musxi.core.update.LatestUpdate
import tech.musxi.core.update.UpdateCheckPhase
import tech.musxi.core.update.UpdateDownloadPhase
import tech.musxi.core.update.UpdateFailure
import tech.musxi.core.update.UpdateState

/**
 * What the "关于" group shows, derived from [UpdateState] by [aboutUpdateUiOf]. Plain data with no
 * Compose types, so every state can be tested without a screen.
 */
data class AboutUpdateUi(
    val developmentBuild: Boolean,
    val checking: Boolean,
    /** Short text at the right of the "检查更新" row. Null shows nothing. */
    val checkNote: String?,
    /** A newer official release to offer. Always null for development builds. */
    val release: ReleaseUi?,
    /** Null hides the "自动检查更新" row, which development builds do not have. */
    val autoCheck: Boolean?,
    /** Red dot on the "我的" tab. */
    val badge: Boolean,
)

/** One newer release as the page shows it. [version] has no leading "v" and goes back to the backend. */
data class ReleaseUi(
    val version: String,
    val title: String,
    val meta: String,
    val notes: List<NoteLine>,
    /** Null when the page is not on this repository, which hides the "查看" button. */
    val releasePage: String?,
    val ignored: Boolean,
    /** True on a metered connection: the download needs the inline confirmation first. */
    val metered: Boolean,
    val confirmText: String,
    val download: DownloadUi,
)

/** What sits under the release text. Each state replaces the action row of the one before it. */
sealed interface DownloadUi {
    /** Nothing is transferring. [failure] is why the last attempt ended, [unavailableNote] why there is no button. */
    data class Idle(
        val canDownload: Boolean,
        val failure: String? = null,
        val unavailableNote: String? = null,
    ) : DownloadUi

    data class Downloading(val fraction: Float, val text: String) : DownloadUi

    data object Verifying : DownloadUi

    data object Ready : DownloadUi

    data object NeedsPermission : DownloadUi

    data object Installing : DownloadUi
}

/** Command callbacks of the "关于" group. Version arguments are [ReleaseUi.version]. */
class UpdateActions(
    val onCheck: () -> Unit = {},
    val onOpenRelease: (String) -> Unit = {},
    val onIgnore: (String) -> Unit = {},
    val onClearIgnore: () -> Unit = {},
    val onDownload: (String) -> Unit = {},
    val onCancelDownload: () -> Unit = {},
    val onInstall: () -> Unit = {},
    val onOpenInstallSettings: () -> Unit = {},
    val onSetAutoCheck: (Boolean) -> Unit = {},
)

/** Release tags may or may not carry a leading "v"; the label always shows one. */
internal fun displayVersion(tag: String): String =
    if (tag.startsWith("v", ignoreCase = true)) tag else "v$tag"

/** Text of the in-app notice shown once after an update. */
fun justUpdatedNotice(currentVersion: String): String = "已更新到 ${displayVersion(currentVersion)}"

/**
 * Maps the backend state to the page. Development builds only report the official version and
 * never offer a release; release builds offer one when it is newer.
 */
fun aboutUpdateUiOf(state: UpdateState, zone: ZoneId = ZoneId.systemDefault()): AboutUpdateUi {
    val latest = state.latest
    val newer = !state.developmentBuild && latest != null && latest.newer
    return AboutUpdateUi(
        developmentBuild = state.developmentBuild,
        checking = state.check.phase == UpdateCheckPhase.CHECKING,
        checkNote = checkNoteOf(state),
        release = if (newer) releaseUiOf(state, latest!!, zone) else null,
        autoCheck = if (state.developmentBuild) null else state.autoCheck,
        badge = newer && !latest!!.ignored,
    )
}

private fun checkNoteOf(state: UpdateState): String? = when (state.check.phase) {
    UpdateCheckPhase.IDLE, UpdateCheckPhase.CHECKING -> null
    UpdateCheckPhase.FAILED -> checkFailureNote(state.check.reason)
    UpdateCheckPhase.SUCCEEDED -> {
        val latest = state.latest
        when {
            state.developmentBuild ->
                if (latest != null) "官方最新 ${displayVersion(latest.version)}" else "未找到官方发布"
            latest == null -> "未找到官方发布"
            // A newer release is described by its own block below, so the row stays quiet.
            latest.newer -> null
            else -> "已是最新"
        }
    }
}

/** Short enough for the right side of the 64dp "检查更新" row. */
fun checkFailureNote(reason: UpdateFailure?): String = when (reason) {
    UpdateFailure.RATE_LIMITED -> "请求太频繁，稍后再试"
    UpdateFailure.TIMEOUT -> "连接超时，点按重试"
    UpdateFailure.INVALID_MANIFEST -> "发布信息异常，点按重试"
    else -> "检查失败，点按重试"
}

private fun releaseUiOf(state: UpdateState, latest: LatestUpdate, zone: ZoneId): ReleaseUi {
    val meta = listOfNotNull(
        if (latest.prerelease) "预发布" else null,
        latest.publishedAt?.let { releaseDate(it, zone) },
        latest.bytes?.let(::sizeText),
        if (latest.ignored) "已忽略提醒" else null,
    ).joinToString(" · ")
    return ReleaseUi(
        version = latest.version,
        title = "发现新版本 ${displayVersion(latest.version)}",
        meta = meta,
        notes = noteLines(latest.notes),
        releasePage = latest.releasePage.takeIf { isTrustedReleasePage(it) },
        ignored = latest.ignored,
        metered = state.meteredNetwork,
        confirmText = meteredConfirmText(latest.bytes),
        download = downloadUiOf(state, latest),
    )
}

private fun downloadUiOf(state: UpdateState, latest: LatestUpdate): DownloadUi {
    val download = state.download
    return when (download.phase) {
        UpdateDownloadPhase.NONE -> idleOf(latest)
        UpdateDownloadPhase.DOWNLOADING -> {
            val fraction = if (download.totalBytes > 0) {
                (download.receivedBytes.toFloat() / download.totalBytes).coerceIn(0f, 1f)
            } else 0f
            DownloadUi.Downloading(fraction, transferText(download.receivedBytes, download.totalBytes))
        }
        UpdateDownloadPhase.VERIFYING -> DownloadUi.Verifying
        UpdateDownloadPhase.READY -> DownloadUi.Ready
        UpdateDownloadPhase.NEEDS_PERMISSION -> DownloadUi.NeedsPermission
        UpdateDownloadPhase.INSTALLING -> DownloadUi.Installing
        // A cancelled download or a dismissed system installer is the user's own choice: back to the button.
        UpdateDownloadPhase.FAILED ->
            if (download.reason == UpdateFailure.CANCELLED) idleOf(latest)
            else idleOf(latest).copy(failure = download.reason?.let(::downloadFailureText) ?: "更新失败")
    }
}

private fun idleOf(latest: LatestUpdate): DownloadUi.Idle = DownloadUi.Idle(
    canDownload = latest.canDownload,
    unavailableNote = when {
        latest.canDownload -> null
        latest.manifestFailure != null -> "更新清单异常，暂不能在应用内安装"
        !latest.manifestAvailable -> "此版本暂无应用内安装包"
        else -> "此版本暂不能在应用内安装"
    },
)

/** Chinese short sentence for every [UpdateFailure]; wording follows docs/android-app-updates.md. */
fun downloadFailureText(reason: UpdateFailure): String = when (reason) {
    UpdateFailure.NETWORK -> "网络连接失败"
    UpdateFailure.TIMEOUT -> "连接超时"
    UpdateFailure.RATE_LIMITED -> "GitHub 暂时限制访问"
    UpdateFailure.UNSAFE_REDIRECT -> "不安全的下载跳转"
    UpdateFailure.INVALID_MANIFEST -> "更新清单异常"
    UpdateFailure.DOWNLOAD_UNAVAILABLE -> "暂无应用内安装包"
    UpdateFailure.SIZE_MISMATCH -> "文件大小不符"
    UpdateFailure.CHECKSUM_MISMATCH -> "文件校验失败"
    UpdateFailure.SIGNATURE_MISMATCH -> "签名不符"
    UpdateFailure.PACKAGE_MISMATCH -> "包名不符"
    UpdateFailure.VERSION_NOT_NEWER -> "版本不高于当前"
    UpdateFailure.STORAGE -> "存储不足或写入失败"
    UpdateFailure.PERMISSION_DENIED -> "未授予安装权限"
    UpdateFailure.CANCELLED -> "已取消"
    UpdateFailure.INSTALL_FAILED -> "安装失败"
    UpdateFailure.RELEASE_CHANGED -> "版本已变化"
    UpdateFailure.DEVELOPMENT_BUILD -> "开发版本不提供应用内更新"
    UpdateFailure.BUSY -> "更新正忙，请稍后再试"
    UpdateFailure.NOT_READY -> "安装包尚未就绪"
    UpdateFailure.CHECKPOINT_FAILED -> "播放进度保存失败"
    UpdateFailure.UNSUPPORTED_SDK -> "系统版本不受支持"
}

private const val MIB = 1024L * 1024L

/** "18.2 MB", or "640 KB" under one megabyte; the same rule as the PC page. */
fun sizeText(bytes: Long): String =
    if (bytes >= MIB) String.format(Locale.US, "%.1f MB", bytes / MIB.toDouble())
    else "${kilobytes(bytes)} KB"

/** "已下载 7.4 / 18.2 MB": both numbers use the unit of the total, so they read as one fraction. */
fun transferText(received: Long, total: Long): String = when {
    total <= 0 -> "已下载 ${sizeText(received)}"
    total >= MIB -> String.format(Locale.US, "已下载 %.1f / %.1f MB", received / MIB.toDouble(), total / MIB.toDouble())
    else -> "已下载 ${received / 1024} / ${kilobytes(total)} KB"
}

private fun kilobytes(bytes: Long): Long = maxOf(1L, (bytes / 1024.0).roundToLong())

/** The question asked inline before a download on a metered connection. Whole megabytes: "约 18 MB". */
private fun meteredConfirmText(bytes: Long?): String {
    val size = when {
        bytes == null || bytes <= 0 -> null
        bytes >= MIB -> "${(bytes / MIB.toDouble()).roundToLong()} MB"
        else -> "${kilobytes(bytes)} KB"
    }
    return if (size == null) "正在使用移动数据，继续下载？" else "正在使用移动数据，约 $size，继续下载？"
}

/** "2026年10月28日" in [zone], from GitHub's ISO-8601 time. Null when the text is not a date. */
fun releaseDate(iso: String, zone: ZoneId): String? = runCatching {
    val local = OffsetDateTime.parse(iso).atZoneSameInstant(zone)
    "${local.year}年${local.monthValue}月${local.dayOfMonth}日"
}.getOrNull()

enum class NoteKind { HEADING, ITEM, TEXT, GAP }

/** One line of release notes as plain text. */
data class NoteLine(val kind: NoteKind, val text: String)

// Notes come from the release page and are untrusted. These caps keep a hostile or accidental
// 128 KB body from becoming thousands of Text nodes or a regex that runs for seconds.
private const val MAX_NOTE_CHARS = 20_000
private const val MAX_NOTE_LINES = 200
private const val MAX_LINE_CHARS = 600

private val LineBreak = Regex("""\r\n|\n|\r""")
private val QuoteMark = Regex("""^\s{0,3}(?:>\s?)+""")
private val HeadingLine = Regex("""^\s{0,3}#{1,6}\s+(.*)$""", RegexOption.DOT_MATCHES_ALL)
private val ItemLine = Regex("""^\s*(?:[-*+]|\d+[.)])\s+(?:\[[ xX]\]\s+)?(.*)$""", RegexOption.DOT_MATCHES_ALL)
private val ImageMark = Regex("""!\[[^\]]*\]\([^)]*\)""")
private val LinkMark = Regex("""\[([^\]]+)\]\([^)]*\)""")
private val EmphasisMark = Regex("""\*\*|__|~~|`""")
private val HtmlTag = Regex("""<[^>]+>""")

/**
 * Release notes are Markdown from GitHub; this keeps readable plain text and never HTML or live
 * links. Same rules as `noteLines` in the PC front end: images dropped, links keep their text,
 * emphasis marks and HTML tags removed, headings, list items and paragraphs kept apart.
 */
fun noteLines(notes: String): List<NoteLine> {
    val out = ArrayList<NoteLine>()
    for (rawLine in withoutComments(notes.take(MAX_NOTE_CHARS)).split(LineBreak)) {
        if (out.size >= MAX_NOTE_LINES) {
            out.add(NoteLine(NoteKind.TEXT, "…"))
            break
        }
        val clipped = rawLine.length > MAX_LINE_CHARS
        val raw = QuoteMark.replace(if (clipped) rawLine.take(MAX_LINE_CHARS) else rawLine, "")
        val heading = HeadingLine.matchEntire(raw)
        val item = ItemLine.matchEntire(raw)
        val text = inlineText(heading?.groupValues?.get(1) ?: item?.groupValues?.get(1) ?: raw)
        if (isRule(raw)) continue
        if (text.isEmpty()) {
            if (out.isNotEmpty() && out.last().kind != NoteKind.GAP) out.add(NoteLine(NoteKind.GAP, ""))
            continue
        }
        val kind = if (heading != null) NoteKind.HEADING else if (item != null) NoteKind.ITEM else NoteKind.TEXT
        out.add(NoteLine(kind, if (clipped) "$text…" else text))
    }
    while (out.isNotEmpty() && out.last().kind == NoteKind.GAP) out.removeAt(out.lastIndex)
    return out
}

/** The collapsed view: one string, so a single Text can cut it at three lines. */
fun notePreview(lines: List<NoteLine>): String = lines
    .filter { it.kind != NoteKind.GAP }
    .joinToString("\n") { if (it.kind == NoteKind.ITEM) "• ${it.text}" else it.text }

private fun inlineText(line: String): String = line
    .replace(ImageMark, "")
    .replace(LinkMark, "\$1")
    .replace(EmphasisMark, "")
    .replace(HtmlTag, "")
    .trim()

/** A horizontal rule: three or more of one of "-", "*" or "_", with spaces allowed between. */
private fun isRule(line: String): Boolean {
    val body = line.filterNot { it.isWhitespace() }
    return body.length >= 3 && body[0] in "-*_" && body.all { it == body[0] }
}

/** Removes `<!-- ... -->` with plain searches; an unclosed comment is left as it is. */
private fun withoutComments(text: String): String {
    var start = text.indexOf("<!--")
    if (start < 0) return text
    val out = StringBuilder()
    var from = 0
    while (start >= 0) {
        val end = text.indexOf("-->", start + 4)
        if (end < 0) break
        out.append(text, from, start)
        from = end + 3
        start = text.indexOf("<!--", from)
    }
    out.append(text, from, text.length)
    return out.toString()
}
