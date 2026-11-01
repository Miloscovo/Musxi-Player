package tech.musxi.player.ui.nav

import androidx.compose.ui.test.junit4.StateRestorationTester
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

/** The stack controller on its own: push limits, interruption, reselect and saving. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class NavStackStateTest {
    @get:Rule val compose = createComposeRule()

    private fun host(maxDepth: Int = 3): NavStackState {
        lateinit var nav: NavStackState
        compose.setContent { nav = rememberNavStackState("root", maxDepth) }
        compose.waitForIdle()
        return nav
    }

    private fun pushed(nav: NavStackState, vararg keys: String) {
        keys.forEach { key ->
            compose.runOnIdle { assertTrue(nav.push(key)) }
            compose.waitForIdle()
        }
    }

    @Test fun aTabWithOneSecondLevelStopsAfterOnePush() {
        val nav = host(maxDepth = 2)
        pushed(nav, "result")
        compose.runOnIdle { assertFalse(nav.push("other")) }
        assertEquals(2, nav.depth)
    }

    @Test fun libraryStopsAtThreeLevels() {
        val nav = host()
        pushed(nav, "playlist-0", "artist-x")
        compose.runOnIdle { assertFalse(nav.push("playlist-1")) }
        assertEquals(listOf("root", "playlist-0", "artist-x"), nav.stack.keys)
    }

    @Test fun aKeyAlreadyOnTheStackIsRefused() {
        val nav = host()
        pushed(nav, "playlist-0")
        compose.runOnIdle { assertFalse(nav.push("playlist-0")) }
        assertEquals(2, nav.depth)
    }

    @Test fun aSecondPushWhileOneIsRunningIsIgnored() {
        val nav = host()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            assertTrue(nav.push("playlist-0"))
            assertFalse(nav.push("playlist-1"))
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(2, nav.depth)
        assertEquals(0f, nav.exit, 0f)
    }

    @Test fun popAtTheRootDoesNothing() {
        val nav = host()
        compose.runOnIdle {
            assertFalse(nav.pop())
            assertFalse(nav.popToRoot())
        }
        assertEquals(1, nav.depth)
    }

    @Test fun popToRootDiscardsEveryPageAboveTheRoot() {
        val nav = host()
        pushed(nav, "playlist-0", "artist-x")
        compose.runOnIdle { assertTrue(nav.popToRoot()) }
        compose.waitForIdle()
        assertEquals(listOf("root"), nav.stack.keys)
        assertEquals(0f, nav.exit, 0f)
    }

    @Test fun popDuringAPushReversesItFromWhereItIs() {
        val nav = host()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { nav.push("playlist-0") }
        compose.mainClock.advanceTimeBy(120)
        assertTrue("the push should be under way, exit=${nav.exit}", nav.exit < 1f && nav.exit > 0f)
        compose.runOnIdle { assertTrue(nav.pop()) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(1, nav.depth)
        assertEquals(0f, nav.exit, 0f)
    }

    @Test fun reselectInADeepStackGoesBackToTheRootAndDoesNotScroll() {
        val nav = host()
        pushed(nav, "playlist-0")
        var scrolled = 0
        compose.runOnIdle { assertEquals(ReselectAction.POP_TO_ROOT, nav.reselect { scrolled++ }) }
        compose.waitForIdle()
        assertEquals(1, nav.depth)
        assertEquals(0, scrolled)
    }

    @Test fun reselectAtTheRootHandsTheScrollToTheCaller() {
        val nav = host()
        var scrolled = 0
        compose.runOnIdle { assertEquals(ReselectAction.SCROLL_TO_TOP, nav.reselect { scrolled++ }) }
        assertEquals(1, scrolled)
        assertEquals(1, nav.depth)
    }

    @Test fun theStackSurvivesSaveAndRestore() {
        val restoration = StateRestorationTester(compose)
        lateinit var nav: NavStackState
        restoration.setContent { nav = rememberNavStackState("root") }
        compose.waitForIdle()
        pushed(nav, "playlist-0", "artist-x")
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(listOf("root", "playlist-0", "artist-x"), nav.stack.keys)
        assertEquals(0f, nav.exit, 0f)
    }
}
