package tech.musxi.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tech.musxi.core.playback.PreferredAudioQuality

class RecommendationsEnabledSettingsTest {
    @Test
    fun defaultsToEnabled() {
        assertTrue(AppSettings().recommendationsEnabled)
        assertTrue(AppSettingsPersistenceFormat.decode(emptyMap()).recommendationsEnabled)
    }

    @Test
    fun olderSnapshotsWithoutTheKeyStayEnabledAndKeepOtherValues() {
        val legacyValues = AppSettingsPersistenceFormat.encode(
            AppSettings(
                themeMode = ThemeMode.DARK,
                lyricsFontSize = LyricsFontSize.LARGE,
                accent = "TEAL",
                mediaCardLyrics = true,
            ),
        ) - AppSettingsPersistenceFormat.RECOMMENDATIONS_ENABLED

        val decoded = AppSettingsPersistenceFormat.decode(legacyValues)

        assertTrue(decoded.recommendationsEnabled)
        assertEquals(ThemeMode.DARK, decoded.themeMode)
        assertEquals(LyricsFontSize.LARGE, decoded.lyricsFontSize)
        assertEquals("TEAL", decoded.accent)
        assertTrue(decoded.mediaCardLyrics)
    }

    @Test
    fun malformedValueFallsBackToEnabled() {
        listOf("", "  ", "off", "disabled").forEach { stored ->
            val decoded = AppSettingsPersistenceFormat.decode(
                mapOf(AppSettingsPersistenceFormat.RECOMMENDATIONS_ENABLED to stored),
            )

            assertTrue("Unexpected value honored: $stored", decoded.recommendationsEnabled)
        }
    }

    @Test
    fun completeSnapshotRoundTripsBothValues() {
        listOf(true, false).forEach { enabled ->
            val expected = AppSettings(
                defaultQuality = PreferredAudioQuality.HIGH,
                mediaCardLyrics = true,
                recommendationsEnabled = enabled,
            )

            val encoded = AppSettingsPersistenceFormat.encode(expected)

            assertEquals(
                enabled.toString(),
                encoded[AppSettingsPersistenceFormat.RECOMMENDATIONS_ENABLED],
            )
            assertEquals(AppSettingsPersistenceFormat.keys, encoded.keys)
            assertEquals(expected, AppSettingsPersistenceFormat.decode(encoded))
        }
    }

    @Test
    fun disablingPersistsFalseAndSurvivesRepositoryRecreation() {
        val persistence = MemoryPersistence()
        val repository = AppSettingsRepository(persistence)
        repository.setLyricsFontSize(LyricsFontSize.LARGE)

        val updated = repository.setRecommendationsEnabled(false)

        assertFalse(updated.recommendationsEnabled)
        assertEquals(LyricsFontSize.LARGE, updated.lyricsFontSize)
        assertFalse(persistence.load()!!.recommendationsEnabled)

        val reloaded = AppSettingsRepository(persistence).settings.value
        assertFalse(reloaded.recommendationsEnabled)
        assertEquals(LyricsFontSize.LARGE, reloaded.lyricsFontSize)
    }

    @Test
    fun genericUpdateCanToggleTheSwitchAndPersists() {
        val persistence = MemoryPersistence()
        val repository = AppSettingsRepository(persistence)

        val updated = repository.update { it.copy(recommendationsEnabled = false) }

        assertFalse(updated.recommendationsEnabled)
        assertFalse(persistence.load()!!.recommendationsEnabled)
        assertEquals(1, persistence.saves)
    }

    @Test
    fun repeatingTheSameValueDoesNotWriteAgain() {
        val persistence = MemoryPersistence()
        val repository = AppSettingsRepository(persistence)

        repository.setRecommendationsEnabled(false)
        val savesAfterFirst = persistence.saves
        repository.setRecommendationsEnabled(false)

        assertEquals(savesAfterFirst, persistence.saves)
        assertFalse(repository.settings.value.recommendationsEnabled)
    }

    @Test
    fun failedWriteKeepsPreviousState() {
        val persistence = MemoryPersistence(AppSettings(recommendationsEnabled = false))
        val repository = AppSettingsRepository(persistence)
        val before = repository.settings.value
        persistence.failWrites = true

        try {
            repository.setRecommendationsEnabled(true)
            fail("Expected the persistence failure")
        } catch (expected: IllegalStateException) {
            assertEquals("disk full", expected.message)
        }

        assertEquals(before, repository.settings.value)
        assertFalse(repository.settings.value.recommendationsEnabled)
    }

    private class MemoryPersistence(
        initial: AppSettings? = null,
        var failWrites: Boolean = false,
    ) : AppSettingsPersistence {
        private var stored: AppSettings? = initial
        var saves: Int = 0
            private set

        override fun load(): AppSettings? = stored

        override fun save(settings: AppSettings) {
            if (failWrites) throw IllegalStateException("disk full")
            saves++
            stored = settings
        }
    }
}
