package tech.musxi.core.account

import org.junit.Assert.*
import org.junit.Test

class AvatarPolicyTest {
    @Test fun platformAvatarsUseHttpsAndSmallSupportedSizes() {
        assertEquals("https://imge.kugou.com/kugouicon/165/2026/photo.jpg",
            AvatarPolicy.sanitize(Platform.KUGOU, "http://imge.kugou.com/kugouicon/500/2026/photo.jpg"))
        assertEquals("https://imge.kugou.com/kugouicon/165/photo.jpg",
            AvatarPolicy.sanitize(Platform.KUGOU, "http://imge.kugou.com/kugouicon/{size}/photo.jpg"))
        assertEquals("https://p3.music.126.net/public-id/image.jpg?param=200y200",
            AvatarPolicy.sanitize(Platform.NETEASE, "http://p3.music.126.net/public-id/image.jpg?param=800y800"))
        assertEquals("https://thirdqq.qlogo.cn/g?b=qq&k=public-picture-key&s=140",
            AvatarPolicy.sanitize(Platform.QQ, "http://thirdqq.qlogo.cn/g?b=qq&k=public-picture-key&s=640"))
        assertEquals("https://q.qlogo.cn/headimg_dl?dst_uin=123&spec=140",
            AvatarPolicy.sanitize(Platform.QQ, "https://q.qlogo.cn/headimg_dl?dst_uin=123&spec=640"))
        assertEquals("https://thirdqq.qlogo.cn/g?b=sdk&k=picture&kti=0&t=0&s=140",
            AvatarPolicy.sanitize(Platform.QQ, "http://thirdqq.qlogo.cn/g?b=sdk&k=picture&kti=0&s=640&t=0"))
        assertEquals("https://thirdqq.qlogo.cn/g?b=sdk&k=picture&kti=opaque-key&s=140",
            AvatarPolicy.sanitize(Platform.QQ, "http://thirdqq.qlogo.cn/g?b=sdk&k=picture&kti=opaque-key&s=640"))
        assertEquals("https://thirdqq.qlogo.cn/g?b=sdk&k=picture&kti=&s=140",
            AvatarPolicy.sanitize(Platform.QQ, "http://thirdqq.qlogo.cn/g?b=sdk&k=picture&kti=&s=640"))
        assertEquals("https://y.gtimg.cn/music/photo/R150x150.jpg",
            AvatarPolicy.sanitize(Platform.QQ, "https://y.gtimg.cn/music/photo/R800x800.jpg"))
    }

    @Test fun rejectsForeignHostsCredentialsTraversalAndUnknownQueries() {
        val rejected = listOf(
            "https://example.com/avatar.jpg", "https://imge.kugou.com.evil.test/avatar.jpg",
            "https://user:secret@imge.kugou.com/avatar.jpg", "https://imge.kugou.com:443/avatar.jpg",
            "https://imge.kugou.com/a/../avatar.jpg", "https://imge.kugou.com/%252e%252e/avatar.jpg",
            "https://imge.kugou.com/avatar.jpg#private", "https://imge.kugou.com/avatar.jpg?token=secret",
            "file:///avatar.jpg", "https://imge.kugou.com/a%0A.jpg",
        )
        rejected.forEach { assertNull(it.substringBefore('?'), AvatarPolicy.sanitize(Platform.KUGOU, it)) }
        listOf(
            "https://thirdqq.qlogo.cn/g?b=qq&k=public&token=secret",
            "https://thirdqq.qlogo.cn/g?b=qq&k=public&k=other",
            "https://thirdqq.qlogo.cn/g?b=qq&k=public&kti=%0Asecret",
            "https://thirdqq.qlogo.cn/g?b=qq&k=public&kti=" + "a".repeat(129),
            "https://thirdqq.qlogo.cn/g?b=qq&k=%0Asecret",
            "https://thirdqq.qlogo.cn/redirect?b=qq&k=public",
        ).forEach { assertNull(AvatarPolicy.sanitize(Platform.QQ, it)) }
        assertNull(AvatarPolicy.sanitize(Platform.NETEASE, "https://p3.music.126.net/a.jpg?cookie=private"))
        assertNull(AvatarPolicy.sanitize(Platform.KUGOU, "https://p3.music.126.net/a.jpg"))
    }

    @Test fun pictureQueriesDoNotLoosenArtworkAndProfileStringStaysRedacted() {
        val url = "https://thirdqq.qlogo.cn/g?b=qq&k=private-picture-id&s=140"
        assertNotNull(AvatarPolicy.sanitize(Platform.QQ, url))
        assertNull(tech.musxi.core.library.ArtworkPolicy.sanitize(Platform.QQ, url))
        val profile = AccountProfile(Platform.QQ, "private-account", "private-name", url)
        assertFalse(profile.toString().contains("private"))
        assertFalse(profile.toString().contains("qlogo"))
    }
}
