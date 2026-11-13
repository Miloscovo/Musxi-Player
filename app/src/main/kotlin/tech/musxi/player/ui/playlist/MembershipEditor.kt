package tech.musxi.player.ui.playlist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tech.musxi.core.account.Platform
import tech.musxi.core.library.CreatedPlaylistSongResult
import tech.musxi.core.library.EditablePlaylist
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.PlaylistChangeResult
import tech.musxi.core.library.PlaylistChangeStatus
import tech.musxi.core.library.PlaylistMembershipResult
import tech.musxi.core.library.SongPlaylistMembership
import tech.musxi.core.library.SongSummary

/**
 * Where the sheet reads and writes a song's playlist membership. In the app this is
 * `app.playlists`; tests give a fake. Both callbacks arrive on the main thread.
 */
interface MembershipSource {
    fun read(song: MediaKey, result: (Result<SongPlaylistMembership>) -> Unit)
    fun update(
        song: SongSummary,
        snapshot: SongPlaylistMembership,
        desired: Map<MediaKey, Boolean>,
        result: (Result<PlaylistMembershipResult>) -> Unit,
    )

    /** Whether this platform's account can make a new playlist from here (Kugou cannot yet). */
    fun canCreate(platform: Platform): Boolean = false

    /**
     * Makes an empty playlist named [name] and adds [song] to it. The add result is separate: a list
     * that was made stays made even when the song did not go in. Never retried automatically.
     */
    fun create(song: SongSummary, name: String, result: (Result<CreatedPlaylistSongResult>) -> Unit) {
        result(Result.failure(LibraryPageFailure("unsupported")))
    }
}

/** Which question the sheet answers: "加入歌单" from a menu, or "收藏到" from a long press on the heart. */
enum class MembershipMode(val title: String) {
    PLAYLISTS("加入歌单"),

    /** Lists "我喜欢" first, above the ordinary playlists. */
    COLLECT("收藏到"),
}

enum class MembershipPhase {
    /** First read, or a reread with nothing usable on screen. */
    LOADING,

    /** The read failed. Nothing is shown as "not in": a failed read is not an empty one. */
    FAILED,

    /** Rows are the server's last confirmed state plus the user's draft. */
    READY,

    /** A save is running. Rows are frozen and the save button is busy. */
    SAVING,

    /** After a save that did not fully succeed: reading the server again before allowing more edits. */
    CHECKING,
}

/** What one row says after a save, besides the check mark. Null for a confirmed change: success is quiet. */
data class RowOutcome(val text: String, val warning: Boolean)

data class MembershipRowUi(
    val target: EditablePlaylist,
    /** The server's state in the snapshot being edited. */
    val present: Boolean,
    /** The user's draft. Different from [present] means "will change on save". */
    val checked: Boolean,
    /** The playlist the sheet was opened from: listed first and labelled. */
    val pinned: Boolean = false,
    val outcome: RowOutcome? = null,
) {
    val changed: Boolean get() = checked != present
}

data class MembershipSheetUi(
    val song: SongSummary,
    val phase: MembershipPhase,
    val rows: List<MembershipRowUi> = emptyList(),
    /** One sentence above the list: why the last save or read did not finish. */
    val message: String? = null,
    val messageWarning: Boolean = false,
    val mode: MembershipMode = MembershipMode.PLAYLISTS,
    /** The platform can make a new playlist here; otherwise the "新建歌单" row says why not. */
    val canCreate: Boolean = false,
    /** The name dialog is open. */
    val naming: Boolean = false,
    /** A create request is running: the dialog's buttons are off so it cannot be sent twice. */
    val creating: Boolean = false,
    /** Why the platform refused the name; shown under the field. */
    val nameError: String? = null,
) {
    val changeCount: Int get() = rows.count { it.changed }
    val canSave: Boolean get() = phase == MembershipPhase.READY && changeCount > 0 && !creating
    val editable: Boolean get() = phase == MembershipPhase.READY && !creating
}

/**
 * What a finished save means for the rest of the app. The shell uses [results] to update the lists
 * it shows and shows [notice] as a pill; the notice is set only when the sheet cannot say it itself
 * (it closed on success, or the user already left). [accountGeneration] is the account the save was
 * made for: a result for an account that has since changed must not edit the new account's lists.
 * [startedAtMs] is when the save began, so a list read after it is known to include the change.
 */
data class MembershipSettled(
    val song: SongSummary,
    val results: List<PlaylistChangeResult>,
    val notice: String?,
    val accountGeneration: Long? = null,
    val startedAtMs: Long? = null,
)

/**
 * State of the "加入歌单" sheet for one song at a time. Owned by the shell, outside any menu, so a
 * save that is still running when the sheet is closed reports back through [onSettled].
 *
 * Rules it keeps (frontend brief and the membership contract):
 * - The snapshot that was read is the one that is written against, and only rows the user changed
 *   go into `desired`. A save never reuses a snapshot after it ran; it reads again first.
 * - Nothing is shown as saved before the server confirmed it. A partial result keeps the confirmed
 *   rows, marks the others, and rereads so the next decision starts from the server's state.
 * - A late reply to a sheet that was closed, reopened for another song or invalidated by an account
 *   change never touches the current sheet.
 */
class MembershipEditor(
    private val source: MembershipSource,
    private val onSettled: (MembershipSettled) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    var state: MembershipSheetUi? by mutableStateOf(null)
        private set

    /** Bumped by every open, close and account change; a reply carrying an older value is stale. */
    private var token = 0L
    private var snapshot: SongPlaylistMembership? = null
    private var pinned: MediaKey? = null

    /**
     * What the last partial save achieved, kept until the sheet closes or a new save starts. A reread,
     * a retry or an account change replaces the rows; this sentence is how the user still learns that
     * some lists were changed.
     */
    private var lastSaveSummary: String? = null

    /**
     * Opens the sheet for [song]. [pinned] is the playlist it was opened from, if any. [mode]
     * [MembershipMode.COLLECT] is the long press on the heart: "我喜欢" is listed first.
     */
    fun open(song: SongSummary, pinned: MediaKey? = null, mode: MembershipMode = MembershipMode.PLAYLISTS) {
        token++
        snapshot = null
        lastSaveSummary = null
        this.pinned = pinned
        state = MembershipSheetUi(
            song, MembershipPhase.LOADING, mode = mode,
            canCreate = source.canCreate(song.key.platform),
        )
        read(token)
    }

    /** Opens the name dialog. Only from a settled list: a new list joins the rows by a reread. */
    fun startCreate() {
        val current = state ?: return
        if (!current.editable || !current.canCreate) return
        state = current.copy(naming = true, nameError = null)
    }

    fun cancelCreate() {
        val current = state ?: return
        if (current.creating) return
        state = current.copy(naming = false, nameError = null)
    }

    /**
     * Makes a playlist named [name] with the song in it. The user's unsaved ticks survive the reread
     * that follows. A result that cannot be confirmed is never retried here: the reread shows whether
     * the list exists, and the user decides.
     */
    fun create(name: String) {
        val current = state ?: return
        val trimmed = name.trim()
        if (!current.naming || current.creating || playlistNameProblem(trimmed, current.song.key.platform) != null) return
        val mine = token
        val song = current.song
        val generation = snapshot?.accountGeneration
        state = current.copy(creating = true, nameError = null)
        source.create(song, trimmed) { response ->
            val live = mine == token
            response.fold(
                onSuccess = { made ->
                    val added = made.songResult.isSuccess
                    val text = if (added) "已新建「${made.target.playlist.title}」并加入"
                    else "已新建「${made.target.playlist.title}」，但歌曲没有加入：" +
                        writeFailureText(made.songResult.exceptionOrNull()?.let(::categoryOf)) + "，可勾选后保存"
                    val results = if (added) listOf(
                        PlaylistChangeResult(made.target.playlist.key, true, PlaylistChangeStatus.APPLIED, confirmedPresent = true),
                    ) else emptyList()
                    onSettled(MembershipSettled(song, results, if (live) null else text, generation, now()))
                    if (live) afterCreate(mine, text, warning = !added)
                },
                onFailure = { error ->
                    val category = categoryOf(error)
                    if (!live) {
                        onSettled(MembershipSettled(song, emptyList(), createFailureText(category), generation))
                        return@fold
                    }
                    val shown = state ?: return@fold
                    if (category == "invalid_request") {
                        // Nothing was sent: the name is the problem, so the dialog stays for a new one.
                        state = shown.copy(creating = false, nameError = "平台不接受这个名称，请换一个")
                    } else {
                        afterCreate(mine, createFailureText(category), warning = true)
                    }
                },
            )
        }
    }

    private fun afterCreate(mine: Long, message: String, warning: Boolean) {
        val current = state ?: return
        snapshot = null
        state = current.copy(
            phase = MembershipPhase.CHECKING, naming = false, creating = false, nameError = null,
            message = message, messageWarning = warning,
        )
        read(mine, keepDraft = true)
    }

    fun close() {
        token++
        snapshot = null
        lastSaveSummary = null
        state = null
    }

    fun retry() {
        val current = state ?: return
        if (current.phase != MembershipPhase.FAILED) return
        token++
        state = current.copy(phase = MembershipPhase.LOADING, message = lastSaveSummary, messageWarning = false)
        read(token)
    }

    /**
     * The account of the open song's platform changed (or reconnected). The snapshot belongs to the
     * old account: drop it and read again, so nothing can be written with it.
     */
    fun accountChanged() {
        val current = state ?: return
        token++
        snapshot = null
        state = MembershipSheetUi(
            current.song, MembershipPhase.LOADING,
            message = listOfNotNull(lastSaveSummary, "账号已变化，已重新读取").joinToString("；"),
            messageWarning = true,
            mode = current.mode,
            canCreate = source.canCreate(current.song.key.platform),
        )
        read(token)
    }

    fun toggle(playlist: MediaKey) {
        val current = state ?: return
        if (!current.editable) return
        state = current.copy(
            rows = current.rows.map {
                if (it.target.playlist.key == playlist) it.copy(checked = !it.checked, outcome = null) else it
            },
        )
    }

    fun save() {
        val current = state ?: return
        val base = snapshot ?: return
        if (!current.canSave) return
        val desired = current.rows.filter { it.changed }.associate { it.target.playlist.key to it.checked }
        val mine = token
        val startedAt = now()
        lastSaveSummary = null
        state = current.copy(phase = MembershipPhase.SAVING, message = null, rows = current.rows.map { it.copy(outcome = null) })
        source.update(current.song, base, desired) { response ->
            saved(mine, current.song, desired.size, base.accountGeneration, startedAt, response)
        }
    }

    private fun read(mine: Long, keepDraft: Boolean = false) {
        val song = state?.song ?: return
        source.read(song.key) { result ->
            if (mine != token) return@read
            val current = state ?: return@read
            result.fold(
                onSuccess = { loaded ->
                    snapshot = loaded
                    // Outcomes of the save that caused this reread stay next to their rows.
                    val outcomes = current.rows.associate { it.target.playlist.key to it.outcome }
                    // After a create, ticks the user had not saved yet are put back on the same lists.
                    val draft = if (keepDraft) current.rows.filter { it.changed }.associate { it.target.playlist.key to it.checked }
                    else emptyMap()
                    state = current.copy(
                        phase = MembershipPhase.READY,
                        rows = rowsOf(loaded, pinned, withLiked = current.mode == MembershipMode.COLLECT).map { row ->
                            row.copy(
                                outcome = outcomes[row.target.playlist.key],
                                checked = draft[row.target.playlist.key] ?: row.checked,
                            )
                        },
                    )
                },
                onFailure = { error ->
                    snapshot = null
                    val reason = readFailureText(categoryOf(error))
                    state = current.copy(
                        phase = MembershipPhase.FAILED,
                        rows = emptyList(),
                        // After a save, keep what it achieved in view: the reread failing does not undo it.
                        message = if (current.phase == MembershipPhase.CHECKING && current.message != null) {
                            "${current.message}。$reason，请重试核对"
                        } else reason,
                        messageWarning = true,
                    )
                },
            )
        }
    }

    private fun saved(
        mine: Long,
        song: SongSummary,
        requested: Int,
        generation: Long,
        startedAt: Long,
        response: Result<PlaylistMembershipResult>,
    ) {
        val live = mine == token
        // The snapshot was used: whatever happened, the next save needs a fresh read.
        if (live) snapshot = null
        fun settled(results: List<PlaylistChangeResult>, notice: String?) =
            onSettled(MembershipSettled(song, results, notice, generation, startedAt))
        response.fold(
            onSuccess = { batch ->
                val results = batch.results
                if (batch.successful) {
                    val notice = savedNotice(results)
                    if (live) close()
                    settled(results, notice)
                } else {
                    val summary = partialSummary(results, requested, rereading = live)
                    val current = state
                    if (live && current != null) {
                        lastSaveSummary = partialSummary(results, requested, rereading = false)
                            .substringBefore('；')
                        val byKey = results.associateBy { it.playlist }
                        state = current.copy(
                            phase = MembershipPhase.CHECKING,
                            message = summary,
                            // The failed rows carry the red; the summary only counts.
                            messageWarning = false,
                            rows = current.rows.map { row ->
                                row.copy(outcome = byKey[row.target.playlist.key]?.let(::outcomeOf))
                            },
                        )
                        read(mine)
                    }
                    settled(results, if (live) null else summary)
                }
            },
            onFailure = { error ->
                // Rejected before any write (busy, account changed, invalid snapshot): nothing changed.
                val category = categoryOf(error)
                if (live) {
                    val current = state
                    if (current != null) {
                        state = current.copy(
                            phase = MembershipPhase.CHECKING,
                            message = rejectedText(category),
                            messageWarning = true,
                        )
                        read(mine)
                    }
                }
                settled(emptyList(), if (live) null else "歌单没有更新：${rejectedText(category)}")
            },
        )
    }
}

/**
 * Ordinary playlists in the server's order, with the one the sheet was opened from first. With
 * [withLiked] (the heart's long press) "我喜欢" leads the list; otherwise it is not listed at all,
 * since the heart is its own control.
 */
internal fun rowsOf(snapshot: SongPlaylistMembership, pinned: MediaKey?, withLiked: Boolean = false): List<MembershipRowUi> {
    val liked = if (withLiked) snapshot.entries.filter { it.target.favorite }.take(1).map {
        MembershipRowUi(it.target, it.present, it.present)
    } else emptyList()
    val rows = snapshot.entries.filterNot { it.target.favorite }.map {
        MembershipRowUi(it.target, it.present, it.present, pinned = it.target.playlist.key == pinned)
    }
    return liked + rows.filter { it.pinned } + rows.filterNot { it.pinned }
}

/** Longest name the sheet lets through: QQ counts UTF-16 units up to 20, the others 80 characters. */
internal fun playlistNameProblem(name: String, platform: Platform): String? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> "请输入歌单名称"
        trimmed.any(Char::isISOControl) -> "名称里不能有换行等控制字符"
        platform == Platform.QQ && trimmed.length > 20 -> "QQ 音乐歌单名称最多 20 个字"
        trimmed.codePointCount(0, trimmed.length) > 80 -> "名称最多 80 个字"
        else -> null
    }
}

/** Why a new playlist was not made. An unconfirmed create is never retried: the reread decides. */
internal fun createFailureText(category: String): String = when (category) {
    "unsupported" -> "这个平台暂不支持在 Musxi 里新建歌单"
    "account_changed", "account_unverified", "expired", "account_unavailable" -> "账号已变化，没有新建歌单"
    "rate", "risk", "limit" -> "请求太频繁，稍后再试"
    "verification_required" -> "平台要求验证，请稍后在官方客户端新建"
    "network", "http", "timeout", "unavailable", "incomplete", "not_confirmed" ->
        "新建结果未确认，已重新读取；如列表里没有新歌单再重试"
    else -> "平台暂时没有接受新建"
}

internal fun categoryOf(error: Throwable): String = (error as? LibraryPageFailure)?.category ?: "unavailable"

/** Text for a confirmed full save; also used when the sheet has already closed itself. */
internal fun savedNotice(results: List<PlaylistChangeResult>): String {
    val added = results.count { it.requestedPresent }
    val removed = results.size - added
    return when {
        added > 0 && removed > 0 -> "已加入 $added 个歌单，移出 $removed 个"
        added > 0 -> "已加入 $added 个歌单"
        removed > 0 -> "已移出 $removed 个歌单"
        else -> "歌单没有变化"
    }
}

internal fun partialSummary(results: List<PlaylistChangeResult>, requested: Int, rereading: Boolean = true): String {
    val done = results.count { it.status == PlaylistChangeStatus.APPLIED || it.status == PlaylistChangeStatus.UNCHANGED }
    val unknown = results.count { it.status == PlaylistChangeStatus.UNKNOWN }
    val total = maxOf(requested, results.size)
    return buildString {
        append(if (done > 0) "$done 项已保存，${total - done} 项未完成" else "$total 项都没有完成")
        if (unknown > 0) append(if (rereading) "；有结果未确认，已重新读取核对" else "；有结果未确认，请重新打开歌单核对")
    }
}

/** A row's note after a save that did not fully succeed. A confirmed row says nothing. */
internal fun outcomeOf(result: PlaylistChangeResult): RowOutcome? = when (result.status) {
    PlaylistChangeStatus.APPLIED, PlaylistChangeStatus.UNCHANGED -> null
    // Not errors: the reread already shows the server's state, the user only needs to look again.
    PlaylistChangeStatus.CONFLICT -> RowOutcome("已在其他地方修改，请按当前状态重新选择", warning = false)
    PlaylistChangeStatus.UNKNOWN -> RowOutcome("结果未确认，已按最新读取显示", warning = false)
    PlaylistChangeStatus.FAILED -> RowOutcome(
        (if (result.requestedPresent) "没能加入：" else "没能移出：") + writeFailureText(result.error),
        warning = true,
    )
}

internal fun writeFailureText(category: String?): String = when (category) {
    "permission" -> "没有修改这个歌单的权限"
    "account_changed", "account_unverified", "expired", "account_unavailable" -> "账号已变化"
    "network", "http", "timeout", "unavailable" -> "网络异常"
    "rate", "risk", "limit" -> "请求太频繁，稍后再试"
    "not_confirmed" -> "平台没有确认这次修改"
    "verification_required" -> "平台要求验证，请稍后在官方客户端处理"
    else -> "平台暂时没有接受"
}

internal fun readFailureText(category: String): String = when (category) {
    "account_unverified", "account_changed", "expired", "account_unavailable" -> "账号未连接或已变化，请到「我的」确认登录"
    "limit" -> "歌单太多，暂时无法读取全部"
    "network", "http", "timeout", "unavailable" -> "网络异常，没能读取歌单"
    "rate", "risk" -> "请求太频繁，稍后再试"
    else -> "没能读取歌单"
}

internal fun rejectedText(category: String): String = when (category) {
    "busy" -> "上一次保存还在进行，已重新读取当前状态"
    "account_changed", "account_unverified" -> "账号已变化，没有保存，已重新读取"
    else -> "没有保存，已重新读取当前状态"
}
