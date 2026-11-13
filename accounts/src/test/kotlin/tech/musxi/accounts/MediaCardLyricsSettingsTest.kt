package tech.musxi.accounts

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
class MediaCardLyricsSettingsTest {
    private lateinit var context: Context

    @Before
    fun clearPreferences() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(LEGACY, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun missingKeyKeepsLegacySnapshotDisabled() {
        preferences().edit()
            .putString(AppSettingsPersistenceFormat.THEME_MODE, ThemeMode.DARK.name)
            .commit()

        val settings = newRepository().settings.value

        assertFalse(settings.mediaCardLyrics)
        assertEquals(ThemeMode.DARK, settings.themeMode)
    }

    @Test
    fun flagIsStoredUnderItsOwnKeyAndSurvivesRecreation() {
        val repository = newRepository()

        repository.setLyricsFontSize(LyricsFontSize.LARGE)
        repository.setMediaCardLyrics(true)

        assertEquals(
            "true",
            preferences().getString(AppSettingsPersistenceFormat.MEDIA_CARD_LYRICS, null),
        )
        val reloaded = newRepository().settings.value
        assertTrue(reloaded.mediaCardLyrics)
        assertEquals(LyricsFontSize.LARGE, reloaded.lyricsFontSize)
        assertEquals(ThemeMode.SYSTEM, reloaded.themeMode)
    }

    @Test
    fun malformedStoredValueReadsAsDisabledWithoutTouchingOtherKeys() {
        preferences().edit()
            .putString(AppSettingsPersistenceFormat.MEDIA_CARD_LYRICS, "enabled")
            .putString(AppSettingsPersistenceFormat.ACCENT, "TEAL")
            .commit()

        val settings = newRepository().settings.value

        assertFalse(settings.mediaCardLyrics)
        assertEquals("TEAL", settings.accent)
    }

    @Test
    fun turningTheFlagOffPersistsFalseAndKeepsOtherSettings() {
        val repository = newRepository()
        repository.setLyricsFontSize(LyricsFontSize.LARGE)
        repository.setMediaCardLyrics(true)

        val updated = repository.setMediaCardLyrics(false)

        assertEquals(
            AppSettings(lyricsFontSize = LyricsFontSize.LARGE),
            updated,
        )
        assertEquals(
            "false",
            preferences().getString(AppSettingsPersistenceFormat.MEDIA_CARD_LYRICS, null),
        )
        assertEquals(
            AppSettings(lyricsFontSize = LyricsFontSize.LARGE),
            newRepository().settings.value,
        )
    }

    private fun preferences() = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)

    private fun newRepository() = AppSettingsRepository(
        context,
        settingsPreferencesName = SETTINGS,
        legacyPreferencesName = LEGACY,
    )

    private companion object {
        const val SETTINGS = "musxi-settings-media-card-test"
        const val LEGACY = "musxi-legacy-media-card-test"
    }
}
