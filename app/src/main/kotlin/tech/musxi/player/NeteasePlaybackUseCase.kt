package tech.musxi.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.musxi.accounts.NeteaseLibraryRepository
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.PlaybackQueue
import tech.musxi.playback.PlaybackConnection

data class QueueLoadState(val loading: Boolean = false, val error: String? = null)

/** Process-owned orchestration. Pages supply IDs and observe state; they never handle stream URLs. */
class NeteasePlaybackUseCase(
    private val loadQueue: (MediaKey, MediaKey, (Result<PlaybackQueue>) -> Unit) -> Unit,
    private val submitQueue: (PlaybackQueue, (Result<Unit>) -> Unit) -> Unit,
    private val stopPlayer: () -> Unit,
) {
    constructor(library: NeteaseLibraryRepository, playback: PlaybackConnection) : this(library::queue, playback::playQueue, playback::stop)
    private val mutable = MutableStateFlow(QueueLoadState())
    val state = mutable.asStateFlow()
    private var request = 0L

    fun playPlaylist(playlist: MediaKey, startSong: MediaKey) {
        val token = ++request
        stopPlayer()
        mutable.value = QueueLoadState(loading = true)
        loadQueue(playlist, startSong) callback@{ response ->
            if (token != request) return@callback
            response.onSuccess { queue ->
                try { submitQueue(queue) { accepted ->
                    if (token == request) mutable.value = QueueLoadState(error = if (accepted.isSuccess) null else "player")
                } }
                catch (_: Exception) { mutable.value = QueueLoadState(error = "player") }
            }.onFailure { error ->
                mutable.value = QueueLoadState(error = (error as? tech.musxi.core.library.LibraryPageFailure)?.category ?: "unclassified")
            }
        }
    }
    fun cancelPending() { ++request; mutable.value = QueueLoadState() }
    fun stop() { cancelPending(); stopPlayer() }
}
