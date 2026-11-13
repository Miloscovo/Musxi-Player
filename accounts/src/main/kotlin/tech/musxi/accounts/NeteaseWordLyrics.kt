package tech.musxi.accounts

import java.util.Collections
import tech.musxi.core.account.Platform
import tech.musxi.core.lyrics.*
import tech.musxi.neteaseloginpoc.NeteaseLibraryApi
import tech.musxi.neteaseloginpoc.YrcLyricsDecoder

/** YRC owns its line timeline and ytlrc translation; legacy tlyric is never pasted onto it. */
internal object NeteaseWordLyrics {
    const val TIMING_REVISION = 1

    fun needsRefresh(platform: Platform, lyrics: ParsedLyrics): Boolean =
        platform == Platform.NETEASE && lyrics.timingRevision < TIMING_REVISION

    fun parse(document: NeteaseLibraryApi.LyricsDocument, previous: ParsedLyrics? = null): ParsedLyrics {
        val translated = if (document.translationStatus == NeteaseLibraryApi.TranslationStatus.INVALID)
            LyricsTranslationAligner.failed(LrcParser.parse(document.original), TranslationReason.INVALID_DATA)
        else LyricsTranslationAligner.parse(document.original, document.translation)
        val timed = attach(document, translated)
        if (previous != null && (previous.plainText != timed.plainText || previous.lines.size != timed.lines.size ||
            previous.lines.indices.any { previous.lines[it].text != timed.lines[it].text ||
                previous.lines[it].timeMs != timed.lines[it].timeMs }))
            return LyricsTranslationAligner.failed(previous, if (document.wordTimings.status == YrcLyricsDecoder.Status.NETWORK)
                TranslationReason.NETWORK else TranslationReason.VERSION_MISMATCH)
        return if (previous != null && document.wordTimings.status !in setOf(
                YrcLyricsDecoder.Status.PRESENT, YrcLyricsDecoder.Status.ABSENT))
            LyricsWordTimeline.retainVerified(previous, timed)
        else timed
    }

    fun attach(document: NeteaseLibraryApi.LyricsDocument, parsed: ParsedLyrics): ParsedLyrics {
        val result = parsed.copy(timingRevision = document.timingRevision)
        if (document.wordTimings.status != YrcLyricsDecoder.Status.PRESENT) return result
        val aligned = KrcTranslationAligner.parse(document.original, document.wordTimings.rows.map { row ->
            KrcTranslationAligner.Row(row.timeMs, row.text, words = row.words.map {
                LyricWord(it.textStart, it.textEnd, it.startTimeMs, it.endTimeMs)
            })
        })
        if (aligned.translation.lookup.status != TranslationLookupStatus.SUCCEEDED) return result
        return result.copy(lines = Collections.unmodifiableList(result.lines.mapIndexed { index, line ->
            line.copy(words = aligned.lines[index].words)
        }))
    }
}
