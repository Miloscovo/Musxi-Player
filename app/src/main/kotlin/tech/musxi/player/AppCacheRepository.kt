package tech.musxi.player

import coil3.SingletonImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import tech.musxi.core.cache.CacheKind
import tech.musxi.core.cache.CacheMaintenance
import tech.musxi.core.cache.CacheOperationResult
import tech.musxi.core.cache.CacheStorage

/** Settings-page facade. Callbacks and StateFlow delivery run on main, disk work runs off main. */
class AppCacheRepository internal constructor(
    private val app: MusxiApplication,
    private val beforeClear: (CacheKind) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val maintenance = CacheMaintenance(CacheKind.entries.associateWith { kind ->
        object : CacheStorage {
            override suspend fun sizeBytes(): Long = if (kind == CacheKind.COVERS) {
                withContext(Dispatchers.IO) { SingletonImageLoader.get(app).diskCache?.size ?: 0L }
            } else suspendCancellableCoroutine { continuation ->
                app.accounts.cachedContentBytes(kind) { continuation.resumeWith(it) }
            }

            override suspend fun clear() {
                beforeClear(kind)
                if (kind == CacheKind.COVERS) {
                    // Use Coil's live cache API so the index and open image requests stay valid.
                    val loader = SingletonImageLoader.get(app)
                    loader.memoryCache?.clear()
                    withContext(Dispatchers.IO) { loader.diskCache?.clear() }
                } else suspendCancellableCoroutine { continuation ->
                    app.accounts.clearCachedContent(kind) { continuation.resumeWith(it) }
                }
            }
        }
    })
    val state = maintenance.state

    fun refresh(result: (CacheOperationResult) -> Unit = {}) = scope.launch { result(maintenance.refresh()) }
    fun clear(result: (CacheOperationResult) -> Unit = {}) = scope.launch { result(maintenance.clear()) }
}
