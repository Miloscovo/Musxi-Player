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
    val translation: LyricsTranslation = LyricsTranslation(),
    val canRefreshTranslation: Boolean = false,
)

/** Main-thread source/account fences. Fetch parses off-thread; no raw responses enter state. */
class LyricsBrowser(
    private val identity: (Platform) -> LibraryCacheIdentity?,
    private val fetch: (MediaKey, (Result<ParsedLyrics>) -> Unit) -> Unit,
    private val fetchTranslation: ((MediaKey, ParsedLyrics, Boolean, (Result<ParsedLyrics>) -> Unit) -> Unit)?,
    private val guardedFetch: ((MediaKey, () -> Boolean, (Result<ParsedLyrics>) -> Unit) -> Unit)? = null,
    private val releaseDocument: (ParsedLyrics) -> Unit = {},
) {
    constructor(identity: (Platform) -> LibraryCacheIdentity?,
        fetch: (MediaKey, (Result<ParsedLyrics>) -> Unit) -> Unit) : this(identity, fetch, null)
    private data class Target(val queue: MediaKey, val source: MediaKey,
        val queueOwner: LibraryCacheIdentity, val sourceOwner: LibraryCacheIdentity)
    private val mutable = MutableStateFlow(LyricsState())
    val state = mutable.asStateFlow()
    @Volatile private var target: Target? = null
    @Volatile private var revision = 0L
    private var translationRevision = 0L
    private var position = 0L
    // Eight bounded parsed documents, process-local and fenced by both account generations.
    private class Document(val original: ParsedLyrics, var displayed: ParsedLyrics)
    private val cache = LinkedHashMap<Target, Document>()

    fun follow(queue: MediaKey?, source: MediaKey?, positionMs: Long) {
        position = positionMs.coerceAtLeast(0)
        val queueOwner = queue?.let { identity(it.platform) }?.takeIf { it.verified }
        val sourceOwner = source?.let { identity(it.platform) }?.takeIf { it.verified }
        val next = if (queue != null && source != null && queueOwner != null && sourceOwner != null)
            Target(queue, source, queueOwner, sourceOwner) else null
        cache.keys.filter { !authorized(it) }.forEach { remove(it) }
        if (target == next) { synchronize(); return }
        target = next
        ++revision
        ++translationRevision
        mutable.value = LyricsState()
        if (next != null) load(next)
    }

    fun refresh() {
        val current = target ?: return
        remove(current)
        ++revision
        ++translationRevision
        if (authorized(current)) load(current) else cancel()
    }

    /** Refresh only the optional track. Original rows, playback and active index remain usable. */
    fun refreshTranslation() {
        val current = target ?: return
        if (!authorized(current)) { cancel(); return }
        val document = cache[current] ?: return
        if (document.original.lines.isNotEmpty()) translate(current, document, true)
    }

    fun cancel() { ++revision; ++translationRevision; target = null; mutable.value = LyricsState() }

    private fun remove(current: Target) { cache.remove(current)?.let { releaseDocument(it.original) } }

    private fun load(current: Target) {
        val token = revision
        cache[current]?.let {
            publish(current.source, it.displayed)
            if (fetchTranslation != null && it.displayed.translation.lookup.status == TranslationLookupStatus.PENDING)
                translate(current, it, false)
            return
        }
        mutable.value = LyricsState(current.source, LyricsStatus.LOADING)
        val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
        fun isCurrent() = !delivered.get() && revision == token && target == current && authorized(current)
        val callback: (Result<ParsedLyrics>) -> Unit = callback@{ result ->
            if (revision != token || target != current || !authorized(current) || !delivered.compareAndSet(false, true)) return@callback
            result.onSuccess { parsed ->
                val document = Document(parsed, parsed)
                cache[current] = document
                while (cache.size > 8) remove(cache.keys.first())
                publish(current.source, parsed)
                if (fetchTranslation != null && parsed.lines.isNotEmpty()) translate(current, document, false)
            }.onFailure { error ->
                val category = (error as? LibraryPageFailure)?.category?.takeIf {
                    it in setOf("expired", "network", "risk", "rate", "protocol", "limit", "permission",
                        "unavailable", "http", "timeout", "account_changed")
                } ?: "unavailable"
                mutable.value = LyricsState(current.source, LyricsStatus.ERROR, error = category)
            }
        }
        if (guardedFetch != null) guardedFetch.invoke(current.source, ::isCurrent, callback)
        else fetch(current.source, callback)
    }

    private fun translate(current: Target, document: Document, refresh: Boolean) {
        val request = fetchTranslation ?: return
        val originalToken = revision
        val token = ++translationRevision
        document.displayed = document.displayed.copy(translation = document.displayed.translation.copy(
            lookup = TranslationLookup(TranslationLookupStatus.PENDING)))
        publish(current.source, document.displayed)
        var delivered = false
        request(current.source, document.original, refresh) callback@{ response ->
            if (delivered || token != translationRevision || originalToken != revision || target != current ||
                cache[current] !== document || !authorized(current)) return@callback
            delivered = true
            val incoming = response.getOrElse { error ->
                LyricsTranslationAligner.failed(document.original, when ((error as? LibraryPageFailure)?.category) {
                    "network" -> TranslationReason.NETWORK
                    "timeout" -> TranslationReason.TIMEOUT
                    else -> TranslationReason.PLATFORM_ERROR
                })
            }
            val compatible = incoming.plainText == document.original.plainText &&
                incoming.lines.size == document.original.lines.size && incoming.lines.indices.all {
                    incoming.lines[it].timeMs == document.original.lines[it].timeMs &&
                        incoming.lines[it].text == document.original.lines[it].text
                }
            val checked = if (compatible) incoming else
                LyricsTranslationAligner.failed(document.original, TranslationReason.VERSION_MISMATCH)
            document.displayed = if (checked.translation.lookup.status in setOf(
                    TranslationLookupStatus.FAILED, TranslationLookupStatus.UNSUPPORTED) &&
                document.displayed.translation.availability == TranslationAvailability.AVAILABLE)
                document.displayed.copy(translation = document.displayed.translation.copy(lookup = checked.translation.lookup))
            else checked
            publish(current.source, document.displayed)
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
        }, parsed.lines, parsed.plainText, LrcParser.activeIndex(parsed.lines, position),
            translation = parsed.translation,
            canRefreshTranslation = fetchTranslation != null && parsed.lines.isNotEmpty())
    }

    private fun synchronize() {
        val value = state.value
        val index = LrcParser.activeIndex(value.lines, position)
        if (index != value.activeIndex) mutable.value = value.copy(activeIndex = index)
    }
}
