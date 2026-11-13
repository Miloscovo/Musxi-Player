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
class LocalArtworkModeSettingsTest {
    private lateinit var context: Context

    @Before
    fun clearPreferences() {
        context = RuntimeEnvironment.getApplication()
        preferences().edit().clear().commit()
        context.getSharedPreferences(LEGACY, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun missingKeyReadsAsAutoAndKeepsLegacySnapshot() {
        preferences().edit()
            .putString(AppSettingsPersistenceFormat.THEME_MODE, ThemeMode.DARK.name)
            .commit()

        val settings = newRepository().settings.value

        assertEquals(AppSettings.LOCAL_ARTWORK_MODE_AUTO, settings.localArtworkMode)
        assertEquals(ThemeMode.DARK, settings.themeMode)
    }

    @Test
    fun bothModesAreStoredUnderTheirOwnKeyAndSurviveRecreation() {
        val repository = newRepository()
        repository.setLyricsFontSize(LyricsFontSize.LARGE)

        repository.setLocalArtworkMode(AppSettings.LOCAL_ARTWORK_MODE_OFF)
        assertEquals(
            AppSettings.LOCAL_ARTWORK_MODE_OFF,
            preferences().getString(AppSettingsPersistenceFormat.LOCAL_ARTWORK_MODE, null),
        )
        assertEquals(
            AppSettings(
                lyricsFontSize = LyricsFontSize.LARGE,
                localArtworkMode = AppSettings.LOCAL_ARTWORK_MODE_OFF,
            ),
            newRepository().settings.value,
        )

        repository.setLocalArtworkMode(AppSettings.LOCAL_ARTWORK_MODE_AUTO)
        assertEquals(
            AppSettings.LOCAL_ARTWORK_MODE_AUTO,
            preferences().getString(AppSettingsPersistenceFormat.LOCAL_ARTWORK_MODE, null),
        )
        assertEquals(
            AppSettings(lyricsFontSize = LyricsFontSize.LARGE),
            newRepository().settings.value,
        )
    }

    @Test
    fun malformedStoredValueReadsAsAutoWithoutTouchingOtherKeys() {
        preferences().edit()
            .putString(AppSettingsPersistenceFormat.LOCAL_ARTWORK_MODE, "NEVER")
            .putString(AppSettingsPersistenceFormat.ACCENT, "TEAL")
            .commit()

        val settings = newRepository().settings.value

        assertEquals(AppSettings.LOCAL_ARTWORK_MODE_AUTO, settings.localArtworkMode)
        assertEquals("TEAL", settings.accent)
    }

    @Test
    fun invalidModeIsRejectedAndNothingIsWritten() {
        val repository = newRepository()

        assertThrows(IllegalArgumentException::class.java) {
            repository.setLocalArtworkMode("auto")
        }
        assertThrows(IllegalArgumentException::class.java) { repository.setLocalArtworkMode("") }
        assertThrows(IllegalArgumentException::class.java) {
            repository.setLocalArtworkMode("ONLY")
        }

        assertEquals(AppSettings(), repository.settings.value)
        assertNull(
            preferences().getString(AppSettingsPersistenceFormat.LOCAL_ARTWORK_MODE, null),
        )
    }

    private fun preferences() = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)

    private fun newRepository() = AppSettingsRepository(
        context,
        settingsPreferencesName = SETTINGS,
        legacyPreferencesName = LEGACY,
    )

    private companion object {
        const val SETTINGS = "musxi-settings-local-artwork-test"
        const val LEGACY = "musxi-legacy-local-artwork-test"
    }
}
