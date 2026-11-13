package tech.musxi.accounts

import java.util.Collections
import tech.musxi.core.lyrics.*
import tech.musxi.qqloginpoc.QrcLyricsDecoder

/** Optional QRC attachment after the ordinary original and translation have been decoded. */
internal object QqWordLyrics {
    fun attach(original: String, translated: ParsedLyrics, document: QrcLyricsDecoder.Document,
        previous: ParsedLyrics? = null): ParsedLyrics {
        if (document.status == QrcLyricsDecoder.Status.ABSENT) return translated
        if (document.status == QrcLyricsDecoder.Status.PRESENT) {
            // The same strict full-document check as KRC, independent of translation success.
            val aligned = KrcTranslationAligner.parse(original, document.rows.map { row ->
                KrcTranslationAligner.Row(row.timeMs, row.text, words = row.words.map {
                    LyricWord(it.textStart, it.textEnd, it.startTimeMs, it.endTimeMs)
                })
            })
            if (aligned.translation.lookup.status == TranslationLookupStatus.SUCCEEDED)
                return translated.copy(lines = Collections.unmodifiableList(translated.lines.mapIndexed { index, line ->
                    line.copy(words = aligned.lines[index].words)
                }))
        }
        return previous?.let { LyricsWordTimeline.retainVerified(it, translated) } ?: translated
    }
}
