package tech.musxi.player.lockscreen

import tech.musxi.core.lyrics.LyricsState
import tech.musxi.core.lyrics.LyricsStatus
import tech.musxi.core.wallpaper.SavedWallpaper
import tech.musxi.playback.PlaybackState

/** `local:<audioId>`: the service's media ID for a queue item that is a device recording. */
private const val LOCAL_MEDIA_PREFIX = "local:"

/**
 * One real reading of the player's position, from the same elapsed-realtime clock the playback
 * state carries. The page extrapolates between readings only while [canExtrapolate] is true, so a
 * paused, buffering or failed player never drifts forward on a locked screen.
 */
data class LockScreenPosition(
    val positionMs: Long,
    val sampledAtElapsedMs: Long,
    val speed: Float,
    val canExtrapolate: Boolean,
)

/**
 * What Musxi's own lock-screen lyrics page may display, as plain data: no Compose, no Android
 * objects, no session and no request. The page owns formatting, colour and layout; which song,
 * which lyrics and which picture may appear is decided here, so every rule is a JVM test.
 *
 * [lyrics] is fenced. It is the only document that can belong to the song on screen; a queue
 * change, a source change, a source search or a disconnected player clears it rather than leaving
 * the previous song's lines on a locked screen.
 *
 * [wallpaper] is the saved wallpaper exactly as applied: the image, the strength and the frame are
 * passed through untouched and nothing is imported or re-measured. A wallpaper that is switched
 * off keeps its image so it can be switched back on; the page draws it only while
 * `wallpaper.preferences.enabled`, and falls back to the cover's gradient when there is no image.
 */
data class LockScreenLyricsContent(
    /** The player reports a connected song: title, artist and cover may be shown at all. */
    val identified: Boolean,
    /** Song title; empty when the player's identity is not trustworthy (see [identified]). */
    val title: String,
    /** Singer, or a recording's creator; empty when unknown. */
    val artist: String,
    /** A cover the page may load as-is: an http(s) address, or `file:` for a recording. */
    val coverUrl: String?,
    /** A device recording plays: no platform lyrics exist, only cover, title and progress. */
    val local: Boolean,
    val playing: Boolean,
    val buffering: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val durationMs: Long,
    val position: LockScreenPosition,
    val lyrics: LyricsState,
    val wallpaper: SavedWallpaper,
) {
    /** The page can follow a line: timed rows for exactly this song. */
    val hasTimedLyrics: Boolean get() = lyrics.status == LyricsStatus.TIMED && lyrics.lines.isNotEmpty()

    companion object {
        /**
         * Maps one playback snapshot and the background follower's current lyrics to display data.
         *
         * [sourceWaiting] means a source search is still in flight, so the song may change again and
         * no lyrics are shown until it settles. Playback state (playing, buffering, position,
         * transport availability) stays truthful in every case, so an empty or missing document
         * still leaves working controls.
         */
        fun from(
            playback: PlaybackState,
            lyrics: LyricsState,
            sourceWaiting: Boolean,
            wallpaper: SavedWallpaper,
        ): LockScreenLyricsContent {
            // A disconnected player keeps its last title, artist and cover; showing them would claim
            // a song is on screen when the service is gone.
            val identified = playback.connected && playback.mediaId != null
            val mediaId = playback.mediaId
            val local = identified && (playback.localAudio != null || mediaId?.startsWith(LOCAL_MEDIA_PREFIX) == true)
            val fenced = if (
                identified && !local && !sourceWaiting && playback.sourceKey != null &&
                playback.sourceKey == lyrics.sourceKey && lyrics.queueKey?.stableId == mediaId
            ) lyrics else LyricsState()
            return LockScreenLyricsContent(
                identified = identified,
                title = if (identified) playback.title else "",
                artist = if (identified) playback.artist else "",
                coverUrl = if (identified) playback.coverUrl else null,
                local = local,
                playing = playback.playing,
                buffering = playback.buffering,
                hasPrevious = playback.hasPrevious,
                hasNext = playback.hasNext,
                durationMs = playback.durationMs.coerceAtLeast(0),
                position = LockScreenPosition(
                    positionMs = playback.positionMs.coerceAtLeast(0),
                    sampledAtElapsedMs = playback.positionSampledAtElapsedMs,
                    speed = playback.playbackSpeed,
                    canExtrapolate = playback.canExtrapolatePosition,
                ),
                lyrics = fenced,
                wallpaper = wallpaper,
            )
        }
    }
}
