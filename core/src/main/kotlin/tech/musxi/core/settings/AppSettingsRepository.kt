package tech.musxi.core.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.musxi.core.playback.PreferredAudioQuality

/** Persists complete snapshots. Implementations may throw when storage rejects a write. */
interface AppSettingsPersistence {
    fun load(): AppSettings?

    fun save(settings: AppSettings)
}

/**
 * Android-independent settings store. Persistence and state publication are serialized,
 * and the published state changes only after the complete snapshot has been saved.
 */
class AppSettingsRepository(
    private val persistence: AppSettingsPersistence,
) {
    private val lock = Any()
    private val mutableSettings = MutableStateFlow((persistence.load() ?: AppSettings()).canonicalLockScreenLyrics())

    val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings): AppSettings = synchronized(lock) {
        val current = mutableSettings.value
        val requested = transform(current)
        // Old generic updates changing only the legacy flag still select CARD/OFF. An explicit
        // new mode wins when both fields are supplied in the same transformation.
        val compatible = if (requested.lockScreenLyricsMode == current.lockScreenLyricsMode &&
            requested.mediaCardLyrics != current.mediaCardLyrics)
            requested.copy(lockScreenLyricsMode = null) else requested
        val updated = compatible.canonicalLockScreenLyrics()
        if (updated != current) {
            persistence.save(updated)
            mutableSettings.value = updated
        }
        mutableSettings.value
    }

    fun reload(): AppSettings = synchronized(lock) {
        val loaded = (persistence.load() ?: AppSettings()).canonicalLockScreenLyrics()
        mutableSettings.value = loaded
        loaded
    }

    fun setThemeMode(value: ThemeMode): AppSettings = update { it.copy(themeMode = value) }

    fun setAutoSwitchSource(value: Boolean): AppSettings =
        update { it.copy(autoSwitchSource = value) }

    fun setRelaxSourceDuration(value: Boolean): AppSettings =
        update { it.copy(relaxSourceDuration = value) }

    fun setTransparencyEnabled(value: Boolean): AppSettings =
        update { it.copy(transparencyEnabled = value) }

    fun setDefaultQuality(value: PreferredAudioQuality): AppSettings =
        update { it.copy(defaultQuality = value) }

    fun setLyricsFontSize(value: LyricsFontSize): AppSettings =
        update { it.copy(lyricsFontSize = value) }

    fun setLyricsColorMode(value: LyricsColorMode): AppSettings =
        update { it.copy(lyricsColorMode = value) }

    fun setLyricsTranslationPreference(value: LyricsTranslationPreference): AppSettings =
        update { it.copy(lyricsTranslationPreference = value) }

    /**
     * Stores an accent by name. Core does not know the palette, so only the name shape is
     * validated here; unknown-but-well-formed names are persisted for the UI to interpret.
     */
    fun setAccent(value: String): AppSettings {
        require(ACCENT_NAME.matches(value)) { "Invalid accent name: $value" }
        return update { it.copy(accent = value) }
    }

    fun setMediaCardLyrics(value: Boolean): AppSettings =
        update { it.copy(mediaCardLyrics = value, lockScreenLyricsMode = null) }

    fun setLockScreenLyricsMode(value: LockScreenLyricsMode): AppSettings =
        update { it.copy(lockScreenLyricsMode = value, mediaCardLyrics = value == LockScreenLyricsMode.CARD) }

    /**
     * Stores the local artwork policy by name. Only the exact names in
     * [AppSettings.LOCAL_ARTWORK_MODES] are accepted; turning artwork off never deletes the
     * artwork already stored for imported items, and turning it back on does not extract
     * artwork for them (callers reuse the existing files or an explicit fill request).
     */
    fun setLocalArtworkMode(value: String): AppSettings {
        require(value in AppSettings.LOCAL_ARTWORK_MODES) {
            "Invalid local artwork mode: $value"
        }
        return update { it.copy(localArtworkMode = value) }
    }

    /** Master switch for recommendation surfaces; disabling only hides them. */
    fun setRecommendationsEnabled(value: Boolean): AppSettings =
        update { it.copy(recommendationsEnabled = value) }

    /** Explicit compatibility entry for AccountRepository's former quality.default value. */
    fun migrateLegacyDefaultQuality(value: String?): PreferredAudioQuality = synchronized(lock) {
        val migrated = PreferredAudioQuality.fromStored(value)
        val updated = mutableSettings.value.copy(defaultQuality = migrated)
        // Migration is explicit, so persist even when loaded state already matches the value.
        persistence.save(updated)
        mutableSettings.value = updated
        return migrated
    }

    private companion object {
        val ACCENT_NAME = Regex("[A-Z0-9_]{1,32}")
    }
}
