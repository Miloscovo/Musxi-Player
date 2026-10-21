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
import java.util.concurrent.Executor
import tech.musxi.core.library.PlaybackQueue
import tech.musxi.core.account.Platform
import tech.musxi.core.library.ArtworkPolicy
import tech.musxi.core.playback.PlaybackFailure

enum class PlaybackSuppression { NONE, AUDIO_FOCUS, OUTPUT, OTHER }
enum class PlaybackRepeatMode { OFF, ONE, ALL }

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
)

/** Main-thread facade. Signed stream URLs stay in memory; diagnostics never include them. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackConnection(context: Context) {
    private val app = context.applicationContext
    private val mutable = MutableStateFlow(PlaybackState())
    val state = mutable.asStateFlow()
    val queueState = queueSnapshot.asStateFlow()
    private var controller: MediaController? = null
    private val main = Handler(Looper.getMainLooper())
    private val progressTick = Runnable { controller?.let(::update) }
    private val future = MediaController.Builder(app, SessionToken(app,
        ComponentName(app, MusxiPlaybackService::class.java)))
        .setListener(object : MediaController.Listener {
            override fun onExtrasChanged(controller: MediaController, extras: Bundle) = update(controller)
            override fun onDisconnected(controller: MediaController) {
                main.removeCallbacks(progressTick)
                this@PlaybackConnection.controller = null
                mutable.value = PlaybackState(error = "播放服务已断开，请重新打开应用")
            }
        }).buildAsync()

    init {
        future.addListener({
            try {
                controller = future.get().also {
                    it.addListener(object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) = update(player)
                    })
                    update(it)
                }
            } catch (_: Exception) { mutable.value = PlaybackState(error = "播放服务暂不可用") }
        }, Executor { Handler(Looper.getMainLooper()).post(it) })
    }

    fun playQueue(queue: PlaybackQueue, callback: (Result<Unit>) -> Unit = {}) {
        val player = controller ?: error("播放服务尚未连接")
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
        val result = player.sendCustomCommand(SessionCommand(MusxiPlaybackService.COMMAND_APPLY_QUEUE, Bundle.EMPTY),
            Bundle().apply { putLong(MusxiPlaybackService.EXTRA_QUEUE_REVISION, token) })
        result.addListener({
            val accepted = try { result.get().resultCode == SessionResult.RESULT_SUCCESS } catch (_: Exception) { false }
            callback(if (accepted) Result.success(Unit) else Result.failure(IllegalStateException("Queue command rejected")))
        }, Executor { main.post(it) })
    }

    private fun update(player: Player) {
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
            canSetPlaybackModes = player.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE) &&
                player.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE))
        if (player.isPlaying) main.postDelayed(progressTick, 500)
    }
}
