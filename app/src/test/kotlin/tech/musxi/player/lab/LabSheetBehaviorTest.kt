package tech.musxi.player.lab

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.theme.MusxiTheme

/**
 * Gesture rules of [LabSheet] (spec sections 6 and 9), driven with injected touches. The screen is
 * 360x800dp, so MEDIUM puts the sheet top at 352dp and FULL at 8dp (no status bar in Robolectric).
 * Dismissal and detents are asserted through the callbacks the sheet's callers rely on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class LabSheetBehaviorTest {
    @get:Rule val compose = createComposeRule()

    private var visible by mutableStateOf(true)
    private var dismissed = 0
    private val detents = mutableListOf<LabSheetDetent>()
    private val haptics = RecordingHaptics()
    private var backDispatcher: OnBackPressedDispatcher? = null

    private class RecordingHaptics : HapticFeedback {
        val events = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            events += hapticFeedbackType
        }
    }

    private val env = LabEnv(
        songs = emptyList(), dark = false, tuning = LabTuning.STANDARD,
        onTuning = {}, onToggleDark = {}, onExit = {}, glass = false,
    )

    private fun show(initial: LabSheetDetent = LabSheetDetent.MEDIUM) {
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                MusxiTheme {
                    LabGlassHost(
                        env,
                        overlay = {
                            LabSheet(
                                visible = visible,
                                onDismiss = { dismissed += 1; visible = false },
                                env = env,
                                initialDetent = initial,
                                onDetentChange = { detents += it },
                            ) {
                                Text("标题", Modifier.padding(horizontal = 20.dp))
                                LazyColumn(Modifier.weight(1f).testTag("rows")) {
                                    items(40) { i -> Text("第 $i 行", Modifier.fillMaxWidth().height(48.dp)) }
                                }
                            }
                        },
                    ) { Box(Modifier.fillMaxSize().background(Color.White)) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun sheetTopDp(): Float =
        compose.onNodeWithTag("lab-sheet").getUnclippedBoundsInRoot().top.value

    private fun assertTop(expected: Float, label: String) {
        val top = sheetTopDp()
        assertTrue("$label: sheet top $top dp, expected about $expected dp", kotlin.math.abs(top - expected) < 6f)
    }

    @Test fun opensAtMediumAndSlidesIn() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MusxiTheme {
                LabGlassHost(
                    env,
                    overlay = { LabSheet(visible = true, onDismiss = {}, env = env) { Text("内容") } },
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.mainClock.advanceTimeBy(48)
        val early = sheetTopDp()
        assertTrue("sheet should still be sliding in (top $early dp)", early > 600f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTop(352f, "settled at MEDIUM")
    }

    @Test fun opensAtFullWhenAsked() {
        show(LabSheetDetent.FULL)
        assertTop(8f, "settled at FULL")
        assertTrue(detents.isEmpty())
    }

    @Test fun fastFlingDownOnHandleDismisses() {
        show()
        compose.onNodeWithTag("lab-sheet-drag").performTouchInput {
            swipe(Offset(width / 2f, 10.dp.toPx()), Offset(width / 2f, 10.dp.toPx() + 300.dp.toPx()), durationMillis = 100)
        }
        compose.waitForIdle()
        assertEquals(1, dismissed)
        compose.onNodeWithTag("lab-sheet").assertDoesNotExist()
        assertEquals(listOf(HapticFeedbackType.GestureThresholdActivate), haptics.events)
        assertTrue(detents.isEmpty())
    }

    // 300dp over 0.8s is about 375dp/s: well under the fling speed, so only the distance decides.
    @Test fun slowDragPastTheLineDismissesWithOneBuzz() {
        show()
        compose.onNodeWithTag("lab-sheet-drag").performTouchInput {
            swipe(Offset(width / 2f, 10.dp.toPx()), Offset(width / 2f, 10.dp.toPx() + 300.dp.toPx()), durationMillis = 800)
        }
        compose.waitForIdle()
        assertEquals(1, dismissed)
        compose.onNodeWithTag("lab-sheet").assertDoesNotExist()
        assertEquals(listOf(HapticFeedbackType.GestureThresholdActivate), haptics.events)
    }

    @Test fun smallDragSnapsBackQuietly() {
        show()
        compose.onNodeWithTag("lab-sheet-drag").performTouchInput {
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
        compose.onNodeWithTag("lab-sheet-drag").performTouchInput {
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
        assertEquals(listOf(LabSheetDetent.FULL), detents)
        assertEquals(listOf(HapticFeedbackType.GestureEnd), haptics.events)
        assertEquals(0, dismissed)
        assertTop(8f, "lifted to FULL")
    }

    @Test fun flingDownOnTheListAtTopStopsAtMediumFirst() {
        show(LabSheetDetent.FULL)
        compose.onRoot().performTouchInput {
            swipe(Offset(width / 2f, 300.dp.toPx()), Offset(width / 2f, 450.dp.toPx()), durationMillis = 100)
        }
        compose.waitForIdle()
        assertEquals(0, dismissed)
        assertEquals(listOf(LabSheetDetent.MEDIUM), detents)
        assertEquals(listOf(HapticFeedbackType.GestureEnd), haptics.events)
        assertTop(352f, "stopped at MEDIUM")
    }

    @Test fun tapOnTheScrimDismisses() {
        show()
        compose.onRoot().performTouchInput { click(Offset(width / 2f, 100.dp.toPx())) }
        compose.waitForIdle()
        assertEquals(1, dismissed)
        compose.onNodeWithTag("lab-sheet").assertDoesNotExist()
        assertTrue(haptics.events.isEmpty())
    }

    @Test fun tapOnTheSheetItselfDoesNotDismiss() {
        show()
        compose.onRoot().performTouchInput { click(Offset(width / 2f, 372.dp.toPx())) }
        compose.waitForIdle()
        assertEquals(0, dismissed)
        compose.onNodeWithTag("lab-sheet").assertExists()
    }

    @Test fun hidingAndShowingTheFlagSlidesOutAndBackIn() {
        show()
        visible = false
        compose.waitForIdle()
        compose.onNodeWithTag("lab-sheet").assertDoesNotExist()
        visible = true
        compose.waitForIdle()
        assertTop(352f, "shown again at MEDIUM")
        assertEquals(0, dismissed)
    }


    @Test fun backKeyDismisses() {
        show()
        compose.runOnUiThread { backDispatcher!!.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, dismissed)
        compose.onNodeWithTag("lab-sheet").assertDoesNotExist()
        assertTrue(haptics.events.isEmpty())
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
        compose.onNodeWithTag("lab-sheet").assertDoesNotExist()
    }

    @Test fun durationDifferenceLabels() {
        assertEquals("长 12 秒", durationDiffLabel(200_000L, 212_000L))
        assertEquals("短 8 秒", durationDiffLabel(200_000L, 192_000L))
        assertEquals("长 5 秒", durationDiffLabel(200_000L, 205_000L))
        assertNull(durationDiffLabel(200_000L, 203_000L))
        assertNull(durationDiffLabel(200_000L, 197_500L))
    }
}
