package tech.musxi.player.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import tech.musxi.core.account.Platform
import tech.musxi.core.library.EditablePlaylist
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.PlaylistSummary
import tech.musxi.core.library.SongSummary
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/** The playlist sheet as it looks: a draft with two changes, the dark theme, and a partial save. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class PlaylistMembershipSheetTest {
    @get:Rule val compose = createComposeRule()

    private val song = SongSummary(MediaKey(Platform.NETEASE, "s"), "夜空中最亮的星", listOf("逃跑计划"), "世界", 252_000)
    private fun row(id: String, title: String, count: Int, present: Boolean, checked: Boolean = present,
        pinned: Boolean = false, outcome: RowOutcome? = null) = MembershipRowUi(
        EditablePlaylist(PlaylistSummary(MediaKey(Platform.NETEASE, id), title, null, count), 1, favorite = false),
        present, checked, pinned, outcome,
    )

    private val draft = MembershipSheetUi(
        song, MembershipPhase.READY,
        rows = listOf(
            row("a", "通勤路上", 86, present = true, pinned = true),
            row("b", "深夜单曲循环", 42, present = false, checked = true),
            row("c", "跑步 160 BPM", 120, present = true, checked = false),
            row("d", "周末", 18, present = false),
            row("e", "老歌", 233, present = false),
        ),
    )

    private val partial = MembershipSheetUi(
        song, MembershipPhase.READY,
        message = "1 项已保存，2 项未完成；有结果未确认，已重新读取核对",
        messageWarning = false,
        rows = listOf(
            row("a", "通勤路上", 85, present = false),
            row("b", "深夜单曲循环", 42, present = false, outcome = RowOutcome("没能加入：没有修改这个歌单的权限", true)),
            row("c", "跑步 160 BPM", 121, present = true, outcome = RowOutcome("结果未确认，已按最新读取显示", false)),
            row("d", "周末", 18, present = false),
        ),
    )

    private fun show(ui: MembershipSheetUi, mode: ThemeMode = ThemeMode.LIGHT) {
        compose.setContent {
            MusxiTheme(mode = mode) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    PlaylistMembershipSheet(ui, dark = mode != ThemeMode.LIGHT, onToggle = {}, onSave = {}, onRetry = {}, onDismiss = {})
                }
            }
        }
        compose.waitForIdle()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) =
        captureScreenRoboImage("build/design/$name.png", RoborazziOptions(taskType = RoborazziTaskType.Record))

    @Test fun draftLight() {
        show(draft)
        compose.onNodeWithText("保存 2 项").assertExists()
        compose.onNodeWithTag(MembershipSaveTag).assertIsEnabled()
        compose.onNodeWithText("保存后加入").assertExists()
        compose.onNodeWithText("保存后移出").assertExists()
        compose.onNodeWithText("当前歌单 · 86 首").assertExists()
        capture("playlist_membership_light")
    }

    @Test fun draftDark() {
        show(draft, ThemeMode.DARK)
        capture("playlist_membership_dark")
    }

    @Test fun partialSave() {
        show(partial)
        compose.onNodeWithTag(MembershipSaveTag).assertIsNotEnabled()
        compose.onNodeWithText("没能加入：没有修改这个歌单的权限").assertExists()
        capture("playlist_membership_partial")
    }

    @Test fun saving() {
        show(draft.copy(phase = MembershipPhase.SAVING))
        compose.onNodeWithText("保存中").assertExists()
        compose.onNodeWithTag(MembershipSaveTag).assertIsNotEnabled()
        capture("playlist_membership_saving")
    }
}
