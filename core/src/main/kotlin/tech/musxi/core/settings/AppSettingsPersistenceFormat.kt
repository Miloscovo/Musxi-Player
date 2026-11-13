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
    const val ACCENT = "settings.accent"
    const val MEDIA_CARD_LYRICS = "settings.media-card-lyrics"
    const val LOCK_SCREEN_LYRICS_MODE = "settings.lockscreen-lyrics-mode"

    /**
     * Local artwork policy, stored as the mode name ("AUTO" / "OFF").
     *
     * Unlike [ACCENT], whose names are opaque to core and preserved verbatim, this key holds a
     * closed set: any missing, unknown, or malformed value decodes to "AUTO" so a corrupted
     * entry can never permanently hide the user's local artwork.
     */
    const val LOCAL_ARTWORK_MODE = "settings.local-artwork-mode"

    /**
     * Recommendation master switch. Missing or malformed values decode to the default (enabled)
     * so an unreadable entry never silently hides the user's recommendation surfaces.
     */
    const val RECOMMENDATIONS_ENABLED = "settings.recommendations-enabled"

    val keys: Set<String> = setOf(
        THEME_MODE,
        AUTO_SWITCH_SOURCE,
        RELAX_SOURCE_DURATION,
        TRANSPARENCY_ENABLED,
        DEFAULT_QUALITY,
        LYRICS_FONT_SIZE,
        LYRICS_COLOR_MODE,
        LYRICS_TRANSLATION_PREFERENCE,
        ACCENT,
        MEDIA_CARD_LYRICS,
        LOCK_SCREEN_LYRICS_MODE,
        LOCAL_ARTWORK_MODE,
        RECOMMENDATIONS_ENABLED,
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
        ACCENT to settings.accent,
        MEDIA_CARD_LYRICS to (settings.effectiveLockScreenLyricsMode == LockScreenLyricsMode.CARD).toString(),
        LOCK_SCREEN_LYRICS_MODE to settings.effectiveLockScreenLyricsMode.name,
        LOCAL_ARTWORK_MODE to settings.localArtworkMode,
        RECOMMENDATIONS_ENABLED to settings.recommendationsEnabled.toString(),
    )

    fun decode(
        values: Map<String, String>,
        legacyDefaultQuality: String? = null,
    ): AppSettings {
        val defaults = AppSettings()
        val mode = if (LOCK_SCREEN_LYRICS_MODE in values)
            enumValue(values[LOCK_SCREEN_LYRICS_MODE], LockScreenLyricsMode.entries, LockScreenLyricsMode.OFF)
        else if (booleanValue(values[MEDIA_CARD_LYRICS], false)) LockScreenLyricsMode.CARD else LockScreenLyricsMode.OFF
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
            // Names are opaque to core: only an absent key falls back.
            accent = values[ACCENT] ?: defaults.accent,
            mediaCardLyrics = mode == LockScreenLyricsMode.CARD,
            lockScreenLyricsMode = mode.takeIf { it == LockScreenLyricsMode.PAGE },
            // Unlike accent, the accepted values are a closed set, so anything unexpected
            // reads as AUTO: a corrupted key must never silently hide local artwork forever.
            localArtworkMode = localArtworkModeValue(values[LOCAL_ARTWORK_MODE]),
            recommendationsEnabled = booleanValue(
                values[RECOMMENDATIONS_ENABLED],
                defaults.recommendationsEnabled,
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

    private fun localArtworkModeValue(value: String?): String =
        AppSettings.LOCAL_ARTWORK_MODES.firstOrNull {
            it.equals(value?.trim(), ignoreCase = true)
        } ?: AppSettings.LOCAL_ARTWORK_MODE_AUTO

    private fun <T : Enum<T>> enumValue(value: String?, entries: List<T>, fallback: T): T =
        entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: fallback
}
