package tech.musxi.player.ui.player

import java.util.Locale
import tech.musxi.core.localaudio.LocalAudioAvailability
import tech.musxi.core.localaudio.LocalAudioError
import tech.musxi.core.localaudio.LocalAudioFolder
import tech.musxi.core.localaudio.LocalAudioItem
import tech.musxi.core.localaudio.LocalImportMode
import tech.musxi.playback.LocalAudioQueueState
import tech.musxi.playback.PlaybackState
import tech.musxi.player.ui.model.formatDuration

/**
 * A recording from the local library is the current item. The controller's item ID is checked as
 * well, because the local queue snapshot can trail the player's timeline by a moment during a
 * change; without it the page would flash platform controls between two local recordings.
 */
fun isLocalPlayback(state: PlaybackState): Boolean =
    state.localAudio != null || state.mediaId?.startsWith(LOCAL_MEDIA_PREFIX) == true

/** The controller's media ID prefix for a local queue slot (`local:<audioId>`), as PlaybackConnection matches it. */
private const val LOCAL_MEDIA_PREFIX = "local:"

/**
 * Why a local recording did not play, said about the file. A file on the phone has no source,
 * account or platform, so none of those words appear, and nothing here suggests another version:
 * a local read failure pauses instead of switching to a platform song.
 */
fun localAudioErrorText(error: LocalAudioError?): String = when (error) {
    LocalAudioError.PERMISSION_REQUIRED -> "需要重新授权访问这个文件"
    LocalAudioError.NOT_FOUND -> "文件已不存在或已移动"
    LocalAudioError.SOURCE_CHANGED -> "文件已被替换，请重新选择"
    LocalAudioError.UNSUPPORTED_AUDIO -> "不支持这个音频格式"
    LocalAudioError.READ_FAILED -> "读取失败，请检查文件"
    LocalAudioError.TOO_LARGE -> "文件太大，无法播放"
    else -> "音频暂时无法播放"
}

/** A file's state in words. The same phrase marks a queue row and fills the info dialog. */
fun localAvailabilityText(availability: LocalAudioAvailability): String = when (availability) {
    LocalAudioAvailability.AVAILABLE -> "可用"
    LocalAudioAvailability.MISSING -> "文件已不存在"
    LocalAudioAvailability.PERMISSION_REQUIRED -> "需要重新授权"
    LocalAudioAvailability.UNREADABLE -> "无法读取"
}

/** The name of the folder a local queue was started from; null for a single recording or a deleted folder. */
fun localFolderNameOf(folderId: String?, folders: List<LocalAudioFolder>): String? =
    folderId?.let { id -> folders.firstOrNull { it.id == id }?.name }

/** The title block's third line while a recording plays normally: its folder, never a path or URI. */
fun localSourceLineOf(folderName: String?): String = folderName?.takeIf { it.isNotBlank() } ?: "本地音频"

/**
 * The queue panel for a local queue. A recording without an author keeps an empty second line
 * ("未知歌手" would call it a song), and a row whose file cannot be read says so instead of looking
 * like any other row. Keys are unique: the service refuses a local queue that repeats an audio ID.
 */
/**
 * [cover] turns a recording into its cover's address (the picked one, or the file's own while
 * 读取本地封面 is on); null when it has none, which leaves the title's placeholder.
 */
fun localQueueUiOf(
    state: LocalAudioQueueState,
    editing: Boolean,
    cover: (LocalAudioItem) -> String? = { null },
): QueueUi = QueueUi(
    items = state.items.map { item ->
        QueueRowUi(
            key = LOCAL_MEDIA_PREFIX + item.id,
            title = item.title,
            artist = item.metadata.creator.orEmpty(),
            durationMs = item.metadata.durationMs ?: 0L,
            coverUrl = cover(item),
            unavailable = item.availability
                .takeIf { it != LocalAudioAvailability.AVAILABLE }
                ?.let(::localAvailabilityText),
        )
    },
    currentIndex = state.currentIndex,
    editing = editing,
    revision = state.revision,
    upcoming = state.upcoming,
    noun = "条",
)

/**
 * The cover beside slot [index] of a local queue, [step] rows away. [index] is the player's own
 * position, the one the cover swipe is anchored to. The cover comes from [cover], as in the queue;
 * without one it is the placeholder seeded by the title, the seed the player's own cover uses. Under
 * shuffle the next recording is not the next row, so none is drawn, as for platform songs.
 */
internal fun localNeighbourCover(
    state: LocalAudioQueueState,
    index: Int,
    step: Int,
    shuffle: Boolean,
    cover: (LocalAudioItem) -> String? = { null },
): CoverRef? {
    if (shuffle || index < 0) return null
    return state.items.getOrNull(index + step)?.let { CoverRef(it.title, cover(it)) }
}

/**
 * The "音频信息" rows: public metadata only, never a path or document URI. [playerDurationMs] is the
 * player's own measure and wins over the stored one once known. Unknown values are left out.
 */
fun localAudioInfoRows(
    item: LocalAudioItem,
    playerDurationMs: Long,
    folderName: String?,
): List<Pair<String, String>> {
    val duration = playerDurationMs.takeIf { it > 0 } ?: item.metadata.durationMs?.takeIf { it > 0 }
    return listOf(
        "作者" to item.metadata.creator.orEmpty(),
        "时长" to duration?.let(::formatDuration).orEmpty(),
        "文件名" to item.metadata.fileName,
        "大小" to item.metadata.sizeBytes?.let(::localSizeText).orEmpty(),
        "保存方式" to when (item.mode) {
            LocalImportMode.COPY -> "已复制到 Musxi"
            LocalImportMode.REFERENCE -> "使用原文件"
        },
        "状态" to localAvailabilityText(item.availability),
        "文件夹" to folderName.orEmpty(),
    ).filter { it.second.isNotBlank() }
}

/** "8.2 MB": binary units, one decimal above bytes. */
internal fun localSizeText(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}
