package tech.musxi.core.library

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform

class ArtworkPolicyTest {
    @Test fun upgradesOnlyKnownPublicImageHostsAndKeepsPlatformIsolation() {
        val samples = mapOf(Platform.NETEASE to "p1.music.126.net", Platform.QQ to "y.gtimg.cn",
            Platform.KUGOU to "imge.kugou.com")
        samples.forEach { (platform, host) ->
            assertEquals("https://$host/image.jpg", ArtworkPolicy.sanitize(platform, "http://$host/image.jpg"))
            Platform.entries.filter { it != platform }.forEach {
                assertNull(ArtworkPolicy.sanitize(it, "https://$host/image.jpg"))
            }
        }
    }

    @Test fun rejectsCredentialsSignedLinksStreamHostsAndAmbiguousAuthorities() {
        listOf("https://p1.music.126.net/image.jpg?token=private", "https://p1.music.126.net/image.jpg?",
            "https://p1.music.126.net/image.jpg#fragment", "https://user@p1.music.126.net/image.jpg",
            "https://p1.music.126.net:443/image.jpg", "https://p1.music.126.net.evil.test/image.jpg",
            "https://m7.music.126.net/audio.mp3", "https://p1.music.126.net/a/../image.jpg",
            "https://p1.music.126.net/a%0dimage.jpg", "file:///private/image.jpg",
            "https://p1.music.126.net/image.jpg\n").forEach { value ->
            assertNull(value, ArtworkPolicy.sanitize(Platform.NETEASE, value))
        }
    }
}
