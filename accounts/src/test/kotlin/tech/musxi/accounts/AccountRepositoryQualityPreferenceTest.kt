package tech.musxi.accounts

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.musxi.core.account.Platform

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AccountRepositoryQualityPreferenceTest {
    private lateinit var context: Context

    @Before
    fun clearPreferences() {
        context = RuntimeEnvironment.getApplication()
        preferences().edit().clear().commit()
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun explicitPreferenceWinsAndSurvivesGlobalDefaultChanges() {
        val repository = AccountRepository(context)
        repository.rememberPreferredQuality(Platform.KUGOU, "kugou:flac")

        repository.setDefaultAudioQuality(AccountRepository.DefaultAudioQuality.STANDARD)

        assertEquals("kugou:flac", repository.preferredQuality(Platform.KUGOU))
        assertEquals("qq:standard", repository.preferredQuality(Platform.QQ))
        assertEquals("netease:standard", repository.preferredQuality(Platform.NETEASE))

        repository.setDefaultAudioQuality(AccountRepository.DefaultAudioQuality.BEST)

        assertEquals("kugou:flac", repository.preferredQuality(Platform.KUGOU))
        assertEquals("qq:best", repository.preferredQuality(Platform.QQ))
        assertEquals("netease:best", repository.preferredQuality(Platform.NETEASE))
    }

    @Test
    fun platformExplicitPreferencesRemainIndependent() {
        val repository = AccountRepository(context)
        repository.setDefaultAudioQuality(AccountRepository.DefaultAudioQuality.STANDARD)
        repository.rememberPreferredQuality(Platform.KUGOU, "kugou:320")
        repository.rememberPreferredQuality(Platform.QQ, "qq:lossless")
        repository.rememberPreferredQuality(Platform.NETEASE, "netease:higher")

        repository.setDefaultAudioQuality(AccountRepository.DefaultAudioQuality.BEST)

        assertEquals(mapOf(
            Platform.KUGOU to "kugou:320",
            Platform.QQ to "qq:lossless",
            Platform.NETEASE to "netease:higher",
        ), Platform.entries.associateWith(repository::preferredQuality))
    }

    @Test
    fun clearingExplicitPreferencesFallsBackToTheCurrentGlobalDefault() {
        val repository = AccountRepository(context)
        repository.setDefaultAudioQuality(AccountRepository.DefaultAudioQuality.LOSSLESS)
        repository.rememberPreferredQuality(Platform.KUGOU, "kugou:320")
        repository.rememberPreferredQuality(Platform.QQ, "qq:high")
        repository.rememberPreferredQuality(Platform.NETEASE, "netease:hires")

        repository.clearPreferredQuality(Platform.KUGOU)

        assertEquals("kugou:flac", repository.preferredQuality(Platform.KUGOU))
        assertEquals("qq:high", repository.preferredQuality(Platform.QQ))
        assertEquals("netease:hires", repository.preferredQuality(Platform.NETEASE))

        repository.clearPreferredQuality(Platform.QQ)
        repository.clearPreferredQuality(Platform.NETEASE)

        assertEquals(mapOf(
            Platform.KUGOU to "kugou:flac",
            Platform.QQ to "qq:lossless",
            Platform.NETEASE to "netease:lossless",
        ), Platform.entries.associateWith(repository::preferredQuality))
    }

    @Test
    fun invalidStoredExplicitPreferencesFallBackToGlobalDefaults() {
        preferences().edit()
            .putString("quality.kugou", "qq:lossless")
            .putString("quality.qq", "qq:future")
            .putString("quality.netease", "netease:hires ")
            .commit()
        val repository = AccountRepository(context)
        repository.setDefaultAudioQuality(AccountRepository.DefaultAudioQuality.HIGH)

        assertEquals(mapOf(
            Platform.KUGOU to "kugou:320",
            Platform.QQ to "qq:high",
            Platform.NETEASE to "netease:exhigh",
        ), Platform.entries.associateWith(repository::preferredQuality))
    }

    private fun preferences() = context.getSharedPreferences(PLAYBACK, Context.MODE_PRIVATE)

    private companion object {
        const val PLAYBACK = "playback-preferences-v1"
        const val SETTINGS = "musxi-settings-v1"
    }
}
