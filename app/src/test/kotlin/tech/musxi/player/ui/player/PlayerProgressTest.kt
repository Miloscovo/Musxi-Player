package tech.musxi.player.ui.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.player.ui.theme.Haptic

/** The pull half of the controller: the finger drives p one to one and the threshold buzzes once per drag. */
class PlayerProgressTest {
    private val buzzes = mutableListOf<Haptic>()

    private fun progress(initial: Float): PlayerProgress =
        PlayerProgress(initial, CoroutineScope(Dispatchers.Unconfined)).also {
            it.travel = 1000f
            it.haptic = { haptic -> buzzes += haptic }
        }

    @Test fun pullFollowsTheFingerWithoutLag() {
        val p = progress(0f)
        p.pullBegin()
        p.pullBy(-300f)
        assertEquals(0.3f, p.p.value, 1e-4f)
        p.pullBy(100f)
        assertEquals(0.2f, p.p.value, 1e-4f)
        assertEquals(-200f, p.pullTotal, 1e-4f)
    }

    @Test fun pullNeverLeavesTheZeroToOneRange() {
        val p = progress(0f)
        p.pullBegin()
        p.pullBy(400f)
        assertEquals(0f, p.p.value, 0f)
        p.pullBy(-2000f)
        assertEquals(1f, p.p.value, 0f)
    }

    @Test fun expandBuzzesOnceWhenItPassesTheThreshold() {
        val p = progress(0f)
        p.pullBegin()
        p.pullBy(-200f)
        assertTrue("20% is short of the threshold", buzzes.isEmpty())
        p.pullBy(-200f)
        assertEquals(listOf(Haptic.THRESHOLD), buzzes)
    }

    @Test fun passingTheThresholdAgainDoesNotBuzzAgain() {
        val p = progress(0f)
        p.pullBegin()
        p.pullBy(-400f)
        p.pullBy(300f)
        p.pullBy(-400f)
        assertEquals(1, buzzes.size)
    }

    @Test fun collapseBuzzesWhenItHasGoneFarEnoughDown() {
        val p = progress(1f)
        p.pullBegin()
        p.pullBy(300f)
        assertTrue("30% down is short of the threshold", buzzes.isEmpty())
        p.pullBy(100f)
        assertEquals(listOf(Haptic.THRESHOLD), buzzes)
    }

    @Test fun aNewDragMayBuzzAgain() {
        val p = progress(0f)
        p.pullBegin()
        p.pullBy(-500f)
        assertEquals(1, buzzes.size)
        p.pullBegin()
        p.pullBy(-100f)
        assertEquals(2, buzzes.size)
    }

    @Test fun busyAndCoveringFollowTheValue() {
        val p = progress(0f)
        assertFalse(p.busy)
        assertFalse(p.covering)
        p.pullBegin()
        p.pullBy(-100f)
        assertTrue(p.busy)
        assertFalse(p.covering)
        p.pullBy(-500f)
        assertTrue(p.covering)
    }
}
