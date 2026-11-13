package tech.musxi.player.ui.localaudio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/** The 本地音频 pages as they look, for review before they are wired into the library tab. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class LocalAudioScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun folder(id: String, name: String, count: Int, subtitle: String, unavailable: Int = 0, links: Int = 0) =
        LocalFolderUi(id, name, count, subtitle, continuePlayback = true, linkedDirectories = links, unavailable = unavailable)

    private val home = LocalHomeUi(
        folders = listOf(
            folder("a", "睡前音声", 12, "上次：第 3 条 · 23:18 · 共 12 条", links = 1),
            folder("b", "三体 有声书", 48, "上次：第 17 条 · 41:05 · 共 48 条"),
            folder("c", "录音", 6, "共 6 条", unavailable = 1),
            folder("d", "新建文件夹", 0, "还没有音频"),
        ),
        unfiled = 3,
        resume = LocalResumeUi("第三夜 · 雨声与低语", "睡前音声 · 第 3 / 12 条 · 上次听到 23:18", restoring = false),
    )

    private fun row(id: String, title: String, subtitle: String, listen: ListenState, unavailable: Boolean = false) =
        LocalAudioRowUi(id, title, subtitle, listen, unavailable, durationMs = 3_600_000, bookmarks = 0, canRelink = unavailable)

    private val page = LocalFolderPageUi(
        folder = folder("a", "睡前音声", 6, "", links = 1),
        rows = listOf(
            row("1", "第一夜 · 海边的风", "某某社团 · 已听完", ListenState.Finished),
            row("2", "第二夜 · 壁炉", "某某社团 · 已听完", ListenState.Finished),
            row("3", "第三夜 · 雨声与低语", "某某社团 · 听到 23:18 / 1:02:40", ListenState.InProgress(1_398_000, 3_760_000)),
            row("4", "第四夜 · 夜行列车", "某某社团 · 58:12", ListenState.Unplayed),
            row("5", "第五夜 · 山中小屋", "某某社团 · 原文件不在了", ListenState.Unplayed, unavailable = true),
            row("6", "特典 · 晚安", "4:30", ListenState.Unplayed),
        ),
        resumeLine = "第 3 条 · 23:18",
        playingId = "3",
    )

    private fun show(mode: ThemeMode = ThemeMode.LIGHT, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            MusxiTheme(mode = mode) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
        compose.waitForIdle()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) =
        captureScreenRoboImage("build/design/$name.png", RoborazziOptions(taskType = RoborazziTaskType.Record))

    @Test fun homeLight() {
        show { LocalAudioHome(home, {}, {}, {}, {}, {}, nowPlayingFolderId = "a") }
        compose.onNodeWithText("未归入文件夹").assertExists()
        capture("local_audio_home_light")
    }

    @Test fun homeDark() {
        show(ThemeMode.DARK) { LocalAudioHome(home, {}, {}, {}, {}, {}, nowPlayingFolderId = "a") }
        capture("local_audio_home_dark")
    }

    @Test fun homeEmpty() {
        show { LocalAudioHome(LocalHomeUi(), {}, {}, {}, {}, {}) }
        compose.onNodeWithText("还没有本地音频").assertExists()
        capture("local_audio_home_empty")
    }

    @Test fun homeImporting() {
        show { LocalAudioHome(home.copy(importing = "正在导入 3 / 8 · 第三夜.mp3", resume = null), {}, {}, {}, {}, {}) }
        capture("local_audio_home_importing")
    }

    @Test fun folderLight() {
        show {
            LocalFolderPage(page, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
        compose.onNodeWithText("继续播放").assertExists()
        compose.onNodeWithText("第 3 条 · 23:18").assertExists()
        capture("local_audio_folder_light")
    }

    @Test fun folderDark() {
        show(ThemeMode.DARK) { LocalFolderPage(page, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        capture("local_audio_folder_dark")
    }

    @Test fun folderSelecting() {
        show {
            Box(Modifier.fillMaxSize()) {
                LocalFolderPage(page.copy(selecting = true, selected = setOf("4", "6")), {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
                LocalSelectionBar(
                    count = 2, inFolder = true, onAction = {},
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
        compose.onNodeWithText("已选 2 条").assertExists()
        capture("local_audio_folder_selecting")
    }

    @Test fun folderEmpty() {
        show {
            LocalFolderPage(
                page.copy(rows = emptyList(), folder = folder("d", "新建文件夹", 0, ""), resumeLine = "", playingId = null),
                {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
            )
        }
        compose.onNodeWithText("添加音频").assertExists()
        capture("local_audio_folder_empty")
    }
}
