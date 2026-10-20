package tech.musxi.core.library

import java.util.Collections

/** A complete account-bound ID snapshot; unknown titles are populated when a song is resolved. */
class PlaybackQueue(songs: List<SongSummary>, val startIndex: Int, val accountGeneration: Long) {
    val songs: List<SongSummary> = Collections.unmodifiableList(songs.map { it.copy(artists = Collections.unmodifiableList(it.artists.toList())) })
    init { require(songs.isNotEmpty() && songs.size <= 20_000 && startIndex in songs.indices) }
}
