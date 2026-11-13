package tech.musxi.accounts

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.musxi.core.settings.AppSettings
import tech.musxi.core.settings.AppSettingsPersistenceFormat
import tech.musxi.core.settings.LyricsFontSize
import tech.musxi.core.settings.ThemeMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AppSettingsAccentTest {
    private lateinit var context: Context

    @Before
    fun clearPreferences() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(LEGACY, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun accentIsStoredUnderItsOwnKeyAndSurvivesRecreation() {
        val repository = newRepository()

        repository.setLyricsFontSize(LyricsFontSize.LARGE)
        repository.setAccent("TEAL")

        val preferences = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        assertEquals("TEAL", preferences.getString(AppSettingsPersistenceFormat.ACCENT, null))
        val reloaded = newRepository().settings.value
        assertEquals("TEAL", reloaded.accent)
        assertEquals(LyricsFontSize.LARGE, reloaded.lyricsFontSize)
        assertEquals(ThemeMode.SYSTEM, reloaded.themeMode)
    }

    @Test
    fun missingAccentKeyKeepsLegacySnapshotReadable() {
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .putString(AppSettingsPersistenceFormat.THEME_MODE, ThemeMode.DARK.name)
            .commit()

        val settings = newRepository().settings.value

        assertEquals("MIST", settings.accent)
        assertEquals(ThemeMode.DARK, settings.themeMode)
    }

    @Test
    fun unknownAccentNameIsLoadedVerbatim() {
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .putString(AppSettingsPersistenceFormat.ACCENT, "FUTURE_COLOR")
            .commit()

        assertEquals("FUTURE_COLOR", newRepository().settings.value.accent)
    }

    @Test
    fun invalidAccentNameIsRejectedAndNothingIsWritten() {
        val repository = newRepository()

        assertThrows(IllegalArgumentException::class.java) { repository.setAccent("mist") }
        assertThrows(IllegalArgumentException::class.java) { repository.setAccent("") }
        assertThrows(IllegalArgumentException::class.java) {
            repository.setAccent("A".repeat(33))
        }

        assertEquals(AppSettings(), repository.settings.value)
        val preferences = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        assertNull(preferences.getString(AppSettingsPersistenceFormat.ACCENT, null))
    }

    private fun newRepository() = AppSettingsRepository(
        context,
        settingsPreferencesName = SETTINGS,
        legacyPreferencesName = LEGACY,
    )

    private companion object {
        const val SETTINGS = "musxi-settings-accent-test"
        const val LEGACY = "musxi-legacy-accent-test"
    }
}
