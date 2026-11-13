package tech.musxi.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import tech.musxi.core.playback.PreferredAudioQuality

class LocalArtworkModeSettingsTest {
    @Test
    fun defaultsToAuto() {
        assertEquals(
            AppSettings.LOCAL_ARTWORK_MODE_AUTO,
            AppSettings().localArtworkMode,
        )
        assertEquals(
            AppSettings.LOCAL_ARTWORK_MODE_AUTO,
            AppSettingsPersistenceFormat.decode(emptyMap()).localArtworkMode,
        )
    }

    @Test
    fun bothModesRoundTripThroughTheStoredKey() {
        listOf(
            AppSettings.LOCAL_ARTWORK_MODE_AUTO,
            AppSettings.LOCAL_ARTWORK_MODE_OFF,
        ).forEach { mode ->
            val expected = AppSettings(
                themeMode = ThemeMode.DARK,
                lyricsFontSize = LyricsFontSize.LARGE,
                defaultQuality = PreferredAudioQuality.HIGH,
                accent = "TEAL",
                mediaCardLyrics = true,
                localArtworkMode = mode,
            )

            val encoded = AppSettingsPersistenceFormat.encode(expected)

            assertEquals(mode, encoded[AppSettingsPersistenceFormat.LOCAL_ARTWORK_MODE])
            assertEquals(AppSettingsPersistenceFormat.keys, encoded.keys)
            assertEquals(expected, AppSettingsPersistenceFormat.decode(encoded))
        }
    }

    @Test
    fun unknownOrMalformedStoredValueFallsBackToAuto() {
        listOf("", "  ", "auto?", "ENABLED", "OFFLINE", "AUTO/OFF").forEach { stored ->
            val decoded = AppSettingsPersistenceFormat.decode(
                mapOf(AppSettingsPersistenceFormat.LOCAL_ARTWORK_MODE to stored),
            )

            assertEquals(
                "Unexpected value kept: $stored",
                AppSettings.LOCAL_ARTWORK_MODE_AUTO,
                decoded.localArtworkMode,
            )
        }
    }

    @Test
    fun missingKeyKeepsItAutoAndPreservesOtherFields() {
        val legacy = AppSettingsPersistenceFormat.encode(
            AppSettings(themeMode = ThemeMode.LIGHT, accent = "TEAL"),
        ) - AppSettingsPersistenceFormat.LOCAL_ARTWORK_MODE

        val decoded = AppSettingsPersistenceFormat.decode(legacy)

        assertEquals(AppSettings.LOCAL_ARTWORK_MODE_AUTO, decoded.localArtworkMode)
        assertEquals(ThemeMode.LIGHT, decoded.themeMode)
        assertEquals("TEAL", decoded.accent)
    }

    @Test
    fun commandPersistsBothModesAndSurvivesRepositoryRecreation() {
        val persistence = MemoryPersistence()
        val repository = AppSettingsRepository(persistence)
        repository.setLyricsColorMode(LyricsColorMode.FIXED)
        repository.setAccent("AMBER")

        assertEquals(
            AppSettings.LOCAL_ARTWORK_MODE_OFF,
            repository.setLocalArtworkMode(AppSettings.LOCAL_ARTWORK_MODE_OFF).localArtworkMode,
        )
        assertEquals(
            AppSettings(
                lyricsColorMode = LyricsColorMode.FIXED,
                accent = "AMBER",
                localArtworkMode = AppSettings.LOCAL_ARTWORK_MODE_OFF,
            ),
            persistence.load(),
        )

        val reloaded = AppSettingsRepository(persistence).settings.value
        assertEquals(AppSettings.LOCAL_ARTWORK_MODE_OFF, reloaded.localArtworkMode)
        assertEquals(LyricsColorMode.FIXED, reloaded.lyricsColorMode)
        assertEquals("AMBER", reloaded.accent)

        repository.setLocalArtworkMode(AppSettings.LOCAL_ARTWORK_MODE_AUTO)
        assertEquals(
            AppSettings.LOCAL_ARTWORK_MODE_AUTO,
            AppSettingsRepository(persistence).settings.value.localArtworkMode,
        )
    }

    @Test
    fun invalidModeIsRejectedWithoutWriting() {
        val persistence = MemoryPersistence()
        val repository = AppSettingsRepository(persistence)

        listOf("", "auto", "off", "REMOVE", "AUTO ", " OFF").forEach { invalid ->
            try {
                repository.setLocalArtworkMode(invalid)
                fail("Expected rejection for: $invalid")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }

        assertEquals(AppSettings(), repository.settings.value)
        assertEquals(
            AppSettings.LOCAL_ARTWORK_MODE_AUTO,
            repository.settings.value.localArtworkMode,
        )
        assertNull(persistence.saved)
    }

    @Test
    fun failedWriteKeepsPreviousStateAndOtherSettings() {
        val persistence = MemoryPersistence(
            AppSettings(
                themeMode = ThemeMode.LIGHT,
                localArtworkMode = AppSettings.LOCAL_ARTWORK_MODE_OFF,
            ),
            failWrites = true,
        )
        val repository = AppSettingsRepository(persistence)
        val before = repository.settings.value

        try {
            repository.setLocalArtworkMode(AppSettings.LOCAL_ARTWORK_MODE_AUTO)
            fail("Expected the persistence failure")
        } catch (expected: IllegalStateException) {
            assertEquals("disk full", expected.message)
        }

        assertEquals(before, repository.settings.value)
        assertEquals(AppSettings.LOCAL_ARTWORK_MODE_OFF, repository.settings.value.localArtworkMode)
        assertEquals(ThemeMode.LIGHT, repository.settings.value.themeMode)
    }

    private class MemoryPersistence(
        initial: AppSettings? = null,
        var failWrites: Boolean = false,
    ) : AppSettingsPersistence {
        var saved: AppSettings? = initial

        override fun load(): AppSettings? = saved

        override fun save(settings: AppSettings) {
            if (failWrites) throw IllegalStateException("disk full")
            saved = settings
        }
    }
}
