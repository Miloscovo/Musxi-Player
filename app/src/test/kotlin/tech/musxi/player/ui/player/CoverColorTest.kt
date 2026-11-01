package tech.musxi.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mirrors the PC client's vinylColor tests so both clients agree on a cover's tint. */
class CoverColorTest {
    private fun argb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun samplesVisibleColors() {
        val rose = argb(180, 40, 80)
        assertEquals(rose, averageCoverColor(intArrayOf(rose, rose)))
    }

    @Test fun ignoresTransparentAndExtremePixels() {
        val pixels = intArrayOf(argb(180, 40, 80), argb(0, 0, 0), argb(255, 255, 255), argb(0, 0, 255, a = 0))
        assertEquals(argb(180, 40, 80), averageCoverColor(pixels))
    }

    @Test fun nothingUsableGivesNull() {
        assertNull(averageCoverColor(intArrayOf(argb(0, 0, 0, a = 0), argb(255, 255, 255))))
        assertNull(averageCoverColor(IntArray(0)))
    }

    @Test fun keepsNeutralGrey() {
        assertEquals(argb(128, 128, 128), averageCoverColor(intArrayOf(argb(128, 128, 128))))
    }

    @Test fun saturatedPixelsWeighMore() {
        // Red weighs 1 + 200/32 = 7.25, grey weighs 1.
        // Red channel (240 * 7.25 + 120) / 8.25 = 225.45 → 225; green and blue (40 * 7.25 + 120) / 8.25 = 49.7 → 50.
        assertEquals(argb(225, 50, 50), averageCoverColor(intArrayOf(argb(240, 40, 40), argb(120, 120, 120))))
    }
}
