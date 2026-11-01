package tech.musxi.core.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CacheKind { COVERS, LYRICS, LIBRARY_PAGES }

/** Persistent, reclaimable bytes only. A missing measurement is unknown, never zero. */
data class CacheUsage(val bytes: Map<CacheKind, Long>) {
    val totalBytes: Long? get() = if (bytes.keys.containsAll(CacheKind.entries))
        bytes.values.fold(0L) { sum, next -> if (next > Long.MAX_VALUE - sum) Long.MAX_VALUE else sum + next }
    else null
}

data class CacheOperationResult(val usage: CacheUsage, val failed: Set<CacheKind>) {
    val succeeded: Boolean get() = failed.isEmpty()
}

data class CacheMaintenanceState(
    val busy: Boolean = false,
    val clearing: Boolean = false,
    val result: CacheOperationResult? = null,
)

/** Adapters supply only their cache, never a general files directory or account cleanup command. */
interface CacheStorage {
    suspend fun sizeBytes(): Long
    suspend fun clear()
}

/** Serializes commands and reports partial failures without exposing storage paths. */
class CacheMaintenance(private val stores: Map<CacheKind, CacheStorage>) {
    init { require(stores.keys == CacheKind.entries.toSet()) }
    private val commands = Mutex()
    private val mutable = MutableStateFlow(CacheMaintenanceState())
    val state = mutable.asStateFlow()

    suspend fun refresh(): CacheOperationResult = operate(clear = false)
    suspend fun clear(): CacheOperationResult = operate(clear = true)

    private suspend fun operate(clear: Boolean): CacheOperationResult = commands.withLock {
        mutable.value = mutable.value.copy(busy = true, clearing = clear)
        try {
            val failed = mutableSetOf<CacheKind>()
            if (clear) stores.forEach { (kind, store) ->
                try { store.clear() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed += kind }
            }
            val bytes = mutableMapOf<CacheKind, Long>()
            stores.forEach { (kind, store) ->
                try { bytes[kind] = store.sizeBytes().also { require(it >= 0) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed += kind }
            }
            CacheOperationResult(CacheUsage(bytes.toMap()), failed.toSet()).also {
                mutable.value = CacheMaintenanceState(result = it)
            }
        } finally {
            mutable.value = mutable.value.copy(busy = false, clearing = false)
        }
    }
}
