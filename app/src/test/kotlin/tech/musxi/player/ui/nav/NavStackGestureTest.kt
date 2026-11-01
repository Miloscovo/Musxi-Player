package tech.musxi.player.ui.nav

import androidx.activity.BackEventCompat
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
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

/** The system back gesture driving the top page: following the finger, committing, cancelling, grabbing. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class NavStackGestureTest {
    @get:Rule val compose = createComposeRule()

    private fun host(): NavStackState {
        lateinit var nav: NavStackState
        compose.setContent { nav = rememberNavStackState("root") }
        compose.waitForIdle()
        return nav
    }

    private fun pushed(nav: NavStackState, key: String) {
        compose.runOnIdle { assertTrue(nav.push(key)) }
        compose.waitForIdle()
    }

    private fun gesture(nav: NavStackState, style: BackStyle, events: Channel<BackEventCompat>): Job =
        CoroutineScope(Dispatchers.Main.immediate).launch { nav.handleBack(events.receiveAsFlow(), style) }

    private fun event(progress: Float, edge: Int = BackEventCompat.EDGE_LEFT) =
        BackEventCompat(if (edge == BackEventCompat.EDGE_LEFT) 10f else 350f, 400f, progress, edge)

    @Test fun committedGestureFollowsTheFingerThenFinishesThePop() {
        val nav = host()
        pushed(nav, "playlist-0")
        val events = Channel<BackEventCompat>(Channel.UNLIMITED)
        val job = gesture(nav, BackStyle.FOLLOW, events)
        events.trySend(event(0.4f))
        compose.waitForIdle()
        assertTrue(nav.dragging)
        assertEquals(0.4f, nav.exit, 1e-4f)
        events.close()
        compose.waitForIdle()
        assertEquals(1, nav.depth)
        assertEquals(0f, nav.exit, 0f)
        assertFalse(nav.dragging)
        assertTrue(job.isCompleted)
    }

    @Test fun cancelledGestureSpringsBackAndKeepsThePage() {
        val nav = host()
        pushed(nav, "playlist-0")
        val events = Channel<BackEventCompat>(Channel.UNLIMITED)
        val job = gesture(nav, BackStyle.FOLLOW, events)
        events.trySend(event(0.4f))
        compose.waitForIdle()
        job.cancel()
        compose.waitForIdle()
        assertEquals(2, nav.depth)
        assertEquals(0f, nav.exit, 0f)
        assertFalse(nav.dragging)
    }

    @Test fun shrinkFromTheRightEdgeDriftsLeftAndStopsAtThePhase() {
        val nav = host()
        pushed(nav, "playlist-0")
        val events = Channel<BackEventCompat>(Channel.UNLIMITED)
        val job = gesture(nav, BackStyle.SHRINK, events)
        events.trySend(event(1f, BackEventCompat.EDGE_RIGHT))
        compose.waitForIdle()
        assertEquals(BackStyle.SHRINK, nav.style)
        assertEquals(-1f, nav.direction, 0f)
        assertEquals(NavPose.ShrinkPhase, nav.exit, 1e-4f)
        job.cancel()
        compose.waitForIdle()
        assertEquals(2, nav.depth)
    }

    @Test fun committedShrinkGestureStillEndsWithThePagePopped() {
        val nav = host()
        pushed(nav, "playlist-0")
        val events = Channel<BackEventCompat>(Channel.UNLIMITED)
        gesture(nav, BackStyle.SHRINK, events)
        events.trySend(event(1f))
        compose.waitForIdle()
        events.close()
        compose.waitForIdle()
        assertEquals(1, nav.depth)
        assertEquals(0f, nav.exit, 0f)
        assertEquals(BackStyle.FOLLOW, nav.style)
    }

    @Test fun gestureWithoutProgressIsAnOrdinaryPop() {
        val nav = host()
        pushed(nav, "playlist-0")
        val events = Channel<BackEventCompat>(Channel.UNLIMITED)
        gesture(nav, BackStyle.SHRINK, events)
        events.close()
        compose.waitForIdle()
        assertEquals(1, nav.depth)
        assertEquals(BackStyle.FOLLOW, nav.style)
    }

    @Test fun gestureAtTheRootChangesNothing() {
        val nav = host()
        val events = Channel<BackEventCompat>(Channel.UNLIMITED)
        gesture(nav, BackStyle.FOLLOW, events)
        events.trySend(event(0.5f))
        events.close()
        compose.waitForIdle()
        assertEquals(1, nav.depth)
        assertEquals(0f, nav.exit, 0f)
        assertFalse(nav.dragging)
    }

    @Test fun buttonPopsAreRefusedWhileAFingerHoldsThePage() {
        val nav = host()
        pushed(nav, "playlist-0")
        val events = Channel<BackEventCompat>(Channel.UNLIMITED)
        val job = gesture(nav, BackStyle.FOLLOW, events)
        events.trySend(event(0.3f))
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(nav.pop())
            assertFalse(nav.popToRoot())
            assertFalse(nav.push("other"))
        }
        job.cancel()
        compose.waitForIdle()
        assertEquals(2, nav.depth)
    }

    @Test fun grabbingARunningPushKeepsItsSlideAndItsPosition() {
        val nav = host()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { nav.push("playlist-0") }
        compose.mainClock.advanceTimeBy(120)
        val mid = nav.exit
        val events = Channel<BackEventCompat>(Channel.UNLIMITED)
        val job = gesture(nav, BackStyle.SHRINK, events)
        events.trySend(event(0f))
        assertEquals(BackStyle.FOLLOW, nav.style)
        assertEquals(mid, nav.exit, 1e-3f)
        job.cancel()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(2, nav.depth)
        assertEquals(0f, nav.exit, 0f)
    }
}
