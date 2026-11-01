package tech.musxi.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverUrlsTest {
    private val qq = "https://y.gtimg.cn/music/photo_new/T002R300x300M000003rsKF44GyaSk.jpg"

    @Test fun nothingInNothingOut() {
        assertNull(hdCoverUrl(null))
        assertEquals("", hdCoverUrl(""))
        assertEquals("  ", hdCoverUrl("  "))
    }

    @Test fun qqSizeBecomes800() {
        assertEquals(
            "https://y.gtimg.cn/music/photo_new/T002R800x800M000003rsKF44GyaSk.jpg",
            hdCoverUrl(qq),
        )
    }

    @Test fun qqAnySourceSizeBecomes800() {
        listOf("90x90", "150x150", "300x300", "500x500", "800x800").forEach { size ->
            assertEquals(
                "https://y.gtimg.cn/music/photo_new/T002R800x800M000abc.jpg",
                hdCoverUrl("https://y.gtimg.cn/music/photo_new/T002R${size}M000abc.jpg"),
            )
        }
    }

    @Test fun qqRewriteKeepsSchemeAndStaysSameOnSecondPass() {
        val http = qq.replace("https://", "http://")
        assertEquals("http://y.gtimg.cn/music/photo_new/T002R800x800M000003rsKF44GyaSk.jpg", hdCoverUrl(http))
        val once = hdCoverUrl(qq)
        assertEquals(once, hdCoverUrl(once))
    }

    @Test fun qqQueryAndFragmentSurvive() {
        assertEquals(
            "https://y.gtimg.cn/music/photo_new/T002R800x800M000abc.jpg?max_age=2592000#x",
            hdCoverUrl("https://y.gtimg.cn/music/photo_new/T002R300x300M000abc.jpg?max_age=2592000#x"),
        )
    }

    @Test fun qqAddressWithoutASizeSegmentIsLeftAlone() {
        val plain = "https://y.gtimg.cn/music/photo.jpg"
        assertEquals(plain, hdCoverUrl(plain))
        // A size-looking query is not part of the file name.
        val inQuery = "https://y.gtimg.cn/music/photo.jpg?x=T002R300x300M000"
        assertEquals(inQuery, hdCoverUrl(inQuery))
    }

    @Test fun otherQqHostsAreLeftAlone() {
        listOf(
            "https://qpic.y.qq.com/music_cover/T002R300x300M000abc.jpg",
            "https://i.gtimg.cn/music/photo_new/T002R300x300M000abc.jpg",
        ).forEach { assertEquals(it, hdCoverUrl(it)) }
    }

    @Test fun neteaseGetsTheSizeParameter() {
        assertEquals(
            "https://p1.music.126.net/abc==/109951.jpg?param=800y800",
            hdCoverUrl("https://p1.music.126.net/abc==/109951.jpg"),
        )
        assertEquals(
            "https://p4.music.126.net/x/1.jpg?param=800y800",
            hdCoverUrl("https://p4.music.126.net/x/1.jpg?"),
        )
    }

    @Test fun neteaseReplacesAnExistingSizeAndKeepsTheRest() {
        assertEquals(
            "https://p2.music.126.net/x/1.jpg?param=800y800",
            hdCoverUrl("https://p2.music.126.net/x/1.jpg?param=130y130"),
        )
        assertEquals(
            "https://p2.music.126.net/x/1.jpg?a=1&b=2&param=800y800#top",
            hdCoverUrl("https://p2.music.126.net/x/1.jpg?a=1&param=130y130&b=2#top"),
        )
        // Only the exact key counts.
        assertEquals(
            "https://p2.music.126.net/x/1.jpg?xparam=1&param=800y800",
            hdCoverUrl("https://p2.music.126.net/x/1.jpg?xparam=1"),
        )
    }

    @Test fun neteaseRewriteStaysSameOnSecondPass() {
        val once = hdCoverUrl("https://p1.music.126.net/abc/1.jpg")
        assertEquals(once, hdCoverUrl(once))
    }

    @Test fun neteaseHostMatchIgnoresCase() {
        assertEquals(
            "https://P1.Music.126.NET/x/1.jpg?param=800y800",
            hdCoverUrl("https://P1.Music.126.NET/x/1.jpg"),
        )
    }

    @Test fun kugouAndUnknownHostsAreReturnedAsTheyAre() {
        listOf(
            "https://imge.kugou.com/stdmusic/150/20200101/cover.jpg",
            "https://imgessl.kugou.com/stdmusic/150/cover.jpg",
            "https://c1.kgimg.com/cover.jpg",
            "https://example.com/T002R300x300M000abc.jpg",
        ).forEach { assertEquals(it, hdCoverUrl(it)) }
    }

    @Test fun lookalikeHostsAreNotRewritten() {
        listOf(
            "https://y.gtimg.cn.evil.test/music/T002R300x300M000abc.jpg",
            "https://evil.test/y.gtimg.cn/T002R300x300M000abc.jpg",
            "https://y.gtimg.cn@evil.test/music/T002R300x300M000abc.jpg",
            "https://evily.gtimg.cn/music/T002R300x300M000abc.jpg",
            "https://music.126.net/x/1.jpg",
            "https://p1.music.126.net.evil.test/x/1.jpg",
            "https://evil.test/p1.music.126.net/1.jpg",
            "https://p1.music.126.net@evil.test/x/1.jpg",
            "https://evilmusic.126.net/x/1.jpg",
        ).forEach { assertEquals(it, hdCoverUrl(it)) }
    }

    @Test fun userInfoBeforeARealHostDoesNotChangeTheHost() {
        // The host here is p1.music.126.net, so the rule applies, and the user info stays as written.
        assertEquals(
            "https://evil.test@p1.music.126.net/x/1.jpg?param=800y800",
            hdCoverUrl("https://evil.test@p1.music.126.net/x/1.jpg"),
        )
    }

    @Test fun nonWebAddressesAndGarbageComeBackUnchanged() {
        listOf(
            "file:///sdcard/T002R300x300M000abc.jpg",
            "content://media/p1.music.126.net/1.jpg",
            "ftp://y.gtimg.cn/T002R300x300M000abc.jpg",
            "//y.gtimg.cn/music/T002R300x300M000abc.jpg",
            "y.gtimg.cn/music/T002R300x300M000abc.jpg",
            "not a url",
            "https://",
            "https://p1.music.126.net",
            "https://y.gtimg.cn",
            "https://p1.music.126.net/a b.jpg",
        ).forEach { assertEquals(it, hdCoverUrl(it)) }
    }

    @Test fun schemeHostPortAndUserInfoNeverChange() {
        val inputs = listOf(
            qq,
            "http://y.gtimg.cn:8080/music/T002R300x300M000abc.jpg",
            "https://p1.music.126.net/a/1.jpg",
            "http://p3.music.126.net:81/a/1.jpg?param=1y1",
            "https://user:pw@p1.music.126.net/a/1.jpg",
        )
        inputs.forEach { input ->
            val out = hdCoverUrl(input)!!
            assertEquals(
                "authority of $input",
                input.substringAfter("://").substringBefore('/'),
                out.substringAfter("://").substringBefore('/'),
            )
            assertEquals("scheme of $input", input.substringBefore("://"), out.substringBefore("://"))
        }
    }
}
