package tech.musxi.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.concurrent.Executor
import tech.musxi.core.library.PlaybackQueue
import tech.musxi.core.account.Platform
import tech.musxi.core.library.ArtworkPolicy
import tech.musxi.core.playback.PlaybackFailure

enum class PlaybackSuppression { NONE, AUDIO_FOCUS, OUTPUT, OTHER }
enum class PlaybackRepeatMode { OFF, ONE, ALL }

internal enum class PlaybackConnectionGate { ALLOW, FAIL, TIMEOUT }

internal enum class PlaybackConnectionValidationPhase {
    CONNECTING, CONNECTED, RETRY_WAIT, FAILED, CLOSED,
    CONNECT_BLOCKED_FAIL, CONNECT_BLOCKED_TIMEOUT, GATE_ALLOWED, GATE_EXPIRED,
    DISCONNECTED, ACK_DELAY_ARMED, ACK_DELAY_STARTED, ACK_DELIVERED, ACK_EXPIRED,
    QUEUE_COMMAND_SUBMITTED,
}

internal data class PlaybackConnectionValidationEvent(
    val phase: PlaybackConnectionValidationPhase,
    val attempt: Int,
    val elapsedMs: Long,
)

internal data class PlaybackConnectionValidationSnapshot(
    val connectionPhase: PlaybackConnectionPhase,
    val attempt: Int,
    val gate: PlaybackConnectionGate,
    val gateExpiresInMs: Long,
    val controllerCreatedCount: Int,
    val controllerReleasedCount: Int,
    val queueCommandSubmitCount: Int,
    val pendingCommandCount: Int,
    val ackDelayArmed: Boolean,
    val events: List<PlaybackConnectionValidationEvent>,
)

data class PlaybackState(
    val connected: Boolean = false,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val mediaId: String? = null,
    val error: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queueIndex: Int = 0,
    val queueSize: Int = 0,
    val playWhenReady: Boolean = false,
    val ended: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val failure: PlaybackFailure? = null,
    val lastSkipped: PlaybackFailure? = null,
    val skippedCount: Int = 0,
    val sourceRefreshCount: Int = 0,
    val automaticTransitions: Int = 0,
    val title: String = "",
    val artist: String = "",
    val suppression: PlaybackSuppression = PlaybackSuppression.NONE,
    val album: String = "",
    val coverUrl: String? = null,
    val queuePlatform: Platform? = null,
    val sourcePlatform: Platform? = null,
    val repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val canSetPlaybackModes: Boolean = false,
    val sourceKey: tech.musxi.core.library.MediaKey? = null,
    val connectionPhase: PlaybackConnectionPhase = PlaybackConnectionPhase.CONNECTING,
    val connectionAttempt: Int = 0,
    val canRetryConnection: Boolean = false,
)

/** Main-thread facade. Signed stream URLs stay in memory; diagnostics never include them. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackConnection(context: Context) {
    private val app = context.applicationContext
    private val mutable = MutableStateFlow(PlaybackState())
    val state = mutable.asStateFlow()
    val queueState = queueSnapshot.asStateFlow()
    private var controller: MediaController? = null
    private val pendingCommands = mutableMapOf<Long, (Result<Unit>) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val progressTick = Runnable { controller?.let(::update) }
    private val validationStartedAtMs = android.os.SystemClock.elapsedRealtime()
    private val validationEvents = ArrayDeque<PlaybackConnectionValidationEvent>(VALIDATION_EVENT_LIMIT)
    private var validationConnectToken: Long? = null
    private var validationGate = PlaybackConnectionGate.ALLOW
    private var validationGateDeadlineMs = 0L
    private var validationControllerCreatedCount = 0
    private var validationControllerReleasedCount = 0
    private var validationQueueCommandSubmitCount = 0
    private var validationAckDelayDeadlineMs = 0L
    private val validationGateExpiry = Runnable {
        if (validationGate != PlaybackConnectionGate.ALLOW) {
            validationGate = PlaybackConnectionGate.ALLOW
            validationGateDeadlineMs = 0L
            recordValidationEvent(PlaybackConnectionValidationPhase.GATE_EXPIRED)
        }
    }
    private val validationAckExpiry = Runnable {
        if (validationAckDelayDeadlineMs != 0L) {
            validationAckDelayDeadlineMs = 0L
            recordValidationEvent(PlaybackConnectionValidationPhase.ACK_EXPIRED)
        }
    }
    private val recovery = ControllerRecovery<MediaController>(
        connect = ::connectController,
        release = ::releaseValidationController,
        schedule = { delay, task ->
            val runnable = Runnable { task() }
            main.postDelayed(runnable, delay)
            val cancel: () -> Unit = { main.removeCallbacks(runnable) }
            cancel
        },
        now = android.os.SystemClock::elapsedRealtime,
        attached = { accepted ->
            controller = accepted
            accepted.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { if (player === controller) update(player) }
            })
            update(accepted)
        },
        changed = { connection ->
            recordValidationEvent(connection.phase.validationPhase(), connection.attempt)
            if (connection.phase != PlaybackConnectionPhase.CONNECTED) {
                main.removeCallbacks(progressTick)
                controller = null
                queueCommands.invalidate()
                mutable.value = PlaybackState(
                    connectionPhase = connection.phase,
                    connectionAttempt = connection.attempt,
                    canRetryConnection = connection.phase == PlaybackConnectionPhase.FAILED,
                    error = when (connection.phase) {
                        PlaybackConnectionPhase.FAILED -> "播放服务暂不可用，请重试连接"
                        PlaybackConnectionPhase.RETRY_WAIT -> "播放服务已断开，正在重新连接"
                        else -> null
                    })
                val rejected = pendingCommands.values.toList()
                pendingCommands.clear()
                rejected.forEach { callback ->
                    runCatching { callback(Result.failure(IllegalStateException("Playback service disconnected"))) }
                }
            }
        })

    init { recovery.start() }

    /** Explicit retry after the automatic budget is exhausted. Never resubmits playback intent. */
    fun retryConnection() { recovery.retry() }

    private fun connectController(token: Long, result: (Result<MediaController>) -> Unit): () -> Unit {
        validationConnectToken = token
        when (currentValidationGate()) {
            PlaybackConnectionGate.FAIL -> {
                recordValidationEvent(PlaybackConnectionValidationPhase.CONNECT_BLOCKED_FAIL)
                result(Result.failure(IllegalStateException("Validation connection blocked")))
                return {}
            }
            PlaybackConnectionGate.TIMEOUT -> {
                recordValidationEvent(PlaybackConnectionValidationPhase.CONNECT_BLOCKED_TIMEOUT)
                return {}
            }
            PlaybackConnectionGate.ALLOW -> Unit
        }
        val future = MediaController.Builder(app, SessionToken(app,
            ComponentName(app, MusxiPlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                    if (controller === this@PlaybackConnection.controller) update(controller)
                }
                override fun onDisconnected(controller: MediaController) { recovery.disconnected(token) }
            }).buildAsync()
        future.addListener({
            val response = runCatching { future.get() }
            if (response.isSuccess) ++validationControllerCreatedCount
            result(response)
        }, Executor { main.post(it) })
        return { MediaController.releaseFuture(future) }
    }

    internal fun validationDisconnect(): Boolean {
        val token = validationConnectToken ?: return false
        if (recovery.state.phase !in setOf(PlaybackConnectionPhase.CONNECTED, PlaybackConnectionPhase.CONNECTING)) {
            return false
        }
        recovery.disconnected(token)
        recordValidationEvent(PlaybackConnectionValidationPhase.DISCONNECTED)
        return true
    }

    internal fun validationBlock(timeout: Boolean) {
        validationGate = if (timeout) PlaybackConnectionGate.TIMEOUT else PlaybackConnectionGate.FAIL
        validationGateDeadlineMs = nowForValidation() + VALIDATION_EXPIRY_MS
        main.removeCallbacks(validationGateExpiry)
        main.postDelayed(validationGateExpiry, VALIDATION_EXPIRY_MS)
        validationDisconnect()
    }

    internal fun validationAllow() {
        main.removeCallbacks(validationGateExpiry)
        validationGate = PlaybackConnectionGate.ALLOW
        validationGateDeadlineMs = 0L
        recordValidationEvent(PlaybackConnectionValidationPhase.GATE_ALLOWED)
    }

    internal fun validationLateAckDisconnect(): Boolean {
        main.removeCallbacks(validationAckExpiry)
        validationAckDelayDeadlineMs = nowForValidation() + VALIDATION_EXPIRY_MS
        main.postDelayed(validationAckExpiry, VALIDATION_EXPIRY_MS)
        recordValidationEvent(PlaybackConnectionValidationPhase.ACK_DELAY_ARMED)
        return true
    }

    internal fun validationSnapshot(): PlaybackConnectionValidationSnapshot {
        val currentGate = currentValidationGate()
        val now = nowForValidation()
        return PlaybackConnectionValidationSnapshot(
            connectionPhase = recovery.state.phase,
            attempt = recovery.state.attempt,
            gate = currentGate,
            gateExpiresInMs = if (currentGate == PlaybackConnectionGate.ALLOW) 0L
            else (validationGateDeadlineMs - now).coerceAtLeast(0L),
            controllerCreatedCount = validationControllerCreatedCount,
            controllerReleasedCount = validationControllerReleasedCount,
            queueCommandSubmitCount = validationQueueCommandSubmitCount,
            pendingCommandCount = pendingCommands.size,
            ackDelayArmed = validationAckDelayDeadlineMs > now,
            events = validationEvents.toList(),
        )
    }

    fun playQueue(queue: PlaybackQueue, callback: (Result<Unit>) -> Unit = {}) {
        val player = controller ?: return callback(Result.failure(IllegalStateException("Player unavailable")))
        require(queue.songs.all { PlaybackSourcePolicy.acceptsMediaId(it.key.stableId) })
        dispatch(player, QueueCommandMailbox.Action.Replace(queue), callback)
    }

    /** Restores original queue metadata as paused/idle. No signed capability is restored or prepared. */
    fun restoreQueue(queue: PlaybackQueue, saved: tech.musxi.core.playback.SavedPlaybackQueue,
        callback: (Result<Unit>) -> Unit) {
        val player = controller ?: return callback(Result.failure(IllegalStateException("Player unavailable")))
        dispatch(player, QueueCommandMailbox.Action.Replace(queue, saved.positionMs, false,
            saved.repeatMode, saved.shuffleEnabled), callback)
    }

    fun toggle() { controller?.let { if (it.playbackState == Player.STATE_ENDED || !it.playWhenReady) resume() else it.pause() } }
    fun pause() { controller?.pause() }
    /** Plays this original queue slot from zero, only if the rendered queue is still current. */
    fun jumpToQueueIndex(index: Int, expectedRevision: Long, callback: (Result<Unit>) -> Unit = {}) =
        editQueue(QueueCommandMailbox.Action.Jump(index, expectedRevision), callback)
    fun removeQueueItem(index: Int, expectedRevision: Long, callback: (Result<Unit>) -> Unit = {}) =
        editQueue(QueueCommandMailbox.Action.Edit(expectedRevision, tech.musxi.core.playback.QueueEdit.Remove(index)), callback)
    fun moveQueueItem(from: Int, to: Int, expectedRevision: Long, callback: (Result<Unit>) -> Unit = {}) =
        editQueue(QueueCommandMailbox.Action.Edit(expectedRevision, tech.musxi.core.playback.QueueEdit.Move(from, to)), callback)
    fun clearQueue(expectedRevision: Long, callback: (Result<Unit>) -> Unit = {}) =
        editQueue(QueueCommandMailbox.Action.Edit(expectedRevision, tech.musxi.core.playback.QueueEdit.Clear), callback)
    /** Only same-platform, account-bound active queues; this never starts an empty queue. */
    fun enqueue(song: tech.musxi.core.library.SongSummary, expectedRevision: Long, playNext: Boolean = false,
        callback: (Result<Unit>) -> Unit = {}) = editQueue(QueueCommandMailbox.Action.Insert(expectedRevision, song, playNext), callback)

    private fun editQueue(action: QueueCommandMailbox.Action, callback: (Result<Unit>) -> Unit) {
        val player = controller ?: return callback(Result.failure(IllegalStateException("Player unavailable")))
        dispatch(player, action, callback)
    }
    fun setRepeatMode(mode: PlaybackRepeatMode) {
        controller?.takeIf { it.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE) }?.repeatMode = when (mode) {
            PlaybackRepeatMode.OFF -> Player.REPEAT_MODE_OFF
            PlaybackRepeatMode.ONE -> Player.REPEAT_MODE_ONE
            PlaybackRepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
    }
    fun setShuffleEnabled(enabled: Boolean) {
        controller?.takeIf { it.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE) }?.shuffleModeEnabled = enabled
    }
    fun resume() { controller?.let {
        if (it.playerError != null || (it.playbackState == Player.STATE_IDLE && it.mediaItemCount > 0)) it.prepare()
        if (it.playbackState == Player.STATE_ENDED) it.seekToDefaultPosition()
        it.play()
    } }
    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs.coerceAtLeast(0)) }
    fun select(index: Int) { controller?.let { if (index in 0 until it.mediaItemCount) it.seekTo(index, 0) } }
    fun next() { navigate(true) }
    fun previous() { navigate(false) }
    private fun navigate(next: Boolean) { controller?.let {
        if (if (next) !it.hasNextMediaItem() else !it.hasPreviousMediaItem()) return
        val retry = it.playerError != null
        if (next) it.seekToNextMediaItem() else it.seekToPreviousMediaItem()
        if (retry) { it.prepare(); it.play() }
    } }
    // Replace and clear use the same path; ordinary batched player commands cannot reopen a
    // queue after clear. The service consumes only the latest intent, once.
    fun stop() {
        val player = controller
        if (player == null) queueCommands.invalidate() else dispatch(player, QueueCommandMailbox.Action.Clear)
    }

    private fun dispatch(player: MediaController, action: QueueCommandMailbox.Action, callback: (Result<Unit>) -> Unit = {}) {
        val token = queueCommands.submit(action)
        ++validationQueueCommandSubmitCount
        recordValidationEvent(PlaybackConnectionValidationPhase.QUEUE_COMMAND_SUBMITTED)
        pendingCommands[token] = callback
        val result = try {
            player.sendCustomCommand(SessionCommand(MusxiPlaybackService.COMMAND_APPLY_QUEUE, Bundle.EMPTY),
                Bundle().apply { putLong(MusxiPlaybackService.EXTRA_QUEUE_REVISION, token) })
        } catch (_: Exception) {
            pendingCommands.remove(token)?.invoke(Result.failure(IllegalStateException("Queue command rejected")))
            return
        }
        result.addListener({
            val accepted = try { result.get().resultCode == SessionResult.RESULT_SUCCESS } catch (_: Exception) { false }
            val deliver: () -> Unit = {
                pendingCommands.remove(token)?.let { pending ->
                    recordValidationEvent(PlaybackConnectionValidationPhase.ACK_DELIVERED)
                    pending(if (accepted && player === controller) Result.success(Unit)
                    else Result.failure(IllegalStateException("Queue command rejected")))
                }
            }
            if (consumeValidationAckDelay()) {
                recordValidationEvent(PlaybackConnectionValidationPhase.ACK_DELAY_STARTED)
                main.postDelayed(Runnable(deliver), VALIDATION_ACK_DELAY_MS)
            } else {
                deliver()
            }
        }, Executor { main.post(it) })
    }

    private fun currentValidationGate(): PlaybackConnectionGate {
        if (validationGate != PlaybackConnectionGate.ALLOW &&
            nowForValidation() >= validationGateDeadlineMs) {
            main.removeCallbacks(validationGateExpiry)
            validationGate = PlaybackConnectionGate.ALLOW
            validationGateDeadlineMs = 0L
            recordValidationEvent(PlaybackConnectionValidationPhase.GATE_EXPIRED)
        }
        return validationGate
    }

    private fun consumeValidationAckDelay(): Boolean {
        val deadline = validationAckDelayDeadlineMs
        if (deadline == 0L) return false
        validationAckDelayDeadlineMs = 0L
        main.removeCallbacks(validationAckExpiry)
        return nowForValidation() <= deadline
    }

    private fun releaseValidationController(controller: MediaController) {
        ++validationControllerReleasedCount
        controller.release()
    }

    private fun recordValidationEvent(
        phase: PlaybackConnectionValidationPhase,
        attempt: Int = recovery.state.attempt,
    ) {
        if (validationEvents.size >= VALIDATION_EVENT_LIMIT) validationEvents.removeFirst()
        validationEvents.addLast(PlaybackConnectionValidationEvent(
            phase = phase,
            attempt = attempt,
            elapsedMs = nowForValidation() - validationStartedAtMs,
        ))
    }

    private fun nowForValidation(): Long = android.os.SystemClock.elapsedRealtime()

    private fun update(player: Player) {
        if (player !== controller) return
        main.removeCallbacks(progressTick)
        val extras = (player as? MediaController)?.sessionExtras ?: Bundle.EMPTY
        fun category(key: String) = extras.getString(key)?.let { name -> PlaybackFailure.entries.firstOrNull { it.name == name } }
        val failure = category(MusxiPlaybackService.EXTRA_FAILURE)
        // Read one item's service-owned snapshot. In-band stream tags and session extras
        // may describe a different source/item while the controller changes its timeline.
        val metadata = player.currentMediaItem?.mediaMetadata
        val queuePlatform = PlaybackMetadataSnapshot.platform(metadata?.extras?.getString(PlaybackMetadataSnapshot.EXTRA_QUEUE_PLATFORM))
        val sourcePlatform = PlaybackMetadataSnapshot.platform(metadata?.extras?.getString(PlaybackMetadataSnapshot.EXTRA_SOURCE_PLATFORM))
        mutable.value = PlaybackState(true, player.isPlaying,
            player.playbackState == Player.STATE_BUFFERING, player.currentMediaItem?.mediaId,
            if (failure != null || player.playerError != null) "播放未完成，请重试或选择其他歌曲" else null,
            player.currentPosition.coerceAtLeast(0), player.duration.coerceAtLeast(0),
            player.currentMediaItemIndex.coerceAtLeast(0), player.mediaItemCount,
            player.playWhenReady, player.playbackState == Player.STATE_ENDED,
            player.hasNextMediaItem(), player.hasPreviousMediaItem(), failure, category(MusxiPlaybackService.EXTRA_SKIPPED),
            extras.getInt(MusxiPlaybackService.EXTRA_SKIP_COUNT), extras.getInt(MusxiPlaybackService.EXTRA_REFRESH_COUNT),
            extras.getInt(MusxiPlaybackService.EXTRA_AUTO_COUNT), metadata?.title?.toString().orEmpty(), metadata?.artist?.toString().orEmpty(),
            when (player.playbackSuppressionReason) {
                Player.PLAYBACK_SUPPRESSION_REASON_NONE -> PlaybackSuppression.NONE
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> PlaybackSuppression.AUDIO_FOCUS
                Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT -> PlaybackSuppression.OUTPUT
                else -> PlaybackSuppression.OTHER
            },
            album = metadata?.albumTitle?.toString().orEmpty(),
            coverUrl = sourcePlatform?.let { ArtworkPolicy.sanitize(it, metadata?.artworkUri?.toString()) },
            queuePlatform = queuePlatform,
            sourcePlatform = sourcePlatform,
            repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> PlaybackRepeatMode.ONE
                Player.REPEAT_MODE_ALL -> PlaybackRepeatMode.ALL
                else -> PlaybackRepeatMode.OFF
            },
            shuffleEnabled = player.shuffleModeEnabled,
            sourceKey = sourcePlatform?.let { platform ->
                metadata?.extras?.getString(PlaybackMetadataSnapshot.EXTRA_SOURCE_ID)?.let { id ->
                    runCatching { tech.musxi.core.library.MediaKey(platform, id) }.getOrNull()
                }
            },
            connectionPhase = PlaybackConnectionPhase.CONNECTED,
            connectionAttempt = recovery.state.attempt,
            canSetPlaybackModes = player.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE) &&
                player.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE))
        if (player.isPlaying) main.postDelayed(progressTick, 500)
    }

    private fun PlaybackConnectionPhase.validationPhase(): PlaybackConnectionValidationPhase = when (this) {
        PlaybackConnectionPhase.CONNECTING -> PlaybackConnectionValidationPhase.CONNECTING
        PlaybackConnectionPhase.CONNECTED -> PlaybackConnectionValidationPhase.CONNECTED
        PlaybackConnectionPhase.RETRY_WAIT -> PlaybackConnectionValidationPhase.RETRY_WAIT
        PlaybackConnectionPhase.FAILED -> PlaybackConnectionValidationPhase.FAILED
        PlaybackConnectionPhase.CLOSED -> PlaybackConnectionValidationPhase.CLOSED
    }

    companion object {
        private const val VALIDATION_EVENT_LIMIT = 32
        private const val VALIDATION_EXPIRY_MS = 60_000L
        private const val VALIDATION_ACK_DELAY_MS = 2_000L
    }
}
