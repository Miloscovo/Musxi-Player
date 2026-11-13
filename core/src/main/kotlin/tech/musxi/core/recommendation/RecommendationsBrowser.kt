package tech.musxi.core.recommendation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibraryCacheIdentity
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.LibrarySource
import tech.musxi.core.library.PlaybackQueue

data class RecommendationState(
    val kind: RecommendationKind,
    val snapshot: RecommendationSnapshot? = null,
    val source: LibrarySource? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val accountVerified: Boolean = false,
    val stale: Boolean = false,
    val error: String? = null,
    val storageError: Boolean = false,
    val retryAfterMs: Long = 0,
    val revision: Long = 0,
)

/** Main-executor entrypoints/delivery; cacheAccess runs only on the supplied serialized disk lane. */
class RecommendationsBrowser(
    private val identity: (Platform) -> LibraryCacheIdentity?,
    private val cacheAccess: (Platform, LibraryCacheIdentity, (RecommendationStore) -> Unit) -> Unit,
    private val fetch: RecommendationFetch,
    private val io: (() -> Unit) -> Unit,
    private val deliver: (() -> Unit) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000L },
    private val isEnabled: () -> Boolean = { true },
    private val clearCache: () -> Unit = {},
) {
    private val gate = Any()
    private var enabled = isEnabled()
    private val tokens = Platform.entries.associateWith { 0L }.toMutableMap()
    private val owners = mutableMapOf<Platform, LibraryCacheIdentity?>()
    private val attempts = mutableMapOf<Platform, Pair<LibraryCacheIdentity, Long>>()
    private val forceRequested = mutableMapOf<Platform, Boolean>()
    private val mutable = MutableStateFlow(Platform.entries.associateWith { RecommendationState(RecommendationsPolicy.kind(it)) })
    val state = mutable.asStateFlow()

    init {
        // A cold start with the setting off also erases caches, without requiring a UI open.
        if (!enabled) eraseCache()
    }

    /** Called synchronously after settings persistence; never fetches on re-enable. */
    fun setEnabled(value: Boolean) = synchronized(gate) {
        if (enabled == value) return@synchronized
        enabled = value
        if (!value) {
            clearSnapshots()
            // Enqueue before returning: subsequent opens share this lane and follow erasure.
            eraseCache()
        }
    }

    fun open(platform: Platform) = load(platform, force = false)
    fun refresh(platform: Platform) = load(platform, force = true)

    fun cancel(platform: Platform) = synchronized(gate) {
        tokens[platform] = tokens.getValue(platform) + 1
        update(platform) { it.copy(loading = false, refreshing = false) }
    }

    /** Does not fetch. A different owner immediately loses access to the former owner's snapshot. */
    fun accountChanged() = synchronized(gate) {
        if (!allowed()) return@synchronized
        Platform.entries.forEach { platform ->
            val next = identity(platform)
            val old = owners[platform]
            if (next != old) {
                tokens[platform] = tokens.getValue(platform) + 1
                owners[platform] = next
                val state = mutable.value.getValue(platform)
                val retained = if (next != null && old?.owner == next.owner) state.snapshot else null
                update(platform) { RecommendationState(kind = RecommendationsPolicy.kind(platform), snapshot = retained,
                    source = if (retained != null) state.source else null,
                    accountVerified = next?.verified == true,
                    stale = retained?.let { !it.isFresh(now()) || next?.verified != true } ?: false,
                    error = if (next?.verified == true) null else "account_unavailable",
                    revision = state.revision + 1) }
            }
        }
    }

    /** Cache maintenance fences pending reads/writes before the disk lane erases the files. */
    fun clearCachedResults() = synchronized(gate) {
        clearSnapshots()
    }

    private fun clearSnapshots() {
        Platform.entries.forEach { platform ->
            tokens[platform] = tokens.getValue(platform) + 1
            forceRequested.remove(platform)
            update(platform) { RecommendationState(kind = RecommendationsPolicy.kind(platform), accountVerified = allowed() && identity(platform)?.verified == true,
                revision = it.revision + 1) }
        }
    }

    private fun eraseCache() = io {
        val failed = runCatching(clearCache).isFailure
        if (failed) deliver {
            synchronized(gate) {
                Platform.entries.forEach { platform -> update(platform) { it.copy(storageError = true) } }
            }
        }
    }

    private fun allowed(): Boolean = enabled && isEnabled()

    /** Complete account-bound snapshot; validates the revision before any queue can be submitted. */
    fun queue(platform: Platform, startIndex: Int = 0, expectedRevision: Long): Result<PlaybackQueue> = synchronized(gate) {
        runCatching {
            if (!allowed()) fail("recommendations_disabled")
            val current = identity(platform)
            val state = mutable.value.getValue(platform)
            if (current == null || !current.verified || current != owners[platform]) fail("account_unavailable")
            if (expectedRevision != state.revision) fail("stale_revision")
            val snapshot = state.snapshot ?: fail("incomplete")
            if (startIndex !in snapshot.songs.indices) fail("invalid_queue")
            PlaybackQueue(snapshot.songs, startIndex, current.generation)
        }
    }

    /** Keep validation and synchronous queue acceptance atomic with a completed disable. */
    fun submitQueue(platform: Platform, startIndex: Int = 0, expectedRevision: Long,
        submit: (PlaybackQueue) -> Unit): Result<Unit> = synchronized(gate) {
        queue(platform, startIndex, expectedRevision).mapCatching(submit)
    }

    private fun load(platform: Platform, force: Boolean) {
        val expected: LibraryCacheIdentity
        val token: Long
        synchronized(gate) {
            if (!allowed()) return
            accountChanged()
            expected = identity(platform) ?: run {
                update(platform) { it.copy(error = "account_unavailable") }; return
            }
            if (mutable.value.getValue(platform).loading) {
                if (force) forceRequested[platform] = true
                return
            }
            token = tokens.getValue(platform) + 1
            tokens[platform] = token
            forceRequested[platform] = force
            update(platform) { it.copy(loading = true, refreshing = false, error = null, retryAfterMs = 0,
                accountVerified = expected.verified) }
        }
        io {
            var cached: RecommendationSnapshot? = null
            val read = runCatching {
                if (current(platform, expected, token)) cacheAccess(platform, expected) { cached = it.read(expected.owner) }
            }
            deliver {
                synchronized(gate) {
                    if (!current(platform, expected, token)) return@synchronized
                    val prior = mutable.value.getValue(platform)
                    val shown = cached ?: prior.snapshot
                    update(platform) { it.copy(snapshot = shown, source = if (cached != null) LibrarySource.CACHE else it.source,
                        stale = shown?.let { !it.isFresh(now()) || !expected.verified } ?: false,
                        storageError = read.isFailure, revision = if (cached != null) it.revision + 1 else it.revision) }
                    if (!expected.verified) {
                        update(platform) { it.copy(loading = false, error = "account_unavailable") }
                    } else if (forceRequested[platform] != true && shown?.isFresh(now()) == true) {
                        update(platform) { it.copy(loading = false) }
                    } else request(platform, expected, token)
                }
            }
        }
    }

    private fun request(platform: Platform, expected: LibraryCacheIdentity, token: Long) {
        val last = attempts[platform]?.takeIf { it.first == expected }?.second
        val remaining = last?.let { (RecommendationsPolicy.MIN_REQUEST_INTERVAL_MS - (elapsed() - it)).coerceAtLeast(0) } ?: 0
        if (remaining > 0) {
            update(platform) { it.copy(loading = false, error = "rate", retryAfterMs = remaining) }; return
        }
        attempts[platform] = expected to elapsed()
        update(platform) { it.copy(refreshing = true) }
        var received = false
        val callback: (Result<List<tech.musxi.core.library.SongSummary>>) -> Unit = { response ->
            deliver {
                synchronized(gate) {
                    if (!current(platform, expected, token) || received) return@synchronized
                    received = true
                    val parsed = response.mapCatching { RecommendationSnapshot(platform, it, now().coerceAtLeast(0)) }
                    parsed.fold({ snapshot -> save(platform, expected, token, snapshot) }, { error ->
                        update(platform) { it.copy(loading = false, refreshing = false, error = safeError(error)) }
                    })
                }
            }
        }
        try { fetch.fetch(platform, { current(platform, expected, token) }, callback) }
        catch (error: Exception) { callback(Result.failure(error)) }
    }

    private fun save(platform: Platform, expected: LibraryCacheIdentity, token: Long, snapshot: RecommendationSnapshot) {
        io {
            val saved = runCatching {
                if (current(platform, expected, token)) cacheAccess(platform, expected) {
                    if (current(platform, expected, token)) it.write(expected.owner, snapshot)
                }
            }
            deliver {
                synchronized(gate) {
                    if (!current(platform, expected, token)) return@synchronized
                    update(platform) { it.copy(snapshot = snapshot, source = LibrarySource.NETWORK,
                        loading = false, refreshing = false, stale = !snapshot.isFresh(now()), error = null,
                        storageError = saved.isFailure || it.storageError, retryAfterMs = 0, revision = it.revision + 1) }
                }
            }
        }
    }

    private fun current(platform: Platform, expected: LibraryCacheIdentity, token: Long): Boolean = synchronized(gate) {
        allowed() && tokens[platform] == token && owners[platform] == expected && identity(platform) == expected
    }
    private fun update(platform: Platform, transform: (RecommendationState) -> RecommendationState) {
        mutable.value = mutable.value + (platform to transform(mutable.value.getValue(platform)))
    }
    private fun safeError(error: Throwable): String {
        val code = (error as? LibraryPageFailure)?.category
        return code?.takeIf { it in setOf("expired", "network", "risk", "rate", "protocol", "identity", "limit", "platform", "request",
            "cancelled", "permission", "unavailable", "endpoint", "http", "timeout", "account_changed", "account_unavailable") }
            ?: if (error is IllegalArgumentException) "protocol" else "unclassified"
    }
    private fun fail(code: String): Nothing = throw LibraryPageFailure(code)
}
