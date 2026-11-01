package tech.musxi.player.lab

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Placement rules of the in-window menu: below when it fits, else above, never over the anchor. */
class LabMenuPlacementTest {
    private val host = IntSize(360, 1000)
    private val gap = 8
    private val margin = 12

    @Test fun belowWhenItFits() {
        val anchor = IntRect(12, 100, 348, 160)
        val p = labMenuPlacement(anchor, host, IntSize(200, 260), gap, margin)
        assertEquals(168, p.offset.y)
        assertEquals(0f, p.origin.pivotFractionY)
    }

    @Test fun aboveWhenBelowIsShort() {
        val anchor = IntRect(12, 800, 348, 860)
        val p = labMenuPlacement(anchor, host, IntSize(200, 260), gap, margin)
        assertEquals(800 - 8 - 260, p.offset.y)
        assertEquals(1f, p.origin.pivotFractionY)
    }

    @Test fun capIsTheLargerSide() {
        val anchor = IntRect(0, 500, 360, 560)
        // Below: 1000 - 12 - (560 + 8) = 420. Above: 500 - 8 - 12 = 480.
        assertEquals(480, labMenuMaxHeight(anchor, host, gap, margin))
    }

    @Test fun leftAnchorAlignsLeftAndRightAnchorAlignsRight() {
        val left = labMenuPlacement(IntRect(20, 100, 120, 160), host, IntSize(200, 260), gap, margin)
        assertEquals(20, left.offset.x)
        val right = labMenuPlacement(IntRect(240, 100, 340, 160), host, IntSize(200, 260), gap, margin)
        assertEquals(140, right.offset.x)
    }

    @Test fun neverCoversTheAnchor() {
        for (top in 0..940 step 20) {
            val anchor = IntRect(12, top, 348, top + 60)
            val card = IntSize(200, minOf(260, labMenuMaxHeight(anchor, host, gap, margin)))
            val p = labMenuPlacement(anchor, host, card, gap, margin)
            val cardTop = p.offset.y
            val cardBottom = p.offset.y + card.height
            assertTrue("anchor top $top", cardBottom <= anchor.top || cardTop >= anchor.bottom)
        }
    }
}
