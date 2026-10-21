package tech.musxi.core.lyrics

import java.util.Collections

data class LyricLine(
    val timeMs: Long,
    val text: String
)

data class ParsedLyrics(
    val lines: List<LyricLine>,
    val plainText: String
)

object LrcParser {
    private const val MAX_UTF8_BYTES = 512 * 1024
    private const val MAX_SOURCE_LINES = 10_000
    private const val MAX_LINE_CHARACTERS = 4_096
    private const val MAX_PARSED_LINES = 20_000
    private const val MAX_TIME_MS = 24L * 60L * 60L * 1_000L

    private val metadataPattern = Regex(
        """^\[(ar|ti|al|by|re|ve|length|offset)\s*:(.*)]$""",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String): ParsedLyrics {
        validateInput(text)

        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val sourceLines = splitLines(normalized)
        if (sourceLines.size > MAX_SOURCE_LINES) invalidLyrics()

        var offsetMs = 0L
        var hasValidTimestamp = false
        var nextSequence = 0
        val timedLines = ArrayList<TimedLine>()
        val plainLines = ArrayList<String>()

        sourceLines.forEach { sourceLine ->
            if (sourceLine.codePointCount(0, sourceLine.length) > MAX_LINE_CHARACTERS) {
                invalidLyrics()
            }

            val trimmedLine = sourceLine.trim()
            val metadata = metadataPattern.matchEntire(trimmedLine)
            if (metadata != null) {
                val key = metadata.groupValues[1]
                if (key.equals("offset", ignoreCase = true)) {
                    parseOffset(metadata.groupValues[2])?.let { offsetMs = it }
                }
                return@forEach
            }

            val timed = parseTimestampPrefix(sourceLine)
            if (timed == null) {
                plainLines.add(trimmedLine)
                return@forEach
            }

            hasValidTimestamp = true
            plainLines.add(timed.text)
            if (timed.text.isNotEmpty()) {
                if (timedLines.size + timed.times.size > MAX_PARSED_LINES) invalidLyrics()
                timed.times.forEach { timestamp ->
                    timedLines.add(TimedLine(nextSequence++, timestamp, timed.text))
                }
            }
        }

        val sortedLines = if (hasValidTimestamp) {
            val adjustedLines = timedLines.mapTo(ArrayList(timedLines.size)) { line ->
                val adjusted = (line.timeMs + offsetMs).coerceAtLeast(0L)
                if (adjusted > MAX_TIME_MS) invalidLyrics()
                line.copy(timeMs = adjusted)
            }
            adjustedLines.sortWith(
                compareBy<TimedLine> { it.timeMs }.thenBy { it.sequence }
            )
            Collections.unmodifiableList(
                adjustedLines.mapTo(ArrayList(adjustedLines.size)) {
                    LyricLine(it.timeMs, it.text)
                }
            )
        } else {
            Collections.emptyList()
        }

        return ParsedLyrics(
            lines = sortedLines,
            plainText = cleanPlainText(plainLines)
        )
    }

    fun activeIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val middle = low + ((high - low) ushr 1)
            if (lines[middle].timeMs <= positionMs) {
                result = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return result
    }

    private fun validateInput(text: String) {
        var utf8Bytes = 0
        var index = 0
        while (index < text.length) {
            val code = text[index].code
            val invalidControl =
                code in 0x00..0x08 ||
                    code in 0x0B..0x0C ||
                    code in 0x0E..0x1F ||
                    code in 0x7F..0x9F
            if (invalidControl) invalidLyrics()

            utf8Bytes += when {
                code <= 0x7F -> 1
                code <= 0x7FF -> 2
                Character.isHighSurrogate(text[index]) &&
                    index + 1 < text.length &&
                    Character.isLowSurrogate(text[index + 1]) -> {
                    index++
                    4
                }
                code in 0xD800..0xDFFF -> invalidLyrics()
                else -> 3
            }
            if (utf8Bytes > MAX_UTF8_BYTES) invalidLyrics()
            index++
        }
    }

    private fun splitLines(text: String): List<String> {
        val lines = ArrayList<String>()
        var start = 0
        for (index in text.indices) {
            if (text[index] == '\n') {
                lines.add(text.substring(start, index))
                start = index + 1
            }
        }
        lines.add(text.substring(start))
        return lines
    }

    private fun parseTimestampPrefix(line: String): TimedText? {
        var cursor = 0
        while (cursor < line.length && line[cursor].isWhitespace()) cursor++

        val times = ArrayList<Long>()
        while (cursor < line.length && line[cursor] == '[') {
            val closingBracket = line.indexOf(']', cursor + 1)
            if (closingBracket < 0) break

            val timestamp = parseTimestamp(line.substring(cursor + 1, closingBracket))
                ?: break
            times.add(timestamp)
            cursor = closingBracket + 1
            while (cursor < line.length && line[cursor].isWhitespace()) cursor++
        }

        if (times.isEmpty()) return null
        return TimedText(times, line.substring(cursor).trim())
    }

    private fun parseTimestamp(value: String): Long? {
        val colon = value.indexOf(':')
        if (colon <= 0 || colon != value.lastIndexOf(':')) return null

        val minuteText = value.substring(0, colon)
        if (minuteText.any { !it.isDigit() }) return null

        val secondAndFraction = value.substring(colon + 1)
        val dot = secondAndFraction.indexOf('.')
        if (dot >= 0 && dot != secondAndFraction.lastIndexOf('.')) return null

        val secondText = if (dot < 0) secondAndFraction else secondAndFraction.substring(0, dot)
        if (secondText.length != 2 || secondText.any { !it.isDigit() }) return null
        val seconds = secondText.toIntOrNull() ?: return null
        if (seconds !in 0..59) return null

        val fractionText = if (dot < 0) "" else secondAndFraction.substring(dot + 1)
        if (dot >= 0 && fractionText.length !in 1..3) return null
        if (fractionText.any { !it.isDigit() }) return null
        val milliseconds = when (fractionText.length) {
            0 -> 0
            1 -> fractionText.toInt() * 100
            2 -> fractionText.toInt() * 10
            else -> fractionText.toInt()
        }

        val minutes = minuteText.toLongOrNull() ?: invalidLyrics()
        if (minutes > MAX_TIME_MS / 60_000L) invalidLyrics()
        val time = minutes * 60_000L + seconds * 1_000L + milliseconds
        if (time > MAX_TIME_MS) invalidLyrics()
        return time
    }

    private fun parseOffset(value: String): Long? {
        val parsed = value.trim().toLongOrNull() ?: return null
        return parsed.takeIf { it in -MAX_TIME_MS..MAX_TIME_MS }
    }

    private fun cleanPlainText(lines: List<String>): String {
        var first = 0
        var last = lines.lastIndex
        while (first <= last && lines[first].isBlank()) first++
        while (last >= first && lines[last].isBlank()) last--
        if (first > last) return ""
        return lines.subList(first, last + 1).joinToString("\n")
    }

    private fun invalidLyrics(): Nothing {
        throw IllegalArgumentException("Invalid lyrics")
    }

    private data class TimedLine(
        val sequence: Int,
        val timeMs: Long,
        val text: String
    )

    private data class TimedText(
        val times: List<Long>,
        val text: String
    )
}
