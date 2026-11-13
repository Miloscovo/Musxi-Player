package tech.musxi.player.ui.components

import android.provider.Settings
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
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
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/**
 * Gesture rules of [MusxiSheet] (spec sections 6 and 9), driven with injected touches. The screen is
 * 360x800dp, so MEDIUM puts the sheet top at 352dp and FULL at 8dp (no status bar in Robolectric).
 * Dismissal and detents are asserted through the callbacks the sheet's callers rely on. The sheet
 * sits outside a glass host, as it does in the shell today, so it draws its solid fallback.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class SheetBehaviorTest {
    @get:Rule val compose = createComposeRule()

    private var visible by mutableStateOf(true)
    private var refuseDismiss = false
    private var dismissed = 0
    private var hostBacks = 0
    private val detents = mutableListOf<SheetDetent>()
    private val haptics = RecordingHaptics()
    private var backDispatcher: OnBackPressedDispatcher? = null

    private class RecordingHaptics : HapticFeedback {
        val events = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            events += hapticFeedbackType
        }
    }

    private fun show(initial: SheetDetent = SheetDetent.MEDIUM, mode: ThemeMode = ThemeMode.LIGHT) {
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                MusxiTheme(mode = mode) {
                    // Registered before the sheet, like the player's own handler in the shell.
                    BackHandler { hostBacks += 1 }
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        MusxiSheet(
                            visible = visible,
                            onDismiss = {
                                dismissed += 1
                                if (!refuseDismiss) visible = false
                            },
                            initialDetent = initial,
                            onDetentChange = { detents += it },
                        ) {
                            Text("标题", Modifier.padding(horizontal = 20.dp))
                            LazyColumn(Modifier.weight(1f).testTag("rows")) {
                                items(40) { i -> Text("第 $i 行", Modifier.fillMaxWidth().height(48.dp)) }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun sheetTopDp(): Float = compose.onNodeWithTag(SheetTag).getUnclippedBoundsInRoot().top.value

    private fun assertTop(expected: Float, label: String) {
        val top = sheetTopDp()
        assertTrue("$label: sheet top $top dp, expected about $expected dp", kotlin.math.abs(top - expected) < 6f)
    }

    private fun pressBack() = compose.runOnUiThread { backDispatcher!!.onBackPressed() }

    private fun flingDownOnTheHandle() {
        compose.onNodeWithTag(SheetDragTag).performTouchInput {
            swipe(Offset(width / 2f, 10.dp.toPx()), Offset(width / 2f, 10.dp.toPx() + 300.dp.toPx()), durationMillis = 100)
        }
        compose.waitForIdle()
    }

    @Test fun opensAtMediumAndSlidesIn() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MusxiTheme { Box(Modifier.fillMaxSize()) { MusxiSheet(visible = true, onDismiss = {}) { Text("内容") } } }
        }
        compose.mainClock.advanceTimeBy(48)
        val early = sheetTopDp()
        assertTrue("sheet should still be sliding in (top $early dp)", early > 600f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTop(352f, "settled at MEDIUM")
    }

    @Test fun opensAtFullWhenAsked() {
        show(SheetDetent.FULL)
        assertTop(8f, "settled at FULL")
        assertTrue(detents.isEmpty())
    }

    @Test fun fastFlingDownOnHandleDismisses() {
        show()
        flingDownOnTheHandle()
        assertEquals(1, dismissed)
        compose.onNodeWithTag(SheetTag).assertDoesNotExist()
        assertEquals(listOf(HapticFeedbackType.GestureThresholdActivate), haptics.events)
        assertTrue(detents.isEmpty())
    }

    // 300dp over 0.8s is about 375dp/s: well under the fling speed, so only the distance decides.
    @Test fun slowDragPastTheLineDismissesWithOneBuzz() {
        show()
        compose.onNodeWithTag(SheetDragTag).performTouchInput {
            swipe(Offset(width / 2f, 10.dp.toPx()), Offset(width / 2f, 10.dp.toPx() + 300.dp.toPx()), durationMillis = 800)
        }
        compose.waitForIdle()
        assertEquals(1, dismissed)
        compose.onNodeWithTag(SheetTag).assertDoesNotExist()
        assertEquals(listOf(HapticFeedbackType.GestureThresholdActivate), haptics.events)
    }

    @Test fun smallDragSnapsBackQuietly() {
        show()
        compose.onNodeWithTag(SheetDragTag).performTouchInput {
            down(Offset(width / 2f, 10.dp.toPx()))
            repeat(2) { moveBy(Offset(0f, 15.dp.toPx()), delayMillis = 100) }
            advanceEventTime(300)
            up()
        }
        compose.waitForIdle()
        assertEquals(0, dismissed)
        assertTrue("no detent change expected: $detents", detents.isEmpty())
        assertTrue("no haptic expected: ${haptics.events}", haptics.events.isEmpty())
        assertTop(352f, "back at MEDIUM")
    }

    // Down past the line, back up, down past it again, back up, one drag, one buzz. The step counts
    // leave a wide margin: the injected moves do not all reach the sheet (about every other one is
    // lost), and the result has to hold either way.
    @Test fun crossingTheLineAndBackBuzzesOnlyOnce() {
        show()
        compose.onNodeWithTag(SheetDragTag).performTouchInput {
            down(Offset(width / 2f, 10.dp.toPx()))
            repeat(2) {
                repeat(14) { moveBy(Offset(0f, 20.dp.toPx()), delayMillis = 100) }
                repeat(14) { moveBy(Offset(0f, -20.dp.toPx()), delayMillis = 100) }
            }
            advanceEventTime(300)
            up()
        }
        compose.waitForIdle()
        assertEquals(0, dismissed)
        assertEquals(listOf(HapticFeedbackType.GestureThresholdActivate), haptics.events)
        assertTop(352f, "back at MEDIUM")
    }

    @Test fun swipeUpOnTheListLiftsTheSheetToFull() {
        show()
        compose.onRoot().performTouchInput {
            swipe(Offset(width / 2f, 700.dp.toPx()), Offset(width / 2f, 400.dp.toPx()), durationMillis = 100)
        }
        compose.waitForIdle()
        assertEquals(listOf(SheetDetent.FULL), detents)
        assertEquals(listOf(HapticFeedbackType.GestureEnd), haptics.events)
        assertEquals(0, dismissed)
        assertTop(8f, "lifted to FULL")
    }

    @Test fun flingDownOnTheListAtTopStopsAtMediumFirst() {
        show(SheetDetent.FULL)
        compose.onRoot().performTouchInput {
            swipe(Offset(width / 2f, 300.dp.toPx()), Offset(width / 2f, 450.dp.toPx()), durationMillis = 100)
        }
        compose.waitForIdle()
        assertEquals(0, dismissed)
        assertEquals(listOf(SheetDetent.MEDIUM), detents)
        assertEquals(listOf(HapticFeedbackType.GestureEnd), haptics.events)
        assertTop(352f, "stopped at MEDIUM")
    }

    // The flick users felt fall back: about 670dp/s up from MEDIUM, well under the hard-fling speed
    // and too short for the sheet to be nearer FULL. It must still open FULL.
    @Test fun aShortFlickUpOnTheHandleOpensFull() {
        show()
        compose.onNodeWithTag(SheetDragTag).performTouchInput {
            swipe(Offset(width / 2f, 30.dp.toPx()), Offset(width / 2f, 30.dp.toPx() - 80.dp.toPx()), durationMillis = 120)
        }
        compose.waitForIdle()
        assertEquals(0, dismissed)
        assertEquals(listOf(SheetDetent.FULL), detents)
        assertEquals(listOf(HapticFeedbackType.GestureEnd), haptics.events)
        assertTop(8f, "opened FULL")
    }

    // Pulled up past FULL on the handle the sheet gives a little (rubber band) instead of hitting a
    // wall, never rises into the strip above FULL's top, and springs back quietly on release.
    @Test fun pullingPastFullRubberBandsAndSpringsBack() {
        show(SheetDetent.FULL)
        compose.onNodeWithTag(SheetDragTag).performTouchInput {
            down(Offset(width / 2f, 20.dp.toPx()))
            repeat(10) { moveBy(Offset(0f, -20.dp.toPx()), delayMillis = 30) }
        }
        compose.waitForIdle()
        val stretched = sheetTopDp()
        assertTrue("sheet should have risen past FULL (top $stretched dp)", stretched < 6f)
        assertTrue("but never past the window top (top $stretched dp)", stretched >= 0f)
        compose.onNodeWithTag(SheetDragTag).performTouchInput {
            advanceEventTime(300)
            up()
        }
        compose.waitForIdle()
        assertTop(8f, "back at FULL")
        assertEquals(0, dismissed)
        assertTrue("no detent change expected: $detents", detents.isEmpty())
        assertTrue("no haptic expected: ${haptics.events}", haptics.events.isEmpty())
    }

    // With "remove animations" on the rubber band (frame-driven) is skipped: a hard stop at FULL.
    @Test fun reducedMotionStopsHardAtFull() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        show(SheetDetent.FULL)
        compose.onNodeWithTag(SheetDragTag).performTouchInput {
            down(Offset(width / 2f, 20.dp.toPx()))
            repeat(10) { moveBy(Offset(0f, -20.dp.toPx()), delayMillis = 30) }
        }
        compose.waitForIdle()
        assertTop(8f, "held at FULL")
        assertTrue("no overdrag at all: top ${sheetTopDp()} dp", sheetTopDp() > 7.5f)
        compose.onNodeWithTag(SheetDragTag).performTouchInput { up() }
        compose.waitForIdle()
        assertTop(8f, "still FULL")
        assertTrue(detents.isEmpty())
    }

    // A list at FULL scrolls on its own: swiping up on it never lifts the sheet past FULL.
    @Test fun swipeUpOnTheListAtFullDoesNotStretchTheSheet() {
        show(SheetDetent.FULL)
        compose.onRoot().performTouchInput {
            down(Offset(width / 2f, 600.dp.toPx()))
            repeat(10) { moveBy(Offset(0f, -30.dp.toPx()), delayMillis = 30) }
        }
        compose.waitForIdle()
        assertTop(8f, "list scrolls, sheet stays at FULL")
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        assertTop(8f, "still FULL")
        assertTrue(detents.isEmpty())
    }

    // Showing again while the slide-out runs carries its momentum on (it eases out of the downward
    // motion and comes back) instead of stopping dead and reversing.
    @Test fun showingDuringTheSlideOutKeepsItsMomentum() {
        show()
        compose.mainClock.autoAdvance = false
        visible = false
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(100)
        val mid = sheetTopDp()
        assertTrue("sheet should be sliding out (top $mid dp)", mid > 400f && mid < 830f)
        visible = true
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(32)
        val next = sheetTopDp()
        assertTrue("momentum lost: top went from $mid to $next dp", next > mid - 8f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTop(352f, "back at MEDIUM")
        assertEquals(0, dismissed)
    }

    @Test fun tapOnTheScrimDismisses() {
        show()
        compose.onRoot().performTouchInput { click(Offset(width / 2f, 100.dp.toPx())) }
        compose.waitForIdle()
        assertEquals(1, dismissed)
        compose.onNodeWithTag(SheetTag).assertDoesNotExist()
        assertTrue(haptics.events.isEmpty())
    }

    @Test fun tapOnTheSheetItselfDoesNotDismiss() {
        show()
        compose.onRoot().performTouchInput { click(Offset(width / 2f, 372.dp.toPx())) }
        compose.waitForIdle()
        assertEquals(0, dismissed)
        compose.onNodeWithTag(SheetTag).assertExists()
    }

    @Test fun hidingAndShowingTheFlagSlidesOutAndBackIn() {
        show()
        visible = false
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTag).assertDoesNotExist()
        visible = true
        compose.waitForIdle()
        assertTop(352f, "shown again at MEDIUM")
        assertEquals(0, dismissed)
    }

    // A caller that ignores onDismiss must not be left with an invisible live sheet.
    @Test fun refusedDismissalSlidesBackToTheDetent() {
        refuseDismiss = true
        show()
        flingDownOnTheHandle()
        assertEquals(1, dismissed)
        compose.onNodeWithTag(SheetTag).assertExists()
        assertTop(352f, "came back to MEDIUM")
    }

    @Test fun backKeyDismisses() {
        show()
        pressBack()
        compose.waitForIdle()
        assertEquals(1, dismissed)
        assertEquals("the sheet took the key, the host did not", 0, hostBacks)
        compose.onNodeWithTag(SheetTag).assertDoesNotExist()
        assertTrue(haptics.events.isEmpty())
    }

    // The gap the lab version had: while the sheet slides out the key must still be held, or the
    // host's own handler would run and close the page behind the sheet as well.
    @Test fun backDuringTheSlideOutIsSwallowed() {
        show()
        compose.mainClock.autoAdvance = false
        visible = false
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag(SheetTag).assertExists()
        pressBack()
        assertEquals("nothing left to dismiss", 0, dismissed)
        assertEquals("the host must not see the key", 0, hostBacks)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTag).assertDoesNotExist()
    }

    @Test fun backAfterTheSlideOutReachesTheHost() {
        show()
        visible = false
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTag).assertDoesNotExist()
        pressBack()
        assertEquals(1, hostBacks)
        assertEquals(0, dismissed)
    }

    // Spec: stop composing only after the slide-out has finished, so it must still be on screen,
    // part way down, a few frames after the flag turns off.
    @Test fun hidingSlidesOutBeforeItStopsComposing() {
        show()
        compose.mainClock.autoAdvance = false
        visible = false
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(100)
        val mid = sheetTopDp()
        assertTrue("sheet should be sliding out (top $mid dp)", mid > 360f && mid < 830f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTag).assertDoesNotExist()
    }

    // Outside a glass host `glass()` alone paints white in every theme. The sheet builds its own
    // fallback so a dark sheet is dark and its light text stays readable.
    private fun fallbackIn(mode: ThemeMode): Pair<GlassScope, Color> {
        var scope: GlassScope? = null
        var raised = Color.Unspecified
        compose.setContent {
            MusxiTheme(mode = mode) {
                scope = sheetGlass()
                raised = MaterialTheme.colorScheme.surfaceContainerHigh
            }
        }
        compose.waitForIdle()
        return scope!! to raised
    }

    @Test fun solidFallbackFollowsTheDarkTheme() {
        val (scope, raised) = fallbackIn(ThemeMode.DARK)
        assertTrue(scope.dark)
        assertFalse(scope.blurs)
        assertEquals(raised, scope.solid)
    }

    @Test fun solidFallbackIsWhiteInTheLightTheme() {
        val (scope, _) = fallbackIn(ThemeMode.LIGHT)
        assertFalse(scope.dark)
        assertFalse(scope.blurs)
        assertEquals(Color.White, scope.solid)
    }

    @Test fun aGlassHostsMaterialWinsOverTheFallback() {
        var scope: GlassScope? = null
        compose.setContent {
            MusxiTheme { GlassHost(overlay = { scope = sheetGlass() }) { Box(Modifier.fillMaxSize()) } }
        }
        compose.waitForIdle()
        assertTrue("the host blurs on this API level, the fallback never does", scope!!.blurs)
    }
}
