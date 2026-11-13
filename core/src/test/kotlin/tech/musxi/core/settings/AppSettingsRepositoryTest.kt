package tech.musxi.core.settings

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import tech.musxi.core.playback.PreferredAudioQuality

class AppSettingsRepositoryTest {
    @Test
    fun commandsPersistAndPublishCompleteSettings() {
        val persistence = MemoryPersistence()
        val repository = AppSettingsRepository(persistence)

        repository.setThemeMode(ThemeMode.DARK)
        repository.setRelaxSourceDuration(true)
        repository.setDefaultQuality(PreferredAudioQuality.HIGH)
        repository.setLyricsTranslationPreference(LyricsTranslationPreference.SHOW)

        val expected = AppSettings(
            themeMode = ThemeMode.DARK,
            relaxSourceDuration = true,
            defaultQuality = PreferredAudioQuality.HIGH,
            lyricsTranslationPreference = LyricsTranslationPreference.SHOW,
        )
        assertEquals(expected, repository.settings.value)
        assertEquals(expected, persistence.saved)
    }

    @Test
    fun writeFailureIsPropagatedAndDoesNotPublishUnpersistedState() {
        val persistence = MemoryPersistence(failWrites = true)
        val repository = AppSettingsRepository(persistence)
        val before = repository.settings.value

        try {
            repository.setThemeMode(ThemeMode.DARK)
            fail("Expected the persistence failure")
        } catch (expected: IllegalStateException) {
            assertEquals("disk full", expected.message)
        }

        assertSame(before, repository.settings.value)
    }

    @Test
    fun concurrentCommandsLeaveStateAndPersistedSnapshotAligned() {
        val persistence = MemoryPersistence()
        val repository = AppSettingsRepository(persistence)
        val workers = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val futures = mutableListOf<java.util.concurrent.Future<*>>()
        repeat(100) { index ->
            futures += workers.submit {
                start.await()
                when (index % 4) {
                    0 -> repository.setAutoSwitchSource(index % 8 == 0)
                    1 -> repository.setLyricsFontSize(
                        if (index % 2 == 0) LyricsFontSize.SMALL else LyricsFontSize.LARGE,
                    )
                    2 -> repository.setThemeMode(
                        if (index % 2 == 0) ThemeMode.LIGHT else ThemeMode.DARK,
                    )
                    else -> repository.setRelaxSourceDuration(index % 2 == 0)
                }
            }
        }
        start.countDown()
        workers.shutdown()
        check(workers.awaitTermination(10, TimeUnit.SECONDS))
        futures.forEach { it.get(1, TimeUnit.SECONDS) }

        assertEquals(repository.settings.value, persistence.saved)
    }

    @Test
    fun accentPersistsWithoutTouchingOtherSettings() {
        val persistence = MemoryPersistence()
        val repository = AppSettingsRepository(persistence)

        val updated = repository.setAccent("AMBER")

        assertEquals("AMBER", updated.accent)
        assertEquals(AppSettings(accent = "AMBER"), repository.settings.value)
        assertEquals(AppSettings(accent = "AMBER"), persistence.saved)
        assertEquals(ThemeMode.SYSTEM, updated.themeMode)
        assertEquals(true, updated.autoSwitchSource)
        assertEquals(false, updated.relaxSourceDuration)
        assertEquals(true, updated.transparencyEnabled)
        assertEquals(PreferredAudioQuality.STANDARD, updated.defaultQuality)
        assertEquals(LyricsFontSize.MEDIUM, updated.lyricsFontSize)
        assertEquals(LyricsColorMode.COVER, updated.lyricsColorMode)
        assertEquals(LyricsTranslationPreference.AUTO, updated.lyricsTranslationPreference)
    }

    @Test
    fun accentKeepsUnknownNamesAndReloadsThem() {
        val persistence = MemoryPersistence()
        val repository = AppSettingsRepository(persistence)

        repository.setAccent("LILAC")

        assertEquals("LILAC", AppSettingsRepository(persistence).settings.value.accent)
    }

    @Test
    fun invalidAccentNamesAreRejectedWithoutSaving() {
        val persistence = MemoryPersistence()
        val repository = AppSettingsRepository(persistence)

        listOf("", "mist", "MIST-2", "MIST 2", "MIST2!", "A".repeat(33)).forEach { invalid ->
            try {
                repository.setAccent(invalid)
                fail("Expected rejection for: $invalid")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }

        assertEquals("MIST", repository.settings.value.accent)
        assertNull(persistence.saved)
    }

    @Test
    fun accentAcceptsThirtyTwoCharsAndMixedAllowedCharacters() {
        val persistence = MemoryPersistence()
        val repository = AppSettingsRepository(persistence)

        val longest = "A".repeat(32)
        assertEquals(longest, repository.setAccent(longest).accent)
        assertEquals(longest, persistence.saved?.accent)

        assertEquals("V2_DARK_1", repository.setAccent("V2_DARK_1").accent)
        assertEquals("V2_DARK_1", persistence.saved?.accent)
    }

    @Test
    fun legacyQualityMigrationPersistsRecognizedValue() {
        val persistence = MemoryPersistence()
        val repository = AppSettingsRepository(persistence)

        val migrated = repository.migrateLegacyDefaultQuality("BEST")

        assertEquals(PreferredAudioQuality.BEST, migrated)
        assertEquals(PreferredAudioQuality.BEST, repository.settings.value.defaultQuality)
        assertEquals(PreferredAudioQuality.BEST, persistence.saved?.defaultQuality)
    }

    @Test
    fun explicitMigrationPersistsEvenWhenLoadedStateAlreadyMatches() {
        val persistence = MemoryPersistence(AppSettings(defaultQuality = PreferredAudioQuality.BEST))
        val repository = AppSettingsRepository(persistence)

        repository.migrateLegacyDefaultQuality("BEST")

        assertEquals(AppSettings(defaultQuality = PreferredAudioQuality.BEST), persistence.saved)
    }

    private class MemoryPersistence(
        var failWrites: Boolean = false,
    ) : AppSettingsPersistence {
        var saved: AppSettings? = null

        constructor(initial: AppSettings) : this() {
            saved = initial
        }

        override fun load(): AppSettings? = saved

        @Synchronized
        override fun save(settings: AppSettings) {
            if (failWrites) throw IllegalStateException("disk full")
            saved = settings
        }
    }
}
