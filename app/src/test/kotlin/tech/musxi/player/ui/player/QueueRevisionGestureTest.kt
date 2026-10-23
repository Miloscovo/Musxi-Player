package tech.musxi.player.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.theme.MusxiTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class QueueRevisionGestureTest {
    @get:Rule val compose = createComposeRule()

    private var ui by mutableStateOf(
        QueueUi(
            items = listOf(
                row("one", "第一首"),
                row("two", "第二首"),
                row("three", "第三首"),
            ),
            revision = 7,
        ),
    )
    private val moves = mutableListOf<Triple<Int, Int, Long>>()

    @Test fun dragCommitsOnceWithRevisionCapturedAtDragStart() {
        show()

        beginDrag("第一首")
        release()

        compose.runOnIdle {
            assertEquals(listOf(Triple(0, 1, 7L)), moves)
        }
    }

    @Test fun revisionChangeWithSameQueueSizeCancelsDrag() {
        show()

        beginDrag("第一首")
        compose.runOnIdle { ui = ui.copy(revision = 8) }
        release()

        compose.runOnIdle { assertEquals(emptyList<Triple<Int, Int, Long>>(), moves) }
    }

    @Test fun editingTurningOffCancelsDrag() {
        show()

        beginDrag("第一首")
        compose.runOnIdle { ui = ui.copy(editing = false) }
        release()

        compose.runOnIdle { assertEquals(emptyList<Triple<Int, Int, Long>>(), moves) }
    }

    private fun show() = compose.setContent {
        MusxiTheme {
            Box(Modifier.fillMaxSize()) {
                QueuePanel(
                    ui = ui,
                    showCover = false,
                    onMove = { from, to, revision ->
                        moves += Triple(from, to, revision)
                    },
                )
            }
        }
    }

    private fun beginDrag(title: String) {
        compose.onNodeWithText(title).performTouchInput {
            down(center)
            advanceEventTime(800L)
            moveBy(Offset(0f, 120f))
        }
    }

    private fun release() {
        compose.onNodeWithText("第一首").performTouchInput { up() }
    }

    private fun row(key: String, title: String) = QueueRowUi(
        key = key,
        title = title,
        artist = "歌手",
        durationMs = 180_000L,
    )
}
