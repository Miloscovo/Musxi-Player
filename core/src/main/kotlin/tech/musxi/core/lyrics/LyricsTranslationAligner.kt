package tech.musxi.core.lyrics

import java.util.Collections
import kotlin.math.abs

/** Bounded LRC-to-LRC attachment. Inputs must describe the same platform recording. */
object LyricsTranslationAligner {
    private const val MAX_TRANSLATION_BYTES = 256 * 1024
    private const val TOLERANCE_MS = 100L

    fun parse(original: String, translation: String?): ParsedLyrics {
        // Invalid originals still fail the entire operation. Translation failures never do.
        val originalTimeline = LrcParser.timeline(original)
        val parsed = originalTimeline.lyrics
        if (parsed.lines.isEmpty()) return parsed.copy(translation = LyricsTranslation(
            lookup = TranslationLookup(TranslationLookupStatus.UNSUPPORTED,
                if (parsed.plainText.isBlank()) TranslationReason.NO_LYRICS else TranslationReason.NOT_SYNCED)))
        if (translation == null) return withoutTranslation(parsed)
        val translated = try {
            require(translation.length <= MAX_TRANSLATION_BYTES)
            require(translation.toByteArray(Charsets.UTF_8).size <= MAX_TRANSLATION_BYTES)
            LrcParser.timeline(translation)
        } catch (_: IllegalArgumentException) { return failed(parsed, TranslationReason.INVALID_DATA) }
        if (translated.lyrics.plainText.isBlank()) return withoutTranslation(parsed)
        if (translated.lyrics.lines.isEmpty()) return failed(parsed, TranslationReason.UNSUPPORTED_FORMAT)

        val originals = parsed.lines.indices.sortedWith(compareBy<Int> { originalTimeline.signedTimes[it] }.thenBy { it })
        val translations = translated.lyrics.lines.indices.filter {
            translated.lyrics.lines[it].text.trim() != "//"
        }.sortedWith(compareBy<Int> { translated.signedTimes[it] }.thenBy { it })
        if (translations.isEmpty()) return withoutTranslation(parsed)

        val matched = IntArray(translations.size) { -1 }
        val used = BooleanArray(originals.size)
        // Reserve exact matches first, preserving file order for duplicate timestamps.
        var left = 0
        translations.forEachIndexed { index, row ->
            val time = translated.signedTimes[row]
            while (left < originals.size && originalTimeline.signedTimes[originals[left]] < time) left++
            if (left < originals.size && originalTimeline.signedTimes[originals[left]] == time) {
                matched[index] = left
                used[left++] = true
            }
        }
        // Tolerance matching stays within exact anchors, so pairs never cross or reuse rows.
        var previousOriginal = -1
        var segmentStart = 0
        for (end in 0..translations.size) {
            if (end != translations.size && matched[end] < 0) continue
            val nextOriginal = if (end == translations.size) originals.size else matched[end]
            var cursor = previousOriginal + 1
            for (index in segmentStart until end) {
                val time = translated.signedTimes[translations[index]]
                while (cursor < nextOriginal && originalTimeline.signedTimes[originals[cursor]] < time - TOLERANCE_MS) cursor++
                var best = -1
                var candidate = cursor
                var distance = Long.MAX_VALUE
                while (candidate < nextOriginal && originalTimeline.signedTimes[originals[candidate]] <= time + TOLERANCE_MS) {
                    val delta = abs(originalTimeline.signedTimes[originals[candidate]] - time)
                    if (!used[candidate] && delta < distance) { best = candidate; distance = delta }
                    // Ordered times: after passing time, later candidates cannot be closer.
                    if (originalTimeline.signedTimes[originals[candidate]] >= time) break
                    candidate++
                }
                if (best >= 0) {
                    val originalTime = originalTimeline.signedTimes[originals[best]]
                    // Dense or competing fuzzy timestamps are ambiguous. Leave them unattached
                    // rather than letting an earlier row consume a later row's better match.
                    val competingOriginal =
                        (best > previousOriginal + 1 && abs(originalTimeline.signedTimes[originals[best - 1]] - time) <= TOLERANCE_MS) ||
                        (best + 1 < nextOriginal && abs(originalTimeline.signedTimes[originals[best + 1]] - time) <= TOLERANCE_MS)
                    val competingTranslation =
                        (index > segmentStart && abs(translated.signedTimes[translations[index - 1]] - originalTime) <= TOLERANCE_MS) ||
                        (index + 1 < end && abs(translated.signedTimes[translations[index + 1]] - originalTime) <= TOLERANCE_MS)
                    if (!competingOriginal && !competingTranslation) {
                        matched[index] = best; used[best] = true; cursor = best + 1
                    }
                }
            }
            if (end != translations.size) { previousOriginal = nextOriginal; segmentStart = end + 1 }
        }
        if (matched.count { it >= 0 } * 5 < translations.size * 4)
            return failed(parsed, TranslationReason.VERSION_MISMATCH)
        val result = parsed.lines.toMutableList()
        matched.forEachIndexed { index, match ->
            if (match < 0) return@forEachIndexed
            val row = originals[match]
            val text = translated.lyrics.lines[translations[index]].text.trim()
            // Copied credits/original words are not a translation.
            if (text != parsed.lines[row].text.trim()) result[row] = result[row].copy(translation = text)
        }
        if (result.none { it.translation != null }) return withoutTranslation(parsed)
        return parsed.copy(lines = Collections.unmodifiableList(result), translation = LyricsTranslation(
            TranslationAvailability.AVAILABLE, TranslationLookup(TranslationLookupStatus.SUCCEEDED)))
    }

    private fun withoutTranslation(parsed: ParsedLyrics) = parsed.copy(translation = LyricsTranslation(
        TranslationAvailability.NONE,
        TranslationLookup(TranslationLookupStatus.SUCCEEDED, TranslationReason.NOT_PROVIDED)))

    fun failed(parsed: ParsedLyrics, reason: TranslationReason): ParsedLyrics = parsed.copy(
        translation = LyricsTranslation(TranslationAvailability.UNKNOWN,
            TranslationLookup(TranslationLookupStatus.FAILED, reason)))
}
