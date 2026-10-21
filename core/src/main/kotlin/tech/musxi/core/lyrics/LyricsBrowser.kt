package tech.musxi.core.lyrics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibraryCacheIdentity
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.MediaKey

enum class LyricsStatus { IDLE, LOADING, TIMED, PLAIN, EMPTY, ERROR }
data class LyricsState(
    val sourceKey: MediaKey? = null,
    val status: LyricsStatus = LyricsStatus.IDLE,
    val lines: List<LyricLine> = emptyList(),
    val plainText: String = "",
    val activeIndex: Int = -1,
    val error: String? = null,
)

/** Main-thread source/account fences. Fetch parses off-thread; no raw responses enter state. */
class LyricsBrowser(
    private val identity: (Platform) -> LibraryCacheIdentity?,
    private val fetch: (MediaKey, (Result<ParsedLyrics>) -> Unit) -> Unit,
) {
    private data class Target(val queue: MediaKey, val source: MediaKey,
        val queueOwner: LibraryCacheIdentity, val sourceOwner: LibraryCacheIdentity)
    private val mutable = MutableStateFlow(LyricsState())
    val state = mutable.asStateFlow()
    private var target: Target? = null
    private var revision = 0L
    private var position = 0L
    // Eight bounded parsed documents, process-local and fenced by both account generations.
    private val cache = LinkedHashMap<Target, ParsedLyrics>()

    fun follow(queue: MediaKey?, source: MediaKey?, positionMs: Long) {
        position = positionMs.coerceAtLeast(0)
        val queueOwner = queue?.let { identity(it.platform) }?.takeIf { it.verified }
        val sourceOwner = source?.let { identity(it.platform) }?.takeIf { it.verified }
        val next = if (queue != null && source != null && queueOwner != null && sourceOwner != null)
            Target(queue, source, queueOwner, sourceOwner) else null
        cache.keys.removeAll { !authorized(it) }
        if (target == next) { synchronize(); return }
        target = next
        ++revision
        mutable.value = LyricsState()
        if (next != null) load(next)
    }

    fun refresh() {
        val current = target ?: return
        cache.remove(current)
        ++revision
        if (authorized(current)) load(current) else cancel()
    }

    fun cancel() { ++revision; target = null; mutable.value = LyricsState() }

    private fun load(current: Target) {
        val token = revision
        cache[current]?.let { publish(current.source, it); return }
        mutable.value = LyricsState(current.source, LyricsStatus.LOADING)
        fetch(current.source) callback@{ result ->
            if (revision != token || target != current || !authorized(current)) return@callback
            result.onSuccess { parsed ->
                cache[current] = parsed
                while (cache.size > 8) cache.remove(cache.keys.first())
                publish(current.source, parsed)
            }.onFailure { error ->
                val category = (error as? LibraryPageFailure)?.category?.takeIf {
                    it in setOf("expired", "network", "risk", "rate", "protocol", "limit", "permission",
                        "unavailable", "http", "timeout", "account_changed")
                } ?: "unavailable"
                mutable.value = LyricsState(current.source, LyricsStatus.ERROR, error = category)
            }
        }
    }

    private fun authorized(value: Target) = value.queueOwner.sameAttempt(identity(value.queue.platform)) &&
        value.sourceOwner.sameAttempt(identity(value.source.platform)) &&
        identity(value.queue.platform)?.verified == true && identity(value.source.platform)?.verified == true

    private fun publish(key: MediaKey, parsed: ParsedLyrics) {
        mutable.value = LyricsState(key, when {
            parsed.lines.isNotEmpty() -> LyricsStatus.TIMED
            parsed.plainText.isNotBlank() -> LyricsStatus.PLAIN
            else -> LyricsStatus.EMPTY
        }, parsed.lines, parsed.plainText, LrcParser.activeIndex(parsed.lines, position))
    }

    private fun synchronize() {
        val value = state.value
        val index = LrcParser.activeIndex(value.lines, position)
        if (index != value.activeIndex) mutable.value = value.copy(activeIndex = index)
    }
}
