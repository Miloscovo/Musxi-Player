package tech.musxi.accounts

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.lyrics.*
import tech.musxi.qqloginpoc.QrcLyricsDecoder

class QqWordLyricsTest {
    private val original = "[00:01.00]Hi\n[00:02.00]Bye"
    private val qrc = "[1004,500]H(1004,200)i(1204,300)\n[2009,300]Bye(2009,300)"
    private fun attach(source: String = original, words: String = qrc, previous: ParsedLyrics? = null,
        translated: ParsedLyrics = LyricsTranslationAligner.parse(source, "[00:01.00]你好")) =
        QqWordLyrics.attach(source, translated, QrcLyricsDecoder.decode(words), previous)

    @Test fun verifiesEveryOriginalRowAndKeepsProviderWordClock() {
        val parsed = attach()
        assertEquals(1000L, parsed.lines[0].timeMs)
        assertEquals(LyricWord(0, 1, 1004, 1204), parsed.lines[0].words[0])
        assertEquals("你好", parsed.lines[0].translation)
        assertEquals(TranslationLookupStatus.SUCCEEDED, parsed.translation.lookup.status)
        assertTrue(parsed.lines.all { LyricsWordTimeline.valid(it.text, it.words) })
    }

    @Test fun rejectsDifferentRecordingAndPartialRowsWithoutChangingTranslation() {
        for (bad in listOf("[5000,500]Hi(5000,500)\n[6000,300]Bye(6000,300)",
            "[1000,500]Hi(1000,500)", qrc.replace("Bye", "Other"))) {
            val parsed = attach(words = bad)
            assertTrue(parsed.lines.all { it.words.isEmpty() })
            assertEquals("你好", parsed.lines[0].translation)
        }
    }

    @Test fun malformedQrcRetainsPriorWordsEvenWhenTranslationSucceeds() {
        val prior = attach()
        val parsed = attach(words = "bad QRC", previous = prior)
        assertEquals(prior, parsed)
        val changed = attach(source = original.replace("Bye", "Other"), words = "bad", previous = prior)
        assertTrue(changed.lines.all { it.words.isEmpty() })
    }

    @Test fun knownAbsenceClearsPriorWordsAndTranslationFailureDoesNotDiscardNewWords() {
        val old = attach()
        assertTrue(attach(words = "", previous = old).lines.all { it.words.isEmpty() })
        val failed = LyricsTranslationAligner.failed(LrcParser.parse(original), TranslationReason.INVALID_DATA)
        val parsed = attach(translated = failed)
        assertTrue(parsed.lines.all { it.words.isNotEmpty() })
        assertEquals(TranslationLookupStatus.FAILED, parsed.translation.lookup.status)
    }

    @Test fun whitespaceFoldingDoesNotMoveUtf16RangesOntoDifferentText() {
        val parsed = attach(words = qrc.replace("H(1004,200)i", "H(1004,200) i"))
        assertTrue(parsed.lines.all { it.words.isEmpty() })
        assertEquals("你好", parsed.lines[0].translation)
    }
}
