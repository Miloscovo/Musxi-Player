package tech.musxi.playback

import androidx.media3.common.Player
import androidx.media3.common.Timeline

/**
 * Returns window indices that would follow [currentIndex] in the supplied timeline.
 *
 * The order comes from Timeline's own shuffle configuration. [Player.REPEAT_MODE_ALL]
 * may wrap to the beginning but the current window is omitted. In
 * [Player.REPEAT_MODE_ONE] the automatic next item is the current item, so there are
 * no distinct upcoming items and an empty list is returned.
 */
internal fun upcomingWindowIndices(
    timeline: Timeline,
    currentIndex: Int,
    shuffleEnabled: Boolean,
    repeatMode: Int,
): List<Int> {
    val windowCount = timeline.windowCount
    if (
        windowCount == 0 ||
        currentIndex !in 0 until windowCount ||
        (repeatMode != Player.REPEAT_MODE_OFF && repeatMode != Player.REPEAT_MODE_ALL)
    ) {
        return emptyList()
    }

    val seen = HashSet<Int>(windowCount)
    seen += currentIndex
    val upcoming = ArrayList<Int>(windowCount - 1)
    var windowIndex =
        timeline.getNextWindowIndex(currentIndex, repeatMode, shuffleEnabled)

    while (windowIndex >= 0) {
        if (windowIndex !in 0 until windowCount || !seen.add(windowIndex)) break
        upcoming += windowIndex
        windowIndex =
            timeline.getNextWindowIndex(windowIndex, repeatMode, shuffleEnabled)
    }
    return upcoming
}
