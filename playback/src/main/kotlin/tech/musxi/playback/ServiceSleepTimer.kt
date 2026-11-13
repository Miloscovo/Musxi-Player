package tech.musxi.playback

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import tech.musxi.core.playback.SleepTimerPhase
import tech.musxi.core.playback.SleepTimerPolicy
import tech.musxi.core.playback.SleepTimerState

internal val sleepTimerSnapshot = MutableStateFlow(SleepTimerState())

/** Owned exclusively by the playback service; ExoPlayer's boundary pause prevents starting next audio. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class ServiceSleepTimer(
    private val player: ExoPlayer,
    private val main: Handler,
    private val pausePlayback: () -> Unit,
) {
    private val policy = SleepTimerPolicy()
    private var closed = false
    private var pausedByTimer = false
    private val tick = Runnable { synchronize() }
    private fun ticket(item: MediaItem? = player.currentMediaItem) = item?.localConfiguration?.uri?.toString()
    private fun canFinish() = player.currentMediaItem != null && player.playbackState in setOf(Player.STATE_READY, Player.STATE_BUFFERING) && player.playerError == null
    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // A delayed main-thread callback can straddle the deadline. Bind to the slot we left.
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                val stop = policy.tick(SystemClock.elapsedRealtime(), ticket(oldPosition.mediaItem), true)
                if (stop || policy.trackEnded(ticket(oldPosition.mediaItem))) { consume(); return }
            }
            synchronize()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                if (policy.trackEnded(ticket())) consume()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && policy.trackEnded(ticket())) consume()
            else synchronize()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason !in setOf(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)) synchronize()
        }
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (timeline.isEmpty) cancel() else synchronize()
        }
    }

    init { sleepTimerSnapshot.value = policy.state.value; player.addListener(listener) }

    fun startDuration(durationMs: Long, finishTrack: Boolean) {
        policy.startDuration(SystemClock.elapsedRealtime(), durationMs, finishTrack)
        pausedByTimer = false
        synchronize()
    }

    fun finishCurrent(expectedTicket: String): Boolean {
        if (ticket() != expectedTicket || !canFinish()) return false
        pausedByTimer = false
        policy.finishCurrent(expectedTicket)
        synchronize()
        return true
    }

    fun cancel() { policy.cancel(); publish() }
    fun explicitPlay() { pausedByTimer = false }
    fun queueReplaced() { pausedByTimer = false; policy.queueReplaced(); publish() }

    /** Called before every gated play, including retries/skips that can synchronously change a slot. */
    fun synchronize(): Boolean {
        if (closed) return false
        if (pausedByTimer) return true
        val stop = policy.tick(SystemClock.elapsedRealtime(), ticket(),
            canFinish())
        if (stop || policy.trackChanged(ticket())) { consume(); return true }
        publish()
        return false
    }

    private fun consume() {
        pausedByTimer = true
        publish()
        pausePlayback()
    }

    private fun publish() {
        main.removeCallbacks(tick)
        val state = policy.state.value
        player.pauseAtEndOfMediaItems = state.phase == SleepTimerPhase.WAITING_FOR_TRACK_END
        sleepTimerSnapshot.value = state
        state.deadlineElapsedMs?.takeIf { state.phase == SleepTimerPhase.COUNTING }?.let {
            main.postDelayed(tick, (it - SystemClock.elapsedRealtime()).coerceIn(1, 15_000))
        }
    }

    fun close() {
        closed = true
        policy.cancel()
        main.removeCallbacks(tick)
        player.removeListener(listener)
        player.pauseAtEndOfMediaItems = false
        sleepTimerSnapshot.value = policy.state.value
    }
}
