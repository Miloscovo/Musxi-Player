package tech.musxi.player.ui.localaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cover window's arithmetic: what a drag or a pinch means for the crop sent to the backend. */
class CoverCropGeometryTest {

    private fun CoverCropGeometry.CoverCropFit.assertValid() {
        val c = crop()
        assertTrue(c.left >= 0f && c.top >= 0f && c.right <= 1f && c.bottom <= 1f)
        assertTrue(c.left < c.right && c.top < c.bottom)
        // Square in picture pixels, and never smaller than the backend keeps.
        val w = (c.right - c.left) * width
        val h = (c.bottom - c.top) * height
        assertEquals(w, h, 0.01f * maxOf(width, height))
        if (shortSide >= 64f) assertTrue("crop $w px", w >= 64f - 0.5f)
    }

    /** The picture point drawn at window point (x, y). */
    private fun CoverCropGeometry.CoverCropFit.pictureAt(x: Float, y: Float) =
        (x - drawnLeft) / drawnWidth to (y - drawnTop) / drawnHeight

    @Test fun theStartCoversTheShorterSideAndIsCentred() {
        val c = CoverCropGeometry.initial(4000, 2000).crop()
        assertEquals(0.25f, c.left, 1e-4f); assertEquals(0.75f, c.right, 1e-4f)
        assertEquals(0f, c.top, 1e-4f); assertEquals(1f, c.bottom, 1e-4f)
        val t = CoverCropGeometry.initial(1000, 4000).crop()
        assertEquals(0f, t.left, 1e-4f); assertEquals(1f, t.right, 1e-4f)
        assertEquals(0.375f, t.top, 1e-4f); assertEquals(0.625f, t.bottom, 1e-4f)
    }

    @Test fun aSquarePictureStartsAsTheWholePicture() {
        val c = CoverCropGeometry.initial(800, 800).crop()
        assertEquals(0f, c.left, 1e-4f); assertEquals(1f, c.right, 1e-4f)
        assertEquals(0f, c.top, 1e-4f); assertEquals(1f, c.bottom, 1e-4f)
    }

    @Test fun theDrawnPictureMatchesTheCrop() {
        // What the window shows is what gets cut: its corners sit on the crop's corners.
        val fit = CoverCropGeometry.initial(4000, 3000).zoomed(2.5f, 0.2f, 0.7f).dragged(0.1f, -0.05f)
        val c = fit.crop()
        val (l, t) = fit.pictureAt(0f, 0f)
        val (r, b) = fit.pictureAt(1f, 1f)
        assertEquals(c.left, l, 1e-4f); assertEquals(c.top, t, 1e-4f)
        assertEquals(c.right, r, 1e-4f); assertEquals(c.bottom, b, 1e-4f)
    }

    @Test fun zoomStopsAtFourTimesAndAtTheStart() {
        val start = CoverCropGeometry.initial(3000, 2000)
        val tooFar = start.zoomed(100f)
        assertEquals(CoverCropGeometry.MAX_ZOOM, tooFar.zoom, 1e-5f)
        assertTrue(tooFar.atMaxZoom)
        assertEquals(1f, start.zoomed(0.01f).zoom, 1e-5f)
        tooFar.assertValid()
    }

    @Test fun aSmallPictureCannotBeZoomedPastTheBackendsMinimum() {
        val small = CoverCropGeometry.initial(160, 120)
        val max = small.zoomed(100f)
        assertEquals(120f / 64f, max.zoom, 1e-4f)
        max.assertValid()
    }

    @Test fun thePointUnderThePinchStaysUnderIt() {
        val start = CoverCropGeometry.initial(4000, 3000).zoomed(1.5f)
        val before = start.pictureAt(0.3f, 0.6f)
        val after = start.zoomed(1.6f, 0.3f, 0.6f).pictureAt(0.3f, 0.6f)
        assertEquals(before.first, after.first, 1e-4f)
        assertEquals(before.second, after.second, 1e-4f)
    }

    @Test fun thePictureFollowsTheFinger() {
        val fit = CoverCropGeometry.initial(4000, 4000).zoomed(2f)
        val before = fit.pictureAt(0.5f, 0.5f)
        // A finger moving 0.1 window right and down drags the picture with it.
        val moved = fit.dragged(0.1f, 0.1f)
        val after = moved.pictureAt(0.6f, 0.6f)
        assertEquals(before.first, after.first, 1e-4f)
        assertEquals(before.second, after.second, 1e-4f)
        // ...so the window now shows more of the top-left.
        assertTrue(moved.centerX < fit.centerX && moved.centerY < fit.centerY)
    }

    @Test fun draggingStopsAtThePictureEdges() {
        val start = CoverCropGeometry.initial(4000, 2000)
        val left = start.dragged(10f, 10f).crop()
        assertEquals(0f, left.left, 1e-4f); assertEquals(0f, left.top, 1e-4f)
        val right = start.dragged(-10f, -10f).crop()
        assertEquals(1f, right.right, 1e-4f); assertEquals(1f, right.bottom, 1e-4f)
    }

    @Test fun zoomedInTheWindowCanReachEveryCorner() {
        val max = CoverCropGeometry.initial(2000, 3000).zoomed(100f)
        val topLeft = max.dragged(10f, 10f).crop()
        assertEquals(0f, topLeft.left, 1e-4f); assertEquals(0f, topLeft.top, 1e-4f)
        val bottomRight = max.dragged(-10f, -10f).crop()
        assertEquals(1f, bottomRight.right, 1e-4f); assertEquals(1f, bottomRight.bottom, 1e-4f)
    }

    @Test fun anyGestureLeavesAUsableCrop() {
        var fit = CoverCropGeometry.initial(1234, 567)
        for (step in 0 until 60) {
            fit = when (step % 3) {
                0 -> fit.zoomed(if (step % 2 == 0) 1.7f else 0.4f, 0.21f, 0.83f)
                1 -> fit.dragged(0.4f, -0.3f)
                else -> fit.dragged(-0.9f, 0.7f)
            }
            fit.assertValid()
        }
    }

    @Test fun anAnimationEndsWhereItWasHeaded() {
        val from = CoverCropGeometry.initial(4000, 3000)
        val to = from.zoomed(2f, 0.8f, 0.2f)
        assertEquals(to, CoverCropGeometry.between(from, to, 1f))
        assertTrue(CoverCropGeometry.between(from, to, 0f).atStart)
        assertFalse(CoverCropGeometry.between(from, to, 0.5f).atStart)
        CoverCropGeometry.between(from, to, 0.5f).assertValid()
    }
}
