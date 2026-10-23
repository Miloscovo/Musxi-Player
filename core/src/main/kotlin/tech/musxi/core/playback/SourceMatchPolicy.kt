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
        "重新录制", "混音", "加速", "减速", "降速", "剪辑", "演奏版", "消音", "卡拉ok", "tv版", "dj版",
        "钢琴版", "鋼琴版", "ライブ", "ライヴ", "カバー", "カラオケ", "弾き語り", "オフボーカル")
    private val englishMarkers = Regex(
        "(?<![a-z0-9])(live|concert|cover|inst(?:rumental)?|karaoke|remix|remaster(?:ed)?|re[- ]?record(?:ed|ing)?|" +
            "acoustic|demo|sped[ -]?up|speed[ -]?up|slowed|radio[ -]?edit|extended|mono|stereo|8d|" +
            "tv[ -]?(?:size|ver(?:sion)?)|dj|piano[ -]?(?:ver(?:sion)?)|off[ -]?vocal)(?![a-z0-9])")
}
