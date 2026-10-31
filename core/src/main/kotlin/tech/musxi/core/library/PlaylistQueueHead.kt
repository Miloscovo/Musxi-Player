package tech.musxi.core.library

data class PlaylistQueueWindow(
    val before: List<SongSummary>,
    val head: List<SongSummary>,
    val after: List<SongSummary>,
    val startIndex: Int,
)

/** Splits a contiguous, display-ordered prefix around the page containing the selected song. */
fun playlistQueueWindow(knownPrefix: List<SongSummary>, startSong: MediaKey, pageSize: Int): PlaylistQueueWindow? {
    require(pageSize > 0)
    val index = knownPrefix.indexOfFirst { it.key == startSong }
    if (index < 0) return null
    val pageStart = index / pageSize * pageSize
    val pageEnd = minOf(pageStart + pageSize, knownPrefix.size)
    return PlaylistQueueWindow(
        before = knownPrefix.subList(0, pageStart).toList(),
        head = knownPrefix.subList(pageStart, pageEnd).toList(),
        after = knownPrefix.subList(pageEnd, knownPrefix.size).toList(),
        startIndex = index - pageStart,
    )
}

data class PlaylistQueueHead(val queue: PlaybackQueue, val remainder: QueueRemainder)

/** A visible 30-row prefix may end inside the backend's 100-row page. */
data class QueuePageRequest(val offset: Int, val limit: Int, val skip: Int) {
    fun <T> unseen(items: List<T>): List<T> {
        require(items.size <= limit && items.size >= skip)
        return items.drop(skip)
    }
}

fun playlistQueuePage(loadedCount: Int, limit: Int = 100): QueuePageRequest {
    require(loadedCount in 0..20_000 && limit in 1..100)
    return QueuePageRequest(loadedCount / limit * limit, limit, loadedCount % limit)
}

/** Remaining pages are fetched serially; cancellation fences late account or playlist results. */
class QueueRemainder(
    val before: List<SongSummary>,
    val after: List<SongSummary>,
    private val loadNextPage: ((Result<List<SongSummary>?>) -> Unit) -> Unit,
    private val cancelWork: () -> Unit,
) {
    private val gate = Any()
    private var cancelled = false
    private var finished = false
    private var loading = false

    fun loadNext(result: (Result<List<SongSummary>?>) -> Unit) {
        synchronized(gate) {
            if (cancelled) return
            if (finished) {
                result(Result.success(null))
                return
            }
            if (loading) {
                result(Result.failure(IllegalStateException("Queue page already loading")))
                return
            }
            loading = true
        }
        try {
            loadNextPage { response ->
                val deliver = synchronized(gate) {
                    loading = false
                    if (cancelled) false else {
                        if (response.getOrNull() == null && response.isSuccess) finished = true
                        true
                    }
                }
                if (deliver) result(response)
            }
        } catch (error: Exception) {
            synchronized(gate) { loading = false }
            result(Result.failure(error))
        }
    }

    fun cancel() {
        val shouldCancel = synchronized(gate) {
            if (cancelled) false else {
                cancelled = true
                true
            }
        }
        if (shouldCancel) cancelWork()
    }
}
