package tech.musxi.player.lab

import androidx.compose.animation.core.animate
import kotlinx.coroutines.launch
import tech.musxi.player.ui.theme.Gestures
import tech.musxi.player.ui.theme.Haptic

/*
 * Horizontal cover swipe. Cover n sits at (n - index) * slot + swipe, so changing the index by
 * `dir` while adding `dir * slot` to swipe leaves every cover where it was: the commit is
 * invisible and the settle animation simply runs swipe back to zero.
 */

internal fun PlayerLabState.swipeBegin() {
    swipeJob?.cancel()
    swipeRaw = swipe
}

internal fun PlayerLabState.swipeBy(dx: Float) {
    swipeRaw += dx
    // Past the first or last song the cover follows at half speed (Gestures.RubberBand).
    val blocked = (swipeRaw > 0f && index == 0) || (swipeRaw < 0f && index == songCount - 1)
    swipe = when {
        !blocked -> swipeRaw
        reduced -> 0f
        else -> swipeRaw * Gestures.RubberBand
    }
}

internal fun PlayerLabState.swipeEnd(velocity: Float) {
    val width = layout.bigCover.width
    val fling = Gestures.FlingDpPerSecond * density
    val hasNext = index < songCount - 1
    val hasPrevious = index > 0
    val dir = when {
        velocity <= -fling && hasNext -> 1
        velocity >= fling && hasPrevious -> -1
        swipe <= -width * Gestures.CoverSwipeFraction && hasNext -> 1
        swipe >= width * Gestures.CoverSwipeFraction && hasPrevious -> -1
        else -> 0
    }
    if (dir == 0) {
        settle(velocity)
    } else {
        haptic(Haptic.TICK)
        commit(dir, velocity)
    }
}

/** Moves to the neighbouring song at once and lets the covers glide into place. */
internal fun PlayerLabState.commit(dir: Int, velocity: Float) {
    swipeJob?.cancel()
    index += dir
    swipe += dir * layout.slot
    settle(velocity)
}

internal fun PlayerLabState.settle(velocity: Float) {
    swipeJob?.cancel()
    swipeJob = scope.launch {
        animate(swipe, 0f, velocity, tuning.release()) { value, _ -> swipe = value }
    }
}

/** Previous / next buttons. Slides the covers only when the big cover is showing. */
internal fun PlayerLabState.step(dir: Int) {
    if (index + dir !in 0 until songCount) return
    if (coverInteractive()) {
        commit(dir, 0f)
    } else {
        swipeJob?.cancel()
        index += dir
        swipe = 0f
    }
}

/** Jump to any song in the queue without a slide. */
internal fun PlayerLabState.jumpTo(target: Int) {
    swipeJob?.cancel()
    index = target.coerceIn(0, songCount - 1)
    swipe = 0f
}
