package tech.musxi.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "up next" order in the queue panel.
 *
 * Sequential playback has no timeline to read, so the order must be the plain slot order. Shuffled
 * playback reports its real timeline, and the panel has to show that order, not the slot order.
 */
class QueueOrderTest {

    @Test fun `empty queue gives an empty order`() {
        val order = queueDisplayOrder(0, -1, emptyList())
        assertEquals(emptyList<Int>(), order.upcoming)
        assertFalse(order.shuffled)
    }

    @Test fun `no upcoming list falls back to slot order after the current song`() {
        val order = queueDisplayOrder(5, 1, emptyList())
        assertEquals(listOf(2, 3, 4), order.upcoming)
        assertFalse(order.shuffled)
    }

    @Test fun `upcoming equal to slot order is not shuffled`() {
        val order = queueDisplayOrder(5, 1, listOf(2, 3, 4))
        assertEquals(listOf(2, 3, 4), order.upcoming)
        assertFalse(order.shuffled)
    }

    @Test fun `timeline that differs from slot order is shuffled and kept as given`() {
        val order = queueDisplayOrder(5, 1, listOf(4, 2, 3))
        assertEquals(listOf(4, 2, 3), order.upcoming)
        assertTrue(order.shuffled)
    }

    @Test fun `out of range slots are dropped`() {
        val order = queueDisplayOrder(4, 0, listOf(-1, 7, 3, 2, 1))
        assertEquals(listOf(3, 2, 1), order.upcoming)
        assertTrue(order.shuffled)
    }

    @Test fun `current slot inside the timeline is dropped`() {
        val order = queueDisplayOrder(4, 2, listOf(2, 3))
        assertEquals(listOf(3), order.upcoming)
        assertFalse(order.shuffled)
    }

    @Test fun `repeated slots are shown once`() {
        val order = queueDisplayOrder(5, 0, listOf(3, 3, 1, 3, 2, 4, 4))
        assertEquals(listOf(3, 1, 2, 4), order.upcoming)
        assertTrue(order.shuffled)
    }

    @Test fun `shuffled timeline is kept as given without filling in missing slots`() {
        val order = queueDisplayOrder(6, 0, listOf(4, 2))
        assertEquals(listOf(4, 2), order.upcoming)
        assertTrue(order.shuffled)
    }

    // Sequential playback is repeat-all, so the timeline wraps past the last slot to the first.
    @Test fun `repeat all wrapping back to the start is not shuffled`() {
        val order = queueDisplayOrder(5, 2, listOf(3, 4, 0, 1))
        assertEquals(listOf(3, 4), order.upcoming)
        assertFalse(order.shuffled)
    }

    @Test fun `shuffled timeline that wraps is shown as given`() {
        val order = queueDisplayOrder(5, 2, listOf(4, 0, 3, 1))
        assertEquals(listOf(4, 0, 3, 1), order.upcoming)
        assertTrue(order.shuffled)
    }

    @Test fun `only invalid entries fall back to slot order`() {
        val order = queueDisplayOrder(3, 0, listOf(0, 9, -4))
        assertEquals(listOf(1, 2), order.upcoming)
        assertFalse(order.shuffled)
    }

    @Test fun `current index -1 uses the whole queue in slot order`() {
        val order = queueDisplayOrder(3, -1, emptyList())
        assertEquals(listOf(0, 1, 2), order.upcoming)
        assertFalse(order.shuffled)
    }

    @Test fun `current index -1 with a timeline keeps the timeline`() {
        val order = queueDisplayOrder(3, -1, listOf(2, 0, 1))
        assertEquals(listOf(2, 0, 1), order.upcoming)
        assertTrue(order.shuffled)
    }

    @Test fun `last song has nothing upcoming`() {
        val order = queueDisplayOrder(3, 2, emptyList())
        assertEquals(emptyList<Int>(), order.upcoming)
        assertFalse(order.shuffled)
    }
}
