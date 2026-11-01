package tech.musxi.core.library

import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.musxi.core.account.Platform

data class FavoritesSnapshot(val keys: Set<MediaKey>, val canWrite: Boolean = true)

fun interface FavoritesFetch {
    fun fetch(
        platform: Platform,
        isCurrent: () -> Boolean,
        result: (Result<FavoritesSnapshot>) -> Unit,
    )
}

fun interface FavoritesWrite {
    fun setLiked(
        song: SongSummary,
        liked: Boolean,
        isCurrent: () -> Boolean,
        result: (Result<Unit>) -> Unit,
    )
}

/** A fresh check of this song only. A failure never means "not liked". */
fun interface FavoritesVerify {
    fun verify(song: SongSummary, liked: Boolean, isCurrent: () -> Boolean, result: (Result<Boolean>) -> Unit)
}

data class FavoritesState(
    val keys: Set<MediaKey> = emptySet(),
    val synced: Boolean = false,
    val loading: Boolean = false,
    val pending: MediaKey? = null,
    val canWrite: Boolean = false,
    val error: String? = null,
    val syncedAtEpochMs: Long = 0,
    val queuedKeys: List<MediaKey> = emptyList(),
)

/**
 * Network synchronization state for one favorites collection per platform.
 * Commands and result callbacks run on the main thread. Network workers may call isCurrent.
 */
class FavoritesBrowser(
    private val identity: (Platform) -> LibraryCacheIdentity?,
    private val fetch: FavoritesFetch,
    private val write: FavoritesWrite,
    private val now: () -> Long = System::currentTimeMillis,
    private val onVerifiedChange: (Platform, LibraryCacheIdentity) -> Unit = { _, _ -> },
    private val verify: FavoritesVerify? = null,
) {
    private val mutable = MutableStateFlow(immutableMap(Platform.entries.associateWith { FavoritesState() }))
    val state = mutable.asStateFlow()

    private val revisions = Platform.entries.associateWith { AtomicLong(0) }
    private val observedIdentities = mutableMapOf<Platform, LibraryCacheIdentity?>()
    private data class Intent(val song: SongSummary, val liked: Boolean, val result: (Result<Unit>) -> Unit)
    private val queues = Platform.entries.associateWith { ArrayDeque<Intent>() }
    private val active = mutableMapOf<Platform, Intent>()
    private val changedOwners = mutableMapOf<Platform, LibraryCacheIdentity>()
    private val recovering = mutableSetOf<Platform>()

    fun refresh(platform: Platform) {
        val currentIdentity = identityOrInvalidate(platform) ?: return
        if (!currentIdentity.verified) {
            setState(platform, state(platform).copy(
                loading = false,
                pending = null,
                error = "account_unverified",
            ))
            return
        }
        if (state(platform).loading || state(platform).pending != null) {
            setState(platform, state(platform).copy(error = "busy"))
            return
        }

        val token = nextRevision(platform)
        setState(platform, state(platform).copy(loading = true, pending = null, error = null))
        val done = AtomicBoolean()
        val callback: (Result<FavoritesSnapshot>) -> Unit = { response ->
            if (done.compareAndSet(false, true) && current(platform, token, currentIdentity)) {
                response.fold(
                    { snapshot -> finishRefresh(platform, token, currentIdentity, snapshot) },
                    { failure -> finishRefreshFailure(platform, token, currentIdentity, failureText(failure)) },
                )
            }
        }
        try {
            fetch.fetch(platform, currentCheck(platform, token, currentIdentity), callback)
        } catch (_: Throwable) {
            callback(Result.failure(LibraryPageFailure("unclassified")))
        }
    }

    fun setLiked(song: SongSummary, liked: Boolean) = enqueueLiked(song, liked)

    /** Accepted intents execute once, in order, under the same account identity. */
    fun enqueueLiked(song: SongSummary, liked: Boolean, result: (Result<Unit>) -> Unit = {}) {
        val platform = song.key.platform
        val currentIdentity = identityOrInvalidate(platform) ?: run {
            deliver(result, Result.failure(LibraryPageFailure("account_unavailable"))); return
        }
        if (!currentIdentity.verified) {
            reject(platform, result, "account_unverified")
            return
        }
        val before = state(platform)
        if (!before.synced && platform !in recovering) {
            reject(platform, result, "not_synced")
            return
        }
        if (!before.canWrite) {
            reject(platform, result, "permission")
            return
        }
        val queue = queues.getValue(platform)
        if (queue.size >= 128) { reject(platform, result, "limit"); return }
        queue.addLast(Intent(song.copy(artists = song.artists.toList()), liked, result))
        setState(platform, before.copy(error = null, queuedKeys = queue.map { it.song.key }))
        drain(platform)
    }

    private fun drain(platform: Platform) {
        val before = state(platform)
        if (before.loading || active.containsKey(platform) || platform in recovering) return
        if (!before.synced || !before.canWrite) { failQueue(platform, before.error ?: "not_synced"); return }
        val intent = queues.getValue(platform).removeFirstOrNull() ?: return
        val currentIdentity = identityOrInvalidate(platform) ?: run {
            deliver(intent.result, Result.failure(LibraryPageFailure("account_changed"))); return
        }
        val song = intent.song
        val liked = intent.liked
        if (liked && song.key !in before.keys && before.keys.size >= MAX_KEYS) {
            setState(platform, state(platform).copy(error = "limit", queuedKeys = queues.getValue(platform).map { it.song.key }))
            deliver(intent.result, Result.failure(LibraryPageFailure("limit"))); drain(platform); return
        }
        if ((song.key in before.keys) == liked) {
            setState(platform, state(platform).copy(queuedKeys = queues.getValue(platform).map { it.song.key }))
            deliver(intent.result, Result.success(Unit)); drain(platform); return
        }
        active[platform] = intent

        val token = nextRevision(platform)
        setState(platform, before.copy(loading = true, pending = song.key, error = null,
            queuedKeys = queues.getValue(platform).map { it.song.key }))
        val done = AtomicBoolean()
        val callback: (Result<Unit>) -> Unit = callback@{ response ->
            if (!done.compareAndSet(false, true) ||
                !current(platform, token, currentIdentity)) return@callback
            response.fold(
                { startWriteVerification(platform, token, currentIdentity, song, liked) },
                { failure ->
                    revisions.getValue(platform).incrementAndGet()
                    setState(platform, state(platform).copy(
                        synced = false,
                        loading = false,
                        pending = null,
                        error = failureText(failure),
                    ))
                    settle(platform, Result.failure(LibraryPageFailure(failureText(failure))), reconcile = verify != null)
                    if (verify != null) refresh(platform)
                },
            )
        }
        try {
            write.setLiked(song, liked, currentCheck(platform, token, currentIdentity), callback)
        } catch (_: Throwable) {
            callback(Result.failure(LibraryPageFailure("unclassified")))
        }
    }

    /** Clears every collection whose attempt or verification status changed. */
    fun accountChanged() {
        Platform.entries.forEach { platform ->
            val latest = try {
                identity(platform)
            } catch (_: Throwable) {
                invalidate(platform, "identity")
                observedIdentities.remove(platform)
                return@forEach
            }
            observe(platform, latest)
        }
    }

    private fun startWriteVerification(
        platform: Platform,
        token: Long,
        currentIdentity: LibraryCacheIdentity,
        song: SongSummary,
        liked: Boolean,
    ) {
        val single = verify
        if (single != null) {
            val done = AtomicBoolean()
            val callback: (Result<Boolean>) -> Unit = callback@{ response ->
                if (!done.compareAndSet(false, true) || !current(platform, token, currentIdentity)) return@callback
                if (response.getOrNull() != liked) {
                    finishUnverifiedWrite(platform, token, currentIdentity)
                    // Only an inconclusive targeted check needs a full reconciliation.
                    refresh(platform)
                    return@callback
                }
                val keys = LinkedHashSet(state(platform).keys)
                if (liked) keys.add(song.key) else keys.remove(song.key)
                revisions.getValue(platform).incrementAndGet()
                setState(platform, state(platform).copy(keys = Collections.unmodifiableSet(keys), synced = true,
                    loading = false, pending = null, error = null, syncedAtEpochMs = timestamp()))
                notifyVerified(platform, currentIdentity)
                settle(platform, Result.success(Unit))
            }
            try { single.verify(song, liked, currentCheck(platform, token, currentIdentity), callback) }
            catch (_: Throwable) { callback(Result.failure(LibraryPageFailure("unclassified"))) }
            return
        }
        val done = AtomicBoolean()
        val callback: (Result<FavoritesSnapshot>) -> Unit = callback@{ response ->
            if (!done.compareAndSet(false, true) ||
                !current(platform, token, currentIdentity)) return@callback
            val snapshot = response.getOrNull()
            val keys = snapshot?.let { validatedKeys(platform, it) }
            if (keys == null || (song.key in keys) != liked) {
                finishUnverifiedWrite(platform, token, currentIdentity)
                return@callback
            }
            revisions.getValue(platform).incrementAndGet()
            setState(platform, FavoritesState(
                keys = keys,
                synced = true,
                loading = false,
                pending = null,
                canWrite = snapshot.canWrite,
                error = null,
                syncedAtEpochMs = timestamp(),
            ))
            notifyVerified(platform, currentIdentity)
            settle(platform, Result.success(Unit))
        }
        try {
            fetch.fetch(platform, currentCheck(platform, token, currentIdentity), callback)
        } catch (_: Throwable) {
            callback(Result.failure(LibraryPageFailure("unclassified")))
        }
    }

    private fun finishRefresh(
        platform: Platform,
        token: Long,
        currentIdentity: LibraryCacheIdentity,
        snapshot: FavoritesSnapshot,
    ) {
        val keys = try {
            validatedKeys(platform, snapshot)
        } catch (_: Throwable) {
            null
        }
        if (keys == null) {
            val error = try {
                when {
                    snapshot.keys.size > MAX_KEYS -> "limit"
                    else -> "protocol"
                }
            } catch (_: Throwable) {
                "protocol"
            }
            finishRefreshFailure(platform, token, currentIdentity, error)
            return
        }
        revisions.getValue(platform).incrementAndGet()
        setState(platform, FavoritesState(
            keys = keys,
            synced = true,
            loading = false,
            pending = null,
            canWrite = snapshot.canWrite,
            error = null,
            syncedAtEpochMs = timestamp(),
            queuedKeys = queues.getValue(platform).map { it.song.key },
        ))
        recovering.remove(platform)
        drain(platform)
    }

    private fun finishRefreshFailure(
        platform: Platform,
        token: Long,
        currentIdentity: LibraryCacheIdentity,
        error: String,
    ) {
        if (!current(platform, token, currentIdentity)) return
        revisions.getValue(platform).incrementAndGet()
        setState(platform, state(platform).copy(loading = false, pending = null, error = error))
        recovering.remove(platform)
        failQueue(platform, error)
        flushVerified(platform)
    }

    private fun finishUnverifiedWrite(
        platform: Platform,
        token: Long,
        currentIdentity: LibraryCacheIdentity,
    ) {
        if (!current(platform, token, currentIdentity)) return
        revisions.getValue(platform).incrementAndGet()
        setState(platform, state(platform).copy(
            synced = false,
            loading = false,
            pending = null,
            error = "write_unverified",
        ))
        settle(platform, Result.failure(LibraryPageFailure("write_unverified")), reconcile = verify != null)
    }

    private fun notifyVerified(platform: Platform, owner: LibraryCacheIdentity) {
        changedOwners[platform] = owner
    }

    private fun flushVerified(platform: Platform) {
        if (active.containsKey(platform) || queues.getValue(platform).isNotEmpty()) return
        val owner = changedOwners.remove(platform) ?: return
        try { if (identity(platform) == owner) onVerifiedChange(platform, owner) } catch (_: Throwable) { }
    }

    private fun settle(platform: Platform, response: Result<Unit>, reconcile: Boolean = false) {
        val intent = active.remove(platform)
        if (reconcile) recovering.add(platform)
        else if (response.isFailure) failQueue(platform, state(platform).error ?: "write_unverified")
        if (intent != null) deliver(intent.result, response)
        drain(platform)
        flushVerified(platform)
    }

    private fun failQueue(platform: Platform, category: String) {
        val waiting = queues.getValue(platform).toList()
        queues.getValue(platform).clear()
        setState(platform, state(platform).copy(queuedKeys = emptyList()))
        waiting.forEach { deliver(it.result, Result.failure(LibraryPageFailure(category))) }
    }

    private fun reject(platform: Platform, callback: (Result<Unit>) -> Unit, error: String) {
        setState(platform, state(platform).copy(error = error))
        deliver(callback, Result.failure(LibraryPageFailure(error)))
    }

    private fun deliver(callback: (Result<Unit>) -> Unit, response: Result<Unit>) {
        try { callback(response) } catch (_: Throwable) { }
    }

    private fun validatedKeys(platform: Platform, snapshot: FavoritesSnapshot): Set<MediaKey>? {
        val source = snapshot.keys
        if (source.size > MAX_KEYS || source.any { it.platform != platform }) return null
        return Collections.unmodifiableSet(LinkedHashSet(source))
    }

    private fun identityOrInvalidate(platform: Platform): LibraryCacheIdentity? {
        val latest = try {
            identity(platform)
        } catch (_: Throwable) {
            invalidate(platform, "identity")
            observedIdentities.remove(platform)
            return null
        }
        observe(platform, latest)
        if (latest == null) setState(platform, FavoritesState(error = "account_unavailable"))
        return latest
    }

    private fun observe(platform: Platform, latest: LibraryCacheIdentity?) {
        val wasObserved = observedIdentities.containsKey(platform)
        val previous = observedIdentities[platform]
        if (latest == null && (!wasObserved || previous != null)) {
            invalidate(platform, "account_unavailable")
        } else if (wasObserved && previous != latest) {
            val error = when {
                latest?.verified == false -> "account_unverified"
                else -> "account_changed"
            }
            invalidate(platform, error)
        }
        observedIdentities[platform] = latest
    }

    private fun invalidate(platform: Platform, error: String) {
        nextRevision(platform)
        changedOwners.remove(platform)
        recovering.remove(platform)
        setState(platform, FavoritesState(error = error))
        active.remove(platform)?.let { deliver(it.result, Result.failure(LibraryPageFailure(error))) }
        failQueue(platform, error)
    }

    private fun current(
        platform: Platform,
        token: Long,
        expectedIdentity: LibraryCacheIdentity,
    ): Boolean {
        if (revisions.getValue(platform).get() != token) return false
        val latest = try {
            identity(platform)
        } catch (_: Throwable) {
            invalidate(platform, "identity")
            observedIdentities.remove(platform)
            return false
        }
        if (latest == expectedIdentity) return true
        val error = when {
            latest == null -> "account_unavailable"
            !latest.verified -> "account_unverified"
            else -> "account_changed"
        }
        invalidate(platform, error)
        observedIdentities[platform] = latest
        return false
    }

    private fun currentCheck(
        platform: Platform,
        token: Long,
        expectedIdentity: LibraryCacheIdentity,
    ): () -> Boolean = {
        revisions.getValue(platform).get() == token && try {
            identity(platform) == expectedIdentity
        } catch (_: Throwable) {
            false
        }
    }

    private fun nextRevision(platform: Platform): Long = revisions.getValue(platform).incrementAndGet()

    private fun state(platform: Platform): FavoritesState = mutable.value[platform] ?: FavoritesState()

    private fun setState(platform: Platform, value: FavoritesState) {
        mutable.value = immutableMap(mutable.value.toMutableMap().apply { put(platform, value) })
    }

    private fun timestamp(): Long = try {
        now()
    } catch (_: Throwable) {
        0L
    }

    private fun failureText(failure: Throwable): String =
        (failure as? LibraryPageFailure)?.category?.takeIf { it in SAFE_ERRORS } ?: "unclassified"

    private fun immutableMap(value: Map<Platform, FavoritesState>): Map<Platform, FavoritesState> =
        Collections.unmodifiableMap(LinkedHashMap(value))

    companion object {
        const val MAX_KEYS = 20_000

        private val SAFE_ERRORS = setOf(
            "expired", "network", "risk", "rate", "protocol", "identity", "limit",
            "incomplete", "permission", "unavailable", "endpoint", "platform", "http",
            "timeout", "request", "account_changed", "account_unavailable",
            "account_unverified", "write_unverified", "busy", "not_synced",
        )
    }
}
