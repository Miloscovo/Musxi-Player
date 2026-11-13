package tech.musxi.player.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.theme.MusxiTheme

/**
 * The queue's gestures against the revision rules: a drag on the handle reorders with the revision
 * captured when it started, and is dropped if the queue changed under the finger; a swipe removes
 * only after the undo time, and "撤销" sends nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class QueueRevisionGestureTest {
    @get:Rule val compose = createComposeRule()

    // The current song is the first one: the three below are all "up next", in slot order.
    private var ui by mutableStateOf(
        QueueUi(
            items = listOf(
                row("zero", "正在播放"),
                row("one", "第一首"),
                row("two", "第二首"),
                row("three", "第三首"),
            ),
            currentIndex = 0,
            revision = 7,
        ),
    )
    private val moves = mutableListOf<Triple<Int, Int, Long>>()
    private val removed = mutableListOf<Int>()

    @Test fun dragCommitsOnceWithRevisionCapturedAtDragStart() {
        show()
        val handles = compose.onAllNodesWithContentDescription("拖动排序").fetchSemanticsNodes()
        val rowHeight = handles[1].boundsInRoot.top - handles[0].boundsInRoot.top
        // One and a half rows down: past the half-row threshold of the next slot only.
        dragHandle(row = 0, by = rowHeight * 1.2f)
        compose.runOnIdle { assertEquals("row height $rowHeight", listOf(Triple(1, 2, 7L)), moves) }
    }

    @Test fun revisionChangeWithSameQueueSizeCancelsDrag() {
        show()
        dragHandle(row = 0, by = 140f) { compose.runOnIdle { ui = ui.copy(revision = 8) } }
        compose.runOnIdle { assertEquals(emptyList<Triple<Int, Int, Long>>(), moves) }
    }

    @Test fun editingTurningOffCancelsDrag() {
        show()
        dragHandle(row = 0, by = 140f) { compose.runOnIdle { ui = ui.copy(editing = false) } }
        compose.runOnIdle { assertEquals(emptyList<Triple<Int, Int, Long>>(), moves) }
    }

    @Test fun aSwipeRemovesOnlyAfterTheUndoTime() {
        compose.mainClock.autoAdvance = false
        show()
        compose.onNodeWithText("第二首").performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("撤销").assertExists()
        assertEquals(emptyList<Int>(), removed)
        compose.mainClock.advanceTimeBy(UNDO_MILLIS)
        compose.runOnIdle { assertEquals(listOf(2), removed) }
    }

    @Test fun undoSendsNothing() {
        compose.mainClock.autoAdvance = false
        show()
        compose.onNodeWithText("第二首").performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("撤销").performClick()
        compose.mainClock.advanceTimeBy(UNDO_MILLIS + 1_000)
        compose.runOnIdle { assertEquals(emptyList<Int>(), removed) }
        compose.onNodeWithText("第二首").assertExists()
    }

    private fun show() = compose.setContent {
        MusxiTheme {
            Column(Modifier.fillMaxSize()) {
                QueueSheetBody(
                    ui = ui,
                    showCover = false,
                    onRemove = { removed += it },
                    onMove = { from, to, revision -> moves += Triple(from, to, revision) },
                )
            }
        }
    }

    /** Drags the [row]-th drag handle (in up-next order) down by [by] px, with [during] mid-drag. */
    private fun dragHandle(row: Int, by: Float, during: () -> Unit = {}) {
        val handle = compose.onAllNodesWithContentDescription("拖动排序")[row]
        handle.performTouchInput {
            // The icon's semantics node spans the whole row; the handle itself is its right end.
            down(Offset(width - 20.dp.toPx(), height / 2f))
            // Past the touch slop first, then the rest in small steps, as a finger moves.
            repeat(8) { moveBy(Offset(0f, by / 8)) }
        }
        during()
        handle.performTouchInput { up() }
    }

    private fun row(key: String, title: String) = QueueRowUi(
        key = key,
        title = title,
        artist = "歌手",
        durationMs = 180_000L,
    )
}
