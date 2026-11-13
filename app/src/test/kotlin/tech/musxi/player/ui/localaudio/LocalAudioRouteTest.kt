package tech.musxi.player.ui.localaudio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
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
import tech.musxi.core.localaudio.LocalAudioError
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/** The 本地音频 routes against a fake controller: which command each tap sends, and the floating menus. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class LocalAudioRouteTest {
    @get:Rule val compose = createComposeRule()

    private val controller = FakeLocalAudioController()
    private val notices = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private val selections = mutableListOf<Boolean>()
    private val sleep = localId(100)

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            MusxiTheme(mode = ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun home() = show {
        LocalAudioHomeRoute(controller, bottomInset = 0.dp, nowPlayingFolderId = null,
            onOpenFolder = { opened += it }, onOpenUnfiled = { opened += "unfiled" }, onNotice = { notices += it })
    }

    private fun folder(id: String? = sleep) = show {
        LocalAudioFolderRoute(controller, folderId = id, bottomInset = 0.dp, nowPlayingAudioId = null,
            onBack = { opened += "back" }, onNotice = { notices += it }, onSelectionChange = { selections += it })
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) =
        captureScreenRoboImage("build/design/$name.png", RoborazziOptions(taskType = RoborazziTaskType.Record))

    /*
     * The name dialog itself cannot be driven here: under Robolectric a text field inside a Dialog
     * window never lets Compose go idle. So the "新建" flow is checked in its two halves: the step
     * the dialog's 创建 runs, and the name field with its rule, composed in the activity window.
     */
    @Test fun newFolderIsCreatedAndOpened() {
        var closed = false
        createFolderAndOpen(controller, "通勤", onOpenFolder = { opened += it }, onNotice = { notices += it }) { closed = true }
        assertEquals(listOf("createFolder:通勤"), controller.calls)
        assertTrue(closed)
        assertEquals(controller.library.value.folders.last().id, opened.single())
        assertTrue(notices.isEmpty())
    }

    @Test fun homeOffersNewFolder() {
        home()
        compose.onNodeWithText("新建").assertExists()
    }

    @Test fun nameFieldExplainsABadName() {
        show {
            var value by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
            }
            LocalNameField(value, { value = it }, nameProblem(value.text), saving = false)
        }
        compose.onNodeWithText("名称不能为空").assertDoesNotExist()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextReplacement("  ")
        compose.waitForIdle()
        compose.onNodeWithText("名称不能为空").assertExists()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextReplacement("通勤")
        compose.waitForIdle()
        compose.onNodeWithText("名称不能为空").assertDoesNotExist()
    }

    @Test fun importMenuOffersThreeWaysAndSwitchesTheSaveMode() {
        folder()
        compose.onNodeWithContentDescription("添加音频").performClick()
        compose.waitForIdle()
        compose.onAllNodes(isPopup()).assertCountEquals(1)
        listOf("选择文件", "关联手机文件夹", "一次性导入整个文件夹").forEach {
            compose.onNode(hasText(it) and hasAnyAncestor(isPopup())).assertExists()
        }
        compose.onNode(hasText("复制到 Musxi") and hasAnyAncestor(isPopup())).assertExists()
        capture("local_audio_menu_import")
        compose.onNode(hasText("保存方式") and hasAnyAncestor(isPopup())).performClick()
        compose.waitForIdle()
        // The menu stays open and now offers the other mode with its own sentence.
        compose.onNode(hasText("使用原文件") and hasAnyAncestor(isPopup())).assertExists()
        compose.onNode(hasText(importModeHint(tech.musxi.core.localaudio.LocalImportMode.REFERENCE)) and hasAnyAncestor(isPopup())).assertExists()
    }

    @Test fun tappingARowPlaysItInItsFolder() {
        folder()
        compose.onNodeWithText("第二夜 · 壁炉").performClick()
        assertEquals(listOf(FakeLocalAudioController.Play(sleep, localId(2), false)), controller.plays)
        compose.onNodeWithText("从头播放").performClick()
        assertEquals(FakeLocalAudioController.Play(sleep, null, true), controller.plays.last())
        compose.onNodeWithText("继续播放").performClick()
        assertEquals(FakeLocalAudioController.Play(sleep, null, false), controller.plays.last())
    }

    @Test fun tappingAnUnfiledRowPlaysItAlone() {
        folder(null)
        compose.onNodeWithText("散落的录音").performClick()
        assertEquals(listOf(FakeLocalAudioController.Play(null, localId(4), false)), controller.plays)
        // No folder, so nothing to add into or edit.
        compose.onNodeWithContentDescription("添加音频").assertDoesNotExist()
        compose.onNodeWithContentDescription("文件夹操作").assertDoesNotExist()
    }

    @Test fun playWaitsForThePlaybackService() {
        controller.connected.value = false
        folder()
        compose.onNodeWithText("第一夜 · 海边的风").performClick()
        assertTrue(controller.plays.isEmpty())
        assertEquals(listOf("播放服务还没准备好，请稍后再试"), notices)
    }

    @Test fun rowMenuShowsItsActions() {
        folder()
        compose.onAllNodesWithContentDescription("更多操作")[1].performClick()
        compose.waitForIdle()
        listOf("从头播放这一条", "重命名", "加入其他文件夹", "移出此文件夹", "重新关联", "检查来源", "从本地库删除").forEach {
            compose.onNode(hasText(it) and hasAnyAncestor(isPopup())).assertExists()
        }
        capture("local_audio_menu_row")
    }

    @Test fun folderMenuShowsItsActions() {
        folder()
        compose.onNodeWithContentDescription("文件夹操作").performClick()
        compose.waitForIdle()
        listOf("重命名文件夹", "每次从头播放", "已关联的手机文件夹", "删除文件夹").forEach {
            compose.onNode(hasText(it) and hasAnyAncestor(isPopup())).assertExists()
        }
        capture("local_audio_menu_folder")
        compose.onNode(hasText("每次从头播放") and hasAnyAncestor(isPopup())).performClick()
        assertEquals(listOf("setContinuePlayback:false"), controller.calls)
    }

    private fun pickTwo() {
        compose.onNodeWithText("第一夜 · 海边的风").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("第三夜 · 雨声与低语").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("已选 2 条").assertExists()
    }

    @Test fun batchAddUsesTheCurrentRevision() {
        controller.library.value = controller.library.value.copy(revision = 42)
        folder()
        pickTwo()
        assertEquals(true, selections.last())
        compose.onNodeWithText("加入").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("有声书") and hasAnyAncestor(isPopup())).performClick()
        compose.waitForIdle()
        assertEquals(listOf(FakeLocalAudioController.Batch("add", localId(101), listOf(localId(1), localId(3)), 42)), controller.batches)
        // Done: picking ends and the dock may come back.
        assertEquals(false, selections.last())
    }

    @Test fun staleStateIsCheckedAndRetriedOnce() {
        folder()
        pickTwo()
        controller.batchErrors += LocalAudioError.STALE_STATE
        // A progress save moved the revision while the first attempt was in flight.
        controller.beforeBatchAnswer = {
            controller.library.value = controller.library.value.copy(revision = 8)
            controller.beforeBatchAnswer = {}
        }
        compose.onNodeWithText("移到").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("有声书") and hasAnyAncestor(isPopup())).performClick()
        compose.waitForIdle()
        assertEquals(listOf(7L, 8L), controller.batches.map { it.revision })
        assertTrue(controller.batches.all { it.action == "move" })
    }

    @Test fun aSecondStaleStateIsReportedNotRetried() {
        folder()
        pickTwo()
        controller.batchErrors += LocalAudioError.STALE_STATE
        controller.batchErrors += LocalAudioError.STALE_STATE
        compose.onNodeWithText("加入").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("有声书") and hasAnyAncestor(isPopup())).performClick()
        compose.waitForIdle()
        assertEquals(2, controller.batches.size)
        assertEquals("列表刚刚更新，请再试一次", notices.last())
    }

    @Test fun deleteAsksFirst() {
        folder()
        pickTwo()
        compose.onNodeWithText("删除").performClick()
        compose.waitForIdle()
        assertTrue(controller.calls.none { it.startsWith("deleteAudio") })
        compose.onNode(isDialog()).assertExists()
        compose.onNode(hasText("会删除这些音频的进度、书签和自定义名称。不会删除手机里的原文件；Musxi 中的副本会被清理。") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("删除") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals(listOf("deleteAudio:${localId(1)}", "deleteAudio:${localId(3)}"), controller.calls)
    }

    @Test fun removeAsksFirst() {
        folder()
        pickTwo()
        compose.onNodeWithText("移出").performClick()
        compose.waitForIdle()
        assertTrue(controller.batches.isEmpty())
        compose.onNode(hasText("移出") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals("remove", controller.batches.single().action)
    }

    @Test fun rowRemoveAsksFirstToo() {
        folder()
        compose.onAllNodesWithContentDescription("更多操作")[1].performClick()
        compose.waitForIdle()
        compose.onNode(hasText("移出此文件夹") and hasAnyAncestor(isPopup())).performClick()
        compose.waitForIdle()
        assertTrue(controller.batches.isEmpty())
        assertTrue(controller.calls.none { it.startsWith("removeFromFolder") })
        compose.onNode(hasText("移出") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals(1, controller.batches.single().ids.size)
    }

    @Test fun theResumeCardHidesWhileLocalAudioPlays() {
        controller.restoreState.value = tech.musxi.player.localaudio.LocalAudioRestoreState(ready = true, available = true)
        show {
            LocalAudioHomeRoute(controller, bottomInset = 0.dp, nowPlayingFolderId = sleep,
                onOpenFolder = {}, onOpenUnfiled = {}, onNotice = {}, localPlaying = true)
        }
        compose.onNodeWithText("上次的本地播放列表").assertDoesNotExist()
    }
}
