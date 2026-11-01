package tech.musxi.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CoverColorCacheTest {
    @Test fun anUnknownAddressIsAMiss() {
        assertNull(CoverColorCache().get("https://p1.music.126.net/a.jpg"))
    }

    @Test fun remembersAColourAndAFailure() {
        val cache = CoverColorCache()
        cache.put("ok", 0xFF112233.toInt())
        cache.put("bad", null)
        assertEquals(0xFF112233.toInt(), cache.get("ok")!!.argb)
        // A remembered failure is an entry without a colour, not a miss: it must not be retried.
        assertNotNull(cache.get("bad"))
        assertNull(cache.get("bad")!!.argb)
    }

    @Test fun putAgainReplacesTheEntry() {
        val cache = CoverColorCache()
        cache.put("a", 1)
        cache.put("a", 2)
        assertEquals(2, cache.get("a")!!.argb)
        assertEquals(1, cache.size())
    }

    @Test fun evictsTheLeastRecentlyUsedAddress() {
        val cache = CoverColorCache(capacity = 3)
        listOf("a", "b", "c").forEach { cache.put(it, 1) }
        // Reading "a" makes "b" the oldest.
        cache.get("a")
        cache.put("d", 4)
        assertNull(cache.get("b"))
        assertNotNull(cache.get("a"))
        assertNotNull(cache.get("c"))
        assertNotNull(cache.get("d"))
        assertEquals(3, cache.size())
    }

    @Test fun holdsSixtyFourByDefault() {
        val cache = CoverColorCache()
        repeat(100) { cache.put("u$it", it) }
        assertEquals(64, cache.size())
        assertNull(cache.get("u0"))
        assertNotNull(cache.get("u36"))
        assertNotNull(cache.get("u99"))
    }
}
