package tech.musxi.player.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.lyrics.LyricLine
import tech.musxi.core.lyrics.LyricsState
import tech.musxi.core.lyrics.LyricsStatus
import tech.musxi.core.lyrics.LyricsTranslation
import tech.musxi.core.lyrics.TranslationAvailability
import tech.musxi.core.lyrics.TranslationLookup
import tech.musxi.core.lyrics.TranslationLookupStatus

class TranslationPreferenceTest {

    @Test fun chineseWithEnglishChorusDefaultsOff() {
        val state = timed(
            "夜色慢慢落下",
            "我们仍然歌唱",
            "We sing tonight",
            "明天再见",
        )

        assertFalse(defaultTranslationEnabled(state))
    }

    @Test fun traditionalChineseAndCantoneseDefaultOff() {
        assertFalse(defaultTranslationEnabled(timed("仍然記得你的笑容")))
        assertFalse(defaultTranslationEnabled(timed("我哋今晚一齊唱歌")))
    }

    @Test fun englishDefaultsOn() {
        assertTrue(defaultTranslationEnabled(timed("We sing in the dark")))
    }

    @Test fun kanaMakesJapaneseDefaultOnEvenWithHan() {
        assertTrue(defaultTranslationEnabled(timed("君の声を聞かせて")))
        assertTrue(defaultTranslationEnabled(timed("東京風景夜空の夢", "晚安")))
    }

    @Test fun mixedOriginalUsesTheDominantLanguage() {
        assertFalse(
            defaultTranslationEnabled(
                timed(
                    "我们走在回家的路上",
                    "风从远处吹来",
                    "東京の夜",
                    "请记得抬头看星星",
                ),
            ),
        )
        assertFalse(
            defaultTranslationEnabled(
                timed(
                    "我们走过很长很长的路看过许多风景",
                    "Oh",
                    "Ah",
                ),
            ),
        )
    }

    @Test fun koreanDefaultsOn() {
        assertTrue(defaultTranslationEnabled(timed("밤하늘에 별이 빛나")))
    }

    @Test fun noOriginalEvidenceDefaultsOnWithoutCallingItChinese() {
        assertTrue(defaultTranslationEnabled(LyricsState(status = LyricsStatus.EMPTY)))
        assertTrue(defaultTranslationEnabled(timed("……", "♪")))
    }

    @Test fun translationTextNeverChangesTheDecision() {
        val chinese = timed("我们仍然歌唱")
            .copy(lines = listOf(LyricLine(0, "我们仍然歌唱", "We keep singing")))
        val english = timed("We keep singing")
            .copy(lines = listOf(LyricLine(0, "We keep singing", "我们继续歌唱")))

        assertFalse(defaultTranslationEnabled(chinese))
        assertTrue(defaultTranslationEnabled(english))
    }

    @Test fun manualChoiceSurvivesDelayedTranslationAndRefresh() {
        val chinese = timed("夜色慢慢落下")
        val delayed = chinese.copy(
            lines = listOf(LyricLine(0, "夜色慢慢落下", "Night is falling")),
            translation = LyricsTranslation(
                availability = TranslationAvailability.AVAILABLE,
                lookup = TranslationLookup(TranslationLookupStatus.SUCCEEDED),
            ),
        )
        val refreshed = delayed.copy(
            translation = delayed.translation.copy(
                lookup = TranslationLookup(TranslationLookupStatus.FAILED),
            ),
        )

        assertTrue(translationVisible(true, chinese))
        assertTrue(translationVisible(true, delayed))
        assertTrue(translationVisible(true, refreshed))
        assertFalse(translationVisible(false, timed("We keep singing")))
    }

    private fun timed(vararg lines: String): LyricsState = LyricsState(
        status = LyricsStatus.TIMED,
        lines = lines.mapIndexed { index, text -> LyricLine(index.toLong(), text) },
    )
}
