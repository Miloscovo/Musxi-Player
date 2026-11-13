package tech.musxi.playback

import android.os.SystemClock
import androidx.media3.common.Player

/** One main-thread read, published atomically with its position in PlaybackState. */
internal data class PlaybackPositionSample(
    val positionMs: Long,
    val sampledAtElapsedMs: Long,
    val speed: Float,
)

internal fun samplePlaybackPosition(
    player: Player,
    nowElapsedMs: () -> Long = SystemClock::elapsedRealtime,
): PlaybackPositionSample {
    val position = player.currentPosition.coerceAtLeast(0)
    val sampledAt = nowElapsedMs()
    return PlaybackPositionSample(position, sampledAt, player.playbackParameters.speed)
}
