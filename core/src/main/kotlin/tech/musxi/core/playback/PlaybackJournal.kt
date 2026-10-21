package tech.musxi.core.playback

import tech.musxi.core.library.SongSummary

/** Public original-queue metadata only. No generation, ticket, credential or stream address. */
data class SavedPlaybackQueue(
    val songs: List<SongSummary>,
    val index: Int,
    val positionMs: Long,
    val repeatMode: String,
    val shuffleEnabled: Boolean,
    val savedAtEpochMs: Long,
)

/** Stored under the original queue's account. Source describes the recording actually played. */
data class RecentPlay(val original: SongSummary, val source: SongSummary, val playedAtEpochMs: Long)
data class PlaybackJournal(val queue: SavedPlaybackQueue? = null, val recent: List<RecentPlay> = emptyList())

interface PlaybackJournalStore {
    fun read(owner: String): PlaybackJournal
    fun write(owner: String, journal: PlaybackJournal)
    fun clear()
}
