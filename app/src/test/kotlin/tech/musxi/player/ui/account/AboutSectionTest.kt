package tech.musxi.player.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.update.UpdateCheckResult

class AboutSectionTest {
    @Test
    fun mapsEveryUpdateResult() {
        assertEquals(UpdateStatusUi.UpToDate, updateStatusOf(UpdateCheckResult.NoUpdate))
        assertEquals(UpdateStatusUi.Failed, updateStatusOf(UpdateCheckResult.Failed))
        assertEquals(
            UpdateStatusUi.RateLimited(123L),
            updateStatusOf(UpdateCheckResult.RateLimited(123L)),
        )
        assertEquals(
            UpdateStatusUi.Available(
                version = "v0.2.0",
                notes = "notes",
                releasePage = "https://github.com/qwt1011/Musxi-Player-Android/releases/tag/v0.2.0",
            ),
            updateStatusOf(
                UpdateCheckResult.UpdateAvailable(
                    version = "v0.2.0",
                    notes = "notes",
                    releasePage = "https://github.com/qwt1011/Musxi-Player-Android/releases/tag/v0.2.0",
                ),
            ),
        )
    }

    @Test
    fun acceptsRepositoryReleasePages() {
        assertTrue(
            isTrustedReleasePage(
                "https://github.com/qwt1011/Musxi-Player-Android/releases/tag/v0.2.0",
            ),
        )
        assertTrue(isTrustedReleasePage("https://github.com/qwt1011/Musxi-Player-Android/releases"))
    }

    @Test
    fun rejectsUntrustedReleasePages() {
        val rejected = listOf(
            "",
            "http://github.com/qwt1011/Musxi-Player-Android/releases/tag/v0.2.0",
            "https://github.com/qwt1011/Other-Repo/releases/tag/v0.2.0",
            "https://github.com/qwt1011/Musxi-Player-Android-evil/releases/tag/v0.2.0",
            "https://evil.example/qwt1011/Musxi-Player-Android/releases/tag/v0.2.0",
            "https://github.com/qwt1011/Musxi-Player-Android@evil.example/releases/tag/v0.2.0",
            "https://github.com/qwt1011/Musxi-Player-Android/releases/tag/v0.2.0?x=1",
            "https://github.com/qwt1011/Musxi-Player-Android/releases/tag/v0.2.0#frag",
            "https://github.com/qwt1011/Musxi-Player-Android/../../other/releases",
            "https://github.com/qwt1011/Musxi-Player-Android/releases/tag/v0.2.0 ",
            "https://github.com/qwt1011/Musxi-Player-Android/releases/tag/a\\b",
            "https://github.com/qwt1011/Musxi-Player-Android/releases/tag/v0.2.0\n",
        )
        rejected.forEach { url ->
            assertFalse("should reject: $url", isTrustedReleasePage(url))
        }
    }
}
