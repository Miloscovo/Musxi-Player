package tech.musxi.player.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.design.libraryHomeSample
import tech.musxi.player.ui.design.libraryLoaded
import tech.musxi.player.ui.localaudio.LocalAudioHome
import tech.musxi.player.ui.localaudio.LocalFolderUi
import tech.musxi.player.ui.localaudio.LocalHomeUi
import tech.musxi.player.ui.localaudio.LocalResumeUi
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/**
 * The library tab's fourth segment, 本地音频. Without a handler the control keeps its three platforms;
 * with one, picking it swaps the playlists for the local home and hides the playlist density button.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class LibraryLocalSegmentTest {
    @get:Rule val compose = createComposeRule()

    private var local by mutableStateOf(false)
    private val filters = mutableListOf<LibraryFilter>()

    private fun show(withLocal: Boolean = true, content: @androidx.compose.runtime.Composable (header: @androidx.compose.runtime.Composable () -> Unit) -> Unit = { header -> androidx.compose.foundation.layout.Column { header(); Text("本地首页") } }) {
        compose.setContent {
            MusxiTheme(mode = ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    LibraryContent(
                        state = libraryLoaded(),
                        onSelectFilter = { filters += it; local = false },
                        home = libraryHomeSample(),
                        initialDensity = LibraryDensity.LIST,
                        localSelected = local,
                        onSelectLocal = if (withLocal) ({ local = true }) else null,
                        localContent = content,
                        localStatus = "3 个文件夹 · 21 条",
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** The segment itself, not any other text that happens to say the same. */
    private fun segment(label: String) = compose.onNode(
        androidx.compose.ui.test.hasText(label) and
            androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected),
    )

    @Test fun withoutAHandlerThereAreThreeSegments() {
        show(withLocal = false)
        compose.onNodeWithText("本地音频").assertDoesNotExist()
        segment("网易云").assertIsSelected()
    }

    @Test fun pickingLocalShowsItsContentAndHidesDensity() {
        show()
        compose.onNodeWithContentDescription("歌单显示", substring = true).assertExists()
        segment("本地音频").performClick()
        compose.waitForIdle()
        segment("本地音频").assertIsSelected()
        segment("网易云").assertIsNotSelected()
        compose.onNodeWithText("本地首页").assertExists()
        compose.onNodeWithText("3 个文件夹 · 21 条").assertExists()
        compose.onNodeWithText("私人雷达").assertDoesNotExist()
        compose.onNodeWithContentDescription("歌单显示", substring = true).assertDoesNotExist()
    }

    @Test fun pickingAPlatformLeavesLocal() {
        local = true
        show()
        segment("酷狗").performClick()
        compose.waitForIdle()
        assertEquals(listOf(LibraryFilter.KUGOU), filters)
        compose.onNodeWithText("本地首页").assertDoesNotExist()
        compose.onNodeWithContentDescription("歌单显示", substring = true).assertExists()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test fun localHomeUnderTheLibraryTitle() {
        local = true
        val folders = listOf(
            LocalFolderUi("a", "睡前音声", 12, "上次：第 3 条 · 23:18 · 共 12 条", true, 1, 0),
            LocalFolderUi("b", "三体 有声书", 48, "上次：第 17 条 · 41:05 · 共 48 条", true, 0, 0),
            LocalFolderUi("c", "录音", 6, "共 6 条", true, 0, 1),
        )
        show { header ->
            LocalAudioHome(
                LocalHomeUi(
                    folders = folders, unfiled = 3,
                    resume = LocalResumeUi("上次的本地播放列表", "恢复后先暂停，从上次停下的地方接着听", restoring = false),
                ),
                {}, {}, {}, {}, {}, bottomInset = 0.dp, nowPlayingFolderId = "a", header = header,
            )
        }
        compose.onNodeWithText("睡前音声").assertExists()
        captureScreenRoboImage(
            "build/design/local_audio_library_segment.png",
            RoborazziOptions(taskType = RoborazziTaskType.Record),
        )
    }
}
