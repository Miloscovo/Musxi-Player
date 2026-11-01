package tech.musxi.player.ui.player

import tech.musxi.core.account.Platform
import tech.musxi.core.library.SongSummary
import tech.musxi.core.playback.SourceSearchProgress
import tech.musxi.core.playback.SourceSearchStatus
import tech.musxi.core.playback.SourceSelectionMode
import tech.musxi.core.playback.SourceSelectionPhase
import tech.musxi.core.playback.SourceSelectionState
import tech.musxi.player.ui.model.formatDuration
import tech.musxi.player.ui.theme.displayName
import kotlin.math.abs

/** The panel has three faces; everything else about it is constant. */
enum class SourcePhase { HIDDEN, SEARCHING, CHOOSING, FAILED }

/**
 * One platform's line in the sheet: while searching, its progress; after a failed search, why it
 * has nothing. [text] already names the platform, the dot beside it only carries its colour.
 */
data class PlatformLineUi(
    val platform: Platform,
    val text: String,
    /** The platform is still working: the line shows a small ring. */
    val busy: Boolean = false,
    /** The original's own platform, which is not searched. */
    val muted: Boolean = false,
)

/** One replacement candidate. */
data class CandidateRowUi(
    val title: String,
    val platform: Platform,
    /** `歌手 · 专辑`. The album is printed because a matching title is not the same recording. */
    val subtitle: String,
    val coverUrl: String?,
    val durationMs: Long,
    /** "长 12 秒" / "短 8 秒", or null when the length is within five seconds of the original. */
    val durationNote: String?,
    /** The row the five-second timer will pick. */
    val autoTarget: Boolean,
    /** 0..1 of the countdown already elapsed, only meaningful on the auto target. */
    val countdownFraction: Float,
    val secondsLeft: Int?,
)

data class SourcePanelUi(
    val phase: SourcePhase = SourcePhase.HIDDEN,
    /** The selection this describes. A new search gets a new revision, which reopens a dismissed sheet. */
    val revision: Long = 0,
    /** True for the search the user started, false for the automatic one after a failure. */
    val manual: Boolean = false,
    /** What the sheet says it is doing, e.g. `《晴天》在酷狗放不了（需要会员）` or `换个音源`. */
    val title: String = "",
    val subtitle: String = "",
    /** A third, smaller line: that some platform did not answer. */
    val note: String = "",
    val originalTitle: String = "",
    val originalArtist: String = "",
    val originalPlatform: Platform? = null,
    /** Searching: each platform's progress. Failed: each platform's reason. Otherwise empty. */
    val platformLines: List<PlatformLineUi> = emptyList(),
    val candidates: List<CandidateRowUi> = emptyList(),
    /** True when at least one platform did not answer. */
    val partial: Boolean = false,
    /** The platform reasons in one sentence; the failed face shows it when there are no lines. */
    val failureDetail: String = "",
)

/** Legacy countdown presentation for previews; runtime selection uses no deadline. */
const val AUTO_SELECT_MS = 5_000L

/** Differences from this size are worth printing: they separate live takes from the studio version. */
private const val DURATION_NOTICE_MS = 5_000L

/** The title of the sheet when the user is picking a version. */
private const val SWITCH_TITLE = "换个音源"

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
        SourceSelectionPhase.NONE, SourceSelectionPhase.SELECTED -> SourcePhase.HIDDEN
        SourceSelectionPhase.SEARCHING -> SourcePhase.SEARCHING
        SourceSelectionPhase.CHOOSING -> SourcePhase.CHOOSING
        SourceSelectionPhase.FAILED -> SourcePhase.FAILED
    }
    val original = state.original
    if (phase == SourcePhase.HIDDEN) return SourcePanelUi(revision = state.revision)
    val manual = state.mode == SourceSelectionMode.MANUAL
    val remainingMs = state.deadlineElapsedMs?.minus(nowElapsedMs)?.coerceAtLeast(0L)
    val fraction = if (remainingMs == null) 0f else (1f - remainingMs.toFloat() / AUTO_SELECT_MS).coerceIn(0f, 1f)
    val seconds = remainingMs?.let { ((it + 999) / 1000).toInt() }
    val failureLines = failureLinesOf(state)
    return SourcePanelUi(
        phase = phase,
        revision = state.revision,
        manual = manual,
        title = titleOf(state, phase, manual),
        subtitle = subtitleOf(state, phase, manual),
        note = if (phase == SourcePhase.CHOOSING && state.partial) "有平台没回应，候选可能不全" else "",
        originalTitle = original?.title.orEmpty(),
        originalArtist = original?.artists?.joinToString(" / ").orEmpty(),
        originalPlatform = original?.key?.platform,
        platformLines = when (phase) {
            SourcePhase.SEARCHING -> searchingLinesOf(state)
            SourcePhase.FAILED -> failureLines
            else -> emptyList()
        },
        candidates = state.candidates.mapIndexed { index, candidate ->
            val song = candidate.song
            CandidateRowUi(
                title = song.title,
                platform = song.key.platform,
                subtitle = candidateSubtitleOf(song),
                coverUrl = song.coverUrl,
                durationMs = song.durationMs,
                durationNote = original?.let { durationDiffLabel(it.durationMs, song.durationMs) },
                autoTarget = index == state.automaticIndex,
                countdownFraction = if (index == state.automaticIndex) fraction else 0f,
                secondsLeft = if (index == state.automaticIndex) seconds else null,
            )
        },
        partial = state.partial,
        failureDetail = failureDetailOf(state, failureLines),
    )
}

/**
 * "长 12 秒" or "短 8 秒" when a candidate runs five seconds or more away from the original; null
 * when it is closer than that, or when either length is unknown (a missing length is not a difference).
 */
internal fun durationDiffLabel(originalMs: Long, candidateMs: Long): String? {
    if (originalMs <= 0 || candidateMs <= 0) return null
    val diff = candidateMs - originalMs
    if (abs(diff) < DURATION_NOTICE_MS) return null
    val seconds = abs(diff) / 1000
    return if (diff > 0) "长 $seconds 秒" else "短 $seconds 秒"
}

/** `歌手 · 专辑` for a candidate row. An unknown album or a missing artist is left out, not printed. */
internal fun candidateSubtitleOf(song: SongSummary): String {
    val artists = song.artists.filter { it.isNotBlank() }.joinToString(" / ")
    val album = song.album.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
    return listOfNotNull(artists.takeIf { it.isNotEmpty() }, album).joinToString(" · ")
}

/** `《歌名》· 歌手 · 3:00`. The artist and the length are left out when they are not known. */
internal fun trackLineOf(song: SongSummary?): String {
    if (song == null) return ""
    val artists = song.artists.filter { it.isNotBlank() }.joinToString(" / ")
    val tail = listOfNotNull(
        artists.takeIf { it.isNotEmpty() },
        formatDuration(song.durationMs).takeIf { song.durationMs > 0 },
    )
    val head = "《${song.title}》"
    return if (tail.isEmpty()) head else "$head· ${tail.joinToString(" · ")}"
}

/**
 * The sheet's headline. The automatic search names what went wrong, with the platform's own reason
 * when the service recorded one, e.g. `《晴天》在酷狗放不了（需要会员）`. The user-started search and
 * the version list are both "换个音源": nothing has failed there.
 */
internal fun titleOf(state: SourceSelectionState, phase: SourcePhase, manual: Boolean): String {
    val original = state.original
    return when (phase) {
        SourcePhase.HIDDEN -> ""
        SourcePhase.CHOOSING -> SWITCH_TITLE
        SourcePhase.SEARCHING -> when {
            manual -> SWITCH_TITLE
            original == null -> "正在找替代音源"
            else -> {
                val platform = original.key.platform
                // Only the original platform's reason belongs here; the others' go to the failed face.
                val why = state.failureReasons[platform]?.let { "（${failureReasonText(it)}）" }.orEmpty()
                "《${original.title}》在${platform.displayName}放不了$why"
            }
        }
        // A user-started search finds nothing while the song plays fine, so it cannot say "没法播".
        SourcePhase.FAILED -> when {
            original == null -> "没找到能播的替代音源"
            manual -> "没找到《${original.title}》的其他版本"
            else -> "没找到能播的《${original.title}》"
        }
    }
}

/** The line under the headline: what is being done, or which song the list is for. */
internal fun subtitleOf(state: SourceSelectionState, phase: SourcePhase, manual: Boolean): String {
    val original = state.original
    return when (phase) {
        SourcePhase.SEARCHING -> when {
            !manual -> "正在其他平台找能播的版本"
            original == null -> "正在其他平台找"
            else -> "正在其他平台找《${original.title}》的其他版本"
        }
        SourcePhase.CHOOSING -> trackLineOf(original)
        else -> ""
    }
}

/**
 * One line per platform while the search runs: `QQ音乐 · 正在找`, `网易云 · 找到 2 个版本`,
 * `酷狗 · 原平台`. The original's platform is not searched and comes last, muted.
 *
 * Before the first report every other platform is about to be searched, so each shows as working.
 * Once reports arrive, a platform without one was not a target (no connected account) and gets no
 * line, instead of working forever.
 */
internal fun searchingLinesOf(state: SourceSelectionState): List<PlatformLineUi> {
    val originPlatform = state.original?.key?.platform
    val progress = state.platformProgress.associateBy { it.platform }
    val others = DETAIL_ORDER.filter { it != originPlatform }
    val searched = if (progress.isEmpty()) others else others.filter { it in progress }
    val lines = searched.map { searchLineOf(it, progress[it]) }
    val origin = originPlatform?.let { PlatformLineUi(it, "${it.displayName} · 原平台", muted = true) }
    return lines + listOfNotNull(origin)
}

private fun searchLineOf(platform: Platform, entry: SourceSearchProgress?): PlatformLineUi {
    val name = platform.displayName
    return when {
        entry == null || entry.status == SourceSearchStatus.SEARCHING ->
            PlatformLineUi(platform, "$name · 正在找", busy = true)
        entry.status == SourceSearchStatus.COMPLETE ->
            PlatformLineUi(platform, "$name · " + if (entry.candidateCount > 0) "找到 ${entry.candidateCount} 个版本" else "没找到")
        else -> PlatformLineUi(platform, "$name · 没有回应")
    }
}

/** The order the old summary sentence used; the detail keeps it so the sheet reads the same. */
private val DETAIL_ORDER = listOf(Platform.NETEASE, Platform.QQ, Platform.KUGOU)

/**
 * One line per platform for the failed face, `网易云：暂无版权`, `QQ音乐：需要会员`, `酷狗：没找到这首`.
 *
 * A recorded failure reason wins; otherwise a finished search with no usable candidate is "没找到这首"
 * and a platform that never answered is "没有回应". The original's own platform is only listed when it
 * has a reason, because it was not searched. Uses the same short names as the candidate rows —
 * mixing "网易云" and "网易云音乐" inside one sheet reads as two different services.
 *
 * Empty when there is no per-platform information at all (a failure before any platform reported).
 */
internal fun failureLinesOf(state: SourceSelectionState): List<PlatformLineUi> {
    val originPlatform = state.original?.key?.platform
    val progress = state.platformProgress.associateBy { it.platform }
    return DETAIL_ORDER.mapNotNull { platform ->
        val reason = state.failureReasons[platform]
        val entry = progress[platform]
        val text = when {
            reason != null -> failureReasonText(reason)
            platform == originPlatform -> null
            entry != null && entry.status == SourceSearchStatus.COMPLETE -> "没找到这首"
            entry != null || progress.isNotEmpty() -> "没有回应"
            else -> null
        }
        text?.let { PlatformLineUi(platform, "${platform.displayName}：$it") }
    }
}

/**
 * The same facts as [failureLinesOf] in one sentence, `网易云：暂无版权 · QQ音乐：需要会员 · 酷狗：没找到这首`.
 * With no per-platform information it falls back to a summary rather than inventing a clause for
 * each platform.
 */
private fun failureDetailOf(state: SourceSelectionState, lines: List<PlatformLineUi>): String {
    if (lines.isNotEmpty()) return lines.joinToString(" · ") { it.text }
    return if (state.partial) "还有平台没回应，稍后可以再试一次"
    else "网易云、QQ音乐、酷狗都没有可播版本"
}
