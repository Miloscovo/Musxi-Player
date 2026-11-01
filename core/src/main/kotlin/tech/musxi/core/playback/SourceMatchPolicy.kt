package tech.musxi.core.playback

import java.text.Normalizer
import java.util.Locale
import tech.musxi.core.library.SongSummary

/** Eligibility for automatic fallback only. A manual choice may still try any offered candidate.
 * Matching metadata is evidence, never proof of the same recording or of playback permission.
 */
enum class SourceMatchReason {
    ELIGIBLE, TITLE_MISMATCH, VERSION_REQUIRES_CONFIRMATION,
    ARTIST_UNKNOWN, ARTIST_MISMATCH, DURATION_UNKNOWN, DURATION_MISMATCH,
}

object SourceMatchPolicy {
    const val MAX_DURATION_DIFFERENCE_MS = 3_000L
    const val MAX_RELAXED_DURATION_DIFFERENCE_MS = 15_000L

    fun eligible(original: SongSummary, candidate: SongSummary, relaxDuration: Boolean = false): Boolean {
        val reason = assess(original, candidate)
        if (reason == SourceMatchReason.ELIGIBLE) return true
        if (!relaxDuration || reason != SourceMatchReason.DURATION_MISMATCH) return false
        val shorter = minOf(original.durationMs, candidate.durationMs)
        return durationDifference(original, candidate) <= minOf(MAX_RELAXED_DURATION_DIFFERENCE_MS, shorter * 8 / 100)
    }

    fun durationDifference(original: SongSummary, candidate: SongSummary): Long =
        kotlin.math.abs(original.durationMs - candidate.durationMs)

    /** Rank verified metadata first, then closest duration; album breaks duration ties. */
    fun rank(original: SongSummary, candidates: List<SourceCandidate>, relaxDuration: Boolean = false): List<SourceCandidate> =
        candidates.map { candidate -> candidate.copy(
            relaxedMatch = assess(original, candidate.song) == SourceMatchReason.DURATION_MISMATCH && eligible(original, candidate.song, true),
            durationDifferenceMs = durationDifference(original, candidate.song))
        }.sortedWith(compareBy<SourceCandidate> { !eligible(original, it.song, relaxDuration) }
            .thenBy { it.durationDifferenceMs }
            .thenBy { compact(original.album).isBlank() || compact(original.album) != compact(it.song.album) })

    /** Recheck an explicitly selected recording against metadata returned by the resolver. */
    fun confirmsSelected(expected: SongSummary, resolved: SongSummary): Boolean {
        if (expected.key != resolved.key) return false
        if (expected == resolved) return true
        return when (assess(expected, resolved)) {
            SourceMatchReason.ELIGIBLE -> true
            SourceMatchReason.VERSION_REQUIRES_CONFIRMATION ->
                SourceSelection.sameTitle(expected.title, resolved.title) && compact(expected.album) == compact(resolved.album) &&
                    assess(expected.copy(title = "recording", album = ""),
                        resolved.copy(title = "recording", album = "")) == SourceMatchReason.ELIGIBLE
            else -> false
        }
    }

    fun assess(original: SongSummary, candidate: SongSummary): SourceMatchReason {
        if (!SourceSelection.sameTitle(original.title, candidate.title)) return SourceMatchReason.TITLE_MISMATCH
        if (hasVersionMarker(original) || hasVersionMarker(candidate))
            return SourceMatchReason.VERSION_REQUIRES_CONFIRMATION
        val artists = artists(original) ?: return SourceMatchReason.ARTIST_UNKNOWN
        val otherArtists = artists(candidate) ?: return SourceMatchReason.ARTIST_UNKNOWN
        if (artists != otherArtists) return SourceMatchReason.ARTIST_MISMATCH
        if (original.durationMs <= 0 || candidate.durationMs <= 0) return SourceMatchReason.DURATION_UNKNOWN
        val shorter = minOf(original.durationMs, candidate.durationMs)
        val difference = maxOf(original.durationMs, candidate.durationMs) - shorter
        // Both limits apply: at most 3 seconds and at most 2% of the shorter recording.
        if (difference > minOf(MAX_DURATION_DIFFERENCE_MS, shorter / 50))
            return SourceMatchReason.DURATION_MISMATCH
        return SourceMatchReason.ELIGIBLE
    }

    private fun artists(song: SongSummary): Set<String>? {
        if (song.artists.isEmpty()) return null
        val names = song.artists.map(::compact)
        if (names.any { it.isEmpty() || it in unknownArtists }) return null
        // Preserve punctuation and do not guess aliases or split artist names on '&', '/' etc.
        return names.toSet()
    }

    private fun hasVersionMarker(song: SongSummary): Boolean {
        val text = Normalizer.normalize(song.title + " " + song.album, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        return chineseMarkers.any(text::contains) || englishMarkers.containsMatchIn(text)
    }

    private fun compact(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filterNot { it.isWhitespace() || Character.isSpaceChar(it) }

    private val unknownArtists = setOf("unknown", "unknownartist", "variousartist", "variousartists", "n/a", "null", "-",
        "未知", "未知歌手", "未知艺术家", "未知藝術家", "未知艺人", "未知藝人", "未知演唱者", "群星", "佚名")
    private val chineseMarkers = listOf("现场", "演唱会", "翻唱", "伴奏", "纯音乐", "重录", "重製", "重制",
        "重新录制", "混音", "加速", "提速", "减速", "降速", "剪辑", "演奏版", "消音", "卡拉ok", "tv版", "dj版",
        "钢琴版", "鋼琴版", "ライブ", "ライヴ", "カバー", "カラオケ", "弾き語り", "オフボーカル")
    private val englishMarkers = Regex(
        "(?<![a-z0-9])(live|concert|cover|inst(?:rumental)?|karaoke|remix|remaster(?:ed)?|re[- ]?record(?:ed|ing)?|" +
            "acoustic|demo|sped[ -]?up|speed[ -]?up|slowed|radio[ -]?edit|extended|mono|stereo|8d|" +
            "tv[ -]?(?:size|ver(?:sion)?)|dj|piano[ -]?(?:ver(?:sion)?)|off[ -]?vocal)(?![a-z0-9])")
}
