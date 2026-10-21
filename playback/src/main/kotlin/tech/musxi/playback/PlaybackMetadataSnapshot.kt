package tech.musxi.playback

import tech.musxi.core.account.Platform
import tech.musxi.core.library.ArtworkPolicy
import tech.musxi.core.library.SongSummary

/** Public display data only. Queue identity is independent of the selected audio source. */
internal data class PlaybackMetadataSnapshot(
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String?,
    val queuePlatform: Platform?,
    val sourcePlatform: Platform?,
    val sourceId: String? = null,
) {
    companion object {
        const val EXTRA_QUEUE_PLATFORM = "musxi.queuePlatform"
        const val EXTRA_SOURCE_PLATFORM = "musxi.sourcePlatform"
        const val EXTRA_SOURCE_ID = "musxi.sourceId"

        fun platform(value: String?): Platform? = Platform.entries.firstOrNull { it.name == value }

        /** A substitute must never replace the saved display data of the queue's original. */
        fun refreshOriginal(original: SongSummary, resolved: SongSummary): SongSummary =
            if (original.key == resolved.key) resolved else original

        fun fromSong(song: SongSummary, queuePlatform: Platform) = PlaybackMetadataSnapshot(
            song.title, song.artists.joinToString(" / "), song.album,
            ArtworkPolicy.sanitize(song.key.platform, song.coverUrl), queuePlatform, song.key.platform, song.key.id,
        )
    }
}
