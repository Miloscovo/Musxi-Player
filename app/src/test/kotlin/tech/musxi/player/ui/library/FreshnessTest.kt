package tech.musxi.player.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreshnessTest {
    private val now = 1_000_000_000_000L

    @Test fun wordsFollowTheAge() {
        assertNull(freshnessLabel(null, now))
        assertEquals("刚刚更新", freshnessLabel(now - 30_000, now))
        assertEquals("5 分钟前更新", freshnessLabel(now - 5 * 60_000, now))
        assertEquals("2 小时前更新", freshnessLabel(now - 125 * 60_000, now))
        assertEquals("3 天前更新", freshnessLabel(now - 3L * 24 * 60 * 60_000, now))
    }

    @Test fun aClockThatWentBackwardsIsNotTheFuture() {
        assertEquals("刚刚更新", freshnessLabel(now + 60_000, now))
    }
}
