package tech.musxi.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSourcePolicyTest {
    @Test fun cleartextCompatibilityIsLimitedToTwoExactKugouAudioHosts() {
        val id = "kugou:" + "a".repeat(32) + "_0_123"
        listOf("fs.youthandroid2.kugou.com", "fs.youthandroid.kugou.com").forEach { host ->
            assertTrue(PlaybackSourcePolicy.accepts(id, "http://$host/song.mp3?sign=fixture"))
            assertFalse(PlaybackSourcePolicy.accepts("netease:123", "http://$host/song.mp3"))
            listOf("http://sub.$host/a", "http://$host.evil.test/a", "http://$host:80/a", "http://$host:/a",
                "http://user@$host/a", "http://$host/a#fragment", "http://$host./a").forEach { url ->
                assertFalse(PlaybackSourcePolicy.accepts(id, url))
            }
        }
        assertFalse(PlaybackSourcePolicy.accepts(id, "http://gateway.kugou.com/v5/url"))
    }
    @Test fun kugouIdentityAndOriginCannotBeConfusedWithNetease() {
        val id = "kugou:" + "a".repeat(32) + "_0_123"
        assertTrue(PlaybackSourcePolicy.accepts(id, "https://fsandroid.tx.kugou.com/file.mp3?sign=fixture"))
        assertFalse(PlaybackSourcePolicy.accepts(id, "https://m.music.126.net/file.mp3"))
        assertFalse(PlaybackSourcePolicy.accepts("netease:123", "https://fsandroid.tx.kugou.com/file.mp3"))
        listOf("https://kugou.com.evil.test/a", "https://evilkugou.com/a", "http://fsandroid.tx.kugou.com/a",
            "https://fsandroid.tx.kugou.com@evil.test/a").forEach { assertFalse(PlaybackSourcePolicy.accepts(id, it)) }
        listOf(id.uppercase(), id.replace("_0_", "_00_"), id.replace("_123", "_9223372036854775808"),
            "kugou:" + "g".repeat(32) + "_0_1").forEach { assertFalse(PlaybackSourcePolicy.acceptsMediaId(it)) }
    }
    @Test fun acceptsOfficialHttpsSourcesAndSignedQueries() {
        listOf(
            "https://m8.music.126.net/song.mp3?sign=abc%2Fdef&expires=123",
            "https://M8.MUSIC.126.NET:443/song.mp3",
            "https://a.b.music.163.com/song.flac",
            "https://music.126.net/song.mp3",
            "https://music.163.com/song.mp3"
        ).forEachIndexed { index, url ->
            assertTrue("Valid source case $index", PlaybackSourcePolicy.accepts("netease:123", url))
        }
        assertTrue(PlaybackSourcePolicy.accepts("netease:9223372036854775807", "https://m.music.126.net/a"))
    }

    @Test fun rejectsOtherOriginsAndAuthorityAmbiguities() {
        listOf(
            "http://m.music.126.net/a", "file:///a", "content://m.music.126.net/a",
            "//m.music.126.net/a", "https:m.music.126.net/a",
            "https://evil.test/a", "https://music.126.net.evil.test/a", "https://evilmusic.126.net/a",
            "https://music.163.com.evil.test/a", "https://music.126.net@evil.test/a",
            "https://evil.test@m.music.126.net/a", "https://@m.music.126.net/a",
            "https://m.music.126.net:80/a", "https://m.music.126.net:444/a",
            "https://m.music.126.net:/a", "https://m.music.126.net:0443/a",
            "https://m.music.126.net:+443/a", "https://m.music.126.net:-1/a",
            "https://m.music.126.net/a#fragment", "https://m.music.126.net/a#",
            "https://m.music.126.net./a", "https://.music.126.net/a", "https://a..music.126.net/a",
            "https://a_b.music.126.net/a", "https://-a.music.126.net/a",
            "https://a-.music.126.net/a", "https://%6d.music.126.net/a",
            "https://m.music.126.net%2fevil.test/a", "https://m.music.126.net\\@evil.test/a",
            "https://m.music.126.net/a\n", "https://m.music.126.net/a\u0000",
            "https://ｍ.music.126.net/a", "https://127.0.0.1/a", "https://[::1]/a",
            "https://m.music.126.net/" + "a".repeat(8192)
        ).forEachIndexed { index, url ->
            assertFalse("Rejected source case $index", PlaybackSourcePolicy.accepts("netease:123", url))
        }
    }

    @Test fun rejectsInvalidOrForeignMediaIds() {
        listOf("", "123", "qq:123", "NETEASE:123", "netease:0", "netease:01", "netease:-1",
            "netease:+1", "netease:1.0", "netease:１２３", "netease:1 ", "netease:1\n",
            "netease:9223372036854775808", "netease:12345678901234567890")
            .forEachIndexed { index, id ->
                assertFalse("Rejected media ID case $index", PlaybackSourcePolicy.accepts(id, "https://m.music.126.net/a"))
            }
    }
    @Test fun qqSongMidAndHttpsAudioDomainsRemainIndependent() {
        val id = "qq:003abcDEF01234"
        assertTrue(PlaybackSourcePolicy.acceptsMediaId(id))
        listOf("dl.stream.qqmusic.qq.com", "isure.stream.qqmusic.qq.com", "aqqmusic.tc.qq.com").forEach { host ->
            val url = "https://$host/M500fixture.mp3?sign=fixture"
            assertTrue(PlaybackSourcePolicy.accepts(id, url))
            assertFalse(PlaybackSourcePolicy.accepts("netease:123", url))
            assertFalse(PlaybackSourcePolicy.accepts("kugou:" + "a".repeat(32) + "_0_1", url))
            listOf("http://$host/a", "https://$host.evil.test/a", "https://user@$host/a",
                "https://$host:0443/a", "https://$host:/a", "https://$host/a#fragment").forEach {
                assertFalse(PlaybackSourcePolicy.accepts(id, it))
            }
        }
        listOf("https://u.y.qq.com/a", "https://qq.com/a", "https://m.music.126.net/a",
            "https://fs.youthandroid.kugou.com/a", "https://sub.aqqmusic.tc.qq.com/a").forEach {
            assertFalse(PlaybackSourcePolicy.accepts(id, it))
        }
        listOf("qq:123", "QQ:003abcDEF01234", "qq:003abcDEF0123_", "qq:003abcDEF01234extra",
            "qq:003abcDEF01234\n", "qq:" + "a".repeat(28)).forEach {
            assertFalse(PlaybackSourcePolicy.acceptsMediaId(it))
        }
    }

}
