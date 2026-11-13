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

enum class LockScreenLyricsMode { OFF, CARD, PAGE }

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
    /**
     * Stored accent name, kept as text so core stays independent of the UI palette.
     * Unknown names are preserved; the UI is responsible for falling back to the default.
     */
    val accent: String = DEFAULT_ACCENT,
    /** Opt-in platform title lyrics; the original song title remains in the card's subtitle. */
    val mediaCardLyrics: Boolean = false,
    /**
     * Local artwork policy, stored by name like [accent]. Only [LOCAL_ARTWORK_MODE_AUTO] and
     * [LOCAL_ARTWORK_MODE_OFF] are valid; OFF hides artwork without deleting existing files.
     */
    val localArtworkMode: String = LOCAL_ARTWORK_MODE_AUTO,
    /** Master switch; the recommendation backend fences requests and erases its list caches. */
    val recommendationsEnabled: Boolean = true,
    /** Null retains source compatibility with old constructors and copy(mediaCardLyrics = ...). */
    val lockScreenLyricsMode: LockScreenLyricsMode? = null,
) {
    /** One authoritative effective mode. PAGE never enables the card-title publisher. */
    val effectiveLockScreenLyricsMode: LockScreenLyricsMode
        get() = lockScreenLyricsMode ?: if (mediaCardLyrics) LockScreenLyricsMode.CARD else LockScreenLyricsMode.OFF

    /** OFF/CARD keep the legacy data-class shape; PAGE has an explicit new value. */
    internal fun canonicalLockScreenLyrics(): AppSettings = when (effectiveLockScreenLyricsMode) {
        LockScreenLyricsMode.PAGE -> copy(mediaCardLyrics = false, lockScreenLyricsMode = LockScreenLyricsMode.PAGE)
        LockScreenLyricsMode.CARD -> copy(mediaCardLyrics = true, lockScreenLyricsMode = null)
        LockScreenLyricsMode.OFF -> copy(mediaCardLyrics = false, lockScreenLyricsMode = null)
    }

    companion object {
        const val DEFAULT_ACCENT = "MIST"
        const val LOCAL_ARTWORK_MODE_AUTO = "AUTO"
        const val LOCAL_ARTWORK_MODE_OFF = "OFF"

        /** The complete set of accepted local artwork modes. */
        val LOCAL_ARTWORK_MODES: Set<String> =
            setOf(LOCAL_ARTWORK_MODE_AUTO, LOCAL_ARTWORK_MODE_OFF)
    }
}
