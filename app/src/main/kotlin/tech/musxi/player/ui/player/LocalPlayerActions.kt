package tech.musxi.player.ui.player

/**
 * What the player page offers in place of the controls a local recording cannot use (no heart, no
 * lyrics, no quality). Agreed with the owner on 11-11:
 * - the heart's place on the top bar: back 15 seconds / forward 30 seconds;
 * - the quality button's place beside play: the playback speed;
 * - the lyrics button's place on the tool row: bookmarks.
 * The places stay the same on every kind of track, so moving from a song to a recording (or a mixed
 * folder from audio to video) never makes the controls jump; only what the slot does changes.
 *
 * Null leaves a control out. Platform songs pass null for the whole thing.
 */
class LocalPlayerActions(
    val onSkip: ((deltaMs: Long) -> Unit)? = null,
    /** Sets this recording's speed (one of [LocalSpeeds]); null leaves the speed slot empty. */
    val onSelectSpeed: ((Float) -> Unit)? = null,
    /** The speed the player reports now, shown on its button ("1.25×"); not the saved value. */
    val speed: Float = 1f,
    /** Puts a bookmark at the current position. */
    val onAddBookmark: (() -> Unit)? = null,
    /** Opens this recording's bookmarks. */
    val onShowBookmarks: (() -> Unit)? = null,
    /** Bookmarks this recording has, for the badge on the button. */
    val bookmarkCount: Int = 0,
)

/** Back and forward steps of the skip buttons. Long recordings: a sentence back, a paragraph on. */
const val LocalSkipBackMs = 15_000L
const val LocalSkipForwardMs = 30_000L

/** The speeds the backend accepts, slowest first. Pitch stays put at every one of them. */
val LocalSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** "1.25×"; whole speeds drop the decimals ("1×", "2×"). */
fun speedLabel(speed: Float): String {
    val rounded = Math.round(speed * 100) / 100f
    val text = if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString().trimEnd('0')
    return "$text×"
}

/** Where a skip lands, kept inside the recording (and short of its very end, which would end it). */
fun skipTarget(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
    val end = if (durationMs > 0) (durationMs - 1_000L).coerceAtLeast(0L) else Long.MAX_VALUE
    return (positionMs + deltaMs).coerceIn(0L, end)
}

/** Default name of the next bookmark: "书签 3" after two. */
fun nextBookmarkName(existing: Int): String = "书签 ${existing + 1}"
