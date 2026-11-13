package tech.musxi.player.ui.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutSectionTest {
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
