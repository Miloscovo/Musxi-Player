package tech.musxi.core.library

import tech.musxi.core.account.Platform

/** One gate per platform browser, shared by manual paging and cache filling. UI executor only.
 * Delays yield the executor; neither account work nor audio waits on a sleeping worker.
 */
class LibraryPageRequestGate(
    private val fetch: LibraryPageFetch,
    platform: Platform,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val elapsedRealtime: () -> Long,
) : LibraryPageFetch {
    private val intervalMs = when (platform) {
        Platform.NETEASE -> 500L
        Platform.QQ -> 750L
        Platform.KUGOU -> 1_000L
    }
    private class Request(val current: () -> Boolean, val start: () -> Unit, val cancel: () -> Unit)
    private val pending = ArrayDeque<Request>()
    private var busy = false
    private var nextAllowedAt = 0L

    override fun playlists(offset: Int, result: (Result<Page<PlaylistSummary>>) -> Unit) =
        playlists(offset, { true }, result)

    override fun tracks(playlist: MediaKey, offset: Int, result: (Result<Page<SongSummary>>) -> Unit) =
        tracks(playlist, offset, { true }, result)

    override fun playlists(offset: Int, isCurrent: () -> Boolean, result: (Result<Page<PlaylistSummary>>) -> Unit) =
        enqueue(isCurrent, result) { callback -> fetch.playlists(offset, isCurrent, callback) }

    override fun tracks(playlist: MediaKey, offset: Int, isCurrent: () -> Boolean,
        result: (Result<Page<SongSummary>>) -> Unit) =
        enqueue(isCurrent, result) { callback -> fetch.tracks(playlist, offset, isCurrent, callback) }

    private fun <T> enqueue(current: () -> Boolean, result: (Result<T>) -> Unit,
        start: ((Result<T>) -> Unit) -> Unit) {
        pending.addLast(Request(current, {
            var accepted = false
            fun finish(response: Result<T>) {
                if (accepted) return
                accepted = true
                val code = (response.exceptionOrNull() as? LibraryPageFailure)?.category
                val delay = when (code) { "rate" -> 30_000L; "risk" -> 60_000L; else -> intervalMs }
                nextAllowedAt = elapsedRealtime() + delay
                busy = false
                try { result(response) } finally { pump() }
            }
            try { start(::finish) }
            catch (_: Exception) { finish(Result.failure(LibraryPageFailure("unclassified"))) }
        }, { result(Result.failure(LibraryPageFailure("cancelled"))) }))
        pump()
    }

    private fun pump() {
        if (busy || pending.isEmpty()) return
        busy = true
        schedule((nextAllowedAt - elapsedRealtime()).coerceAtLeast(0)) {
            val request = pending.removeFirst()
            if (request.current()) request.start()
            else {
                busy = false
                try { request.cancel() } finally { pump() }
            }
        }
    }
}
