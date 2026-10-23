package tech.musxi.core.lyrics

import java.util.Collections

/**
 * Verifies bounded KRC rows against an original LRC timeline.
 *
 * KRC rows are only attached when every original line has one corresponding
 * KRC row on the same signed timeline. A partial result is never exposed.
 */
object KrcTranslationAligner {
    private const val MAX_ROWS = 10_000
    private const val MIN_TIME_MS = -24L * 60L * 60L * 1_000L
    private const val MAX_TIME_MS = 48L * 60L * 60L * 1_000L
    private const val MAX_TEXT_CODE_POINTS = 4_096
    private const val MAX_TRANSLATION_UTF8_BYTES = 256 * 1024
    private const val MAX_SOURCE_UTF8_BYTES = 512 * 1024
    private const val TOLERANCE_MS = 100L

    data class Row(
        val timeMs: Long,
        val text: String,
        val translation: String? = null,
    ) {
        override fun toString() = "KrcRow(redacted)"
    }

    fun parse(original: String, rows: List<Row>): ParsedLyrics {
        // Parsing the original first preserves its own validation and exception contract.
        val originalTimeline = LrcParser.timeline(original)
        val parsed = originalTimeline.lyrics
        if (parsed.lines.isEmpty()) return LyricsTranslationAligner.parse(original, null)

        val alignedRows = try {
            validateAndAlignRows(rows)
        } catch (_: InvalidRowsException) {
            return LyricsTranslationAligner.failed(parsed, TranslationReason.INVALID_DATA)
        }

        val originalIndices = parsed.lines.indices.sortedWith(
            compareBy<Int> { originalTimeline.signedTimes[it] }.thenBy { it }
        )
        if (alignedRows.size != originalIndices.size) {
            return LyricsTranslationAligner.failed(parsed, TranslationReason.VERSION_MISMATCH)
        }

        alignedRows.indices.forEach { index ->
            val row = alignedRows[index]
            val originalIndex = originalIndices[index]
            val originalText = parsed.lines[originalIndex].text
            val signedTime = originalTimeline.signedTimes[originalIndex]
            if (absoluteDifference(row.row.timeMs, signedTime) > TOLERANCE_MS ||
                row.normalizedText != normalizeText(originalText)
            ) {
                return LyricsTranslationAligner.failed(parsed, TranslationReason.VERSION_MISMATCH)
            }
        }

        var hasTranslation = false
        val result = parsed.lines.toMutableList()
        alignedRows.indices.forEach { index ->
            val row = alignedRows[index].row
            val originalIndex = originalIndices[index]
            val originalText = parsed.lines[originalIndex].text
            val translation = row.translation?.trim()
            val normalizedTranslation = translation?.let(::normalizeText)
            if (!translation.isNullOrEmpty() &&
                normalizedTranslation != "//" &&
                normalizedTranslation != normalizeText(originalText)
            ) {
                result[originalIndex] = result[originalIndex].copy(translation = translation)
                hasTranslation = true
            }
        }

        if (!hasTranslation) {
            return parsed.copy(translation = LyricsTranslation(
                TranslationAvailability.NONE,
                TranslationLookup(TranslationLookupStatus.SUCCEEDED, TranslationReason.NOT_PROVIDED),
            ))
        }

        return parsed.copy(
            lines = Collections.unmodifiableList(result),
            translation = LyricsTranslation(
                TranslationAvailability.AVAILABLE,
                TranslationLookup(TranslationLookupStatus.SUCCEEDED),
            ),
        )
    }

    private fun validateAndAlignRows(rows: List<Row>): List<AlignedRow> {
        if (rows.size > MAX_ROWS) invalidRows()

        var sourceBytes = 0L
        var translationBytes = 0L
        val alignedRows = ArrayList<AlignedRow>(rows.size)
        rows.forEachIndexed { inputIndex, row ->
            if (row.timeMs !in MIN_TIME_MS..MAX_TIME_MS) invalidRows()
            sourceBytes += measureText(row.text)
            row.translation?.let { translationBytes += measureText(it) }
            if (sourceBytes > MAX_SOURCE_UTF8_BYTES ||
                translationBytes > MAX_TRANSLATION_UTF8_BYTES
            ) {
                invalidRows()
            }

            val normalizedText = normalizeText(row.text)
            if (normalizedText.isNotEmpty()) {
                alignedRows.add(AlignedRow(row, inputIndex, normalizedText))
            }
        }
        alignedRows.sortWith(
            compareBy<AlignedRow> { it.row.timeMs }.thenBy { it.inputIndex }
        )
        return alignedRows
    }

    private fun measureText(text: String): Long {
        var codePoints = 0
        var utf8Bytes = 0L
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (codePoint in 0xD800..0xDFFF) invalidRows()
            if (Character.isISOControl(codePoint) && codePoint != '\t'.code) invalidRows()
            codePoints++
            if (codePoints > MAX_TEXT_CODE_POINTS) invalidRows()

            utf8Bytes += when {
                codePoint <= 0x7F -> 1
                codePoint <= 0x7FF -> 2
                codePoint <= 0xFFFF -> 3
                else -> 4
            }
            index += Character.charCount(codePoint)
        }
        return utf8Bytes
    }

    private fun normalizeText(text: String): String {
        val normalized = StringBuilder(text.length)
        var pendingSpace = false
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.isWhitespace(codePoint)) {
                if (normalized.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) normalized.append(' ')
                normalized.appendCodePoint(codePoint)
                pendingSpace = false
            }
            index += Character.charCount(codePoint)
        }
        return normalized.toString()
    }

    private fun absoluteDifference(first: Long, second: Long): Long =
        if (first >= second) first - second else second - first

    private fun invalidRows(): Nothing = throw InvalidRowsException()

    private data class AlignedRow(
        val row: Row,
        val inputIndex: Int,
        val normalizedText: String,
    )

    private class InvalidRowsException : RuntimeException()
}
