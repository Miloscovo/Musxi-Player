package tech.musxi.core.cache

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CacheMaintenanceTest {
    private class Store(var bytes: Long) : CacheStorage {
        var clears = 0
        var failClear = false
        var failRead = false
        override suspend fun sizeBytes(): Long {
            if (failRead) throw IOException("private storage path")
            return bytes
        }
        override suspend fun clear() {
            clears++
            if (failClear) throw IOException("private storage path")
            bytes = 0
        }
    }

    @Test fun reportsDiskTotalsAndClearsEveryCategoryDespiteOneFailure() = runBlocking {
        val stores = CacheKind.entries.associateWith { Store(10) }
        val cache = CacheMaintenance(stores)
        assertEquals(30L, cache.refresh().usage.totalBytes)
        stores.getValue(CacheKind.LYRICS).failClear = true
        val result = cache.clear()
        assertFalse(result.succeeded)
        assertEquals(setOf(CacheKind.LYRICS), result.failed)
        assertEquals(10L, result.usage.totalBytes)
        assertTrue(stores.values.all { it.clears == 1 })
        assertFalse(cache.state.value.busy)
        assertFalse(result.toString().contains("private"))
    }

    @Test fun unreadableCategoryIsUnknownInsteadOfPretendingToBeEmpty() = runBlocking {
        val stores = CacheKind.entries.associateWith { Store(10) }
        stores.getValue(CacheKind.COVERS).failRead = true
        val result = CacheMaintenance(stores).refresh()
        assertEquals(setOf(CacheKind.COVERS), result.failed)
        assertNull(result.usage.totalBytes)
        assertFalse(result.usage.bytes.containsKey(CacheKind.COVERS))
        assertEquals(10L, result.usage.bytes[CacheKind.LYRICS])
        assertEquals(Long.MAX_VALUE, CacheUsage(CacheKind.entries.associateWith { Long.MAX_VALUE }).totalBytes)
    }

    @Test fun clearWaitsForQueryAndCancellationReleasesBusyStateAndLock() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = Store(5)
        var first = true
        val blocking = object : CacheStorage {
            override suspend fun sizeBytes(): Long {
                if (first) { first = false; entered.complete(Unit); release.await() }
                return store.sizeBytes()
            }
            override suspend fun clear() = store.clear()
        }
        val cache = CacheMaintenance(CacheKind.entries.associateWith { blocking })
        val query = launch { cache.refresh() }
        entered.await()
        assertTrue(cache.state.value.busy)
        val clear = async { cache.clear() }
        assertEquals(0, store.clears)
        query.cancelAndJoin()
        assertTrue(clear.await().succeeded)
        assertEquals(3, store.clears)
        assertEquals(0L, cache.state.value.result!!.usage.totalBytes)
        assertFalse(cache.state.value.busy)
    }
}
