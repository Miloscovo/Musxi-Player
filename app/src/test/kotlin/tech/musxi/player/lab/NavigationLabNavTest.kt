package tech.musxi.player.lab

import androidx.activity.BackEventCompat
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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

/** The stack controller on its own: push limits, interruption, and the back gesture. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class NavigationLabNavTest {
    @get:Rule val compose = createComposeRule()

    private val discarded = mutableListOf<LabRoute>()
    private val standard: FiniteAnimationSpec<Float> get() = LabTuning.STANDARD.standard()

    private fun host(tab: LabTab = LabTab.LIBRARY): LabTabNav {
        lateinit var nav: LabTabNav
        compose.setContent {
            val scope = rememberCoroutineScope()
            nav = remember { LabTabNav(tab, scope, { discarded += it }) }
        }
        compose.waitForIdle()
        return nav
    }

    private fun pushed(nav: LabTabNav, vararg routes: LabRoute) {
        routes.forEach { route ->
            compose.runOnIdle { assertTrue(nav.push(route, standard)) }
            compose.waitForIdle()
        }
    }

    private fun gesture(nav: LabTabNav, style: BackStyle, events: Channel<BackEventCompat>): Job =
        CoroutineScope(Dispatchers.Main.immediate).launch {
            nav.handleBack(
                events = events.receiveAsFlow(),
                preferred = style,
                release = { LabTuning.STANDARD.release() },
                standard = { LabTuning.STANDARD.standard() },
            )
        }

    private fun event(progress: Float, edge: Int = BackEventCompat.EDGE_LEFT) =
        BackEventCompat(if (edge == BackEventCompat.EDGE_LEFT) 10f else 350f, 400f, progress, edge)

    @Test fun searchAllowsOneLevelAndLibraryThree() {
        val search = host(LabTab.SEARCH)
        pushed(search, LabRoute.SearchResult("夜航"))
        compose.runOnIdle { assertFalse(search.push(LabRoute.SearchResult("回声"), standard)) }
        assertEquals(2, search.depth)
    }

    @Test fun libraryStopsAtTheArtistPage() {
        val nav = host()
        pushed(nav, LabRoute.Playlist(0), LabRoute.Artist("林间"))
        compose.runOnIdle { assertFalse(nav.push(LabRoute.Playlist(1), standard)) }
        assertEquals(3, nav.depth)
    }

    @Test fun aSecondPushWhileOneIsRunningIsIgnored() {
        val nav = host()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            assertTrue(nav.push(LabRoute.Playlist(0), standard))
            assertFalse(nav.push(LabRoute.Playlist(1), standard))
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(2, nav.depth)
        assertEquals(0f, nav.exit, 0f)
    }

    @Test fun popToRootDiscardsEveryPageAboveTheRoot() {
        val nav = host()
        pushed(nav, LabRoute.Playlist(0), LabRoute.Artist("林间"))
        compose.runOnIdle { nav.popToRoot(standard) }
        compose.waitForIdle()
        assertEquals(1, nav.depth)
        assertEquals(listOf(LabRoute.Playlist(0), LabRoute.Artist("林间")), discarded)
    }

    @Test fun backDuringAPushReversesIt() {
        val nav = host()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { nav.push(LabRoute.Playlist(0), standard) }
        compose.mainClock.advanceTimeBy(120)
        assertTrue("the push should be under way, exit=${nav.exit}", nav.exit < 1f && nav.exit > 0f)
        compose.runOnIdle { nav.pop(standard) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(1, nav.depth)
        assertEquals(0f, nav.exit, 0f)
    }

    @Test fun committedGestureFollowsTheFingerThenFinishesThePop() {
        val nav = host()
        pushed(nav, LabRoute.Playlist(0))
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
        pushed(nav, LabRoute.Playlist(0))
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
        pushed(nav, LabRoute.Playlist(0))
        val events = Channel<BackEventCompat>(Channel.UNLIMITED)
        val job = gesture(nav, BackStyle.SHRINK, events)
        events.trySend(event(1f, BackEventCompat.EDGE_RIGHT))
        compose.waitForIdle()
        assertEquals(BackStyle.SHRINK, nav.style)
        assertEquals(-1f, nav.direction, 0f)
        assertEquals(LabPose.ShrinkPhase, nav.exit, 1e-4f)
        job.cancel()
        compose.waitForIdle()
        assertEquals(2, nav.depth)
    }

    @Test fun gestureWithoutProgressIsAnOrdinaryPop() {
        val nav = host()
        pushed(nav, LabRoute.Playlist(0))
        val events = Channel<BackEventCompat>(Channel.UNLIMITED)
        gesture(nav, BackStyle.SHRINK, events)
        events.close()
        compose.waitForIdle()
        assertEquals(1, nav.depth)
        assertEquals(BackStyle.FOLLOW, nav.style)
    }

    @Test fun grabbingARunningPushKeepsItsSlideAndItsPosition() {
        val nav = host()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { nav.push(LabRoute.Playlist(0), standard) }
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
