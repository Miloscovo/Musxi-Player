package tech.musxi.player.ui.player

import tech.musxi.core.lyrics.LyricLine
import tech.musxi.core.lyrics.LyricsState
import tech.musxi.core.lyrics.LyricsStatus
import tech.musxi.core.lyrics.TranslationAvailability
import tech.musxi.core.lyrics.TranslationLookupStatus

/**
 * Why the lyrics are not readable, reduced to the things the user can actually do about it.
 *
 * The backend reports eleven categories. Showing eleven sentences would be honest and useless: what
 * differs for this user is only the next step. The one distinction that must never be lost is
 * [FORMAT] against [UNAVAILABLE]: a protocol error, an over-limit document or a risk block are our
 * failures, and reporting them as "the platform does not provide lyrics" blames the wrong party and
 * removes the retry that would have fixed it.
 */
enum class LyricsErrorKind {
    /** Retrying is worth it. */
    NETWORK,

    /** The account needs attention; retrying unchanged will fail again. */
    ACCOUNT,

    /** Too frequent; waiting is the fix. */
    RATE,

    /** The answer arrived but could not be used: protocol, limits, unreadable payload. */
    FORMAT,

    /** The platform states it has none for this song. Not a fault, so no button. */
    UNAVAILABLE,
}

/** What the lyrics area is showing. IDLE and LOADING are one phase on purpose. */
enum class LyricsPhase {
    /** Nothing requested yet, or a read is in flight: a skeleton, never an empty-state sentence. */
    LOADING,
    TIMED,
    /** Text with no timeline: readable, but it cannot follow or highlight. */
    PLAIN,
    EMPTY,
    ERROR,
}

/**
 * The translation chip's state. It carries the whole translation story, because translation is an
 * optional layer: it may be absent for a song, absent for a platform, off by preference, or in
 * trouble — and none of those may disturb the original text underneath.
 */
enum class TranslationChip {
    /** No route for this source: the chip is not drawn at all. */
    HIDDEN,

    /** Route exists, user turned it off. */
    OFF,

    /** On, showing whatever lines have a translation. */
    ON,

    /** On, but the latest lookup failed: text stays, the chip offers a retry. */
    WARNING,

    /** The platform confirmed this song has none: the chip is visible but inert. */
    NONE,
}

data class TranslationUi(
    val chip: TranslationChip = TranslationChip.HIDDEN,
    /** Whether a refresh is allowed right now (false while a query is in flight). */
    val canRefresh: Boolean = false,
    /** Whether verified translation rows contain displayable text. */
    val hasText: Boolean = false,
    /** The latest lookup failed; older verified text may still exist when [hasText] is true. */
    val lookupFailed: Boolean = false,
) {
    val showsText: Boolean
        get() = chip == TranslationChip.ON || chip == TranslationChip.WARNING
}

/**
 * Everything the lyrics area needs to draw.
 *
 * `browsing` means the user has taken the scroll over: following stops, a picked line offers its
 * time and a play mark, and a tap on that line seeks. In following mode a tap does nothing, so a
 * stray touch while reading does not jump the song.
 */
data class LyricsUi(
    val phase: LyricsPhase = LyricsPhase.LOADING,
    val lines: List<LyricLine> = emptyList(),
    val plainText: String = "",
    val activeIndex: Int = -1,
    val errorKind: LyricsErrorKind = LyricsErrorKind.UNAVAILABLE,
    val browsing: Boolean = false,
    val translation: TranslationUi = TranslationUi(),
) {
    val isFollowing: Boolean get() = phase == LyricsPhase.TIMED && !browsing

    /** The translation to draw under a line, or null when this line has none to show. */
    fun translationOf(line: LyricLine): String? =
        if (translation.showsText) line.translation?.takeIf { it.isNotBlank() } else null
}

/**
 * Collapses the lyric state. Pure, so the sheet and the page cannot disagree.
 *
 * Two rules live here. A null `sourceKey` mid-swap arrives as IDLE with no lines; mapping that to
 * EMPTY would tell the user "this song has no lyrics" every time a source is being chosen, so IDLE
 * and LOADING both become [LyricsPhase.LOADING] and the area shows a skeleton instead.
 *
 * And translation is never allowed to demote the original: AVAILABLE with a failed lookup still
 * shows the translations it has (marked as stale by the chip), while UNKNOWN with a failure says
 * only that the translation could not be read — never that the platform has none.
 */
fun lyricsUiOf(
    state: LyricsState,
    browsing: Boolean = false,
    showTranslation: Boolean = defaultTranslationEnabled(state),
): LyricsUi = LyricsUi(
    phase = when (state.status) {
        LyricsStatus.IDLE, LyricsStatus.LOADING -> LyricsPhase.LOADING
        LyricsStatus.TIMED -> LyricsPhase.TIMED
        LyricsStatus.PLAIN -> LyricsPhase.PLAIN
        LyricsStatus.EMPTY -> LyricsPhase.EMPTY
        LyricsStatus.ERROR -> LyricsPhase.ERROR
    },
    lines = state.lines,
    plainText = state.plainText,
    activeIndex = state.activeIndex,
    errorKind = errorKindOf(state.error),
    browsing = browsing,
    translation = translationUiOf(state, showTranslation),
)

private fun translationUiOf(state: LyricsState, showTranslation: Boolean): TranslationUi {
    val availability = state.translation.availability
    val lookup = state.translation.lookup.status
    val failed = lookup == TranslationLookupStatus.FAILED
    return TranslationUi(
        chip = when {
            // No route at all (a source parsed only for the original): nothing to offer.
            // The button is an entry point, not a status readout: it stays on screen even when the
            // source has no translations at all, because asking for a translation is exactly what a
            // user will want there. Hiding it removed the only place that feature could live.
            lookup == TranslationLookupStatus.UNSUPPORTED -> TranslationChip.NONE
            availability == TranslationAvailability.NONE -> TranslationChip.NONE
            !showTranslation -> TranslationChip.OFF
            availability == TranslationAvailability.AVAILABLE -> {
                if (failed) TranslationChip.WARNING else TranslationChip.ON
            }
            // UNKNOWN: nothing verified yet. A failure means "we could not read it", which is a
            // retry, not a claim that the song has none.
            failed -> TranslationChip.WARNING
            else -> TranslationChip.ON
        },
        canRefresh = state.canRefreshTranslation,
        hasText = availability == TranslationAvailability.AVAILABLE &&
            state.lines.any { it.translation?.isNotBlank() == true },
        lookupFailed = failed,
    )
}

private fun errorKindOf(code: String?): LyricsErrorKind = when (code) {
    "network", "timeout", "http" -> LyricsErrorKind.NETWORK
    "expired", "account_changed", "permission" -> LyricsErrorKind.ACCOUNT
    "rate" -> LyricsErrorKind.RATE
    // protocol / limit / risk: the answer came back but was unusable. Ours, not the platform's.
    "protocol", "limit", "risk" -> LyricsErrorKind.FORMAT
    // "unavailable" is the only code that actually means the platform has none. Anything else —
    // including codes this build has never heard of — is a neutral failure, because blaming the
    // platform also removes the retry that the user could have used.
    "unavailable" -> LyricsErrorKind.UNAVAILABLE
    else -> LyricsErrorKind.FORMAT
}
