package tech.musxi.core.playback

import java.text.Normalizer
import java.util.Locale
import tech.musxi.core.library.SongSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SourceCandidate(val song: SongSummary, val accountGeneration: Long)
data class AlternativeSources(val original: SongSummary, val candidates: List<SourceCandidate>, val partial: Boolean = false)
enum class SourceSelectionPhase { NONE, SEARCHING, CHOOSING, SELECTED, FAILED }
data class SourceSelectionState(
    val revision: Long = 0,
    val phase: SourceSelectionPhase = SourceSelectionPhase.NONE,
    val original: SongSummary? = null,
    val candidates: List<SourceCandidate> = emptyList(),
    val selected: SourceCandidate? = null,
    val deadlineElapsedMs: Long? = null,
    val automaticIndex: Int? = null,
    val partial: Boolean = false,
    val error: String? = null,
)

/** Single owner thread. Tokens fence search, user actions and timer delivery alike.
 * A deadline starts only when a stable candidate list becomes available.
 */
class SourceSelection(private val now: () -> Long) {
    private var revision = 0L
    private var choose: ((SourceCandidate) -> Unit)? = null
    private var dismiss: (() -> Unit)? = null
    private val mutable = MutableStateFlow(SourceSelectionState())
    val state = mutable.asStateFlow()

    fun begin(original: SongSummary, onChoose: (SourceCandidate) -> Unit, onDismiss: () -> Unit): Long {
        val token = ++revision
        choose = onChoose; dismiss = onDismiss
        mutable.value = SourceSelectionState(token, SourceSelectionPhase.SEARCHING, original)
        return token
    }
    fun offer(token: Long, result: AlternativeSources) {
        if (token != revision || mutable.value.phase != SourceSelectionPhase.SEARCHING) return
        val items = result.candidates.filter { it.song.key.platform != result.original.key.platform }
            .distinctBy { it.song.key }.take(20).toList()
        val first = items.indexOfFirst { sameTitle(result.original.title, it.song.title) }.takeIf { it >= 0 }
        mutable.value = SourceSelectionState(token,
            if (items.isEmpty()) SourceSelectionPhase.FAILED else SourceSelectionPhase.CHOOSING,
            result.original, items, deadlineElapsedMs = first?.let { now() + AUTO_SELECT_MS },
            automaticIndex = first, partial = result.partial, error = if (items.isEmpty()) "no_candidates" else null)
        if (items.isEmpty()) { choose = null; dismiss = null }
    }
    fun fail(token: Long, category: String) {
        if (token != revision || mutable.value.phase != SourceSelectionPhase.SEARCHING) return
        mutable.value = mutable.value.copy(phase = SourceSelectionPhase.FAILED, error = category,
            deadlineElapsedMs = null)
        choose = null; dismiss = null
    }
    fun select(token: Long, index: Int): Boolean {
        val current = mutable.value
        if (token != revision || current.phase != SourceSelectionPhase.CHOOSING) return false
        val candidate = current.candidates.getOrNull(index) ?: return false
        val callback = choose ?: return false
        choose = null; dismiss = null
        mutable.value = current.copy(phase = SourceSelectionPhase.SELECTED, selected = candidate, deadlineElapsedMs = null)
        callback(candidate)
        return true
    }
    fun tick(token: Long) {
        val current = mutable.value
        val deadline = current.deadlineElapsedMs ?: return
        if (token == revision && now() >= deadline) current.automaticIndex?.let { select(token, it) }
    }
    fun cancel(token: Long): Boolean {
        if (token != revision || mutable.value.phase !in setOf(SourceSelectionPhase.SEARCHING, SourceSelectionPhase.CHOOSING)) return false
        val callback = dismiss
        clear(); callback?.invoke(); return true
    }
    /** Stop, pause, navigation and queue replacement never invoke a stale callback. */
    fun clear() { ++revision; choose = null; dismiss = null; mutable.value = SourceSelectionState(revision) }

    companion object {
        const val AUTO_SELECT_MS = 5_000L
        fun sameTitle(a: String, b: String): Boolean = normalized(a).let { it.isNotEmpty() && it == normalized(b) }
        private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).filterNot { it.isWhitespace() || Character.isSpaceChar(it) }
    }
}
