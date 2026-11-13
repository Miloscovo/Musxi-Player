package tech.musxi.player.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.player.ui.theme.Gestures
import kotlin.math.abs

/**
 * The sheet's release and overdrag rules (spec section 9) without Compose. Geometry of an 800dp
 * tall window at density 3 with a 24dp status bar: FULL shows 768dp, MEDIUM 448dp, the dismiss line
 * is at 336dp. Velocities are px/s, positive downward, as the drag reports them.
 */
class SheetPhysicsTest {
    private val density = 3f
    private fun dp(v: Float) = v * density

    private val fullPx = dp(800f - 24f - 8f)
    private val mediumPx = dp(800f * SheetDetent.MEDIUM.fraction)

    private fun target(visibleDp: Float, velocityDpPerSecond: Float): SheetDetent? =
        SheetPhysics.releaseTarget(
            visible = dp(visibleDp),
            velocity = dp(velocityDpPerSecond),
            fullPx = fullPx,
            mediumPx = mediumPx,
            minProjectionPxPerSecond = dp(Gestures.MinProjectionDpPerSecond),
            stepPxPerSecond = dp(Gestures.SheetStepDpPerSecond),
            flingPxPerSecond = dp(Gestures.FlingDpPerSecond),
            slackPx = dp(6f),
        )

    private val medium = 448f
    private val full = 768f

    @Test fun aSlowReleaseStaysWhereItIsNearest() {
        assertEquals(SheetDetent.MEDIUM, target(medium + 40f, 0f))
        assertEquals(SheetDetent.MEDIUM, target(medium + 40f, -200f))
        assertEquals(SheetDetent.FULL, target(full - 40f, 200f))
        assertEquals(SheetDetent.FULL, target(full, 0f))
    }

    // The bug users felt: a 500dp/s flick up from MEDIUM fell back to MEDIUM.
    @Test fun aMediumFlickUpFromMediumOpensFull() {
        assertEquals(SheetDetent.FULL, target(medium + 30f, -500f))
        assertEquals(SheetDetent.FULL, target(medium + 60f, -500f))
    }

    @Test fun aHardFlingUpFromMediumOpensFull() {
        assertEquals(SheetDetent.FULL, target(medium + 30f, -3000f))
    }

    @Test fun aHardFlingDownFromFullStopsAtMediumFirst() {
        assertEquals(SheetDetent.MEDIUM, target(full - 40f, 1500f))
        assertEquals(SheetDetent.MEDIUM, target(full - 120f, 3000f))
        assertEquals("even a very hard one", SheetDetent.MEDIUM, target(full - 40f, 10000f))
    }

    @Test fun aHardFlingDownFromMediumCloses() {
        assertNull(target(medium - 30f, 1500f))
        assertNull(target(medium, 3000f))
    }

    @Test fun aMediumFlickDownFromMediumDoesNotClose() {
        assertEquals(SheetDetent.MEDIUM, target(medium - 30f, 500f))
    }

    @Test fun aLightPullFromFullNeverCloses() {
        // Anywhere above the dismiss line, slow or medium speed: FULL or MEDIUM, never closed.
        var visible = full
        while (visible > 340f) {
            for (speed in listOf(0f, 200f, 500f, 900f)) {
                val result = target(visible, speed)
                assertNotNull("visible $visible dp at $speed dp/s closed", result)
            }
            visible -= 10f
        }
    }

    @Test fun pulledBelowTheDismissLineCloses() {
        assertNull(target(330f, 0f))
        assertNull(target(200f, 100f))
        assertNull(target(330f, 2000f))
    }

    @Test fun belowTheLineAFlingBackUpRescuesIt() {
        assertEquals(SheetDetent.MEDIUM, target(330f, -1500f))
        assertNull("too low to be rescued by a flick", target(60f, -300f))
    }

    @Test fun rubberBandFollowsTheFingerWithoutJumps() {
        val extent = dp(8f)
        assertEquals(0f, SheetPhysics.overdragShown(0f, extent), 0f)
        assertEquals(dp(100f), SheetPhysics.overdragShown(dp(100f), extent), 0f)
        var previous = 0f
        var raw = 0f
        while (raw > -dp(400f)) {
            raw -= 1f
            val shown = SheetPhysics.overdragShown(raw, extent)
            assertTrue("never past the extent", shown > -extent)
            assertTrue("keeps rising, slower and slower", shown < previous && previous - shown <= 1f)
            previous = shown
        }
    }

    @Test fun rubberBandInverseRoundTrips() {
        val extent = dp(30f)
        for (raw in listOf(-1f, -10f, -60f, -200f)) {
            val shown = SheetPhysics.overdragShown(raw, extent)
            assertTrue("raw $raw", abs(SheetPhysics.overdragRaw(shown, extent) - raw) < 0.05f)
        }
        assertEquals(50f, SheetPhysics.overdragRaw(50f, extent), 0f)
        assertEquals("no overdrag allowed", 0f, SheetPhysics.overdragRaw(-5f, 0f), 0f)
    }

    @Test fun aSheetCaughtDeepInTheBandDoesNotNeedAMarathonBack() {
        // Caught right at the hard stop (e.g. an overshoot drawn there): the finger offset it resumes
        // from stays a few extents, not the ~1800 the exact inverse gives at 0.999 of the band.
        val extent = dp(8f)
        val raw = SheetPhysics.overdragRaw(-extent, extent)
        assertTrue("raw $raw", raw < 0f && raw > -10f * extent)
    }

    @Test fun releaseVelocityUsesTheBandsLocalSlope() {
        val extent = dp(8f)
        assertEquals(1f, SheetPhysics.overdragSlope(10f, extent), 0f)
        assertEquals("0.55 just inside the band", Gestures.RubberBandCoefficient, SheetPhysics.overdragSlope(-1e-3f, extent), 1e-3f)
        var previous = 1f
        for (raw in listOf(-1f, -10f, -60f, -200f)) {
            val slope = SheetPhysics.overdragSlope(raw, extent)
            val secant = SheetPhysics.overdragShown(raw, extent) / raw
            assertTrue("stiffer deeper in", slope < previous)
            assertTrue("never more than the secant drawn/raw", slope <= secant + 1e-6f)
            previous = slope
        }
    }
}
