package tech.musxi.player

import android.os.Handler
import android.os.Looper
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.musxi.accounts.NeteaseLibraryRepository
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.PlaybackQueue
import tech.musxi.core.library.SongSummary
import tech.musxi.core.library.ArtworkPolicy
import tech.musxi.core.library.PlaylistQueueHead
import tech.musxi.playback.PlaybackConnection
import tech.musxi.playback.QueueInsertAt

data class QueueLoadState(val loading: Boolean = false, val error: String? = null)

/** Process-owned orchestration. Pages supply IDs and observe state; they never handle stream URLs. */
class NeteasePlaybackUseCase(
    private val loadQueue: (MediaKey, MediaKey, (Result<PlaybackQueue>) -> Unit) -> Unit,
    private val submitQueue: (PlaybackQueue, (Result<Unit>) -> Unit) -> Unit,
    private val stopPlayer: () -> Unit,
    private val generationFor: (MediaKey) -> Long? = { null },
    private val submitRestoredQueue: ((PlaybackQueue, tech.musxi.core.playback.SavedPlaybackQueue, (Result<Unit>) -> Unit) -> Unit)? = null,
    private val onNewIntent: () -> Unit = {},
    private val loadQueueHead: ((MediaKey, MediaKey, List<SongSummary>, (Result<PlaylistQueueHead>) -> Unit) -> Unit)? = null,
    private val enqueueBatch: ((List<SongSummary>, QueueInsertAt, String, String, (Result<Unit>) -> Unit) -> Unit)? = null,
    private val currentQueueEpoch: () -> String = { "" },
    private val scheduleBatchRetry: (Long, () -> Unit) -> Unit = { delay, action ->
        Handler(Looper.getMainLooper()).postDelayed(action, delay)
    },
) {
    constructor(library: NeteaseLibraryRepository, playback: PlaybackConnection) : this(library::queue, playback::playQueue, playback::stop)
    private val mutable = MutableStateFlow(QueueLoadState())
    val state = mutable.asStateFlow()
    private var request = 0L
    private var activeRemainder: tech.musxi.core.library.QueueRemainder? = null
    private var sourcePlaylist: MediaKey? = null

    fun playPlaylist(playlist: MediaKey, startSong: MediaKey, knownSongs: List<SongSummary> = emptyList()) {
        val loadHead = loadQueueHead
        if (loadHead != null && knownSongs.isNotEmpty()) {
            startPlaylistHead(playlist, startSong, knownSongs, loadHead)
            return
        }
        start(load = { callback -> loadQueue(playlist, startSong, callback) }, playlistSource = playlist)
    }

    /** Search selection replaces the timeline with one account-bound item; no synthetic playlist ID. */
    fun playSong(song: SongSummary) {
        sourcePlaylist = null
        val generation = generationFor(song.key)
        if (generation == null) {
            onNewIntent(); cancelPending(); mutable.value = QueueLoadState(error = "account_unavailable"); return
        }
        start(load = { callback -> callback(Result.success(PlaybackQueue(listOf(song.copy(artists = song.artists.toList(),
            coverUrl = ArtworkPolicy.sanitize(song.key.platform, song.coverUrl))), 0, generation))) })
    }

    /** A complete recommendation snapshot already has an account generation and no playlist ID. */
    fun playSnapshot(queue: PlaybackQueue, result: (Result<Unit>) -> Unit = {}) {
        sourcePlaylist = null
        val first = queue.songs.first()
        if (queue.songs.any { it.key.platform != first.key.platform } || generationFor(first.key) != queue.accountGeneration) {
            result(Result.failure(tech.musxi.core.library.LibraryPageFailure("account_changed"))); return
        }
        start({ callback -> callback(Result.success(queue)) }, result = result)
    }

    fun restore(saved: tech.musxi.core.playback.SavedPlaybackQueue, result: (Result<Unit>) -> Unit) {
        sourcePlaylist = null
        val generation = saved.songs.firstOrNull()?.key?.let(generationFor)
        val submit = submitRestoredQueue
        if (generation == null || submit == null) {
            result(Result.failure(tech.musxi.core.library.LibraryPageFailure("account_unavailable"))); return
        }
        val queue = runCatching { PlaybackQueue(saved.songs, saved.index, generation) }.getOrElse {
            result(Result.failure(tech.musxi.core.library.LibraryPageFailure("invalid_queue"))); return
        }
        start({ callback -> callback(Result.success(queue)) },
            { value, accepted -> submit(value, saved, accepted) }, result, false)
    }

    private fun start(load: ((Result<PlaybackQueue>) -> Unit) -> Unit,
        submit: (PlaybackQueue, (Result<Unit>) -> Unit) -> Unit = submitQueue,
        result: (Result<Unit>) -> Unit = {}, notifyIntent: Boolean = true, playlistSource: MediaKey? = null) {
        if (notifyIntent) onNewIntent()
        sourcePlaylist = playlistSource
        cancelRemainder()
        val token = ++request
        mutable.value = QueueLoadState(loading = true)
        load callback@{ response ->
            if (token != request) return@callback
            response.onSuccess { queue ->
                try { submit(queue) { accepted ->
                    if (token == request) {
                        mutable.value = QueueLoadState(error = if (accepted.isSuccess) null else "player")
                        result(accepted)
                    }
                } }
                catch (_: Exception) {
                    if (token == request) {
                        mutable.value = QueueLoadState(error = "player")
                        result(Result.failure(tech.musxi.core.library.LibraryPageFailure("player")))
                    }
                }
            }.onFailure { error ->
                mutable.value = QueueLoadState(error = (error as? tech.musxi.core.library.LibraryPageFailure)?.category ?: "unclassified")
            }
        }
    }

    private fun startPlaylistHead(playlist: MediaKey, startSong: MediaKey, knownSongs: List<SongSummary>,
        loadHead: (MediaKey, MediaKey, List<SongSummary>, (Result<PlaylistQueueHead>) -> Unit) -> Unit) {
        onNewIntent()
        sourcePlaylist = playlist
        cancelRemainder()
        val token = ++request
        mutable.value = QueueLoadState(loading = true)
        try {
            loadHead(playlist, startSong, knownSongs) { response ->
                if (token != request) return@loadHead
                response.onSuccess { head ->
                    activeRemainder = head.remainder
                    try {
                        submitQueue(head.queue) { accepted ->
                            if (token != request) return@submitQueue
                            if (accepted.isFailure) {
                                finishRemainder(token, head.remainder, "player")
                            } else {
                                val epoch = currentQueueEpoch()
                                if (epoch.isBlank()) finishRemainder(token, head.remainder, "player")
                                else insertKnownRemainder(token, head.remainder, epoch, UUID.randomUUID().toString())
                            }
                        }
                    } catch (_: Exception) {
                        finishRemainder(token, head.remainder, "player")
                    }
                }.onFailure { error ->
                    mutable.value = QueueLoadState(error = (error as? tech.musxi.core.library.LibraryPageFailure)?.category ?: "unclassified")
                }
            }
        } catch (_: Exception) {
            if (token == request) mutable.value = QueueLoadState(error = "unclassified")
        }
    }

    private fun insertKnownRemainder(token: Long, remainder: tech.musxi.core.library.QueueRemainder,
        epoch: String, batchPrefix: String) {
        val known = listOf(QueueInsertAt.HEAD to remainder.before, QueueInsertAt.TAIL to remainder.after)
        fun insertKnownAt(index: Int) {
            if (token != request) return
            if (index >= known.size) {
                loadNextRemainder(token, remainder, epoch, batchPrefix, 0)
                return
            }
            val (at, songs) = known[index]
            if (songs.isEmpty()) {
                insertKnownAt(index + 1)
                return
            }
            submitBatch(token, songs, at, "$batchPrefix:$index", epoch) { accepted ->
                if (accepted.isFailure) finishRemainder(token, remainder, "player")
                else insertKnownAt(index + 1)
            }
        }
        insertKnownAt(0)
    }

    private fun loadNextRemainder(token: Long, remainder: tech.musxi.core.library.QueueRemainder,
        epoch: String, batchPrefix: String, pageIndex: Int) {
        if (token != request) return
        remainder.loadNext { response ->
            if (token != request) return@loadNext
            response.onSuccess { songs ->
                if (songs == null) {
                    activeRemainder = null
                    mutable.value = QueueLoadState()
                } else if (songs.isEmpty()) {
                    loadNextRemainder(token, remainder, epoch, batchPrefix, pageIndex)
                } else {
                    submitBatch(token, songs, QueueInsertAt.TAIL, "$batchPrefix:page:$pageIndex", epoch) { accepted ->
                        if (accepted.isFailure) finishRemainder(token, remainder, "player")
                        else loadNextRemainder(token, remainder, epoch, batchPrefix, pageIndex + 1)
                    }
                }
            }.onFailure { error ->
                finishRemainder(token, remainder,
                    (error as? tech.musxi.core.library.LibraryPageFailure)?.category ?: "unclassified")
            }
        }
    }

    private fun submitBatch(token: Long, songs: List<SongSummary>, at: QueueInsertAt,
        batchId: String, epoch: String, attempt: Int = 0, result: (Result<Unit>) -> Unit) {
        if (token != request) return
        val submit = enqueueBatch ?: return result(Result.failure(IllegalStateException("Batch insert unavailable")))
        try {
            submit(songs, at, batchId, epoch) { accepted ->
                if (token != request) return@submit
                if (accepted.isSuccess || attempt >= MAX_BATCH_RETRIES) result(accepted)
                else scheduleBatchRetry(RETRY_BATCH_MS * (attempt + 1)) {
                    if (token == request) submitBatch(token, songs, at, batchId, epoch, attempt + 1, result)
                }
            }
        } catch (_: Exception) {
            if (attempt >= MAX_BATCH_RETRIES) result(Result.failure(IllegalStateException("Batch insert rejected")))
            else scheduleBatchRetry(RETRY_BATCH_MS * (attempt + 1)) {
                if (token == request) submitBatch(token, songs, at, batchId, epoch, attempt + 1, result)
            }
        }
    }

    private fun finishRemainder(token: Long, remainder: tech.musxi.core.library.QueueRemainder, error: String) {
        if (token != request) return
        remainder.cancel()
        activeRemainder = null
        mutable.value = QueueLoadState(error = error)
    }

    private fun cancelRemainder() {
        activeRemainder?.cancel()
        activeRemainder = null
    }

    fun cancelPending() { ++request; cancelRemainder(); sourcePlaylist = null; mutable.value = QueueLoadState() }
    /** Stop only pending loading from the deleted source; keep already accepted playback intact. */
    fun playlistDeleted(key: MediaKey) { if (sourcePlaylist == key) cancelPending() }
    fun stop() { onNewIntent(); cancelPending(); stopPlayer() }

    private companion object {
        const val MAX_BATCH_RETRIES = 4
        const val RETRY_BATCH_MS = 120L
    }
}
