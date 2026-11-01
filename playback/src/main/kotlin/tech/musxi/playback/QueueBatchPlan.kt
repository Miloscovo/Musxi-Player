package tech.musxi.playback

import tech.musxi.core.library.SongSummary

internal data class QueueBatchPlan(val insertionIndex: Int, val songs: List<SongSummary>)

internal fun planQueueBatch(current: List<SongSummary>, added: List<SongSummary>, at: QueueInsertAt): QueueBatchPlan? {
    if (added.isEmpty() || added.size > QueueSources.MAX_QUEUE_SIZE - current.size) return null
    val index = if (at == QueueInsertAt.HEAD) 0 else current.size
    return QueueBatchPlan(index, current.toMutableList().apply { addAll(index, added) })
}
