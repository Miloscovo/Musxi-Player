package tech.musxi.player.ui.player

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.theme.Haptic

/**
 * The horizontal half of the surface drag, driven directly: the queue index arrives a little after
 * the finger lets go, and the covers must not jump when it does (spec section 8, cover swipe).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class CoverSwipeTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var swipe: CoverSwipe
    private val buzzes = mutableListOf<Haptic>()
    private var nexts = 0
    private var previouses = 0

    private fun host(hasPrevious: Boolean = true, hasNext: Boolean = true, neighbours: Boolean = true) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            swipe = remember { CoverSwipe(scope, 3) }
        }
        compose.runOnIdle {
            swipe.slot = 360f
            swipe.coverWidth = 300f
            swipe.density = 1f
            swipe.hasPrevious = hasPrevious
            swipe.hasNext = hasNext
            if (neighbours) {
                swipe.previous = CoverRef("previous", null)
                swipe.next = CoverRef("next", null)
            }
            swipe.haptic = { buzzes += it }
            swipe.onNext = { nexts++ }
            swipe.onPrevious = { previouses++ }
        }
    }

    private fun flick(dx: Float, velocity: Float) = compose.runOnIdle {
        swipe.onLock(false)
        swipe.onDelta(false, dx)
        swipe.onEnd(false, velocity)
    }

    @Test fun aFlingLeftAsksForTheNextSongAndTicksOnce() {
        host()
        flick(-40f, -4000f)
        assertEquals(1, nexts)
        assertEquals(0, previouses)
        assertEquals(listOf(Haptic.TICK), buzzes)
    }

    @Test fun theNewSongTakingOverDoesNotMoveAnyCover() {
        host()
        flick(-200f, -4000f)
        // The queue moves on: the anchor follows and the offset takes up one slot, so the cover that
        // was sliding in keeps its place and only the settle animation runs on.
        compose.runOnIdle {
            val before = swipe.offset
            swipe.indexChanged(4)
            assertEquals(4, swipe.anchor)
            assertEquals(before + 360f, swipe.offset, 1e-3f)
        }
        compose.waitForIdle()
        assertEquals(4, swipe.anchor)
        assertEquals(0f, swipe.offset, 1e-3f)
    }

    @Test fun aSongChangeNobodyAskedForSnapsWithoutSliding() {
        host()
        compose.runOnIdle {
            swipe.indexChanged(4)
            assertEquals(4, swipe.anchor)
            assertEquals(0f, swipe.offset, 0f)
            assertFalse("no slide to follow", swipe.following())
        }
    }

    @Test fun aJumpOverSeveralSongsSnaps() {
        host()
        compose.runOnIdle {
            swipe.indexChanged(6)
            assertEquals(6, swipe.anchor)
            assertEquals(0f, swipe.offset, 0f)
        }
    }

    @Test fun theQueueMovingUnderTheFingerSettlesInsteadOfChangingSong() {
        host()
        compose.runOnIdle {
            swipe.onLock(false)
            swipe.onDelta(false, -100f)
            swipe.indexChanged(4)
            assertEquals(4, swipe.anchor)
            assertTrue(swipe.following())
            swipe.onEnd(false, 0f)
        }
        compose.waitForIdle()
        assertEquals("no extra song change", 0, nexts + previouses)
        assertEquals(4, swipe.anchor)
        assertEquals(0f, swipe.offset, 1e-3f)
    }

    @Test fun withoutANextSongTheCoverIsHeldBackAndComesHome() {
        host(hasNext = false)
        compose.runOnIdle {
            swipe.onLock(false)
            swipe.onDelta(false, -200f)
            assertEquals("half speed past the last song", -100f, swipe.offset, 1e-3f)
            swipe.onEnd(false, -4000f)
        }
        compose.waitForIdle()
        assertEquals(0, nexts)
        assertTrue(buzzes.isEmpty())
        assertEquals(0f, swipe.offset, 1e-3f)
    }

    @Test fun whenTheNeighbourIsUnknownTheSongStillChangesAndTheCoverReturns() {
        // Shuffle: the next song is not the next row, so nothing is drawn to slide in.
        host(neighbours = false)
        flick(-40f, -4000f)
        compose.waitForIdle()
        assertEquals(1, nexts)
        assertEquals(listOf(Haptic.TICK), buzzes)
        assertEquals(3, swipe.anchor)
        assertEquals(0f, swipe.offset, 1e-3f)
    }

    @Test fun aShortSlowDragKeepsTheSong() {
        host()
        flick(-60f, -200f)
        compose.waitForIdle()
        assertEquals(0, nexts + previouses)
        assertTrue(buzzes.isEmpty())
        assertEquals(0f, swipe.offset, 1e-3f)
    }
}
