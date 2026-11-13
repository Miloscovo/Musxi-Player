package tech.musxi.player.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RubberBandTest {
    private val extent = 100f

    @Test fun nothingShowsWithoutAPull() {
        assertEquals(0f, Gestures.rubberBand(0f, extent), 0f)
        assertEquals(0f, Gestures.rubberBand(-20f, extent), 0f)
        assertEquals(0f, Gestures.rubberBand(50f, 0f), 0f)
    }

    @Test fun growsWithThePull() {
        var previous = 0f
        for (step in 1..400) {
            val shown = Gestures.rubberBand(step * 5f, extent)
            assertTrue("pull ${step * 5}: $shown should exceed $previous", shown > previous)
            previous = shown
        }
    }

    @Test fun neverReachesTheExtent() {
        for (pull in listOf(1f, 100f, 1_000f, 100_000f)) {
            assertTrue(Gestures.rubberBand(pull, extent) < extent)
        }
    }

    @Test fun startsAtTheCoefficientSlope() {
        val pull = 0.5f
        val slope = Gestures.rubberBand(pull, extent) / pull
        assertEquals(Gestures.RubberBandCoefficient, slope, 0.01f)
    }

    @Test fun getsHeavierTheFurtherItGoes() {
        val early = Gestures.rubberBand(10f, extent) - Gestures.rubberBand(0f, extent)
        val late = Gestures.rubberBand(310f, extent) - Gestures.rubberBand(300f, extent)
        assertTrue("later pulls should move it less ($early vs $late)", late < early)
    }
}
