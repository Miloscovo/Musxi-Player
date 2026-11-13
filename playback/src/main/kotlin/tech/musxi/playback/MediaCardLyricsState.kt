package tech.musxi.playback

import tech.musxi.core.lyrics.LyricLine
import tech.musxi.core.lyrics.LyricsState
import tech.musxi.core.lyrics.LyricsStatus

/** Public display data only; no session, account capability, URI or raw platform response. */
data class MediaCardLyricsState(
    val enabled: Boolean = false,
    val mediaId: String? = null,
    val sourceId: String? = null,
    val lines: List<LyricLine> = emptyList(),
    val offsetMs: Long = 0,
) {
    companion object {
        /** Do not pair a new queue/source with a document still belonging to the previous target. */
        fun from(enabled: Boolean, playback: PlaybackState, lyrics: LyricsState, sourceWaiting: Boolean): MediaCardLyricsState {
            if (!enabled || !playback.connected || sourceWaiting || playback.mediaId == null ||
                playback.sourceKey == null || lyrics.status != LyricsStatus.TIMED ||
                lyrics.queueKey?.stableId != playback.mediaId || lyrics.sourceKey != playback.sourceKey)
                return MediaCardLyricsState(enabled = enabled)
            return MediaCardLyricsState(true, playback.mediaId, playback.sourceKey.stableId, lyrics.lines, lyrics.offsetMs)
        }
    }
}
