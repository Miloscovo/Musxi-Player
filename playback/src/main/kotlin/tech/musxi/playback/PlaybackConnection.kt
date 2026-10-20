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
import tech.musxi.core.playback.PlaybackFailure

enum class PlaybackSuppression { NONE, AUDIO_FOCUS, OUTPUT, OTHER }

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
)

/** Main-thread facade. Signed stream URLs stay in memory; diagnostics never include them. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackConnection(context: Context) {
    private val app = context.applicationContext
    private val mutable = MutableStateFlow(PlaybackState())
    val state = mutable.asStateFlow()
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

    fun toggle() { controller?.let { if (it.playbackState == Player.STATE_ENDED || !it.playWhenReady) resume() else it.pause() } }
    fun pause() { controller?.pause() }
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
        mutable.value = PlaybackState(true, player.isPlaying,
            player.playbackState == Player.STATE_BUFFERING, player.currentMediaItem?.mediaId,
            if (failure != null || player.playerError != null) "播放未完成，请重试或选择其他歌曲" else null,
            player.currentPosition.coerceAtLeast(0), player.duration.coerceAtLeast(0),
            player.currentMediaItemIndex.coerceAtLeast(0), player.mediaItemCount,
            player.playWhenReady, player.playbackState == Player.STATE_ENDED,
            player.hasNextMediaItem(), player.hasPreviousMediaItem(), failure, category(MusxiPlaybackService.EXTRA_SKIPPED),
            extras.getInt(MusxiPlaybackService.EXTRA_SKIP_COUNT), extras.getInt(MusxiPlaybackService.EXTRA_REFRESH_COUNT),
            extras.getInt(MusxiPlaybackService.EXTRA_AUTO_COUNT), player.mediaMetadata.title?.toString().orEmpty(), player.mediaMetadata.artist?.toString().orEmpty(),
            when (player.playbackSuppressionReason) {
                Player.PLAYBACK_SUPPRESSION_REASON_NONE -> PlaybackSuppression.NONE
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> PlaybackSuppression.AUDIO_FOCUS
                Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT -> PlaybackSuppression.OUTPUT
                else -> PlaybackSuppression.OTHER
            })
        if (player.isPlaying) main.postDelayed(progressTick, 500)
    }
}
