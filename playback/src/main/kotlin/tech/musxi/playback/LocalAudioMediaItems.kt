package tech.musxi.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import tech.musxi.core.localaudio.LocalAudioLimits
import tech.musxi.core.localaudio.LocalAudioItem
import tech.musxi.core.localaudio.LocalAudioSegment

/**
 * Local queue items keep the source ticket. A CUE slice adds only source-time clipping metadata,
 * so Media3 exposes the slice as a normal clip-relative timeline and no position offset is needed.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object LocalAudioMediaItems {
    fun build(
        audio: LocalAudioItem,
        ticket: String,
        metadata: MediaMetadata,
        mediaId: String,
    ): MediaItem {
        val builder = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(ticket)
            .setMediaMetadata(metadata)
        audio.segment?.let { segment ->
            builder.setClippingConfiguration(segment.clippingConfiguration())
        }
        return builder.build()
    }

    /** Progress and seek positions are slice-relative; keep them strictly inside the clip. */
    fun positionMs(audio: LocalAudioItem, positionMs: Long): Long {
        val durationMs = audio.segment?.durationMs
            ?: return positionMs.coerceIn(0, LocalAudioLimits.MAX_POSITION_MS)
        return positionMs.coerceIn(0, durationMs.coerceAtLeast(0))
    }

    /** A segment end is exclusive, matching ClippingConfiguration.endPositionUs. */
    private fun LocalAudioSegment.clippingConfiguration(): MediaItem.ClippingConfiguration =
        MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs(startUs)
            .setEndPositionUs(endUs)
            .setRelativeToDefaultPosition(false)
            .setRelativeToLiveWindow(false)
            .setStartsAtKeyFrame(false)
            .build()
}
