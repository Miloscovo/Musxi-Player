package tech.musxi.player.ui.lockscreen

import tech.musxi.player.ui.player.LyricsPhase
import tech.musxi.player.ui.player.LyricsUi
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * What Musxi's own lock-screen page shows (plan: docs/lockscreen-page-plan-20261111.md). Plain data,
 * no Android types, so every rule below is a JVM test.
 *
 * The page is read-only by design: on a locked phone the only things a touch may do are the
 * transport buttons at the bottom and the swipe up to unlock. Lyrics cannot be tapped to seek and
 * the page never scrolls by hand, so a phone carried in a pocket cannot be talked into anything.
 */
data class LockScreenUi(
    /** "21:47", in the phone's own 24h/12h choice (formatted by the host). */
    val time: String,
    /** "11月11日 星期二". */
    val date: String,
    val title: String,
    /** "周杰伦" or, for a local recording, its creator; empty when unknown. */
    val subtitle: String,
    val coverSeed: String,
    val coverUrl: String?,
    val playing: Boolean,
    val buffering: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val durationMs: Long,
    /** Lyrics to read, or null when the middle of the page shows the large cover instead. */
    val lyrics: LyricsUi?,
)

/** How the middle of the page is used. */
enum class LockScreenBody {
    /** Timed lyrics: the current line centred and large, the next ones fading below. */
    LYRICS,

    /** Untimed text, no lyrics, a local recording, or still loading: a large cover and the title. */
    COVER,
}

/**
 * Lyrics take the page only when they can follow the song. Plain text cannot (there is no "current
 * line" to keep in view) and an error or loading state has nothing to read, so those show the cover:
 * a lock screen is glanced at, and a spinner or an error message there would only be noise.
 */
fun lockScreenBodyOf(lyrics: LyricsUi?): LockScreenBody =
    if (lyrics != null && lyrics.phase == LyricsPhase.TIMED && lyrics.lines.isNotEmpty()) LockScreenBody.LYRICS
    else LockScreenBody.COVER

/** "11月11日 星期二", the date line under the clock. */
fun lockScreenDate(now: LocalDateTime): String {
    val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE)
    return "${now.monthValue}月${now.dayOfMonth}日 $weekday"
}

/**
 * Upward drag in px, released at [velocity] px/s (negative = upward), over a page [height] px tall:
 * does it unlock? A quarter of the page, or a deliberate flick, as on iOS. A drag that ended moving
 * down never unlocks however far it went: the user changed their mind.
 */
fun lockScreenUnlocks(dragUp: Float, velocity: Float, height: Float): Boolean {
    if (height <= 0f || velocity > LockScreenFlickBackPxPerSecond) return false
    return dragUp >= height * LockScreenUnlockFraction || -velocity >= LockScreenFlickPxPerSecondPerHeight * height
}

/** Which way a release leaves the page; [NONE] stays. */
enum class LockScreenExit { NONE, UP, DOWN, LEFT, RIGHT }

/**
 * Release of a free drag of ([dx], [dy]) px (dy positive = down) at ([vx], [vy]) px/s over a
 * [width] x [height] page: a swipe in any of the four directions unlocks (the owner asked for up,
 * left, right and down alike). The direction is the one the finger mostly went. Vertically it takes
 * a quarter of the height, sideways a third of the width, or a flick either way. A drag that ends
 * moving back against its own direction stays: the user changed their mind.
 */
fun lockScreenExitOf(dx: Float, dy: Float, vx: Float, vy: Float, width: Float, height: Float): LockScreenExit {
    if (width <= 0f || height <= 0f) return LockScreenExit.NONE
    val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
    val forward = if (horizontal) dx else dy
    if (forward == 0f) return LockScreenExit.NONE
    val sign = if (forward > 0f) 1f else -1f
    val along = forward * sign
    val speed = (if (horizontal) vx else vy) * sign
    if (speed < -LockScreenFlickBackPxPerSecond) return LockScreenExit.NONE
    val span = if (horizontal) width * LockScreenSideUnlockFraction else height * LockScreenUnlockFraction
    val passes = along >= span || speed >= LockScreenFlickPxPerSecondPerHeight * height
    if (!passes) return LockScreenExit.NONE
    return when {
        horizontal && sign > 0f -> LockScreenExit.RIGHT
        horizontal -> LockScreenExit.LEFT
        sign > 0f -> LockScreenExit.DOWN
        else -> LockScreenExit.UP
    }
}

/** Share of the page width a sideways drag must cover to unlock. */
const val LockScreenSideUnlockFraction = 0.33f

/** Share of the page height an upward drag must cover to unlock. */
const val LockScreenUnlockFraction = 0.25f

/** A flick unlocks from this speed, in page heights per second (about 1400dp/s on an 800dp phone). */
const val LockScreenFlickPxPerSecondPerHeight = 1.8f

/** A release still moving down faster than this (px/s) never unlocks. */
const val LockScreenFlickBackPxPerSecond = 200f
