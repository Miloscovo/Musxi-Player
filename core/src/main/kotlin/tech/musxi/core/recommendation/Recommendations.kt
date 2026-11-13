package tech.musxi.core.recommendation

import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import tech.musxi.core.account.Platform
import tech.musxi.core.library.ArtworkPolicy
import tech.musxi.core.library.SongSummary

enum class RecommendationKind { DAILY_SONGS, PERSONAL_RADIO }

/** A bounded, complete recommendation read, never a synthetic playlist. */
fun interface RecommendationFetch {
    fun fetch(platform: Platform, isCurrent: () -> Boolean, result: (Result<List<SongSummary>>) -> Unit)
}

/** Cache date is the date of retrieval in UTC+8, not a platform-declared refresh deadline. */
class RecommendationSnapshot(platform: Platform, songs: List<SongSummary>, val fetchedAtEpochMs: Long) {
    val kind: RecommendationKind = RecommendationsPolicy.kind(platform)
    val songs: List<SongSummary> = RecommendationsPolicy.freeze(platform, songs)
    init { require(fetchedAtEpochMs >= 0) }
    val retrievedDate: String get() = RecommendationsPolicy.date(fetchedAtEpochMs)
    fun isFresh(now: Long): Boolean = now >= fetchedAtEpochMs && retrievedDate == RecommendationsPolicy.date(now) &&
        (kind == RecommendationKind.DAILY_SONGS || now - fetchedAtEpochMs < RecommendationsPolicy.RADIO_CACHE_MS)
    override fun toString() = "RecommendationSnapshot(songs=${songs.size}, retrievedDate=$retrievedDate)"
}

interface RecommendationStore {
    fun read(owner: String): RecommendationSnapshot?
    fun write(owner: String, snapshot: RecommendationSnapshot)
    fun clear()
}

object RecommendationsPolicy {
    const val MAX_SONGS = 200
    const val MIN_REQUEST_INTERVAL_MS = 30_000L
    const val RADIO_CACHE_MS = 3_600_000L
    private val zone = ZoneOffset.ofHours(8)
    fun kind(platform: Platform): RecommendationKind = if (platform == Platform.QQ) RecommendationKind.PERSONAL_RADIO
        else RecommendationKind.DAILY_SONGS
    fun date(epochMs: Long): String = Instant.ofEpochMilli(epochMs.coerceAtLeast(0)).atOffset(zone).toLocalDate().toString()

    internal fun freeze(platform: Platform, songs: List<SongSummary>): List<SongSummary> {
        require(songs.size <= MAX_SONGS)
        return Collections.unmodifiableList(songs.map { song ->
            require(song.key.platform == platform)
            text(song.title); require(song.title.isNotBlank())
            text(song.album)
            require(song.durationMs in 0..604_800_000L)
            require(song.artists.size <= 100 && song.artistIds.size <= 100)
            song.artists.forEach(::text)
            song.artistIds.forEach { text(it, 128) }
            song.albumId?.let { text(it, 128) }
            song.copy(artists = Collections.unmodifiableList(song.artists.toList()),
                artistIds = Collections.unmodifiableList(song.artistIds.toList()),
                coverUrl = ArtworkPolicy.sanitize(platform, song.coverUrl))
        })
    }

    internal fun text(value: String, max: Int = 1024) {
        require(value.length <= max && value.none { it.isISOControl() })
    }
}
