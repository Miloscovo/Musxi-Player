package tech.musxi.player.ui.player

/**
 * The songs after the current one, in the order playback will reach them.
 *
 * `upcoming` holds raw slot indices. `shuffled` is true when that order differs from the plain slot
 * order, which is when the panel must show the real order and stop offering drag-to-reorder.
 */
data class QueueOrder(val upcoming: List<Int>, val shuffled: Boolean)

/**
 * Turns the playback timeline into the display order of the "up next" rows.
 *
 * Out-of-range, current and repeated slots are dropped. Sequential playback with repeat-all wraps
 * from the last slot back to the first, so the timeline is compared with the slot order rotated to
 * start after the current song; a match is not shuffled and keeps the plain order the panel draws
 * today. Anything else is shuffled and shown exactly as the timeline lists it. An empty timeline
 * (or one with no usable slot) falls back to slot order.
 */
fun queueDisplayOrder(size: Int, currentIndex: Int, upcoming: List<Int>): QueueOrder {
    val natural = (currentIndex + 1 until size).toList()
    val valid = upcoming
        .filter { it in 0 until size && it != currentIndex }
        .distinct()
    if (valid.isEmpty()) return QueueOrder(natural, shuffled = false)
    val cyclic = natural + (0 until currentIndex.coerceIn(0, size))
    if (valid == cyclic.take(valid.size)) return QueueOrder(natural, shuffled = false)
    return QueueOrder(valid, shuffled = true)
}
