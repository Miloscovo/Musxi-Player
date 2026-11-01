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
    private val mutableSettings = MutableStateFlow(persistence.load() ?: AppSettings())

    val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings): AppSettings = synchronized(lock) {
        val current = mutableSettings.value
        val updated = transform(current)
        if (updated != current) {
            persistence.save(updated)
            mutableSettings.value = updated
        }
        mutableSettings.value
    }

    fun reload(): AppSettings = synchronized(lock) {
        val loaded = persistence.load() ?: AppSettings()
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

    /** Explicit compatibility entry for AccountRepository's former quality.default value. */
    fun migrateLegacyDefaultQuality(value: String?): PreferredAudioQuality = synchronized(lock) {
        val migrated = PreferredAudioQuality.fromStored(value)
        val updated = mutableSettings.value.copy(defaultQuality = migrated)
        // Migration is explicit, so persist even when loaded state already matches the value.
        persistence.save(updated)
        mutableSettings.value = updated
        return migrated
    }
}
