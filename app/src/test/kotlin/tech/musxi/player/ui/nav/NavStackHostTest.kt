package tech.musxi.player.ui.nav

import android.provider.Settings
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication

/** A list page that hands out its state, so a test can read where it is scrolled to. */
@Composable
private fun ListPage(key: String, states: MutableMap<String, LazyListState>) {
    val state = rememberLazyListState()
    states[key] = state
    LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("page-$key")) {
        items(60) { Text("$key row $it", Modifier.height(48.dp)) }
    }
}

/** The host with real pages: what stays composed, what is remembered, and who may take back. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class NavStackHostTest {
    @get:Rule val compose = createComposeRule()

    private var shown by mutableStateOf(true)
    private var backEnabled by mutableStateOf(true)
    private val states = mutableMapOf<String, LazyListState>()
    private lateinit var nav: NavStackState
    private var dispatcher: OnBackPressedDispatcher? = null

    private fun setPages() {
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            nav = rememberNavStackState("root")
            if (shown) NavStackHost(nav, backEnabled = backEnabled) { key -> ListPage(key, states) }
        }
        compose.waitForIdle()
    }

    private fun push(key: String) {
        compose.runOnIdle { assertTrue(nav.push(key)) }
        compose.waitForIdle()
    }

    private fun scrollTo(key: String, index: Int) {
        compose.onNodeWithTag("page-$key").performScrollToIndex(index)
        compose.waitForIdle()
        assertEquals(index, states.getValue(key).firstVisibleItemIndex)
    }

    private fun hasEnabledBack(): Boolean {
        var enabled = false
        compose.runOnIdle { enabled = dispatcher!!.hasEnabledCallbacks() }
        return enabled
    }

    @Test fun aCoveredPageKeepsItsScrollPositionAndAPoppedOneStartsOver() {
        setPages()
        scrollTo("root", 20)
        push("a")
        // Still composed, one level down.
        assertEquals(20, states.getValue("root").firstVisibleItemIndex)
        push("b")
        // Two levels down it has left composition; the position waits in the holder.
        compose.onNodeWithTag("page-root", useUnmergedTree = true).assertDoesNotExist()
        scrollTo("b", 15)
        compose.runOnIdle { assertTrue(nav.pop()) }
        compose.waitForIdle()
        assertEquals(20, states.getValue("root").firstVisibleItemIndex)
        push("b")
        assertEquals(0, states.getValue("b").firstVisibleItemIndex)
    }

    @Test fun leavingAndReturningToTheTabKeepsTheStackAndTheScrollPosition() {
        setPages()
        push("a")
        scrollTo("a", 12)
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        compose.onNodeWithTag("page-a").assertDoesNotExist()
        compose.runOnIdle { shown = true }
        compose.waitForIdle()
        assertEquals(listOf("root", "a"), nav.stack.keys)
        assertEquals(12, states.getValue("a").firstVisibleItemIndex)
    }

    @Test fun backIsInterceptedOnlyWhileThereIsAPageToGoBackTo() {
        setPages()
        assertFalse(hasEnabledBack())
        push("a")
        assertTrue(hasEnabledBack())
        compose.runOnIdle { backEnabled = false }
        compose.waitForIdle()
        assertFalse(hasEnabledBack())
        compose.runOnIdle { backEnabled = true }
        compose.waitForIdle()
        compose.runOnIdle { nav.pop() }
        compose.waitForIdle()
        assertFalse(hasEnabledBack())
    }

    @Test fun systemBackWithoutAGesturePopsTheTopPage() {
        setPages()
        push("a")
        compose.runOnIdle { dispatcher!!.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, nav.depth)
        compose.onNodeWithTag("page-root").assertExists()
    }

    @Test fun thePageBelowIsHiddenFromAccessibilityAndShiftedLeft() {
        setPages()
        push("a")
        compose.onNodeWithTag("page-root").assertDoesNotExist()
        val left = compose.onNodeWithTag("page-root", useUnmergedTree = true).getUnclippedBoundsInRoot().left.value
        // 30% of the 360dp width.
        assertEquals(-108f, left, 1f)
    }

    @Test fun reducedMotionKeepsThePageBelowWhereItIs() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        setPages()
        push("a")
        val left = compose.onNodeWithTag("page-root", useUnmergedTree = true).getUnclippedBoundsInRoot().left.value
        assertEquals(0f, left, 1f)
    }
}
