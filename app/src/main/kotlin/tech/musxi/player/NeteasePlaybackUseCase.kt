package tech.musxi.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.musxi.accounts.NeteaseLibraryRepository
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.PlaybackQueue
import tech.musxi.core.library.SongSummary
import tech.musxi.core.library.ArtworkPolicy
import tech.musxi.playback.PlaybackConnection

data class QueueLoadState(val loading: Boolean = false, val error: String? = null)

/** Process-owned orchestration. Pages supply IDs and observe state; they never handle stream URLs. */
class NeteasePlaybackUseCase(
    private val loadQueue: (MediaKey, MediaKey, (Result<PlaybackQueue>) -> Unit) -> Unit,
    private val submitQueue: (PlaybackQueue, (Result<Unit>) -> Unit) -> Unit,
    private val stopPlayer: () -> Unit,
    private val generationFor: (MediaKey) -> Long? = { null },
    private val submitRestoredQueue: ((PlaybackQueue, tech.musxi.core.playback.SavedPlaybackQueue, (Result<Unit>) -> Unit) -> Unit)? = null,
    private val onNewIntent: () -> Unit = {},
) {
    constructor(library: NeteaseLibraryRepository, playback: PlaybackConnection) : this(library::queue, playback::playQueue, playback::stop)
    private val mutable = MutableStateFlow(QueueLoadState())
    val state = mutable.asStateFlow()
    private var request = 0L

    fun playPlaylist(playlist: MediaKey, startSong: MediaKey) {
        start(load = { callback -> loadQueue(playlist, startSong, callback) })
    }

    /** Search selection replaces the timeline with one account-bound item; no synthetic playlist ID. */
    fun playSong(song: SongSummary) {
        val generation = generationFor(song.key)
        if (generation == null) {
            onNewIntent(); cancelPending(); stopPlayer(); mutable.value = QueueLoadState(error = "account_unavailable"); return
        }
        start(load = { callback -> callback(Result.success(PlaybackQueue(listOf(song.copy(artists = song.artists.toList(),
            coverUrl = ArtworkPolicy.sanitize(song.key.platform, song.coverUrl))), 0, generation))) })
    }

    fun restore(saved: tech.musxi.core.playback.SavedPlaybackQueue, result: (Result<Unit>) -> Unit) {
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
        result: (Result<Unit>) -> Unit = {}, notifyIntent: Boolean = true) {
        if (notifyIntent) onNewIntent()
        val token = ++request
        stopPlayer()
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
    fun cancelPending() { ++request; mutable.value = QueueLoadState() }
    fun stop() { onNewIntent(); cancelPending(); stopPlayer() }
}
