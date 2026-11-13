package tech.musxi.accounts

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.StateFlow
import tech.musxi.core.playback.PreferredAudioQuality
import tech.musxi.core.settings.AppSettings
import tech.musxi.core.settings.AppSettingsPersistence
import tech.musxi.core.settings.AppSettingsPersistenceFormat
import tech.musxi.core.settings.AppSettingsRepository as CoreAppSettingsRepository
import tech.musxi.core.settings.LyricsColorMode
import tech.musxi.core.settings.LyricsFontSize
import tech.musxi.core.settings.LyricsTranslationPreference
import tech.musxi.core.settings.ThemeMode

/**
 * SharedPreferences adapter for non-credential app settings.
 *
 * Writes use commit() so callers receive storage failures and StateFlow is not advanced
 * when persistence fails. Construct this before the UI starts collecting [settings].
 */
class AppSettingsRepository(
    context: Context,
    settingsPreferencesName: String = "musxi-settings-v1",
    legacyPreferencesName: String = "playback-preferences-v1",
    /**
     * Synchronous notification for the recommendation master switch. Called inside
     * [commandLock] only after the write was persisted and only when the value changed, so
     * listeners see every transition (including fast off/on) without relying on Flow delivery.
     */
    private val onRecommendationsEnabledChanged: (Boolean) -> Unit = {},
) {
    private val commandLock = Any()
    private val preferences = context.applicationContext.getSharedPreferences(
        settingsPreferencesName,
        Context.MODE_PRIVATE,
    )
    private val legacyPreferences = context.applicationContext.getSharedPreferences(
        legacyPreferencesName,
        Context.MODE_PRIVATE,
    )
    private val core = CoreAppSettingsRepository(
        SharedPreferencesPersistence(preferences, legacyPreferences),
    )

    val settings: StateFlow<AppSettings> = core.settings

    fun update(transform: (AppSettings) -> AppSettings): AppSettings = synchronized(commandLock) {
        val previous = core.settings.value.recommendationsEnabled
        val updated = core.update(transform)
        notifyRecommendationsEnabledChange(previous, updated)
        updated
    }

    fun setThemeMode(value: ThemeMode): AppSettings =
        synchronized(commandLock) { core.setThemeMode(value) }

    fun setAutoSwitchSource(value: Boolean): AppSettings =
        synchronized(commandLock) { core.setAutoSwitchSource(value) }

    fun setRelaxSourceDuration(value: Boolean): AppSettings =
        synchronized(commandLock) { core.setRelaxSourceDuration(value) }

    fun setTransparencyEnabled(value: Boolean): AppSettings =
        synchronized(commandLock) { core.setTransparencyEnabled(value) }

    fun setDefaultQuality(value: PreferredAudioQuality): AppSettings =
        synchronized(commandLock) { core.setDefaultQuality(value) }

    fun setLyricsFontSize(value: LyricsFontSize): AppSettings =
        synchronized(commandLock) { core.setLyricsFontSize(value) }

    fun setLyricsColorMode(value: LyricsColorMode): AppSettings =
        synchronized(commandLock) { core.setLyricsColorMode(value) }

    fun setLyricsTranslationPreference(value: LyricsTranslationPreference): AppSettings =
        synchronized(commandLock) { core.setLyricsTranslationPreference(value) }

    /** Stores the accent name; see [CoreAppSettingsRepository.setAccent] for the accepted shape. */
    fun setAccent(value: String): AppSettings =
        synchronized(commandLock) { core.setAccent(value) }

    fun setMediaCardLyrics(value: Boolean): AppSettings =
        synchronized(commandLock) { core.setMediaCardLyrics(value) }

    fun setLockScreenLyricsMode(value: tech.musxi.core.settings.LockScreenLyricsMode): AppSettings =
        synchronized(commandLock) { core.setLockScreenLyricsMode(value) }

    /** Stores the local artwork policy; see [CoreAppSettingsRepository.setLocalArtworkMode]. */
    fun setLocalArtworkMode(value: String): AppSettings =
        synchronized(commandLock) { core.setLocalArtworkMode(value) }

    fun setRecommendationsEnabled(value: Boolean): AppSettings =
        synchronized(commandLock) {
            val previous = core.settings.value.recommendationsEnabled
            val updated = core.setRecommendationsEnabled(value)
            notifyRecommendationsEnabledChange(previous, updated)
            updated
        }

    /**
     * Copies AccountRepository's former quality.default into the unified snapshot once.
     * The current state already falls back to this value before migration is invoked.
     */
    fun migrateLegacyDefaultQuality(): PreferredAudioQuality = synchronized(commandLock) {
        val existing = preferences.getString(AppSettingsPersistenceFormat.DEFAULT_QUALITY, null)
        val validExisting = PreferredAudioQuality.entries.firstOrNull {
            it.name.equals(existing?.trim(), ignoreCase = true)
        }
        if (validExisting != null) return@synchronized validExisting
        core.migrateLegacyDefaultQuality(
            legacyPreferences.getString(LEGACY_DEFAULT_QUALITY, null),
        )
    }

    private class SharedPreferencesPersistence(
        private val preferences: SharedPreferences,
        private val legacyPreferences: SharedPreferences,
    ) : AppSettingsPersistence {
        override fun load(): AppSettings? {
            val values = preferences.all.mapNotNull { (key, value) ->
                when {
                    key !in AppSettingsPersistenceFormat.keys -> null
                    value is String -> key to value
                    // A present but malformed new mode must not revive the legacy card flag.
                    key == AppSettingsPersistenceFormat.LOCK_SCREEN_LYRICS_MODE -> key to ""
                    else -> null
                }
            }.toMap()
            val legacyQuality = legacyPreferences.getString(LEGACY_DEFAULT_QUALITY, null)
            if (values.isEmpty() && legacyQuality == null) return null
            return AppSettingsPersistenceFormat.decode(values, legacyQuality)
        }

        override fun save(settings: AppSettings) {
            val values = AppSettingsPersistenceFormat.encode(settings)
            val editor = preferences.edit()
            values.forEach { (key, value) -> editor.putString(key, value) }
            check(editor.commit()) { "Unable to persist app settings" }
        }
    }

    private companion object {
        const val LEGACY_DEFAULT_QUALITY = "quality.default"
    }

    private fun notifyRecommendationsEnabledChange(previous: Boolean, updated: AppSettings) {
        if (updated.recommendationsEnabled != previous) {
            onRecommendationsEnabledChanged(updated.recommendationsEnabled)
        }
    }
}
