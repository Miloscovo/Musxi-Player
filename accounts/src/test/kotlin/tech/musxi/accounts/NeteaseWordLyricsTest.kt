package tech.musxi.accounts

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.lyrics.*
import tech.musxi.neteaseloginpoc.NeteaseLibraryApi
import tech.musxi.neteaseloginpoc.YrcLyricsDecoder

class NeteaseWordLyricsTest {
    private val native = YrcLyricsDecoder.decode("[1500,500](1500,250,0)H(1750,250,0)i\n[2500,500](2500,500,0)Bye")
    private fun doc(words: YrcLyricsDecoder.Document = native,
        original: String = words.original.ifEmpty { native.original }, translation: String? = "[00:01.500]你好",
        status: NeteaseLibraryApi.TranslationStatus = NeteaseLibraryApi.TranslationStatus.PRESENT,
        revision: Int = 1): NeteaseLibraryApi.LyricsDocument {
        val constructor = NeteaseLibraryApi.LyricsDocument::class.java.declaredConstructors.single { it.parameterCount == 5 }
        constructor.isAccessible = true
        return constructor.newInstance(original, translation, status, words, revision) as NeteaseLibraryApi.LyricsDocument
    }

    @Test fun nativeTimelineAndMatchingTranslationKeepTheirOwnClock() {
        val parsed = NeteaseWordLyrics.parse(doc())
        assertEquals(listOf(1500L, 2500L), parsed.lines.map { it.timeMs })
        assertEquals(LyricWord(0, 1, 1500, 1750), parsed.lines[0].words[0])
        assertEquals("你好", parsed.lines[0].translation)
        assertEquals(1, parsed.timingRevision)
        assertTrue(parsed.lines.all { LyricsWordTimeline.valid(it.text, it.words) })
    }

    @Test fun unrelatedOldTranslationFailsSafelyWithoutDiscardingValidNativeWords() {
        val parsed = NeteaseWordLyrics.parse(doc(translation = "[00:01.000]旧时间轴"))
        assertTrue(parsed.lines.all { it.words.isNotEmpty() })
        assertTrue(parsed.lines.all { it.translation == null })
        assertEquals(TranslationReason.VERSION_MISMATCH, parsed.translation.lookup.reason)
        val invalid = NeteaseWordLyrics.parse(doc(status = NeteaseLibraryApi.TranslationStatus.INVALID))
        assertTrue(invalid.lines.all { it.words.isNotEmpty() })
        assertEquals(TranslationReason.INVALID_DATA, invalid.translation.lookup.reason)
    }

    @Test fun originalStageCanAttachWordsBeforeTranslationLookup() {
        val document = doc()
        val original = LrcParser.parse(document.original).copy(translation = LyricsTranslation(
            lookup = TranslationLookup(TranslationLookupStatus.IDLE)))
        val staged = NeteaseWordLyrics.attach(document, original)
        assertTrue(staged.lines.all { it.words.isNotEmpty() })
        assertEquals(TranslationLookupStatus.IDLE, staged.translation.lookup.status)
        assertTrue(staged.lines.all { it.translation == null })
    }

    @Test fun failedOptionalWordLookupPreservesOnlyAnIdenticalOriginal() {
        val old = NeteaseWordLyrics.parse(doc())
        val failed = doc(words = YrcLyricsDecoder.decode("bad"))
        assertEquals(old.lines, NeteaseWordLyrics.parse(failed, old).lines)
        val other = doc(words = YrcLyricsDecoder.decode("bad"), original = native.original.replace("Bye", "Other"))
        val mismatch = NeteaseWordLyrics.parse(other, old)
        assertEquals(old.lines, mismatch.lines)
        assertEquals(TranslationReason.VERSION_MISMATCH, mismatch.translation.lookup.reason)
        val absent = doc(words = YrcLyricsDecoder.decode(""))
        assertTrue(NeteaseWordLyrics.parse(absent, old).lines.all { it.words.isEmpty() })
    }

    @Test fun refreshCannotDowngradeNativeCacheOrReplaceDisplayedTimelineWithLegacyFallback() {
        val old = NeteaseWordLyrics.parse(doc())
        val fallback = doc(words = YrcLyricsDecoder.decode("bad"), original = "[00:01.000]Hi\n[00:02.000]Bye", revision = 0)
        val parsed = NeteaseWordLyrics.parse(fallback, old)
        assertEquals(old.lines, parsed.lines)
        assertEquals(1, parsed.timingRevision)
        assertEquals(TranslationReason.VERSION_MISMATCH, parsed.translation.lookup.reason)
    }

    @Test fun onlyOlderNeteaseTimelinesNeedOneOnlineCacheUpgrade() {
        val legacy = LrcParser.parse("[00:01.000]Hi")
        assertTrue(NeteaseWordLyrics.needsRefresh(Platform.NETEASE, legacy))
        assertFalse(NeteaseWordLyrics.needsRefresh(Platform.QQ, legacy))
        assertFalse(NeteaseWordLyrics.needsRefresh(Platform.KUGOU, legacy))
        assertFalse(NeteaseWordLyrics.needsRefresh(Platform.NETEASE, legacy.copy(timingRevision = 1)))
        assertFalse(NeteaseWordLyrics.needsRefresh(Platform.NETEASE, legacy.copy(timingRevision = 2)))
    }
}
