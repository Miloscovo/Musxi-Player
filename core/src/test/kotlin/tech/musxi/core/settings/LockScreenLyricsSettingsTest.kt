package tech.musxi.core.settings

import org.junit.Assert.*
import org.junit.Test

class LockScreenLyricsSettingsTest {
    @Test fun oldSnapshotsMigrateWithoutEnablingNewPage() {
        listOf("true" to LockScreenLyricsMode.CARD, "false" to LockScreenLyricsMode.OFF,
            "broken" to LockScreenLyricsMode.OFF).forEach { (stored, mode) ->
            val value = AppSettingsPersistenceFormat.decode(mapOf(AppSettingsPersistenceFormat.MEDIA_CARD_LYRICS to stored))
            assertEquals(mode, value.effectiveLockScreenLyricsMode)
        }
        assertEquals(LockScreenLyricsMode.OFF, AppSettingsPersistenceFormat.decode(emptyMap()).effectiveLockScreenLyricsMode)
    }

    @Test fun newKeyWinsAndCorruptionCannotReenableOldCard() {
        listOf("PAGE" to LockScreenLyricsMode.PAGE, "OFF" to LockScreenLyricsMode.OFF,
            "broken" to LockScreenLyricsMode.OFF).forEach { (stored, mode) ->
            val value = AppSettingsPersistenceFormat.decode(mapOf(
                AppSettingsPersistenceFormat.MEDIA_CARD_LYRICS to "true",
                AppSettingsPersistenceFormat.LOCK_SCREEN_LYRICS_MODE to stored))
            assertEquals(mode, value.effectiveLockScreenLyricsMode)
            assertFalse(value.mediaCardLyrics)
        }
    }

    @Test fun everyModeRoundTripsAndDowngradeFlagOnlyEnablesCard() {
        val repository = AppSettingsRepository(Memory())
        LockScreenLyricsMode.entries.forEach { mode ->
            val value = repository.setLockScreenLyricsMode(mode)
            assertEquals(mode, value.effectiveLockScreenLyricsMode)
            val encoded = AppSettingsPersistenceFormat.encode(value)
            assertEquals(mode.name, encoded[AppSettingsPersistenceFormat.LOCK_SCREEN_LYRICS_MODE])
            assertEquals((mode == LockScreenLyricsMode.CARD).toString(), encoded[AppSettingsPersistenceFormat.MEDIA_CARD_LYRICS])
            assertEquals(value, AppSettingsPersistenceFormat.decode(encoded))
        }
    }

    @Test fun legacySetterAndGenericCopySelectCardAndOffFromPage() {
        val repository = AppSettingsRepository(Memory())
        repository.setLockScreenLyricsMode(LockScreenLyricsMode.PAGE)
        assertEquals(LockScreenLyricsMode.CARD, repository.update { it.copy(mediaCardLyrics = true) }.effectiveLockScreenLyricsMode)
        repository.setLockScreenLyricsMode(LockScreenLyricsMode.PAGE)
        assertEquals(LockScreenLyricsMode.OFF, repository.setMediaCardLyrics(false).effectiveLockScreenLyricsMode)
        repository.setLockScreenLyricsMode(LockScreenLyricsMode.PAGE)
        assertEquals(LockScreenLyricsMode.CARD, repository.setMediaCardLyrics(true).effectiveLockScreenLyricsMode)
        assertEquals(AppSettings(mediaCardLyrics = true), repository.settings.value)
    }

    @Test fun explicitPageClearsLegacyCardIncludingGenericUpdates() {
        val repository = AppSettingsRepository(Memory(AppSettings(mediaCardLyrics = true)))
        val value = repository.update { it.copy(lockScreenLyricsMode = LockScreenLyricsMode.PAGE) }
        assertEquals(LockScreenLyricsMode.PAGE, value.effectiveLockScreenLyricsMode)
        assertFalse(value.mediaCardLyrics)
        assertEquals(LockScreenLyricsMode.OFF,
            repository.update { it.copy(lockScreenLyricsMode = LockScreenLyricsMode.OFF, mediaCardLyrics = true) }.effectiveLockScreenLyricsMode)
    }

    @Test fun failureDoesNotPublishOrChangeOtherPreferences() {
        val memory = Memory(AppSettings(accent = "TEAL", mediaCardLyrics = true))
        val repository = AppSettingsRepository(memory)
        val before = repository.settings.value
        memory.fail = true
        assertThrows(IllegalStateException::class.java) { repository.setLockScreenLyricsMode(LockScreenLyricsMode.PAGE) }
        assertEquals(before, repository.settings.value)
        assertEquals(before, memory.value)
    }

    @Test fun pageSurvivesRepositoryRecreationAndUnrelatedUpdates() {
        val memory = Memory()
        val repository = AppSettingsRepository(memory)
        repository.setLockScreenLyricsMode(LockScreenLyricsMode.PAGE)
        repository.setAccent("TEAL")
        val restored = AppSettingsRepository(memory).settings.value
        assertEquals("TEAL", restored.accent)
        assertFalse(restored.mediaCardLyrics)
        assertEquals(LockScreenLyricsMode.PAGE, restored.effectiveLockScreenLyricsMode)
    }

    private class Memory(var value: AppSettings? = null, var fail: Boolean = false) : AppSettingsPersistence {
        override fun load() = value
        override fun save(settings: AppSettings) { check(!fail) { "disk full" }; value = settings }
    }
}
