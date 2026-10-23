package tech.musxi.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The arithmetic behind drag-to-reorder.
 *
 * Worth testing on its own because the gesture itself cannot be captured in a design sheet: the
 * threshold, the clamping at both ends and the no-op case are exactly the parts that break silently.
 */
class QueueDragTest {

    private val row = 120f // 60dp at xhdpi

    @Test fun `no movement keeps the row in place`() {
        assertEquals(3, dragTargetIndex(3, 0f, row, 10))
    }

    @Test fun `less than half a row does not move it`() {
        assertEquals(3, dragTargetIndex(3, row * 0.49f, row, 10))
    }

    @Test fun `half a row is the threshold`() {
        assertEquals(4, dragTargetIndex(3, row * 0.5f, row, 10))
    }

    @Test fun `dragging down two rows moves it two`() {
        assertEquals(5, dragTargetIndex(3, row * 1.8f, row, 10))
    }

    @Test fun `dragging up moves it up`() {
        assertEquals(1, dragTargetIndex(3, -row * 1.6f, row, 10))
    }

    @Test fun `over-dragging past the end is clamped`() {
        assertEquals(9, dragTargetIndex(8, row * 5f, row, 10))
        assertEquals(0, dragTargetIndex(1, -row * 5f, row, 10))
    }

    @Test fun `an empty or unmeasured list is a no-op`() {
        assertEquals(0, dragTargetIndex(0, row * 3f, row, 0))
        assertEquals(2, dragTargetIndex(2, row * 3f, 0f, 10))
    }
}
