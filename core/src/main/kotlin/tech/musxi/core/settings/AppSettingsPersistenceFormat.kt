package tech.musxi.core.settings

import tech.musxi.core.playback.PreferredAudioQuality

/** Android-independent string representation used by preference-backed implementations. */
object AppSettingsPersistenceFormat {
    const val THEME_MODE = "settings.theme-mode"
    const val AUTO_SWITCH_SOURCE = "settings.auto-switch-source"
    const val RELAX_SOURCE_DURATION = "settings.relax-source-duration"
    const val TRANSPARENCY_ENABLED = "settings.transparency-enabled"
    const val DEFAULT_QUALITY = "settings.default-quality"
    const val LYRICS_FONT_SIZE = "settings.lyrics-font-size"
    const val LYRICS_COLOR_MODE = "settings.lyrics-color-mode"
    const val LYRICS_TRANSLATION_PREFERENCE = "settings.lyrics-translation-preference"

    val keys: Set<String> = setOf(
        THEME_MODE,
        AUTO_SWITCH_SOURCE,
        RELAX_SOURCE_DURATION,
        TRANSPARENCY_ENABLED,
        DEFAULT_QUALITY,
        LYRICS_FONT_SIZE,
        LYRICS_COLOR_MODE,
        LYRICS_TRANSLATION_PREFERENCE,
    )

    fun encode(settings: AppSettings): Map<String, String> = mapOf(
        THEME_MODE to settings.themeMode.name,
        AUTO_SWITCH_SOURCE to settings.autoSwitchSource.toString(),
        RELAX_SOURCE_DURATION to settings.relaxSourceDuration.toString(),
        TRANSPARENCY_ENABLED to settings.transparencyEnabled.toString(),
        DEFAULT_QUALITY to settings.defaultQuality.name,
        LYRICS_FONT_SIZE to settings.lyricsFontSize.name,
        LYRICS_COLOR_MODE to settings.lyricsColorMode.name,
        LYRICS_TRANSLATION_PREFERENCE to settings.lyricsTranslationPreference.name,
    )

    fun decode(
        values: Map<String, String>,
        legacyDefaultQuality: String? = null,
    ): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            themeMode = enumValue(values[THEME_MODE], ThemeMode.entries, defaults.themeMode),
            autoSwitchSource = booleanValue(values[AUTO_SWITCH_SOURCE], defaults.autoSwitchSource),
            relaxSourceDuration = booleanValue(
                values[RELAX_SOURCE_DURATION],
                defaults.relaxSourceDuration,
            ),
            transparencyEnabled = booleanValue(
                values[TRANSPARENCY_ENABLED],
                defaults.transparencyEnabled,
            ),
            defaultQuality = qualityValue(values[DEFAULT_QUALITY], legacyDefaultQuality),
            lyricsFontSize = enumValue(
                values[LYRICS_FONT_SIZE],
                LyricsFontSize.entries,
                defaults.lyricsFontSize,
            ),
            lyricsColorMode = enumValue(
                values[LYRICS_COLOR_MODE],
                LyricsColorMode.entries,
                defaults.lyricsColorMode,
            ),
            lyricsTranslationPreference = enumValue(
                values[LYRICS_TRANSLATION_PREFERENCE],
                LyricsTranslationPreference.entries,
                defaults.lyricsTranslationPreference,
            ),
        )
    }

    private fun booleanValue(value: String?, fallback: Boolean): Boolean = when {
        value?.trim()?.equals("true", ignoreCase = true) == true -> true
        value?.trim()?.equals("false", ignoreCase = true) == true -> false
        else -> fallback
    }

    private fun qualityValue(
        value: String?,
        legacyValue: String?,
    ): PreferredAudioQuality =
        PreferredAudioQuality.entries.firstOrNull {
            it.name.equals(value?.trim(), ignoreCase = true)
        } ?: PreferredAudioQuality.fromStored(legacyValue)

    private fun <T : Enum<T>> enumValue(value: String?, entries: List<T>, fallback: T): T =
        entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: fallback
}
