package tech.musxi.core.playback

import java.text.Normalizer
import java.util.Locale
import tech.musxi.core.account.Platform
import tech.musxi.core.library.SongSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SourceAudioQuality { UNKNOWN, STANDARD, HIGH, LOSSLESS }
enum class SourcePreviewAvailability { UNKNOWN, UNAVAILABLE, AVAILABLE }
enum class SourcePlayability { UNKNOWN, UNAVAILABLE, AVAILABLE }
data class SourceAudioMetadata(
    val quality: SourceAudioQuality = SourceAudioQuality.UNKNOWN,
    val preview: SourcePreviewAvailability = SourcePreviewAvailability.UNKNOWN,
    val playability: SourcePlayability = SourcePlayability.UNKNOWN,
    val qualities: List<QualityOption> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class SourceCandidate(
    val song: SongSummary,
    val accountGeneration: Long,
    val audio: SourceAudioMetadata = SourceAudioMetadata(),
    val relaxedMatch: Boolean = false,
    val durationDifferenceMs: Long = 0,
)

data class SourceSwitchPreferences(val automatic: Boolean = true, val relaxDuration: Boolean = false)

enum class SourceSelectionMode { AUTO, MANUAL }
enum class SourceSearchStatus { SEARCHING, COMPLETE, FAILURE }
enum class SourceSearchCompletion { COMPLETE, PARTIAL, FAILED }
data class SourceSearchProgress(
    val platform: Platform,
    val status: SourceSearchStatus,
    val completedPlatforms: Int,
    val totalPlatforms: Int,
    val candidateCount: Int = 0,
    val error: String? = null,
)
fun interface SourceSearchProgressListener {
    fun onProgress(progress: SourceSearchProgress)
}

data class AlternativeSources(
    val original: SongSummary,
    val candidates: List<SourceCandidate>,
    val partial: Boolean = false,
    val platformProgress: List<SourceSearchProgress> = emptyList(),
) {
    val completion: SourceSearchCompletion
        get() = when {
            platformProgress.isNotEmpty() && platformProgress.all { it.status == SourceSearchStatus.FAILURE } ->
                SourceSearchCompletion.FAILED
            partial || platformProgress.any { it.status != SourceSearchStatus.COMPLETE } ->
                SourceSearchCompletion.PARTIAL
            else -> SourceSearchCompletion.COMPLETE
        }
}

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
    val mode: SourceSelectionMode = SourceSelectionMode.AUTO,
    val platformProgress: List<SourceSearchProgress> = emptyList(),
    val playbackFailures: Map<Platform, PlaybackFailure> = emptyMap(),
    val failureReasons: Map<Platform, FailureReason> = emptyMap(),
    val preferences: SourceSwitchPreferences = SourceSwitchPreferences(),
    val selectedPlatform: Platform? = null,
    val selectedRelaxedMatch: Boolean = false,
    val playbackConfirmed: Boolean = false,
)

/** Single owner thread. Tokens fence search, user actions and callbacks alike.
 * AUTO selects the first strictly matched candidate as soon as results are offered.
 * MANUAL waits for the caller's complete result and never invokes the choice callback automatically.
 */
class SourceSelection(@Suppress("UNUSED_PARAMETER") now: () -> Long,
    private val preferences: () -> SourceSwitchPreferences) {
    constructor(now: () -> Long) : this(now, { SourceSwitchPreferences() })
    private var revision = 0L
    private var choose: ((SourceCandidate) -> Unit)? = null
    private var dismiss: (() -> Unit)? = null
    private val mutable = MutableStateFlow(SourceSelectionState())
    val state = mutable.asStateFlow()

    fun begin(
        original: SongSummary,
        onChoose: (SourceCandidate) -> Unit,
        onDismiss: () -> Unit,
        mode: SourceSelectionMode = SourceSelectionMode.AUTO,
    ): Long {
        val token = ++revision
        choose = onChoose; dismiss = onDismiss
        mutable.value = SourceSelectionState(
            revision = token,
            phase = SourceSelectionPhase.SEARCHING,
            mode = mode,
            original = original,
            preferences = preferences(),
        )
        return token
    }
    fun progress(token: Long, progress: SourceSearchProgress) {
        if (token != revision) return
        val current = mutable.value
        if (current.phase != SourceSelectionPhase.SEARCHING &&
            current.phase != SourceSelectionPhase.CHOOSING) return
        mutable.value = current.copy(platformProgress =
            current.platformProgress.filterNot { it.platform == progress.platform } + progress)
    }
    fun candidateAudio(token: Long, key: tech.musxi.core.library.MediaKey, audio: SourceAudioMetadata): Boolean {
        val current = mutable.value
        if (token != revision || current.phase != SourceSelectionPhase.CHOOSING || current.candidates.none { it.song.key == key }) return false
        mutable.value = current.copy(candidates = current.candidates.map { if (it.song.key == key) it.copy(audio = audio) else it })
        return true
    }
    fun playbackFailure(token: Long, platform: Platform, failure: PlaybackFailure,
        reason: FailureReason = FailureReason.fromFailure(failure)) {
        if (token != revision) return
        mutable.value = mutable.value.copy(playbackFailures = mutable.value.playbackFailures + (platform to failure),
            failureReasons = mutable.value.failureReasons + (platform to reason))
    }
    fun offer(token: Long, result: AlternativeSources,
        automaticCandidates: Set<tech.musxi.core.library.MediaKey>? = null) {
        if (token != revision || mutable.value.phase != SourceSelectionPhase.SEARCHING) return
        // The active original owns the search; a mismatched response cannot replace its identity.
        val activeOriginal = mutable.value.original ?: return
        if (result.original.key != activeOriginal.key) return
        // The repository may enrich an unavailable original's missing display metadata.
        val original = result.original
        val prefs = preferences()
        val items = SourceMatchPolicy.rank(original, result.candidates.filter { it.song.key.platform != result.original.key.platform }
            .filter { mutable.value.mode != SourceSelectionMode.MANUAL || SourceMatchPolicy.eligible(original, it.song, prefs.relaxDuration) }
            .distinctBy { it.song.key }, prefs.relaxDuration).take(20)
        val first = items.indexOfFirst { SourceMatchPolicy.eligible(original, it.song, prefs.relaxDuration) &&
            (automaticCandidates == null || it.song.key in automaticCandidates) }
            .takeIf { it >= 0 }
        val current = mutable.value
        val automatic = first.takeIf { current.mode == SourceSelectionMode.AUTO && prefs.automatic }
        mutable.value = current.copy(
            phase = if (items.isEmpty()) SourceSelectionPhase.FAILED else SourceSelectionPhase.CHOOSING,
            original = result.original,
            candidates = items,
            selected = null,
            deadlineElapsedMs = null,
            automaticIndex = automatic,
            partial = result.partial,
            platformProgress = result.platformProgress.ifEmpty { current.platformProgress },
            preferences = prefs,
            error = when {
                items.isNotEmpty() -> null
                result.completion == SourceSearchCompletion.FAILED -> "search_failed"
                else -> "no_matching_candidates"
            },
        )
        if (items.isEmpty()) {
            choose = null; dismiss = null
        } else {
            automatic?.let { select(token, it) }
        }
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
        mutable.value = current.copy(phase = SourceSelectionPhase.SELECTED, selected = candidate, deadlineElapsedMs = null,
            selectedPlatform = candidate.song.key.platform, selectedRelaxedMatch = candidate.relaxedMatch)
        callback(candidate)
        return true
    }
    @Suppress("UNUSED_PARAMETER")
    fun tick(token: Long) = Unit
    fun confirmPlayback(key: tech.musxi.core.library.MediaKey) {
        if (mutable.value.selected?.song?.key == key) mutable.value = mutable.value.copy(playbackConfirmed = true)
    }
    fun cancel(token: Long): Boolean {
        if (token != revision || mutable.value.phase !in setOf(SourceSelectionPhase.SEARCHING, SourceSelectionPhase.CHOOSING)) return false
        val callback = dismiss
        clear(); callback?.invoke(); return true
    }
    /** Stop, pause, navigation and queue replacement never invoke a stale callback. */
    fun clear() { ++revision; choose = null; dismiss = null; mutable.value = SourceSelectionState(revision) }

    companion object {
        fun sameTitle(a: String, b: String): Boolean = normalized(a).let { it.isNotEmpty() && it == normalized(b) }
        private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).filterNot { it.isWhitespace() || Character.isSpaceChar(it) }
    }
}
