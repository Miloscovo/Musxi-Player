package tech.musxi.core.lyrics

import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibraryCacheIdentity
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.Page
import tech.musxi.core.library.SongSummary

data class LyricsMatchState(
    val originalKey: MediaKey? = null,
    val candidates: List<SongSummary> = emptyList(),
    val previewKey: MediaKey? = null,
    val preview: ParsedLyrics? = null,
    val binding: LyricsBinding? = null,
    val searching: Boolean = false,
    val previewing: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val loading: Boolean get() = searching || previewing || saving
}

/**
 * Main-thread controller for manual online-lyrics matching. Every asynchronous callback is fenced
 * by a request revision, the original account attempt and the candidate account attempt.
 */
class LyricsMatchBrowser(
    private val identity: (Platform) -> LibraryCacheIdentity?,
    private val search: (Platform, String, Int, (Result<Page<SongSummary>>) -> Unit) -> Unit,
    private val previewFetch: (MediaKey, () -> Boolean, (Result<ParsedLyrics>) -> Unit) -> Unit,
    private val bindingAccess: (
        MediaKey,
        LibraryCacheIdentity,
        ((LyricsBinding?) -> LyricsBinding?)?,
        (Result<LyricsBinding?>) -> Unit,
    ) -> Unit,
    private val onBindingChanged: (MediaKey, LyricsBinding?) -> Unit = { _, _ -> },
) {
    private data class Target(val key: MediaKey, val owner: LibraryCacheIdentity)
    private data class CandidateFence(val platform: Platform, val owner: LibraryCacheIdentity)

    private val gate = Any()
    private val mutable = MutableStateFlow(LyricsMatchState())
    val state = mutable.asStateFlow()
    private var revision = 0L
    private var target: Target? = null

    fun follow(original: MediaKey) {
        val owner = verifiedIdentity(original.platform)
        val token: Long
        val current: Target
        if (owner == null) {
            synchronized(gate) {
                ++revision
                target = null
                mutable.value = LyricsMatchState(originalKey = original, error = ACCOUNT_UNAVAILABLE)
            }
            return
        }
        synchronized(gate) {
            current = target?.takeIf { it.key == original && it.owner == owner }
                ?: Target(original, owner)
            if (target == current) return
            target = current
            token = ++revision
            mutable.value = LyricsMatchState(originalKey = original)
        }

        val delivered = AtomicBoolean(false)
        val callback: (Result<LyricsBinding?>) -> Unit = callback@ { response ->
            if (!delivered.compareAndSet(false, true)) return@callback
            val binding = synchronized(gate) {
                if (!current(token, current, null)) return@callback
                response.fold(
                    onSuccess = { saved ->
                        if (saved != null && saved.originalKey != original) {
                            mutable.value = mutable.value.copy(error = STORAGE)
                            null
                        } else {
                            mutable.value = mutable.value.copy(binding = saved)
                            saved
                        }
                    },
                    onFailure = {
                        mutable.value = mutable.value.copy(error = ACCESS_UNAVAILABLE)
                        null
                    },
                )
            }
            val candidate = binding?.candidateKey ?: return@callback
            synchronized(gate) {
                if (!current(token, current, null)) return@callback
                if (!candidateMatches(binding)) {
                    mutable.value = mutable.value.copy(error = ACCOUNT_CHANGED)
                    return@callback
                }
                preview(candidate)
            }
        }
        try {
            bindingAccess(original, owner, null, callback)
        } catch (_: Throwable) {
            if (!delivered.compareAndSet(false, true)) return
            synchronized(gate) {
                if (current(token, current, null)) mutable.value = mutable.value.copy(error = ACCESS_UNAVAILABLE)
            }
        }
    }

    fun search(platform: Platform, keywords: String, page: Int) {
        require(page in 1..10)
        require(keywords.isNotBlank() && keywords.length <= 200)
        val original: Target
        val candidateOwner: CandidateFence
        val token: Long
        synchronized(gate) {
            original = target ?: run {
                mutable.value = mutable.value.copy(error = NO_ORIGINAL)
                return
            }
            if (!originalStillCurrent(original)) {
                mutable.value = mutable.value.copy(error = ACCOUNT_CHANGED)
                return
            }
            candidateOwner = verifiedIdentity(platform)?.let { CandidateFence(platform, it) } ?: run {
                mutable.value = mutable.value.copy(error = ACCOUNT_UNAVAILABLE)
                return
            }
            token = ++revision
            mutable.value = mutable.value.copy(
                candidates = emptyList(),
                previewKey = null,
                preview = null,
                searching = true,
                previewing = false,
                saving = false,
                error = null,
            )
        }

        val delivered = AtomicBoolean(false)
        val callback: (Result<Page<SongSummary>>) -> Unit = callback@ { response ->
            if (!delivered.compareAndSet(false, true)) return@callback
            synchronized(gate) {
                if (!current(token, original, candidateOwner)) return@callback
                response.fold(
                    onSuccess = { pageResult ->
                        val items = Collections.unmodifiableList(
                            pageResult.items.filter { it.key.platform == platform }.toList()
                        )
                        mutable.value = mutable.value.copy(
                            candidates = items,
                            searching = false,
                            error = null,
                        )
                    },
                    onFailure = {
                        mutable.value = mutable.value.copy(
                            searching = false,
                            error = safeNetworkError(it),
                        )
                    },
                )
            }
        }
        try {
            search(platform, keywords.trim(), page, callback)
        } catch (_: Throwable) {
            if (!delivered.compareAndSet(false, true)) return
            synchronized(gate) {
                if (current(token, original, candidateOwner)) {
                    mutable.value = mutable.value.copy(searching = false, error = UNAVAILABLE)
                }
            }
        }
    }

    fun preview(candidate: MediaKey) {
        val original: Target
        val candidateOwner: CandidateFence
        val token: Long
        synchronized(gate) {
            original = target ?: run {
                mutable.value = mutable.value.copy(error = NO_ORIGINAL)
                return
            }
            if (!originalStillCurrent(original)) {
                mutable.value = mutable.value.copy(error = ACCOUNT_CHANGED)
                return
            }
            candidateOwner = verifiedIdentity(candidate.platform)
                ?.let { CandidateFence(candidate.platform, it) }
                ?: run {
                mutable.value = mutable.value.copy(error = ACCOUNT_UNAVAILABLE)
                return
            }
            token = ++revision
            mutable.value = mutable.value.copy(
                previewKey = candidate,
                preview = null,
                searching = false,
                previewing = true,
                saving = false,
                error = null,
            )
        }

        val delivered = AtomicBoolean(false)
        fun valid() = synchronized(gate) {
            revision == token && target == original &&
                verifiedIdentity(original.key.platform)?.sameAttempt(original.owner) == true &&
                verifiedIdentity(candidate.platform)?.sameAttempt(candidateOwner.owner) == true
        }
        val callback: (Result<ParsedLyrics>) -> Unit = callback@ { response ->
            if (!delivered.compareAndSet(false, true) || !valid()) return@callback
            synchronized(gate) {
                if (!current(token, original, candidateOwner)) return@callback
                response.onSuccess { parsed ->
                    mutable.value = mutable.value.copy(preview = parsed, previewing = false, error = null)
                }.onFailure {
                    mutable.value = mutable.value.copy(
                        previewing = false,
                        error = safeNetworkError(it),
                    )
                }
            }
        }
        try {
            previewFetch(candidate, ::valid, callback)
        } catch (_: Throwable) {
            if (!delivered.compareAndSet(false, true)) return
            synchronized(gate) {
                if (current(token, original, candidateOwner)) {
                    mutable.value = mutable.value.copy(previewing = false, error = UNAVAILABLE)
                }
            }
        }
    }

    fun bind(original: MediaKey, candidate: MediaKey, offsetMs: Long) {
        if (offsetMs !in -LyricsBinding.MAX_OFFSET_MS..LyricsBinding.MAX_OFFSET_MS) {
            synchronized(gate) { mutable.value = mutable.value.copy(error = INVALID_OFFSET) }
            return
        }
        val currentTarget: Target
        val candidateOwner: CandidateFence
        val binding: LyricsBinding
        val token: Long
        synchronized(gate) {
            currentTarget = target ?: run {
                mutable.value = mutable.value.copy(error = NO_ORIGINAL)
                return
            }
            if (currentTarget.key != original || !originalStillCurrent(currentTarget)) {
                mutable.value = mutable.value.copy(error = ACCOUNT_CHANGED)
                return
            }
            candidateOwner = verifiedIdentity(candidate.platform)
                ?.let { CandidateFence(candidate.platform, it) }
                ?: run {
                mutable.value = mutable.value.copy(error = ACCOUNT_UNAVAILABLE)
                return
            }
            binding = LyricsBinding(
                original,
                candidate,
                offsetMs,
                candidateOwner.owner.owner,
                candidateOwner.owner.generation,
            )
            token = ++revision
            mutable.value = mutable.value.copy(
                searching = false,
                previewing = false,
                saving = true,
                error = null,
            )
        }

        val delivered = AtomicBoolean(false)
        val callback: (Result<LyricsBinding?>) -> Unit = callback@ { response ->
            if (!delivered.compareAndSet(false, true)) return@callback
            var changed = false
            synchronized(gate) {
                if (!current(token, currentTarget, candidateOwner)) return@callback
                response.fold(
                    onSuccess = {
                        mutable.value = mutable.value.copy(binding = binding, saving = false, error = null)
                        changed = true
                    },
                    onFailure = {
                        mutable.value = mutable.value.copy(saving = false, error = ACCESS_UNAVAILABLE)
                    },
                )
            }
            if (changed) onBindingChanged(original, binding)
        }
        try {
            bindingAccess(original, currentTarget.owner, { binding }, callback)
        } catch (_: Throwable) {
            if (!delivered.compareAndSet(false, true)) return
            synchronized(gate) {
                if (current(token, currentTarget, candidateOwner)) {
                    mutable.value = mutable.value.copy(saving = false, error = ACCESS_UNAVAILABLE)
                }
            }
        }
    }

    fun reset(original: MediaKey) {
        val currentTarget: Target
        val token: Long
        synchronized(gate) {
            currentTarget = target ?: run {
                mutable.value = mutable.value.copy(error = NO_ORIGINAL)
                return
            }
            if (currentTarget.key != original || !originalStillCurrent(currentTarget)) {
                mutable.value = mutable.value.copy(error = ACCOUNT_CHANGED)
                return
            }
            token = ++revision
            mutable.value = mutable.value.copy(
                previewKey = null,
                preview = null,
                searching = false,
                previewing = false,
                saving = true,
                error = null,
            )
        }

        val delivered = AtomicBoolean(false)
        val callback: (Result<LyricsBinding?>) -> Unit = callback@ { response ->
            if (!delivered.compareAndSet(false, true)) return@callback
            var changed = false
            synchronized(gate) {
                if (!current(token, currentTarget, null)) return@callback
                response.fold(
                    onSuccess = {
                        mutable.value = mutable.value.copy(
                            binding = null,
                            previewKey = null,
                            preview = null,
                            saving = false,
                            error = null,
                        )
                        changed = true
                    },
                    onFailure = {
                        mutable.value = mutable.value.copy(saving = false, error = ACCESS_UNAVAILABLE)
                    },
                )
            }
            if (changed) onBindingChanged(original, null)
        }
        try {
            bindingAccess(original, currentTarget.owner, { null }, callback)
        } catch (_: Throwable) {
            if (!delivered.compareAndSet(false, true)) return
            synchronized(gate) {
                if (current(token, currentTarget, null)) {
                    mutable.value = mutable.value.copy(saving = false, error = ACCESS_UNAVAILABLE)
                }
            }
        }
    }

    /** Cancel pending work. A new account signal may immediately start a fenced binding read. */
    fun accountChanged() {
        val original = synchronized(gate) {
            ++revision
            val current = target ?: return@synchronized null
            mutable.value = mutable.value.copy(
                candidates = emptyList(),
                previewKey = null,
                preview = null,
                binding = null,
                searching = false,
                previewing = false,
                saving = false,
                error = null,
            )
            target = null
            current.key
        }
        if (original != null) follow(original)
    }

    fun cancel() {
        synchronized(gate) {
            ++revision
            target = null
            mutable.value = LyricsMatchState()
        }
    }

    private fun originalStillCurrent(value: Target): Boolean =
        verifiedIdentity(value.key.platform)?.sameAttempt(value.owner) == true

    private fun candidateMatches(value: LyricsBinding): Boolean {
        val owner = verifiedIdentity(value.candidateKey.platform)
        return owner != null && owner.owner == value.candidateOwner
    }

    private fun current(
        token: Long,
        expectedOriginal: Target,
        expectedCandidate: CandidateFence?,
    ): Boolean {
        if (revision != token || target != expectedOriginal || !originalStillCurrent(expectedOriginal)) {
            return false
        }
        if (expectedCandidate == null) return true
        val owner = verifiedIdentity(expectedCandidate.platform)
        return owner != null && owner.sameAttempt(expectedCandidate.owner)
    }

    private fun verifiedIdentity(platform: Platform): LibraryCacheIdentity? =
        identity(platform)?.takeIf { it.verified && SAFE_OWNER.matches(it.owner) }

    private fun safeNetworkError(error: Throwable): String {
        val category = (error as? LibraryPageFailure)?.category
        return category?.takeIf { it in SAFE_CATEGORIES } ?: UNAVAILABLE
    }

    companion object {
        private const val ACCOUNT_CHANGED = "account_changed"
        private const val ACCOUNT_UNAVAILABLE = "account_unavailable"
        private const val ACCESS_UNAVAILABLE = "storage"
        private const val INVALID_OFFSET = "invalid_offset"
        private const val NO_ORIGINAL = "no_original"
        private const val STORAGE = "storage"
        private const val UNAVAILABLE = "unavailable"
        private val SAFE_OWNER = Regex("[0-9a-f]{64}")
        private val SAFE_CATEGORIES = setOf(
            "account_changed",
            "expired",
            "http",
            "limit",
            "network",
            "permission",
            "protocol",
            "rate",
            "risk",
            "timeout",
            "unavailable",
        )
    }
}
