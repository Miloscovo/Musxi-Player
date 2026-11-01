package tech.musxi.accounts

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.account.MembershipLevel
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.playback.QualityOption
import tech.musxi.core.playback.QualityOptions

class QualityMenuCacheTest {
    private val song = MediaKey(Platform.KUGOU, "song")
    private val options = QualityOptions(listOf(QualityOption("kugou:128", "standard")), null)

    @Test fun membershipChangesInvalidateTheMenuWithoutWaitingForTtl() {
        var probes = 0
        val cache = QualityMenuCache({ 100L })
        fun read(level: MembershipLevel) = cache.getOrLoad(1, song, { true }, level) { probes++; options }
        read(MembershipLevel.NO); read(MembershipLevel.NO)
        assertEquals(1, probes)
        read(MembershipLevel.YES); read(MembershipLevel.YES)
        assertEquals(2, probes)
        read(MembershipLevel.NO)
        assertEquals(3, probes)
    }

    @Test fun repeatedMenusReuseNegativeHighProbeUntilTtlExpires() {
        var clock = 100L
        var probes = 0
        val cache = QualityMenuCache({ clock })
        fun read() = cache.getOrLoad(1, song, { true }) { probes++; options }
        assertEquals(options, read())
        clock += 59_999
        assertEquals(options, read())
        assertEquals(1, probes)
        clock++
        read()
        assertEquals(2, probes)
    }

    @Test fun accountGenerationAndSongIdentitySeparateEntriesAndStorageIsBounded() {
        var probes = 0
        val cache = QualityMenuCache({ 100 }, maxEntries = 1)
        fun read(generation: Long, key: MediaKey = song) = cache.getOrLoad(generation, key, { true }) { probes++; options }
        read(1); read(2)
        assertEquals(2, probes)
        read(2, song.copy(id = "other")); read(2)
        assertEquals(4, probes)
    }

    @Test fun accountChangeDuringProbeRejectsTheResultAndFailuresAreNotCached() {
        val cache = QualityMenuCache({ 100 })
        var current = true
        assertThrows(LibraryPageFailure::class.java) {
            cache.getOrLoad(1, song, { current }) { current = false; options }
        }
        current = true
        var probes = 0
        assertThrows(java.io.IOException::class.java) {
            cache.getOrLoad(1, song, { current }) { probes++; throw java.io.IOException() }
        }
        cache.getOrLoad(1, song, { current }) { probes++; options }
        assertEquals(2, probes)
    }

    @Test fun simultaneousMenuMissesShareOneProbe() {
        val cache = QualityMenuCache({ 100 })
        val probes = java.util.concurrent.atomic.AtomicInteger()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<QualityOptions> { cache.getOrLoad(1, song, { true }) {
                probes.incrementAndGet(); started.countDown(); check(release.await(2, java.util.concurrent.TimeUnit.SECONDS)); options
            } }
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val second = executor.submit<QualityOptions> { cache.getOrLoad(1, song, { true }) { probes.incrementAndGet(); options } }
            release.countDown()
            assertEquals(options, first.get(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(options, second.get(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, probes.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
