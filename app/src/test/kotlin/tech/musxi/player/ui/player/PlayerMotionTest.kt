package tech.musxi.player.ui.player

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerMotionTest {
    private val travel = 1000f

    @Test fun miniControlsAreGoneByThirtyPercent() {
        assertEquals(1f, PlayerMotion.miniAlpha(0f), 0.0001f)
        assertEquals(0.5f, PlayerMotion.miniAlpha(0.15f), 0.0001f)
        assertEquals(0f, PlayerMotion.miniAlpha(0.3f), 0.0001f)
        assertEquals(0f, PlayerMotion.miniAlpha(1f), 0.0001f)
    }

    @Test fun fullControlsFadeInAfterHalf() {
        assertEquals(0f, PlayerMotion.fullAlpha(0.5f), 0.0001f)
        assertEquals(0f, PlayerMotion.fullAlpha(0.2f), 0.0001f)
        assertEquals(0.5f, PlayerMotion.fullAlpha(0.65f), 0.0001f)
        assertEquals(1f, PlayerMotion.fullAlpha(0.8f), 0.0001f)
        assertEquals(1f, PlayerMotion.fullAlpha(1f), 0.0001f)
    }

    @Test fun miniAndFullControlsNeverShowTogether() {
        for (i in 0..100) {
            val p = i / 100f
            assertTrue("p=$p", PlayerMotion.miniAlpha(p) == 0f || PlayerMotion.fullAlpha(p) == 0f)
        }
    }

    @Test fun backdropGrowsMonotonicallyFromZeroToOne() {
        assertEquals(0f, PlayerMotion.backdropAlpha(0f), 0.0001f)
        assertEquals(1f, PlayerMotion.backdropAlpha(0.55f), 0.0001f)
        var last = 0f
        for (i in 0..100) {
            val value = PlayerMotion.backdropAlpha(i / 100f)
            assertTrue(value >= last - 1e-6f)
            last = value
        }
    }

    @Test fun surfaceFillIsSeeThroughAtRest() {
        assertEquals(0f, PlayerMotion.fillAlpha(0f), 0.0001f)
        assertEquals(1f, PlayerMotion.fillAlpha(0.08f), 0.0001f)
    }

    @Test fun coverFadesOnlyOnTheWayToLyrics() {
        // Cover on screen: always opaque. Lyrics on screen: opaque while docked, gone at the full page.
        assertEquals(1f, PlayerMotion.coverAlpha(1f, shown = 1f), 0.0001f)
        assertEquals(1f, PlayerMotion.coverAlpha(0.3f, shown = 0f), 0.0001f)
        assertEquals(0.5f, PlayerMotion.coverAlpha(0.75f, shown = 0f), 0.0001f)
        assertEquals(0f, PlayerMotion.coverAlpha(1f, shown = 0f), 0.0001f)
    }

    @Test fun rectInterpolationHitsBothEnds() {
        val mini = Rect(10f, 20f, 58f, 68f)
        val big = Rect(40f, 100f, 340f, 400f)
        assertEquals(mini, PlayerMotion.lerpRect(mini, big, 0f))
        assertEquals(big, PlayerMotion.lerpRect(mini, big, 1f))
        val half = PlayerMotion.lerpRect(mini, big, 0.5f)
        assertEquals(25f, half.left, 0.0001f)
        assertEquals(60f, half.top, 0.0001f)
        assertEquals(199f, half.right, 0.0001f)
        assertEquals(234f, half.bottom, 0.0001f)
    }

    @Test fun pullFollowsTheFingerOneToOneAndClamps() {
        assertEquals(0.3f, PlayerMotion.pullProgress(0f, -300f, travel), 0.0001f)
        assertEquals(0f, PlayerMotion.pullProgress(0f, 200f, travel), 0.0001f)
        assertEquals(1f, PlayerMotion.pullProgress(0.9f, -500f, travel), 0.0001f)
        assertEquals(0.7f, PlayerMotion.pullProgress(1f, 300f, travel), 0.0001f)
    }

    @Test fun thresholdIsCrossedPastThirtyFivePercentInEitherDirection() {
        assertFalse(PlayerMotion.pastThreshold(0.34f, fromExpanded = false))
        assertTrue(PlayerMotion.pastThreshold(0.36f, fromExpanded = false))
        assertFalse(PlayerMotion.pastThreshold(0.66f, fromExpanded = true))
        assertTrue(PlayerMotion.pastThreshold(0.64f, fromExpanded = true))
    }

    @Test fun releaseExpandsOnDistanceOrOnAnUpwardFlick() {
        // From the mini row: 40% up expands, 20% up does not, 20% plus a fast upward flick does.
        assertTrue(PlayerMotion.expandsOnRelease(0.4f, 0f, travel, fromExpanded = false))
        assertFalse(PlayerMotion.expandsOnRelease(0.2f, 0f, travel, fromExpanded = false))
        assertTrue(PlayerMotion.expandsOnRelease(0.2f, -1000f, travel, fromExpanded = false))
        // A downward flick from the mini row cancels even a long pull.
        assertFalse(PlayerMotion.expandsOnRelease(0.4f, 2000f, travel, fromExpanded = false))
    }

    @Test fun releaseCollapsesOnDistanceOrADownwardFlick() {
        // From the full page: pulled down 40% collapses, 20% does not, 20% plus a fast flick down does.
        assertFalse(PlayerMotion.expandsOnRelease(0.6f, 0f, travel, fromExpanded = true))
        assertTrue(PlayerMotion.expandsOnRelease(0.8f, 0f, travel, fromExpanded = true))
        assertFalse(PlayerMotion.expandsOnRelease(0.8f, 1000f, travel, fromExpanded = true))
        // An upward flick from the full page keeps it open even after a long pull.
        assertTrue(PlayerMotion.expandsOnRelease(0.6f, -2000f, travel, fromExpanded = true))
    }

    @Test fun swipeFollowsTheFingerWhereASongExists() {
        assertEquals(-120f, PlayerMotion.swipeOffset(-120f, hasPrevious = true, hasNext = true, reduced = false), 0.0001f)
        assertEquals(80f, PlayerMotion.swipeOffset(80f, hasPrevious = true, hasNext = true, reduced = false), 0.0001f)
    }

    @Test fun swipeIsHeldBackAtTheEnds() {
        // No next song: dragging left moves at half speed. No previous song: dragging right likewise.
        assertEquals(-60f, PlayerMotion.swipeOffset(-120f, hasPrevious = true, hasNext = false, reduced = false), 0.0001f)
        assertEquals(40f, PlayerMotion.swipeOffset(80f, hasPrevious = false, hasNext = true, reduced = false), 0.0001f)
        // Reduced motion: the cover stays put instead of stretching.
        assertEquals(0f, PlayerMotion.swipeOffset(-120f, hasPrevious = true, hasNext = false, reduced = true), 0.0001f)
        // The open side is never held back.
        assertEquals(-120f, PlayerMotion.swipeOffset(-120f, hasPrevious = false, hasNext = true, reduced = false), 0.0001f)
    }

    @Test fun swipeChangesSongPastThirtyPercentOfTheCoverOrOnAFling() {
        val width = 300f
        val fling = 1000f * 3f
        assertEquals(1, PlayerMotion.swipeDirection(-91f, 0f, width, fling, hasPrevious = true, hasNext = true))
        assertEquals(0, PlayerMotion.swipeDirection(-89f, 0f, width, fling, hasPrevious = true, hasNext = true))
        assertEquals(-1, PlayerMotion.swipeDirection(91f, 0f, width, fling, hasPrevious = true, hasNext = true))
        assertEquals(1, PlayerMotion.swipeDirection(-20f, -3500f, width, fling, hasPrevious = true, hasNext = true))
        assertEquals(-1, PlayerMotion.swipeDirection(20f, 3500f, width, fling, hasPrevious = true, hasNext = true))
        assertEquals(0, PlayerMotion.swipeDirection(-20f, -2500f, width, fling, hasPrevious = true, hasNext = true))
    }

    @Test fun swipeNeverChangesSongWhereNoneExists() {
        val width = 300f
        val fling = 3000f
        assertEquals(0, PlayerMotion.swipeDirection(-200f, -5000f, width, fling, hasPrevious = true, hasNext = false))
        assertEquals(0, PlayerMotion.swipeDirection(200f, 5000f, width, fling, hasPrevious = false, hasNext = true))
    }
}
