package tech.musxi.player.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListMotionHelpersTest {
    @Test fun theNextPageIsAskedForSixRowsBeforeTheEnd() {
        assertFalse(nearEnd(lastVisibleIndex = 10, totalCount = 40))
        assertTrue(nearEnd(lastVisibleIndex = 33, totalCount = 40))
        assertTrue(nearEnd(lastVisibleIndex = 39, totalCount = 40))
        assertTrue("a short list is already near its end", nearEnd(lastVisibleIndex = 2, totalCount = 5))
    }

    @Test fun anEmptyOrUnmeasuredListNeverAsks() {
        assertFalse(nearEnd(lastVisibleIndex = -1, totalCount = 40))
        assertFalse(nearEnd(lastVisibleIndex = 0, totalCount = 0))
    }

    @Test fun barsStayWithinTheirRangeAndMoveOutOfStep() {
        for (i in 0 until 3) {
            for (step in 0..200) {
                val h = barFraction(i, step * 0.017)
                assertTrue("bar $i at $step: $h", h in 0.3f..1f)
            }
        }
        // At the same moment the three bars are not all the same height: they never march in step.
        val t = 0.4
        val heights = (0 until 3).map { barFraction(it, t) }
        assertTrue(heights.toSet().size > 1)
        // Each bar comes back to where it was after its own period.
        assertEquals(barFraction(1, 0.2), barFraction(1, 0.2 + 0.9), 1e-4f)
    }
}
