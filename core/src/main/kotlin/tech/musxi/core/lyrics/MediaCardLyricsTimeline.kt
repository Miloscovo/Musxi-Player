package tech.musxi.core.lyrics

/** A line sample and the next absolute player position at which it must be sampled again. */
data class MediaCardLyricLine(val text: String?, val nextPositionMs: Long?)

/** Shares the lyric browser's offset convention: positive offset delays the displayed line. */
object MediaCardLyricsTimeline {
    private val whitespace = Regex("[\\s\\p{Cc}]+")

    fun sample(lines: List<LyricLine>, positionMs: Long, offsetMs: Long): MediaCardLyricLine {
        // Parser and binding limits are much smaller; bounding also avoids arithmetic overflow.
        val offset = offsetMs.coerceIn(-LyricsWordTimeline.MAX_TIME_MS, LyricsWordTimeline.MAX_TIME_MS)
        val position = positionMs.coerceIn(0, LyricsWordTimeline.MAX_TIME_MS)
        val index = LrcParser.activeIndex(lines, position - offset)
        val next = lines.getOrNull(index + 1)?.timeMs?.plus(offset)?.coerceAtLeast(0)
        val text = lines.getOrNull(index)?.text?.let(::displayText)?.takeIf(String::isNotBlank)
        return MediaCardLyricLine(text, next)
    }

    /** Bounded single-line text; do not split a supplementary Unicode character at the limit. */
    fun displayText(text: String): String = text.replace(whitespace, " ").trim().take(512)
        .dropLastWhile { Character.isHighSurrogate(it) }
}
