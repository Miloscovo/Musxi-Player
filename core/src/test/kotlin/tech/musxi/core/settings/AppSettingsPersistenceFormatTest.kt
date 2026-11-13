package tech.musxi.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.musxi.core.playback.PreferredAudioQuality

class AppSettingsPersistenceFormatTest {
    @Test
    fun defaultsMatchProductPolicy() {
        val settings = AppSettingsPersistenceFormat.decode(emptyMap())

        assertEquals(AppSettings(), settings)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertEquals(true, settings.autoSwitchSource)
        assertEquals(false, settings.relaxSourceDuration)
        assertEquals(true, settings.transparencyEnabled)
        assertEquals(PreferredAudioQuality.STANDARD, settings.defaultQuality)
        assertEquals(LyricsFontSize.MEDIUM, settings.lyricsFontSize)
        assertEquals(LyricsColorMode.COVER, settings.lyricsColorMode)
        assertEquals(LyricsTranslationPreference.AUTO, settings.lyricsTranslationPreference)
        assertEquals("MIST", settings.accent)
    }

    @Test
    fun completeSnapshotRoundTrips() {
        val expected = AppSettings(
            themeMode = ThemeMode.DARK,
            autoSwitchSource = false,
            relaxSourceDuration = true,
            transparencyEnabled = false,
            defaultQuality = PreferredAudioQuality.LOSSLESS,
            lyricsFontSize = LyricsFontSize.LARGE,
            lyricsColorMode = LyricsColorMode.FIXED,
            lyricsTranslationPreference = LyricsTranslationPreference.HIDE,
            accent = "TEAL",
        )

        val encoded = AppSettingsPersistenceFormat.encode(expected)

        assertEquals(expected, AppSettingsPersistenceFormat.decode(encoded))
        assertEquals(AppSettingsPersistenceFormat.keys, encoded.keys)
    }

    @Test
    fun missingAccentKeyFallsBackToDefault() {
        val legacy = AppSettingsPersistenceFormat.encode(AppSettings()) -
            AppSettingsPersistenceFormat.ACCENT

        val decoded = AppSettingsPersistenceFormat.decode(legacy)

        assertEquals("MIST", decoded.accent)
        assertEquals(AppSettings(), decoded)
    }

    @Test
    fun unknownAccentNameIsPreservedVerbatim() {
        val decoded = AppSettingsPersistenceFormat.decode(
            mapOf(AppSettingsPersistenceFormat.ACCENT to "VIOLET_2027"),
        )

        assertEquals("VIOLET_2027", decoded.accent)
        val reEncoded = AppSettingsPersistenceFormat.encode(decoded)
        assertEquals("VIOLET_2027", reEncoded[AppSettingsPersistenceFormat.ACCENT])
    }

    @Test
    fun malformedFieldsFallBackIndependentlyAndLegacyQualityIsReadable() {
        val decoded = AppSettingsPersistenceFormat.decode(
            values = mapOf(
                AppSettingsPersistenceFormat.THEME_MODE to "sepia",
                AppSettingsPersistenceFormat.AUTO_SWITCH_SOURCE to "not-a-boolean",
                AppSettingsPersistenceFormat.RELAX_SOURCE_DURATION to "TRUE",
                AppSettingsPersistenceFormat.TRANSPARENCY_ENABLED to "false",
                AppSettingsPersistenceFormat.LYRICS_FONT_SIZE to "huge",
                AppSettingsPersistenceFormat.DEFAULT_QUALITY to "unknown",
            ),
            legacyDefaultQuality = "best",
        )

        assertEquals(ThemeMode.SYSTEM, decoded.themeMode)
        assertEquals(true, decoded.autoSwitchSource)
        assertEquals(true, decoded.relaxSourceDuration)
        assertEquals(false, decoded.transparencyEnabled)
        assertEquals(PreferredAudioQuality.BEST, decoded.defaultQuality)
        assertEquals(LyricsFontSize.MEDIUM, decoded.lyricsFontSize)
        assertEquals("MIST", decoded.accent)
    }
}
