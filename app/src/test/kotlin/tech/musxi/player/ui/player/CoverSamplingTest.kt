package tech.musxi.player.ui.player

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication

/** Real Skia scaling (native graphics), because the point is that the whole cover is read, not a corner. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = DesignTestApplication::class)
class CoverSamplingTest {
    private fun argb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun bitmap(width: Int, height: Int, pixel: (x: Int, y: Int) -> Int): Bitmap {
        val pixels = IntArray(width * height) { pixel(it % width, it / width) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun assertChannels(expected: Int, actual: Int?, tolerance: Int) {
        assertNotNull("no colour", actual)
        listOf(16, 8, 0).forEach { shift ->
            val e = (expected shr shift) and 0xFF
            val a = (actual!! shr shift) and 0xFF
            assertTrue("channel at $shift: expected $e, got $a", kotlin.math.abs(e - a) <= tolerance)
        }
        assertEquals(0xFF, (actual!! ushr 24))
    }

    @Test fun aPlainCoverOfAnyShapeKeepsItsColour() {
        val rose = argb(180, 40, 80)
        assertChannels(rose, sampleBitmapColor(bitmap(100, 60) { _, _ -> rose }), tolerance = 1)
        assertChannels(rose, sampleBitmapColor(bitmap(32, 32) { _, _ -> rose }), tolerance = 0)
        assertChannels(rose, sampleBitmapColor(bitmap(7, 300) { _, _ -> rose }), tolerance = 1)
    }

    @Test fun theWholeCoverCountsNotOneCorner() {
        // Left half red, right half blue, equally saturated: the average is the middle of both.
        val red = argb(200, 40, 40)
        val blue = argb(40, 40, 200)
        val sampled = sampleBitmapColor(bitmap(64, 64) { x, _ -> if (x < 32) red else blue })
        assertChannels(argb(120, 40, 120), sampled, tolerance = 6)
    }

    @Test fun aTransparentCoverHasNoColour() {
        assertNull(sampleBitmapColor(bitmap(40, 40) { _, _ -> 0 }))
    }
}
