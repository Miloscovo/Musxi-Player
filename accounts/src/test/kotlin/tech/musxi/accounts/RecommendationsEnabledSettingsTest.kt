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
class RecommendationsEnabledSettingsTest {
    private lateinit var context: Context
    private val notifications = mutableListOf<Boolean>()

    @Before
    fun clearPreferences() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(LEGACY, Context.MODE_PRIVATE).edit().clear().commit()
        notifications.clear()
    }

    @Test
    fun missingKeyKeepsRecommendationsEnabled() {
        preferences().edit()
            .putString(AppSettingsPersistenceFormat.THEME_MODE, ThemeMode.DARK.name)
            .commit()

        val settings = newRepository().settings.value

        assertTrue(settings.recommendationsEnabled)
        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertEquals(emptyList<Boolean>(), notifications)
    }

    @Test
    fun malformedStoredValueReadsAsEnabled() {
        preferences().edit()
            .putString(AppSettingsPersistenceFormat.RECOMMENDATIONS_ENABLED, "off")
            .commit()

        assertTrue(newRepository().settings.value.recommendationsEnabled)
    }

    @Test
    fun disablingPersistsFalseAndSurvivesRecreation() {
        val repository = newRepository()
        repository.setLyricsFontSize(LyricsFontSize.LARGE)

        val updated = repository.setRecommendationsEnabled(false)

        assertFalse(updated.recommendationsEnabled)
        assertEquals(
            "false",
            preferences().getString(AppSettingsPersistenceFormat.RECOMMENDATIONS_ENABLED, null),
        )
        assertEquals(listOf(false), notifications)

        val reloaded = newRepository().settings.value
        assertFalse(reloaded.recommendationsEnabled)
        assertEquals(LyricsFontSize.LARGE, reloaded.lyricsFontSize)
    }

    @Test
    fun genericUpdateOnTheSwitchAlsoNotifies() {
        val repository = newRepository()

        repository.update { it.copy(recommendationsEnabled = false) }
        repository.update { it.copy(themeMode = ThemeMode.LIGHT) }

        assertEquals(listOf(false), notifications)
        assertEquals(
            AppSettings(themeMode = ThemeMode.LIGHT, recommendationsEnabled = false),
            newRepository().settings.value,
        )
    }

    @Test
    fun idempotentAndUnrelatedWritesDoNotNotify() {
        val repository = newRepository()
        repository.setRecommendationsEnabled(false)
        notifications.clear()

        repository.setRecommendationsEnabled(false)
        repository.setThemeMode(ThemeMode.DARK)
        repository.update { it.copy(autoSwitchSource = false) }

        assertEquals(emptyList<Boolean>(), notifications)
        assertFalse(repository.settings.value.recommendationsEnabled)
    }

    @Test
    fun fastOffOnNotifiesEveryTransitionSynchronously() {
        val observed = mutableListOf<Pair<Boolean, Boolean>>()
        var callbacks = 0

        val busy = AppSettingsRepository(
            context,
            settingsPreferencesName = SETTINGS,
            legacyPreferencesName = LEGACY,
        ) { enabled ->
            callbacks++
            // The stored state is already committed when the callback runs.
            val persisted = preferences()
                .getString(AppSettingsPersistenceFormat.RECOMMENDATIONS_ENABLED, null)
            observed += enabled to (persisted == enabled.toString())
        }

        busy.setRecommendationsEnabled(false)
        busy.setRecommendationsEnabled(true)

        assertEquals(2, callbacks)
        assertEquals(listOf(false to true, true to true), observed)
        assertTrue(busy.settings.value.recommendationsEnabled)
        assertTrue(newRepository().settings.value.recommendationsEnabled)
        assertEquals(emptyList<Boolean>(), notifications)
    }

    private fun preferences() = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)

    private fun newRepository() = AppSettingsRepository(
        context,
        settingsPreferencesName = SETTINGS,
        legacyPreferencesName = LEGACY,
        onRecommendationsEnabledChanged = { notifications += it },
    )

    private companion object {
        const val SETTINGS = "musxi-settings-recommendations-test"
        const val LEGACY = "musxi-legacy-recommendations-test"
    }
}
