package tech.musxi.playback

import kotlinx.coroutines.flow.MutableStateFlow
import tech.musxi.core.localaudio.LocalAudioItem
import tech.musxi.core.localaudio.LocalAudioLimits
import tech.musxi.core.localaudio.LocalAudioProgress
import tech.musxi.core.localaudio.SavedLocalAudioQueue

/** Source versions are opaque and backend-only; paths and grants never enter MediaItems. */
class LocalAudioQueueEntry(val audio: LocalAudioItem, val sourceVersion: String, val resumePositionMs: Long = 0) {
    init {
        require(sourceVersion.matches(Regex("[0-9a-f]{64}")))
        require(resumePositionMs in 0..LocalAudioLimits.MAX_POSITION_MS)
    }
    override fun toString() = "LocalAudioQueueEntry(${audio.id}, redacted)"
}

class LocalAudioPlaybackQueue(
    val folderId: String?,
    entries: List<LocalAudioQueueEntry>,
    val startIndex: Int,
    val positionMs: Long,
    val continuePlayback: Boolean = true,
    val repeatMode: String = "OFF",
    val shuffleEnabled: Boolean = false,
    shuffleOrder: List<Int>? = null,
    val useStoredProgress: Boolean = false,
) {
    val entries: List<LocalAudioQueueEntry> = java.util.Collections.unmodifiableList(entries.toList())
    val shuffleOrder: List<Int>? = shuffleOrder?.let { java.util.Collections.unmodifiableList(it.toList()) }
    init {
        folderId?.let(LocalAudioLimits::requireId)
        require(entries.size in 1..QueueSources.MAX_QUEUE_SIZE && startIndex in entries.indices)
        require(entries.map { it.audio.id }.toSet().size == entries.size)
        require(positionMs in 0..LocalAudioLimits.MAX_POSITION_MS)
        require(repeatMode in setOf("OFF", "ONE", "ALL"))
        require(this.shuffleOrder == null || (this.shuffleOrder.size == entries.size && this.shuffleOrder.toSet() == entries.indices.toSet()))
    }
}

/** Application adapter. resolve is loader-thread IO; checkpoint is nonblocking and serialized. */
interface LocalAudioPlaybackHost {
    fun resolve(entry: LocalAudioQueueEntry): String
    /** Local preference/visibility changes; never starts loading or preparing media. */
    val changes: kotlinx.coroutines.flow.Flow<Unit> get() = kotlinx.coroutines.flow.emptyFlow()
    fun currentItem(entry: LocalAudioQueueEntry): LocalAudioItem = entry.audio
    fun setPlaybackSpeed(audioId: String, speed: Float, result: (Result<Unit>) -> Unit) {
        result(Result.failure(IllegalStateException("Local preferences unavailable")))
    }
    /** Derived, app-owned image only; never the original audio/video document URI. */
    fun artworkUri(entry: LocalAudioQueueEntry): String? = null
    /** Fence a prepared payload again when its delayed mailbox command reaches the service. */
    fun isQueueCurrent(queue: LocalAudioPlaybackQueue): Boolean = true
    fun checkpoint(folderId: String?, audioId: String, positionMs: Long, completed: Boolean,
        result: (Result<Unit>) -> Unit = {})
    /** Metadata-only journal. A paused installation must not overwrite the track's old progress. */
    fun checkpointQueue(queue: SavedLocalAudioQueue, progress: LocalAudioProgress?, active: Boolean,
        result: (Result<Unit>) -> Unit = {}) {
        if (progress == null) result(Result.success(Unit))
        else checkpoint(queue.folderId, progress.audioId, progress.positionMs, progress.completed, result)
    }
    /** Main-thread callback. Rebuild fresh slots only; never prepare media or request focus. */
    fun restoreQueue(result: (Result<LocalAudioPlaybackQueue?>) -> Unit) { result(Result.success(null)) }
    fun deactivateQueue(clear: Boolean = false) = Unit
}

data class LocalAudioQueueState(
    val revision: Long = 0,
    val folderId: String? = null,
    val items: List<LocalAudioItem> = emptyList(),
    val currentIndex: Int = -1,
    val upcoming: List<Int> = emptyList(),
)

internal val localQueueSnapshot = MutableStateFlow(LocalAudioQueueState())
