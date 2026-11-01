package tech.musxi.player.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
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
 * The player's "更多" menu, first page. It names no song (the page behind it does, and "歌曲信息"
 * has the rest), so its first and last rows sit the same distance from the card's edges.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1500dp-xhdpi", application = DesignTestApplication::class)
class MoreMenuTest {
    @get:Rule val compose = createComposeRule()

    private fun show() {
        compose.setContent {
            MusxiTheme {
                Box(Modifier.fillMaxSize()) {
                    // The trigger: the menu anchors to the bounds of the Box it is in.
                    Box(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 8.dp).size(48.dp)) {
                        MoreMenu(
                            menu = MoreMenuUi(artist = "王菲", album = "只爱陌生人", sleepRemaining = null),
                            page = MorePage.MAIN,
                            onPage = {},
                            onDismiss = {},
                            onAction = {},
                            loadPlaylists = {},
                            onAddToPlaylist = {},
                            onMatchLyrics = {},
                            onFindSources = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun SemanticsNode.root(): SemanticsNode = generateSequence(this) { it.parent }.last()

    @Test fun theFirstPageNamesNoSong() {
        show()
        compose.onNodeWithText("定时停止").assertExists()
        compose.onNodeWithText("歌曲信息").assertExists()
        compose.onNodeWithText("王菲", substring = true).assertDoesNotExist()
    }

    @Test fun theFirstAndLastRowsSitTheSameDistanceFromTheCardEdges() {
        show()
        val first = compose.onNodeWithText("定时停止").fetchSemanticsNode()
        val last = compose.onNodeWithText("歌曲信息").fetchSemanticsNode()
        // The popup's root is the card plus the same shadow room on every side.
        val popup = first.root().boundsInRoot
        val above = first.boundsInRoot.top - popup.top
        val below = popup.bottom - last.boundsInRoot.bottom
        assertTrue("both rows should be inside the popup ($above, $below)", above > 0f && below > 0f)
        assertEquals("space above the first row and below the last", above, below, 1f)
    }

    // The menu is a popup window of its own, so only a capture of the whole screen includes it.
    @OptIn(ExperimentalRoborazziApi::class)
    @Test fun screenshotOfTheFirstPage() {
        show()
        captureScreenRoboImage("build/design/more_menu_light.png", RoborazziOptions(taskType = RoborazziTaskType.Record))
    }
}
