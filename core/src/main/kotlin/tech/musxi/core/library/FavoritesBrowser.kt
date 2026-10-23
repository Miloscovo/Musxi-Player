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

data class FavoritesState(
    val keys: Set<MediaKey> = emptySet(),
    val synced: Boolean = false,
    val loading: Boolean = false,
    val pending: MediaKey? = null,
    val canWrite: Boolean = false,
    val error: String? = null,
    val syncedAtEpochMs: Long = 0,
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
) {
    private val mutable = MutableStateFlow(immutableMap(Platform.entries.associateWith { FavoritesState() }))
    val state = mutable.asStateFlow()

    private val revisions = Platform.entries.associateWith { AtomicLong(0) }
    private val observedIdentities = mutableMapOf<Platform, LibraryCacheIdentity?>()

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

    fun setLiked(song: SongSummary, liked: Boolean) {
        val platform = song.key.platform
        val currentIdentity = identityOrInvalidate(platform) ?: return
        if (!currentIdentity.verified) {
            setState(platform, state(platform).copy(
                loading = false,
                pending = null,
                error = "account_unverified",
            ))
            return
        }

        val before = state(platform)
        if (before.loading || before.pending != null) {
            setState(platform, before.copy(error = "busy"))
            return
        }
        if (!before.synced) {
            setState(platform, before.copy(error = "not_synced"))
            return
        }
        if (!before.canWrite) {
            setState(platform, before.copy(error = "permission"))
            return
        }
        if ((song.key in before.keys) == liked) return

        val token = nextRevision(platform)
        setState(platform, before.copy(loading = true, pending = song.key, error = null))
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
            try {
                if (identity(platform) == currentIdentity) {
                    onVerifiedChange(platform, currentIdentity)
                }
            } catch (_: Throwable) {
                // A listener cannot turn a verified collection into an error state.
            }
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
        ))
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
        setState(platform, FavoritesState(error = error))
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
