package tech.musxi.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.lyrics.LyricLine
import tech.musxi.core.lyrics.LyricsState
import tech.musxi.core.lyrics.LyricsStatus
import tech.musxi.core.lyrics.LyricsTranslation
import tech.musxi.core.lyrics.TranslationAvailability
import tech.musxi.core.lyrics.TranslationLookup
import tech.musxi.core.lyrics.TranslationLookupStatus

class LyricsTranslationUiTest {

    @Test fun originalsChooseTheDefaultUnlessTheUserOverridesIt() {
        val available = state(
            TranslationAvailability.AVAILABLE, TranslationLookupStatus.SUCCEEDED, "译文",
        )
        val chinese = available.copy(lines = listOf(LyricLine(0, "我们走在回家的路上", "English translation")))
        assertEquals(TranslationChip.OFF, lyricsUiOf(chinese).translation.chip)
        assertEquals(TranslationChip.ON, lyricsUiOf(available).translation.chip)
        assertEquals(TranslationChip.ON, lyricsUiOf(chinese, showTranslation = true).translation.chip)
        assertEquals(TranslationChip.OFF, lyricsUiOf(available, showTranslation = false).translation.chip)
    }

    @Test fun confirmedNoneIsNotAFailure() {
        val ui = lyricsUiOf(state(TranslationAvailability.NONE, TranslationLookupStatus.SUCCEEDED))

        assertEquals(TranslationChip.NONE, ui.translation.chip)
        assertFalse(ui.translation.lookupFailed)
        assertFalse(ui.translation.hasText)
    }

    @Test fun unsupportedIsConfirmedNone() {
        val ui = lyricsUiOf(state(TranslationAvailability.UNKNOWN, TranslationLookupStatus.UNSUPPORTED))

        assertEquals(TranslationChip.NONE, ui.translation.chip)
        assertFalse(ui.translation.lookupFailed)
    }

    @Test fun failedLookupWithUnknownAvailabilityIsRetryableWarning() {
        val ui = lyricsUiOf(
            state(TranslationAvailability.UNKNOWN, TranslationLookupStatus.FAILED),
            showTranslation = true,
        )

        assertEquals(TranslationChip.WARNING, ui.translation.chip)
        assertTrue(ui.translation.lookupFailed)
        assertFalse(ui.translation.hasText)
    }

    @Test fun failedRefreshKeepsUsableOldTranslation() {
        val ui = lyricsUiOf(
            state(
                TranslationAvailability.AVAILABLE,
                TranslationLookupStatus.FAILED,
                translation = "Old translation",
            ),
            showTranslation = true,
        )

        assertEquals(TranslationChip.WARNING, ui.translation.chip)
        assertTrue(ui.translation.hasText)
        assertTrue(ui.translation.showsText)
        assertEquals("Old translation", ui.translationOf(ui.lines.single()))
    }

    @Test fun manualOffDoesNotShowOldTranslation() {
        val ui = lyricsUiOf(
            state(
                TranslationAvailability.AVAILABLE,
                TranslationLookupStatus.FAILED,
                translation = "Old translation",
            ),
            showTranslation = false,
        )

        assertEquals(TranslationChip.OFF, ui.translation.chip)
        assertTrue(ui.translation.hasText)
        assertFalse(ui.translation.showsText)
        assertNull(ui.translationOf(ui.lines.single()))
    }

    @Test fun blankTranslationsDoNotCountAsText() {
        val ui = lyricsUiOf(
            state(
                TranslationAvailability.AVAILABLE,
                TranslationLookupStatus.SUCCEEDED,
                translation = "  ",
            ),
        )

        assertFalse(ui.translation.hasText)
    }

    private fun state(
        availability: TranslationAvailability,
        lookup: TranslationLookupStatus,
        translation: String? = null,
    ): LyricsState = LyricsState(
        status = LyricsStatus.TIMED,
        lines = listOf(LyricLine(0, "Original", translation)),
        translation = LyricsTranslation(
            availability = availability,
            lookup = TranslationLookup(lookup),
        ),
        canRefreshTranslation = true,
    )
}
