package tech.musxi.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class KugouLibraryRepositoryTest {
    @Test
    fun `cover lookups are bounded ordered and reuse exactly three workers`() {
        val executor = Executors.newFixedThreadPool(3)
        val caller = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(3)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val workers = ConcurrentHashMap.newKeySet<Long>()
        try {
            val response = caller.submit<List<Int?>> {
                KugouLibraryRepository.lookupFirstSixInOrder((1..9).toList(), executor) { value ->
                    workers += Thread.currentThread().id
                    val current = active.incrementAndGet()
                    peak.accumulateAndGet(current, ::maxOf)
                    try {
                        if (value <= 3) started.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        if (value == 2) error("expected cover lookup failure")
                        value * 10
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }

            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFalse(response.isDone)
            release.countDown()
            assertEquals(listOf(10, null, 30, 40, 50, 60), response.get(5, TimeUnit.SECONDS))

            val reused = KugouLibraryRepository.lookupFirstSixInOrder((7..13).toList(), executor) { value ->
                workers += Thread.currentThread().id
                value
            }
            assertEquals((7..12).toList(), reused)
            assertEquals(3, peak.get())
            assertEquals(3, workers.size)
        } finally {
            release.countDown()
            caller.shutdownNow()
            executor.shutdownNow()
        }
    }
}
