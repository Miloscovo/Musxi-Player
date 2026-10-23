package tech.musxi.player.ui.player

import tech.musxi.core.account.Platform
import tech.musxi.core.library.SongSummary
import tech.musxi.core.playback.SourceSelectionPhase
import tech.musxi.core.playback.SourceSelectionState
import tech.musxi.player.ui.theme.displayName

/** The panel has three faces; everything else about it is constant. */
enum class SourcePhase { HIDDEN, SEARCHING, CHOOSING, FAILED }

/** One replacement candidate. */
data class CandidateRowUi(
    val title: String,
    val platform: Platform,
    /** Shown because a matching title is not the same recording. */
    val album: String,
    val durationMs: Long,
    /** Signed difference from the original, or null when it is too small to matter. */
    val durationDeltaMs: Long?,
    /** The row the five-second timer will pick. */
    val autoTarget: Boolean,
    /** 0..1 of the countdown already elapsed, only meaningful on the auto target. */
    val countdownFraction: Float,
    val secondsLeft: Int?,
)

data class SourcePanelUi(
    val phase: SourcePhase = SourcePhase.HIDDEN,
    val originalTitle: String = "",
    val originalArtist: String = "",
    val originalPlatform: Platform? = null,
    /** Why the panel is open, in one line. */
    val reason: String = "",
    val candidates: List<CandidateRowUi> = emptyList(),
    /** True when at least one platform did not answer. */
    val partial: Boolean = false,
    val failureHeadline: String = "",
    val failureDetail: String = "",
)

/** How long the automatic pick waits; mirrors `SourceSelection.AUTO_SELECT_MS`. */
const val AUTO_SELECT_MS = 5_000L

/** Differences above this are worth printing: they separate live takes from the studio version. */
private const val DURATION_NOTICE_MS = 5_000L

/**
 * Collapses the source-selection state into the panel.
 *
 * [nowElapsedMs] is injected rather than read here so the mapping stays pure: the countdown is the
 * only part of this panel that depends on time, and the service owns the clock.
 */
fun sourcePanelUiOf(
    state: SourceSelectionState,
    nowElapsedMs: Long,
): SourcePanelUi {
    val phase = when (state.phase) {
        SourceSelectionPhase.NONE -> SourcePhase.HIDDEN
        SourceSelectionPhase.SEARCHING -> SourcePhase.SEARCHING
        SourceSelectionPhase.CHOOSING, SourceSelectionPhase.SELECTED -> SourcePhase.CHOOSING
        SourceSelectionPhase.FAILED -> SourcePhase.FAILED
    }
    val original = state.original
    if (phase == SourcePhase.HIDDEN) return SourcePanelUi()
    val remainingMs = state.deadlineElapsedMs?.minus(nowElapsedMs)?.coerceAtLeast(0L)
    val fraction = if (remainingMs == null) 0f else (1f - remainingMs.toFloat() / AUTO_SELECT_MS).coerceIn(0f, 1f)
    val seconds = remainingMs?.let { ((it + 999) / 1000).toInt() }
    return SourcePanelUi(
        phase = phase,
        originalTitle = original?.title.orEmpty(),
        originalArtist = original?.artists?.joinToString(" / ").orEmpty(),
        originalPlatform = original?.key?.platform,
        reason = reasonOf(original, state),
        candidates = state.candidates.mapIndexed { index, candidate ->
            CandidateRowUi(
                title = candidate.song.title,
                platform = candidate.song.key.platform,
                album = candidate.song.album,
                durationMs = candidate.song.durationMs,
                durationDeltaMs = original?.let { diff ->
                    diff.durationMs.takeIf { it > 0 }?.let { base ->
                        (candidate.song.durationMs - base).takeIf { kotlin.math.abs(it) > DURATION_NOTICE_MS }
                    }
                },
                autoTarget = index == state.automaticIndex,
                countdownFraction = if (index == state.automaticIndex) fraction else 0f,
                secondsLeft = if (index == state.automaticIndex) seconds else null,
            )
        },
        partial = state.partial,
        // One statement. The headline used to repeat the sentence underneath it.
        failureHeadline = if (original != null) "没找到能播的《${original.title}》" else "没找到能播的替代音源",
        failureDetail = failureDetailOf(state.partial),
    )
}

/**
 * "Why is this panel here" in one line. Naming the platform matters: the user needs to know which
 * account is the one that failed. The original's duration is included so the candidate durations
 * below it have something to be compared against.
 *
 * The searching wording is short on purpose: the longer version was ellipsised on the one screen
 * where the sentence is the only sign that anything is happening.
 */
private fun reasonOf(original: SongSummary?, state: SourceSelectionState): String {
    val platform = original?.key?.platform?.displayName.orEmpty()
    val track = original?.let {
        val duration = if (it.durationMs > 0) " · ${tech.musxi.player.ui.model.formatDuration(it.durationMs)}" else ""
        "《${it.title}》· ${it.artists.joinToString(" / ")}$duration"
    }.orEmpty()
    return when {
        state.phase == SourceSelectionPhase.SEARCHING -> "$track · 正在其他平台找"
        original == null -> "正在其他平台找"
        else -> "$track · 在${platform}放不了"
    }.trim()
}

/**
 * Names the platforms instead of counting them, and uses the same short names as the candidate
 * captions — mixing "网易云" and "网易云音乐" inside one sheet reads as two different services.
 */
private fun failureDetailOf(partial: Boolean): String =
    if (partial) "还有平台没回应，稍后可以再试一次"
    else "网易云、QQ音乐、酷狗都没有可播版本"
