package tech.musxi.core.settings

import tech.musxi.core.playback.PreferredAudioQuality

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class LyricsFontSize {
    SMALL,
    MEDIUM,
    LARGE,
}

enum class LyricsColorMode {
    COVER,
    FIXED,
}

enum class LyricsTranslationPreference {
    AUTO,
    SHOW,
    HIDE,
}

/**
 * Non-credential preferences shared by the app. Values represent user intent only;
 * account identities and platform credentials do not belong in this model.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val autoSwitchSource: Boolean = true,
    val relaxSourceDuration: Boolean = false,
    val transparencyEnabled: Boolean = true,
    val defaultQuality: PreferredAudioQuality = PreferredAudioQuality.STANDARD,
    val lyricsFontSize: LyricsFontSize = LyricsFontSize.MEDIUM,
    val lyricsColorMode: LyricsColorMode = LyricsColorMode.COVER,
    val lyricsTranslationPreference: LyricsTranslationPreference = LyricsTranslationPreference.AUTO,
)
