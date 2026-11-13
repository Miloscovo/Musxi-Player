package tech.musxi.player.localaudio

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.musxi.core.localaudio.*
import tech.musxi.playback.LocalAudioPlaybackHost
import tech.musxi.playback.LocalAudioPlaybackQueue
import tech.musxi.playback.LocalAudioQueueEntry

data class LocalAudioRestoreState(val ready: Boolean = false, val available: Boolean = false,
    val restoring: Boolean = false, val error: LocalAudioError? = null)

/** Application-scoped writer survives service teardown; no account or second player is involved. */
class AppLocalAudioPlaybackAdapter internal constructor(
    private val repository: AppLocalAudioRepository,
    private val dispatch: (LocalAudioPlaybackQueue, Boolean, (Result<Unit>) -> Unit) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val beforePrepare: ((Result<Unit>) -> Unit) -> Unit = { it(Result.success(Unit)) },
    private val journalStore: LocalAudioPlaybackJournalStore? = null,
    private val onNewIntent: () -> Unit = {},
) : LocalAudioPlaybackHost {
    override val changes = combine(repository.state.map { it.items }.distinctUntilChanged(), repository.artworkMode) { _, _ -> Unit }
    private var indexedItems: List<LocalAudioItem>? = null
    private var preferenceItems = emptyMap<String, LocalAudioItem>()
    private val preferenceLock = Any()
    override fun currentItem(entry: LocalAudioQueueEntry): LocalAudioItem {
        val latest = synchronized(preferenceLock) {
            val items = repository.state.value.items
            if (indexedItems !== items) { preferenceItems = items.associateBy { it.id }; indexedItems = items }
            preferenceItems[entry.audio.id]
        } ?: return entry.audio
        // Preserve the queue's recording metadata; only refresh preferences and derived images.
        return entry.audio.copy(playbackSpeed = latest.playbackSpeed, customArtworkKey = latest.customArtworkKey,
            metadata = entry.audio.metadata.copy(artworkKey = latest.metadata.artworkKey))
    }
    override fun setPlaybackSpeed(audioId: String, speed: Float, result: (Result<Unit>) -> Unit) {
        repository.setPlaybackSpeed(audioId, speed, result)
    }
    override fun artworkUri(entry: LocalAudioQueueEntry): String? = repository.displayArtworkFile(currentItem(entry))
        ?.let { android.net.Uri.fromFile(it).toString() }

    private class Write(val action: suspend () -> Unit, val callback: (Result<Unit>) -> Unit)
    private val checkpoints = Channel<Write>(Channel.UNLIMITED)
    private val journalLock = Mutex()
    private var journal: LocalAudioPlaybackJournal? = null
    private val restoring = MutableStateFlow(LocalAudioRestoreState())
    val restoreState = restoring.asStateFlow()
    private var request = 0L
    private var pendingPlays = 0
    private var submittedQueue: LocalAudioPlaybackQueue? = null
    private var submittedRequest = -1L
    private val writer = scope.launch {
        for (value in checkpoints) {
            try {
                value.action()
                value.callback(Result.success(Unit))
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val reason = (error as? LocalAudioFailure)?.error ?: LocalAudioError.STORAGE_FAILED
                restoring.value = restoring.value.copy(error = reason)
                value.callback(Result.failure(LocalAudioFailure(reason)))
            }
        }
    }

    /** Main-thread entry. Flush the old timeline before preparing fresh folder/track progress. */
    fun play(folderId: String?, audioId: String? = null, fromStart: Boolean = false, playWhenReady: Boolean = true,
        callback: (Result<Unit>) -> Unit = {}): Job {
        val token = ++request
        onNewIntent()
        pendingPlays++
        restoring.value = restoring.value.copy(restoring = false, error = null)
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                callback(playResult {
                    flush()
                    val queue = repository.preparePlayback(folderId, audioId, fromStart)
                    ensureCurrent(token)
                    stage(queue, token)
                    install(queue, playWhenReady)
                })
            } finally { pendingPlays-- }
        }
    }

    /** Invalidate an earlier preparation when an explicit online/stop intent takes over. */
    fun cancelPendingPlay() {
        request++
        submittedQueue = null
        restoring.value = restoring.value.copy(restoring = false)
    }

    /** Explicit restoration may recover the last local queue after switching to online music. */
    fun restoreLastQueue(callback: (Result<Unit>) -> Unit = {}): Job {
        val token = ++request
        onNewIntent()
        pendingPlays++
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                restoring.value = restoring.value.copy(restoring = true, error = null)
                val result = playResult {
                    flush()
                    val saved = accessJournal().queue ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
                    val queue = repository.prepareSavedPlayback(saved)
                    ensureCurrent(token)
                    stage(queue, token)
                    install(queue, false)
                }
                if (token == request) restoring.value = restoring.value.copy(restoring = false,
                    error = (result.exceptionOrNull() as? LocalAudioFailure)?.error)
                callback(result)
            } finally {
                pendingPlays--
                if (token == request) restoring.value = restoring.value.copy(restoring = false)
            }
        }
    }

    /** Service startup only. A newer user intent always wins; no media is opened here. */
    override fun restoreQueue(result: (Result<LocalAudioPlaybackQueue?>) -> Unit) {
        if (pendingPlays > 0) { result(Result.success(null)); return }
        val token = request
        scope.launch {
            restoring.value = restoring.value.copy(restoring = true, error = null)
            val response = try {
                val queue = withTimeout(15_000) {
                    val saved = accessJournal().takeIf { it.active }?.queue
                    saved?.let { repository.prepareSavedPlayback(it) }
                }
                if (queue != null && token == request) stage(queue, token)
                Result.success(queue.takeIf { token == request })
            } catch (_: TimeoutCancellationException) { Result.failure(LocalAudioFailure(LocalAudioError.READ_FAILED)) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { Result.failure(error as? LocalAudioFailure ?: LocalAudioFailure(LocalAudioError.STORAGE_FAILED)) }
            if (token == request) restoring.value = restoring.value.copy(restoring = false,
                error = (response.exceptionOrNull() as? LocalAudioFailure)?.error)
            result(if (token == request) response else Result.success(null))
        }
    }

    override fun resolve(entry: LocalAudioQueueEntry): String = runBlocking {
        repository.resolveForPlayback(entry.audio.id, entry.sourceVersion).readUri().toString()
    }

    override fun isQueueCurrent(queue: LocalAudioPlaybackQueue) = queue === submittedQueue && submittedRequest == request
    private fun stage(queue: LocalAudioPlaybackQueue, token: Long) { submittedQueue = queue; submittedRequest = token }

    override fun checkpoint(folderId: String?, audioId: String, positionMs: Long, completed: Boolean, result: (Result<Unit>) -> Unit) {
        enqueue(result) {
            saveProgress(folderId, LocalAudioProgress(audioId, positionMs, completed, System.currentTimeMillis().coerceAtLeast(0)))
        }
    }

    override fun checkpointQueue(queue: SavedLocalAudioQueue, progress: LocalAudioProgress?, active: Boolean, result: (Result<Unit>) -> Unit) {
        enqueue(result) {
            if (progress != null) saveProgress(queue.folderId, progress)
            accessJournal { old ->
                val next = if (old.queue?.let { sameLayout(it, queue) } == true) old.queue else queue
                LocalAudioPlaybackJournal(next, active)
            }
        }
    }

    override fun deactivateQueue(clear: Boolean) {
        enqueue({}) { accessJournal { old -> LocalAudioPlaybackJournal(if (clear) null else old.queue, false) } }
    }

    private fun enqueue(result: (Result<Unit>) -> Unit, action: suspend () -> Unit) {
        if (!writer.isActive || !checkpoints.trySend(Write(action, result)).isSuccess) {
            result(Result.failure(LocalAudioFailure(LocalAudioError.STORAGE_FAILED)))
        }
    }

    private suspend fun saveProgress(folder: String?, progress: LocalAudioProgress) {
        var result: Result<Unit>? = null
        repository.saveProgress(folder, progress) { result = it }.join()
        (result ?: Result.failure(LocalAudioFailure(LocalAudioError.STORAGE_FAILED))).getOrThrow()
    }

    private suspend fun accessJournal(change: ((LocalAudioPlaybackJournal) -> LocalAudioPlaybackJournal)? = null): LocalAudioPlaybackJournal {
        val value = withContext(Dispatchers.IO) {
            journalLock.withLock {
                val old = journal ?: (journalStore?.read() ?: LocalAudioPlaybackJournal())
                val next = change?.invoke(old) ?: old
                if (next != old) journalStore?.write(next)
                journal = next
                next
            }
        }
        restoring.value = restoring.value.copy(ready = true, available = value.queue != null, error = null)
        return value
    }

    // Position checkpoints already live in SQLite. Avoid rewriting a 20,000-slot file every 15s.
    private fun sameLayout(a: SavedLocalAudioQueue, b: SavedLocalAudioQueue) =
        a.folderId == b.folderId && a.index == b.index && a.repeatMode == b.repeatMode &&
            a.shuffleEnabled == b.shuffleEnabled && a.shuffleOrder == b.shuffleOrder &&
            a.continuePlayback == b.continuePlayback && a.useStoredProgress == b.useStoredProgress &&
            a.entries.size == b.entries.size && a.entries.indices.all {
                a.entries[it].audioId == b.entries[it].audioId && a.entries[it].sourceVersion == b.entries[it].sourceVersion
            } &&
            (a.useStoredProgress || a.positionMs == b.positionMs)

    private suspend fun flush() = withTimeout(15_000) {
        suspendCancellableCoroutine<Unit> { continuation -> beforePrepare { if (continuation.isActive) continuation.resumeWith(it) } }
    }
    private suspend fun install(queue: LocalAudioPlaybackQueue, ready: Boolean) = withTimeout(15_000) {
        suspendCancellableCoroutine<Unit> { continuation -> dispatch(queue, ready) { if (continuation.isActive) continuation.resumeWith(it) } }
    }
    private suspend fun ensureCurrent(token: Long) {
        currentCoroutineContext().ensureActive()
        if (token != request) throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
    }
    private suspend fun playResult(action: suspend () -> Unit): Result<Unit> = try {
        action(); Result.success(Unit)
    } catch (_: TimeoutCancellationException) { Result.failure(LocalAudioFailure(LocalAudioError.READ_FAILED)) }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { Result.failure(error as? LocalAudioFailure ?: LocalAudioFailure(LocalAudioError.READ_FAILED)) }
}
