package tech.musxi.player.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tech.musxi.core.account.Platform
import tech.musxi.core.library.EditablePlaylist
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.PlaylistChangeStatus
import tech.musxi.core.library.PlaylistDeletionResult
import tech.musxi.core.library.PlaylistManagementInfo
import tech.musxi.core.library.PlaylistKind
import tech.musxi.core.library.PlaylistSummary

data class PlaylistMenuUi(
    val playlist: PlaylistSummary,
    val loading: Boolean = true,
    val info: PlaylistManagementInfo? = null,
    val error: String? = null,
)

data class PlaylistNameUi(
    val title: String,
    val initial: String,
    val platform: Platform,
    val target: EditablePlaylist? = null,
    val submitting: Boolean = false,
    val error: String? = null,
    val draft: String = initial,
)

data class PlaylistDeleteUi(val target: EditablePlaylist, val submitting: Boolean = false)

interface PlaylistManagementActions {
    fun capabilities(platform: Platform): tech.musxi.core.library.PlaylistManagementCapabilities
    fun info(key: MediaKey, result: (Result<PlaylistManagementInfo>) -> Unit)
    fun create(platform: Platform, name: String, result: (Result<EditablePlaylist>) -> Unit)
    fun rename(target: EditablePlaylist, name: String, result: (Result<Unit>) -> Unit)
    fun delete(target: EditablePlaylist, result: (Result<PlaylistDeletionResult>) -> Unit)
    fun refresh(platform: Platform)
}

/** Owns transient library management UI. Every remote answer is fenced to its account attempt. */
class PlaylistManagementController(
    private val actions: PlaylistManagementActions,
    private val onNotice: (String) -> Unit,
    private val onDeleted: (MediaKey) -> Unit,
) {
    var menu by mutableStateOf<PlaylistMenuUi?>(null)
        private set
    var naming by mutableStateOf<PlaylistNameUi?>(null)
        private set
    var deleting by mutableStateOf<PlaylistDeleteUi?>(null)
        private set
    private var platform = Platform.NETEASE
    private var generation: Long? = null
    private var fence = 0L
    private var page = 0L

    fun sync(selected: Platform, accountGeneration: Long?) {
        if (selected == platform && accountGeneration == generation) return
        platform = selected
        generation = accountGeneration
        fence++
        menu = null
        naming = null
        deleting = null
    }

    fun pageChanged() {
        page++
        dismissMenu()
        naming = null
        deleting = null
    }

    fun openMenu(playlist: PlaylistSummary) {
        if (naming != null || deleting != null) return
        if (playlist.kind == PlaylistKind.LIKED || playlist.key.platform != platform) return
        val token = fence
        val openedPage = page
        val expected = generation
        menu = PlaylistMenuUi(playlist)
        actions.info(playlist.key) { response ->
            if (token != fence || openedPage != page || expected != generation) return@info
            val current = menu?.takeIf { it.playlist.key == playlist.key } ?: return@info
            menu = response.fold(
                onSuccess = { current.copy(loading = false, info = it) },
                onFailure = { current.copy(loading = false, error = playlistManagementError(category(it))) },
            )
        }
    }

    fun dismissMenu() { menu = null }

    fun openCreate() {
        if (naming != null || deleting != null) return
        menu = null
        if (actions.capabilities(platform).create) naming = PlaylistNameUi("新建歌单", "", platform)
    }

    fun openRename() {
        val info = menu?.info?.takeIf { it.canRename } ?: return
        menu = null
        naming = PlaylistNameUi("重命名歌单", info.target.playlist.title, platform, info.target)
    }

    fun openDelete() {
        val info = menu?.info?.takeIf { it.canDelete } ?: return
        menu = null
        deleting = PlaylistDeleteUi(info.target)
    }

    fun dismissNaming() { if (naming?.submitting != true) naming = null }
    fun dismissDelete() { if (deleting?.submitting != true) deleting = null }

    fun submitName(name: String) {
        val current = naming?.takeUnless { it.submitting } ?: return
        if (playlistNameProblem(name, current.platform) != null) return
        val token = fence
        val expected = generation
        val openedPage = page
        val selected = current.platform
        naming = current.copy(submitting = true, error = null)
        fun complete(response: Result<String>) {
            if (token != fence || openedPage != page || expected != generation) return
            response.fold(onSuccess = { actual ->
                naming = null
                onNotice(if (current.target == null) "已创建“$actual”" else "已改名，正在刷新歌单")
            }, onFailure = {
                val category = category(it)
                when {
                    category in uncertainResults -> {
                        naming = null
                        actions.refresh(selected)
                        onNotice("结果未确认，已刷新列表，请核对后再操作")
                    }
                    category in terminalNameErrors -> {
                        naming = null
                        onNotice(playlistManagementError(category))
                    }
                    else -> {
                        naming = naming?.copy(draft = name, submitting = false, error = playlistManagementError(category))
                    }
                }
            })
        }
        current.target?.let { target -> actions.rename(target, name) { complete(it.map { target.playlist.title }) } }
            ?: actions.create(selected, name) { complete(it.map { created -> created.playlist.title }) }
    }

    fun confirmDelete() {
        val current = deleting?.takeUnless { it.submitting } ?: return
        val token = fence
        val expected = generation
        val openedPage = page
        val selected = current.target.playlist.key.platform
        deleting = current.copy(submitting = true)
        actions.delete(current.target) { response ->
            if (token != fence || openedPage != page || expected != generation) return@delete
            response.fold(onSuccess = { outcome ->
                when {
                    outcome.confirmedAbsent == true && outcome.status in setOf(PlaylistChangeStatus.APPLIED, PlaylistChangeStatus.UNCHANGED) -> {
                        deleting = null
                        onDeleted(outcome.playlist)
                        actions.refresh(selected)
                        onNotice(if (outcome.status == PlaylistChangeStatus.APPLIED) "已删除歌单" else "歌单已不存在，列表已刷新")
                    }
                    outcome.status == PlaylistChangeStatus.UNKNOWN -> {
                        deleting = null
                        actions.refresh(selected)
                        onNotice("结果未确认，请刷新核对")
                    }
                    outcome.confirmedAbsent == false -> {
                        deleting = null
                        if (outcome.error in uncertainResults) actions.refresh(selected)
                        val reason = if (outcome.error == "not_confirmed") "删除未生效" else playlistManagementError(outcome.error)
                        onNotice("$reason，歌单仍在")
                    }
                    else -> {
                        deleting = null
                        val cat = outcome.error
                        if (cat != "account_changed") actions.refresh(selected)
                        val message = if (cat == null || cat in uncertainResults) {
                            "未能确认是否已删除，已刷新列表，请核对"
                        } else {
                            "${playlistManagementError(cat)}，未能确认是否已删除，请刷新核对"
                        }
                        onNotice(message)
                    }
                }
            }, onFailure = {
                deleting = null
                val cat = category(it)
                if (cat in uncertainResults) {
                    actions.refresh(selected)
                    onNotice("未能确认是否已删除，已刷新列表，请核对")
                } else {
                    onNotice(playlistManagementError(cat))
                }
            })
        }
    }
}

private val uncertainResults = setOf("network", "timeout", "http", "not_confirmed", "unknown", "unavailable", "incomplete", "protocol")
private val terminalNameErrors = setOf("permission", "unsupported", "account_changed", "account_unverified", "account_unavailable")
private fun category(error: Throwable): String = (error as? LibraryPageFailure)?.category ?: "unavailable"

fun playlistNameProblem(name: String, platform: Platform): String? = when {
    name.isBlank() -> "请输入歌单名称"
    name.any(Char::isISOControl) -> "名称不能包含控制字符"
    name.codePointCount(0, name.length) > 80 -> "名称最多 80 个字符"
    platform == Platform.QQ && name.trim().length > 20 -> "QQ 音乐歌单名称最多 20 个字符"
    else -> null
}

fun playlistManagementError(category: String?): String = when (category) {
    "busy" -> "该平台有操作进行中，请稍后再试"
    "account_changed" -> "账号已变化，请重新读取当前歌单"
    "account_unavailable", "account_unverified" -> "请先连接并验证平台账号"
    "conflict" -> "列表已变化，请重新操作"
    "incomplete" -> "请先加载完整歌单列表"
    "storage" -> "顺序保存失败，请检查存储空间后重试"
    "permission" -> "当前账号没有此歌单的操作权限"
    "unsupported" -> "当前平台暂不支持此操作"
    "risk", "verification_required" -> "平台要求验证，请按平台提示完成后再试"
    "rate", "limit" -> "操作过于频繁，请稍后再试"
    "invalid_request", "request" -> "歌单信息或名称不符合要求，请重新读取"
    "network", "timeout", "http" -> "网络异常，请检查连接后刷新核对"
    "not_confirmed" -> "结果未确认，请刷新核对"
    else -> "操作未完成，请稍后重试"
}
