package tech.musxi.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tech.musxi.core.playback.PreferredAudioQuality

class MediaCardLyricsSettingsTest {
    @Test
    fun olderSnapshotsWithoutTheKeyStayOffAndKeepOtherValues() {
        val legacyValues = AppSettingsPersistenceFormat.encode(
            AppSettings(
                themeMode = ThemeMode.DARK,
                lyricsFontSize = LyricsFontSize.LARGE,
                accent = "TEAL",
                mediaCardLyrics = true,
            ),
        ) - AppSettingsPersistenceFormat.MEDIA_CARD_LYRICS - AppSettingsPersistenceFormat.LOCK_SCREEN_LYRICS_MODE

        val decoded = AppSettingsPersistenceFormat.decode(legacyValues)

        assertFalse(decoded.mediaCardLyrics)
        assertEquals(ThemeMode.DARK, decoded.themeMode)
        assertEquals(LyricsFontSize.LARGE, decoded.lyricsFontSize)
        assertEquals("TEAL", decoded.accent)
    }

    @Test
    fun completeSnapshotRoundTripsTheFlag() {
        val expected = AppSettings(
            mediaCardLyrics = true,
            lyricsTranslationPreference = LyricsTranslationPreference.HIDE,
            defaultQuality = PreferredAudioQuality.HIGH,
        )

        val encoded = AppSettingsPersistenceFormat.encode(expected)

        assertEquals("true", encoded[AppSettingsPersistenceFormat.MEDIA_CARD_LYRICS])
        assertEquals(AppSettingsPersistenceFormat.keys, encoded.keys)
        assertEquals(expected, AppSettingsPersistenceFormat.decode(encoded))
    }

    @Test
    fun malformedValueFallsBackToOff() {
        val decoded = AppSettingsPersistenceFormat.decode(
            mapOf(AppSettingsPersistenceFormat.MEDIA_CARD_LYRICS to "sometimes"),
        )

        assertFalse(decoded.mediaCardLyrics)
    }

    @Test
    fun commandPersistsAndSurvivesRepositoryRecreation() {
        val persistence = MemoryPersistence()
        val repository = AppSettingsRepository(persistence)

        repository.setLyricsColorMode(LyricsColorMode.FIXED)
        val updated = repository.setMediaCardLyrics(true)

        assertTrue(updated.mediaCardLyrics)
        assertEquals(
            AppSettings(lyricsColorMode = LyricsColorMode.FIXED, mediaCardLyrics = true),
            persistence.load(),
        )
        val reloaded = AppSettingsRepository(persistence).settings.value
        assertTrue(reloaded.mediaCardLyrics)
        assertEquals(LyricsColorMode.FIXED, reloaded.lyricsColorMode)
    }

    @Test
    fun failedWriteKeepsPreviousStateAndOtherSettings() {
        val persistence = MemoryPersistence(
            AppSettings(themeMode = ThemeMode.LIGHT, mediaCardLyrics = true),
            failWrites = true,
        )
        val repository = AppSettingsRepository(persistence)
        val before = repository.settings.value

        try {
            repository.setMediaCardLyrics(false)
            fail("Expected the persistence failure")
        } catch (expected: IllegalStateException) {
            assertEquals("disk full", expected.message)
        }

        assertEquals(before, repository.settings.value)
        assertTrue(repository.settings.value.mediaCardLyrics)
        assertEquals(ThemeMode.LIGHT, repository.settings.value.themeMode)
    }

    private class MemoryPersistence(
        initial: AppSettings? = null,
        var failWrites: Boolean = false,
    ) : AppSettingsPersistence {
        private var stored: AppSettings? = initial

        override fun load(): AppSettings? = stored

        override fun save(settings: AppSettings) {
            if (failWrites) throw IllegalStateException("disk full")
            stored = settings
        }
    }
}
