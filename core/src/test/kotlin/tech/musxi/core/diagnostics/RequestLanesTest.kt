package tech.musxi.core.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RequestLanesTest {
    @Test fun audioCompletesWhileFullFavoritesReadRemainsBlocked() = withLanes { lanes ->
        val reading = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val resolved = CountDownLatch(1)
        val readDone = CountDownLatch(1)
        try {
            lanes.execute(RequestOperation.FAVORITES_READ) {
                reading.countDown(); releaseRead.await(); readDone.countDown()
            }
            assertTrue(reading.await(3, TimeUnit.SECONDS))
            lanes.execute(RequestOperation.AUDIO) { resolved.countDown() }
            assertTrue(resolved.await(3, TimeUnit.SECONDS))
            assertEquals(1L, readDone.count)
        } finally { releaseRead.countDown() }
    }

    @Test fun nonAudioRequestsKeepOrderIncludingWriteAndReadback() = withLanes { lanes ->
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val observed = Collections.synchronizedList(mutableListOf<RequestOperation>())
        val operations = RequestOperation.entries.filter { it != RequestOperation.AUDIO }
        try {
            lanes.execute(RequestOperation.OTHER) { release.await() }
            operations.forEach { op -> lanes.execute(op) { observed += op } }
            lanes.execute(RequestOperation.FAVORITES_READ) { done.countDown() }
            assertTrue(observed.isEmpty())
            release.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals(operations, observed)
        } finally { release.countDown() }
    }

    @Test fun audioRequestsRemainSerialAndDoNotBlockLibraryReads() = withLanes { lanes ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val second = CountDownLatch(1)
        val library = CountDownLatch(1)
        try {
            lanes.execute(RequestOperation.AUDIO) { started.countDown(); release.await() }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            lanes.execute(RequestOperation.AUDIO) { second.countDown() }
            lanes.execute(RequestOperation.SEARCH) { library.countDown() }
            assertTrue(library.await(3, TimeUnit.SECONDS))
            assertEquals(1L, second.count)
            release.countDown()
            assertTrue(second.await(3, TimeUnit.SECONDS))
        } finally { release.countDown() }
    }

    private fun withLanes(test: (RequestLanes) -> Unit) {
        val library = Executors.newSingleThreadExecutor()
        val audio = Executors.newSingleThreadExecutor()
        try { test(RequestLanes(library, audio)) }
        finally { library.shutdownNow(); audio.shutdownNow() }
    }
}
