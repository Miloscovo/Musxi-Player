package tech.musxi.accounts

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.musxi.core.settings.AppSettingsPersistenceFormat
import tech.musxi.core.settings.LockScreenLyricsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class LockScreenLyricsSettingsTest {
    private lateinit var context: Context
    private val preferences get() = context.getSharedPreferences("lockscreen-settings-test", Context.MODE_PRIVATE)
    private fun repository() = AppSettingsRepository(context, "lockscreen-settings-test", "lockscreen-legacy-test")

    @Before fun clear() {
        context = RuntimeEnvironment.getApplication()
        preferences.edit().clear().commit()
        context.getSharedPreferences("lockscreen-legacy-test", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun oldEnabledCardMigratesAndPageStoresFalseInDowngradeKey() {
        preferences.edit().putString(AppSettingsPersistenceFormat.MEDIA_CARD_LYRICS, "true").commit()
        val repository = repository()
        assertEquals(LockScreenLyricsMode.CARD, repository.settings.value.effectiveLockScreenLyricsMode)
        repository.setLockScreenLyricsMode(LockScreenLyricsMode.PAGE)
        assertEquals("PAGE", preferences.getString(AppSettingsPersistenceFormat.LOCK_SCREEN_LYRICS_MODE, null))
        assertEquals("false", preferences.getString(AppSettingsPersistenceFormat.MEDIA_CARD_LYRICS, null))
        assertEquals(LockScreenLyricsMode.PAGE, repository().settings.value.effectiveLockScreenLyricsMode)
    }

    @Test fun legacySetterReplacesPageAtomicallyAndPreservesOtherSettings() {
        val repository = repository()
        repository.setAccent("TEAL")
        repository.setLockScreenLyricsMode(LockScreenLyricsMode.PAGE)
        repository.setMediaCardLyrics(true)
        val settings = repository().settings.value
        assertEquals(LockScreenLyricsMode.CARD, settings.effectiveLockScreenLyricsMode)
        assertTrue(settings.mediaCardLyrics)
        assertEquals("TEAL", settings.accent)
    }

    @Test fun malformedNewValueNeverRevivesOldCardMode() {
        preferences.edit().putString(AppSettingsPersistenceFormat.MEDIA_CARD_LYRICS, "true")
            .putString(AppSettingsPersistenceFormat.LOCK_SCREEN_LYRICS_MODE, "unknown").commit()
        assertEquals(LockScreenLyricsMode.OFF, repository().settings.value.effectiveLockScreenLyricsMode)
    }

    @Test fun wrongStoredTypeAlsoDisablesInsteadOfFallingBackToLegacyTrue() {
        preferences.edit().putString(AppSettingsPersistenceFormat.MEDIA_CARD_LYRICS, "true")
            .putBoolean(AppSettingsPersistenceFormat.LOCK_SCREEN_LYRICS_MODE, true).commit()
        assertEquals(LockScreenLyricsMode.OFF, repository().settings.value.effectiveLockScreenLyricsMode)
    }
}
