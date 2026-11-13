package tech.musxi.player.ui.localaudio

import tech.musxi.core.localaudio.LocalAudioAvailability
import tech.musxi.core.localaudio.LocalAudioDirectoryLink
import tech.musxi.core.localaudio.LocalAudioError
import tech.musxi.core.localaudio.LocalAudioFolder
import tech.musxi.core.localaudio.LocalAudioImportResult
import tech.musxi.core.localaudio.LocalAudioImportState
import tech.musxi.core.localaudio.LocalAudioItem
import tech.musxi.core.localaudio.LocalAudioLibraryState
import tech.musxi.core.localaudio.LocalAudioProgress
import tech.musxi.core.localaudio.LocalImportMode
import tech.musxi.player.ui.model.formatDuration

/**
 * Plain mappings from the local library state to what the 本地音频 pages show. No Android types, so
 * every wording and every derived number is checked by a JVM test.
 *
 * The library state is rebuilt whole on each write (it can hold 20,000 items), so callers build these
 * once per state with `remember(state)` instead of searching the lists inside every row.
 */

/** Where one recording stands for the listener. */
sealed interface ListenState {
    data object Unplayed : ListenState
    data class InProgress(val positionMs: Long, val durationMs: Long?) : ListenState {
        /** 0..1 for the thin bar under the row, null when the length is unknown. */
        val fraction: Float? get() = durationMs?.takeIf { it > 0 }?.let { (positionMs.toFloat() / it).coerceIn(0f, 1f) }
    }
    data object Finished : ListenState
}

internal fun listenStateOf(progress: LocalAudioProgress?, durationMs: Long?): ListenState = when {
    progress == null -> ListenState.Unplayed
    progress.completed -> ListenState.Finished
    progress.positionMs > 0 -> ListenState.InProgress(progress.positionMs, durationMs)
    else -> ListenState.Unplayed
}

/** The second line's progress part: "听到 23:18 / 1:02:40", "已听完", or nothing for unplayed. */
internal fun listenText(state: ListenState): String? = when (state) {
    ListenState.Unplayed -> null
    ListenState.Finished -> "已听完"
    is ListenState.InProgress -> buildString {
        append("听到 ").append(formatDuration(state.positionMs))
        state.durationMs?.takeIf { it > 0 }?.let { append(" / ").append(formatDuration(it)) }
    }
}

/** A short mark for a recording that cannot play as it is. Null when it can. */
internal fun availabilityText(availability: LocalAudioAvailability): String? = when (availability) {
    LocalAudioAvailability.AVAILABLE -> null
    LocalAudioAvailability.MISSING -> "原文件不在了"
    LocalAudioAvailability.PERMISSION_REQUIRED -> "需要重新授权"
    LocalAudioAvailability.UNREADABLE -> "暂时无法读取"
}

internal fun modeText(mode: LocalImportMode): String = when (mode) {
    LocalImportMode.COPY -> "已复制到 Musxi"
    LocalImportMode.REFERENCE -> "使用原文件"
}

/** One row of a folder, or of the "未归入文件夹" list. */
data class LocalAudioRowUi(
    val id: String,
    val title: String,
    /** "作者 · 听到 23:18 / 1:02:40", or "原文件不在了" when it cannot play. */
    val subtitle: String,
    val listen: ListenState,
    val unavailable: Boolean,
    val durationMs: Long?,
    val bookmarks: Int,
    /** Only a 使用原文件 recording can be pointed at another file. */
    val canRelink: Boolean,
    /** A video file played for its sound: the row says so, and one day it may offer to watch. */
    val video: Boolean = false,
    /**
     * The key of the cover to show, already filtered by the 本地封面 setting; the screen resolves it
     * to a file. Null: none, or a cover read from the file while that setting is 不读取.
     */
    val artworkKey: String? = null,
    /** The user picked this cover; the row menu then offers 恢复默认封面. */
    val artworkCustom: Boolean = false,
)

/** One folder on the list page. */
data class LocalFolderUi(
    val id: String,
    val name: String,
    val count: Int,
    /** "上次：第 3 条 · 23:18", "共 12 条" or "还没有音频". */
    val subtitle: String,
    val continuePlayback: Boolean,
    val linkedDirectories: Int,
    val unavailable: Int,
)

/** Everything the two pages derive from one library state. */
class LocalLibraryIndex(
    state: LocalAudioLibraryState,
    /** False under 本地封面 → 不读取: only covers the user picked are shown. */
    private val autoArtwork: Boolean = true,
) {
    val ready = state.ready
    val revision = state.revision
    private val items: Map<String, LocalAudioItem> = state.items.associateBy { it.id }
    private val progress: Map<String, LocalAudioProgress> = state.progress.associateBy { it.audioId }
    private val bookmarks: Map<String, Int> = state.bookmarks.groupingBy { it.audioId }.eachCount()
    private val cursors: Map<String, String> = state.cursors.associate { it.folderId to it.audioId }
    /** Member ids of each folder in its manual order. Positions may have gaps; only their order counts. */
    private val members: Map<String, List<String>> = state.members.groupBy { it.folderId }
        .mapValues { (_, list) -> list.sortedBy { it.position }.map { it.audioId }.filter { it in items } }
    private val filed: Set<String> = state.members.mapTo(HashSet()) { it.audioId }
    val folders: List<LocalAudioFolder> = state.folders
    val directories: List<LocalAudioDirectoryLink> = state.directories
    val totalItems: Int = state.items.size
    val unfiled: List<String> = state.items.filter { it.id !in filed }.map { it.id }

    fun folder(id: String): LocalAudioFolder? = folders.firstOrNull { it.id == id }
    fun memberIds(folderId: String): List<String> = members[folderId].orEmpty()
    fun item(id: String): LocalAudioItem? = items[id]
    fun bookmarksOf(id: String): Int = bookmarks[id] ?: 0

    fun row(id: String): LocalAudioRowUi? {
        val item = items[id] ?: return null
        val listen = listenStateOf(progress[id], item.metadata.durationMs)
        val unavailable = availabilityText(item.availability)
        val parts = listOfNotNull(
            item.metadata.creator?.takeIf { it.isNotBlank() },
            unavailable ?: listenText(listen) ?: item.metadata.durationMs?.takeIf { it > 0 }?.let(::formatDuration),
        )
        return LocalAudioRowUi(
            id = id,
            title = item.title,
            subtitle = parts.joinToString(" · "),
            listen = listen,
            unavailable = unavailable != null,
            durationMs = item.metadata.durationMs,
            bookmarks = bookmarksOf(id),
            canRelink = item.mode == LocalImportMode.REFERENCE,
            video = item.kind == tech.musxi.core.localaudio.LocalMediaKind.VIDEO,
            artworkKey = item.artworkKey.takeIf { item.artworkCustom || autoArtwork },
            artworkCustom = item.artworkCustom,
        )
    }

    fun rows(folderId: String): List<LocalAudioRowUi> = memberIds(folderId).mapNotNull(::row)

    fun folderUi(folder: LocalAudioFolder): LocalFolderUi {
        val ids = memberIds(folder.id)
        val cursorIndex = cursors[folder.id]?.let { ids.indexOf(it) }?.takeIf { it >= 0 }
        val subtitle = when {
            ids.isEmpty() -> "还没有音频"
            cursorIndex != null -> {
                val at = progress[ids[cursorIndex]]
                val where = when {
                    at == null || at.completed || at.positionMs <= 0 -> ""
                    else -> " · " + formatDuration(at.positionMs)
                }
                "上次：第 ${cursorIndex + 1} 条$where · 共 ${ids.size} 条"
            }
            else -> "共 ${ids.size} 条"
        }
        return LocalFolderUi(
            id = folder.id,
            name = folder.name,
            count = ids.size,
            subtitle = subtitle,
            continuePlayback = folder.continuePlayback,
            linkedDirectories = directories.count { it.folderId == folder.id },
            unavailable = ids.count { items[it]?.availability != LocalAudioAvailability.AVAILABLE },
        )
    }

    /**
     * What "继续播放" will pick, for the folder page's header: the cursor's item and where in it,
     * or null when the folder would start from its first item.
     */
    fun resumePoint(folderId: String): Pair<Int, Long>? {
        val ids = memberIds(folderId)
        val index = cursors[folderId]?.let { ids.indexOf(it) }?.takeIf { it >= 0 } ?: return null
        val at = progress[ids[index]]
        return index to if (at == null || at.completed) 0L else at.positionMs
    }
}

/** The line under the folder page's 继续播放 button. */
internal fun resumeLine(point: Pair<Int, Long>?, count: Int): String = when {
    count == 0 -> "导入音频后就可以开始听"
    point == null -> "从第 1 条开始"
    point.second > 0 -> "第 ${point.first + 1} 条 · ${formatDuration(point.second)}"
    else -> "从第 ${point.first + 1} 条开始"
}

/**
 * Friendly words for a library error. [busy] is set when another import, refresh, export or source
 * check is already running: the backend reports that clash as LIMIT_REACHED too.
 */
internal fun localErrorText(error: LocalAudioError?, busy: Boolean = false, limitText: String? = null): String = when (error) {
    null -> "操作没有完成，请重试"
    // The backend uses one code for several ceilings (batch size, library size, links); a caller
    // that knows which one it hit passes its own words.
    LocalAudioError.LIMIT_REACHED -> when {
        busy -> "已有任务在进行，请稍后再试"
        limitText != null -> limitText
        else -> "超出数量上限，每次最多 500 个"
    }
    LocalAudioError.INVALID_NAME -> "名称不能为空，也不能含特殊字符"
    LocalAudioError.NOT_FOUND -> "内容已不存在，可能已被删除或移出"
    LocalAudioError.INVALID_ORDER -> "列表已变化，请重试"
    LocalAudioError.READ_FAILED -> "暂时无法读取，云端文件可能仍在加载"
    LocalAudioError.STORAGE_FAILED -> "本地音频库暂时不可用"
    LocalAudioError.PERMISSION_REQUIRED -> "没有读取权限，请重新选择，或改用“复制到 Musxi”"
    // Also a video with no sound track: there is nothing in it to listen to.
    LocalAudioError.UNSUPPORTED_AUDIO -> "不是支持的音频或视频文件，或者视频没有声音"
    LocalAudioError.TOO_LARGE -> "文件太大"
    LocalAudioError.NOT_ENOUGH_SPACE -> "手机存储空间不足"
    LocalAudioError.SOURCE_CHANGED -> "原文件已变化，请重新选择"
    LocalAudioError.INVALID_BACKUP -> "备份文件已损坏，或不是 Musxi 整理备份"
    LocalAudioError.UNSUPPORTED_BACKUP -> "备份版本较新，当前版本无法读取"
    LocalAudioError.STALE_STATE -> "列表刚刚更新，请再试一次"
    LocalAudioError.INVALID_TARGET -> "所选位置不可用，请新建一个空文件"
}

/** The import panel's progress line while a batch runs. */
internal fun importProgressText(state: LocalAudioImportState): String = when {
    !state.running -> ""
    state.total == 0 -> "正在读取文件列表…"
    else -> buildString {
        append("正在导入 ").append(minOf(state.processed + 1, state.total)).append(" / ").append(state.total)
        state.currentName?.let { append(" · ").append(it) }
    }
}

/** One line after a batch: "已导入 8 个", or "已导入 6 个，2 个没有导入". */
internal fun importSummary(results: List<LocalAudioImportResult>): String {
    val failed = results.count { it.error != null }
    val done = results.size - failed
    return when {
        results.isEmpty() -> "没有找到音频或视频文件"
        failed == 0 -> "已导入 $done 个"
        done == 0 -> "$failed 个都没有导入"
        else -> "已导入 $done 个，$failed 个没有导入"
    }
}

/** "8.2 MB", for the info dialog. Null for an unknown size. */
internal fun sizeText(bytes: Long?): String? {
    if (bytes == null || bytes <= 0) return null
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return if (unit == 0) "$bytes B" else String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit])
}

/** "3 个文件夹 · 21 条", the library title's status line while the 本地音频 segment shows. */
fun localLibraryStatus(state: LocalAudioLibraryState): String = when {
    !state.ready && state.error != null -> "暂时打不开"
    !state.ready -> "正在打开…"
    else -> "${state.folders.size} 个文件夹 · ${state.items.size} 条"
}

/**
 * Why a name cannot be saved, or null when it can. Mirrors the backend's rule (trimmed, not blank,
 * at most 1024 characters, no control characters) so the button is off before a round trip fails.
 */
internal fun nameProblem(text: String): String? {
    val name = text.trim()
    return when {
        name.isEmpty() -> "名称不能为空"
        name.length > tech.musxi.core.localaudio.LocalAudioLimits.MAX_NAME -> "名称太长了"
        name.any { it.isISOControl() } -> "名称不能包含换行或控制字符"
        else -> null
    }
}

/**
 * One line after an import or refresh. The first failure's reason is added, because "2 个没有导入"
 * alone leaves the user guessing. A REFERENCE source that refuses lasting access gets the advice to
 * copy instead: that is the one fix the user can apply.
 */
internal fun importNotice(results: List<LocalAudioImportResult>, mode: LocalImportMode): String {
    val summary = importSummary(results)
    val first = results.firstOrNull { it.error != null }?.error ?: return summary
    if (results.all { it.error != null } && results.size == 1) return localImportErrorText(first, mode)
    return "$summary：${localImportErrorText(first, mode)}"
}

internal fun localImportErrorText(error: LocalAudioError, mode: LocalImportMode): String =
    if (mode == LocalImportMode.REFERENCE && error == LocalAudioError.PERMISSION_REQUIRED) "此来源不支持长期访问，可改用“复制到 Musxi”"
    else localErrorText(error)

/** After a manual refresh of a linked phone folder. */
internal fun refreshNotice(results: List<LocalAudioImportResult>, missingCount: Int, mode: LocalImportMode): String {
    val base = if (results.isEmpty()) "没有新的音频" else importNotice(results, mode)
    return if (missingCount > 0) "$base；$missingCount 个原文件已不在手机文件夹中" else base
}

/** The status under a linked phone folder in the folder menu. */
internal fun directoryStatus(link: LocalAudioDirectoryLink): String = when {
    link.error == LocalAudioError.PERMISSION_REQUIRED -> "需要重新授权"
    link.lastRefreshedAtMs == null -> "尚未刷新"
    link.missingCount > 0 -> "${link.missingCount} 个原文件已不在"
    else -> if (link.mode == LocalImportMode.COPY) "复制到 Musxi · 已刷新" else "使用原文件 · 已刷新"
}

/**
 * After a manual source check: "5 条可用，1 条有问题". A result the backend did not apply (the source
 * changed while it was read) counts as a problem: it says nothing about the current source.
 */
internal fun sourceCheckSummary(results: List<tech.musxi.core.localaudio.LocalAudioSourceCheckResult>): String {
    val fine = results.count { it.applied && it.error == null && it.availability == LocalAudioAvailability.AVAILABLE }
    val bad = results.size - fine
    return when {
        results.isEmpty() -> "没有检查任何音频"
        bad == 0 -> "$fine 条可用"
        results.all { !it.applied } -> "检查期间列表有变化，请重新检查"
        else -> "$fine 条可用，$bad 条有问题"
    }
}

/** The two ways to keep an import, and the contract's sentence for each. */
internal fun importModeLabel(mode: LocalImportMode): String = when (mode) {
    LocalImportMode.COPY -> "复制到 Musxi"
    LocalImportMode.REFERENCE -> "使用原文件"
}

internal fun importModeHint(mode: LocalImportMode): String = when (mode) {
    LocalImportMode.COPY -> "保存在 Musxi 中，导入成功后可自行删除原文件；卸载 Musxi 会删除这份应用副本。视频会整份复制，较占空间"
    LocalImportMode.REFERENCE -> "不复制音频；原文件需保留，移动、删除或撤回权限后需要重新关联"
}
