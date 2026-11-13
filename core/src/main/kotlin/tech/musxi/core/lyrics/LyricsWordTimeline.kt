package tech.musxi.core.lyrics

/** UTF-16 text range [textStart, textEnd), with absolute track times before manual offset. */
data class LyricWord(
    val textStart: Int,
    val textEnd: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
)

data class LyricWordPosition(val index: Int = -1, val progress: Float = 0f)

/** Pure sampling: seek and pause use the playback clock, never an independent running timer. */
object LyricsWordTimeline {
    const val MAX_WORDS_PER_LINE = 4_096
    const val MAX_WORDS_PER_DOCUMENT = 100_000
    const val MAX_TIME_MS = 24L * 60L * 60L * 1_000L

    /** A failed optional lookup cannot erase verified words from this same original document. */
    fun retainOnFailure(previous: ParsedLyrics, incoming: ParsedLyrics): ParsedLyrics {
        if (incoming.translation.lookup.status !in setOf(TranslationLookupStatus.FAILED,
                TranslationLookupStatus.UNSUPPORTED)) return incoming
        return retainVerified(previous, incoming)
    }

    /** For an independently failed word lookup, even when the translation lookup succeeds. */
    fun retainVerified(previous: ParsedLyrics, incoming: ParsedLyrics): ParsedLyrics {
        if (previous.plainText != incoming.plainText ||
            previous.lines.size != incoming.lines.size || previous.lines.indices.any {
                previous.lines[it].text != incoming.lines[it].text ||
                    previous.lines[it].timeMs != incoming.lines[it].timeMs
            }) return incoming
        return incoming.copy(lines = java.util.Collections.unmodifiableList(incoming.lines.mapIndexed { index, line ->
            val old = previous.lines[index]
            if (line.words.isEmpty() && valid(old.text, old.words)) line.copy(words = old.words) else line
        }))
    }

    fun valid(text: String, words: List<LyricWord>): Boolean {
        if (words.isEmpty()) return true
        if (words.size > MAX_WORDS_PER_LINE) return false
        var textEnd = 0
        var startTime = -1L
        for (word in words) {
            if (word.textStart != textEnd || word.textEnd <= word.textStart || word.textEnd > text.length ||
                word.startTimeMs !in 0..MAX_TIME_MS || word.endTimeMs !in word.startTimeMs..MAX_TIME_MS ||
                word.startTimeMs < startTime || splitsSurrogate(text, word.textStart) ||
                splitsSurrogate(text, word.textEnd)) return false
            textEnd = word.textEnd
            startTime = word.startTimeMs
        }
        return textEnd == text.length
    }

    /** The latest started span remains selected through gaps and after its end, with progress 1. */
    fun sample(words: List<LyricWord>, positionMs: Long): LyricWordPosition {
        var low = 0
        var high = words.lastIndex
        var index = -1
        while (low <= high) {
            val middle = low + (high - low) / 2
            if (words[middle].startTimeMs <= positionMs) { index = middle; low = middle + 1 }
            else high = middle - 1
        }
        if (index < 0) return LyricWordPosition()
        val word = words[index]
        val duration = word.endTimeMs - word.startTimeMs
        val progress = if (duration == 0L) 1f else
            ((positionMs - word.startTimeMs).toDouble() / duration).coerceIn(0.0, 1.0).toFloat()
        return LyricWordPosition(index, progress)
    }

    private fun splitsSurrogate(text: String, index: Int): Boolean =
        index > 0 && index < text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()
}
