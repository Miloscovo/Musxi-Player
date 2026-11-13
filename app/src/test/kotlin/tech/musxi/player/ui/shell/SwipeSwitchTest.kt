package tech.musxi.player.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.musxi.player.design.DesignTestApplication

/** Task 1: the order of the stops, and which drags the swipe takes and which it leaves alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class SwipeSwitchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun stopsRunThroughTheSegmentsThenTheTabs() {
        val next = SwipeDir.NEXT
        val prev = SwipeDir.PREVIOUS
        assertEquals(SwipeTarget.Segment(1), swipeTarget(ShellTab.LIBRARY, 0, 4, next))
        assertNull(swipeTarget(ShellTab.LIBRARY, 0, 4, prev))
        assertEquals(SwipeTarget.Tab(ShellTab.SEARCH), swipeTarget(ShellTab.LIBRARY, 3, 4, next))
        assertEquals(SwipeTarget.Segment(2), swipeTarget(ShellTab.LIBRARY, 3, 4, prev))
        assertEquals(SwipeTarget.Tab(ShellTab.LIBRARY), swipeTarget(ShellTab.SEARCH, 0, 4, prev))
        assertEquals(SwipeTarget.Tab(ShellTab.ME), swipeTarget(ShellTab.SEARCH, 0, 4, next))
        assertNull(swipeTarget(ShellTab.ME, 0, 4, next))
        assertEquals(SwipeTarget.Tab(ShellTab.SEARCH), swipeTarget(ShellTab.ME, 0, 4, prev))
    }

    @Test fun theDockAndALockedLibraryWalkOnlyTheTabs() {
        val next = SwipeDir.NEXT
        val prev = SwipeDir.PREVIOUS
        assertEquals(SwipeTarget.Tab(ShellTab.SEARCH), swipeTarget(ShellTab.LIBRARY, 0, 4, next, tabsOnly = true))
        assertEquals(SwipeTarget.Tab(ShellTab.SEARCH), swipeTarget(ShellTab.LIBRARY, 1, 4, next, tabsOnly = true))
        assertNull(swipeTarget(ShellTab.LIBRARY, 2, 4, prev, tabsOnly = true))
        assertEquals(SwipeTarget.Tab(ShellTab.LIBRARY), swipeTarget(ShellTab.SEARCH, 0, 4, prev, tabsOnly = true))
    }

    private var stop by mutableIntStateOf(0)
    private var enabled by mutableStateOf(true)
    private var dockEnabled by mutableStateOf(true)

    private fun host() {
        compose.setContent {
            val swipe = rememberSwipeSwitchState()
            swipe.reduced = true
            swipe.enabled = enabled
            swipe.dockEnabled = dockEnabled
            // A dock swipe goes ten stops, so the test can tell it from a page swipe.
            val step = { dir: SwipeDir -> if (swipe.tabsOnly) dir.step * 10 else dir.step }
            swipe.canGo = { dir -> (stop + step(dir)) in 0..30 }
            swipe.go = { dir -> stop += step(dir) }
            // As in the shell: the dock is beside the pages (in the overlay), not inside them.
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(520.dp).swipeSwitch(swipe)) {
                    Column(Modifier.fillMaxSize().swipeSwitchPage(swipe)) {
                        LazyRow(Modifier.fillMaxWidth().height(120.dp).testTag("strip")) {
                            items(30) { Box(Modifier.size(100.dp)) { Text("$it") } }
                        }
                        LazyColumn(Modifier.fillMaxSize().testTag("list")) {
                            items(60) { Box(Modifier.fillMaxWidth().height(56.dp)) { Text("row $it") } }
                        }
                    }
                }
                Box(
                    Modifier.fillMaxWidth().height(120.dp).swipeSwitch(swipe, tabsOnly = true)
                        .testTag("dock"),
                ) { Text("dock") }
            }
        }
        compose.waitForIdle()
    }

    @Test fun aSidewaysSwipeOnTheListGoesToTheNextStopAndBack() {
        host()
        compose.onNodeWithTag("list").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, stop)
        compose.onNodeWithTag("list").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(0, stop)
    }

    @Test fun nothingOnThatSideStaysPut() {
        host()
        compose.onNodeWithTag("list").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(0, stop)
    }

    @Test fun theRecentStripKeepsItsOwnDrag() {
        host()
        compose.onNodeWithTag("strip").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(0, stop)
    }

    @Test fun aScrollIsNotASwipe() {
        host()
        compose.onNodeWithTag("list").performTouchInput {
            swipeUp(startY = centerY + 200f, endY = centerY - 200f)
        }
        compose.waitForIdle()
        assertEquals(0, stop)
    }

    @Test fun aShortNudgeSpringsBack() {
        host()
        compose.onNodeWithTag("list").performTouchInput {
            swipeLeft(startX = centerX, endX = centerX - 30f, durationMillis = 600)
        }
        compose.waitForIdle()
        assertEquals(0, stop)
    }

    @Test fun offOnSubPagesAndWhileTyping() {
        enabled = false
        host()
        compose.onNodeWithTag("list").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(0, stop)
    }

    @Test fun aSwipeOnTheDockGoesStraightToTheNextTab() {
        host()
        compose.onNodeWithTag("dock").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(10, stop)
    }

    @Test fun theDockIsOffWhileItIsHidden() {
        dockEnabled = false
        host()
        compose.onNodeWithTag("dock").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(0, stop)
    }
}
