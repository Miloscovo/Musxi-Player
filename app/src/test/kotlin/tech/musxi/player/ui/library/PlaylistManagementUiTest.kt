package tech.musxi.player.ui.library

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.remember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.core.account.Platform
import tech.musxi.core.library.EditablePlaylist
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.PlaylistKind
import tech.musxi.core.library.PlaylistManagementInfo
import tech.musxi.core.library.PlaylistSummary

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = DesignTestApplication::class)
class PlaylistManagementUiTest {
    @get:Rule val compose = createComposeRule()
    private val playlist = PlaylistSummary(MediaKey(Platform.QQ, "7_8"), "通勤", null, 12, PlaylistKind.CREATED)
    private val target = EditablePlaylist(playlist, 2, false)

    @Test fun menuDoesNotExposeRemoteWritesBeforePermissionArrives() {
        compose.setContent {
            PlaylistManagementMenu(PlaylistMenuUi(playlist), {}, {}, {}, {}, {}, {},
                canMove = true, canMoveToEnd = false, customOrder = true, enabled = true)
        }
        compose.onNodeWithText("重命名").assertDoesNotExist()
        compose.onNodeWithText("删除歌单").assertDoesNotExist()
        compose.onNodeWithText("移到最前").assertIsEnabled()
        compose.onNodeWithText("移到最后").assertIsNotEnabled()
        compose.onNodeWithText("恢复默认顺序").assertExists()
    }

    @Test fun menuUsesFreshIndependentRenameAndDeletePermissions() {
        compose.setContent {
            PlaylistManagementMenu(PlaylistMenuUi(playlist, false,
                PlaylistManagementInfo(target, canRename = false, canDelete = true, canUnfollow = true, reason = null)),
                {}, {}, {}, {}, {}, {}, canMove = true, canMoveToEnd = true, customOrder = false, enabled = true)
        }
        compose.onNodeWithText("重命名").assertDoesNotExist()
        compose.onNodeWithText("删除歌单").assertIsEnabled()
    }

    @Test fun deleteConfirmationNamesThePlatformAndWholePlaylist() {
        compose.setContent { PlaylistDeleteDialog(PlaylistDeleteUi(target), {}, {}) }
        compose.onNodeWithText("删除QQ音乐歌单？", substring = true).assertExists()
        compose.onNodeWithText("包含其中 12 首歌曲的歌单关系", substring = true).assertExists()
        compose.onNodeWithText("已经开始播放的队列继续保留", substring = true).assertExists()
    }

    @Test fun aNameSubmitsOnceAndTrimsQqWhitespace() {
        var value: String? = null
        compose.setContent { PlaylistNameDialog(PlaylistNameUi("新建歌单", "", Platform.QQ), {}) { value = it } }
        compose.onNodeWithText("创建").assertIsNotEnabled()
    }

    @Test fun eachPlaylistIsALazyItemAndADeepRowCanBeReached() {
        lateinit var list: LazyListState
        val rows = (0 until 100).map { index -> playlist.copy(key = MediaKey(Platform.QQ, "p$index"), title = "测试歌单$index") }
        compose.setContent {
            list = remember { LazyListState() }
            LibraryContent(PlaylistUiState(items = rows, accountVerified = true, complete = true), {},
                listState = list, initialDensity = LibraryDensity.LIST)
        }
        compose.runOnIdle {
            assertTrue("Each playlist must contribute an independent lazy item", list.layoutInfo.totalItemsCount >= 100)
            assertTrue(list.layoutInfo.visibleItemsInfo.size < 100)
        }
        compose.onNodeWithText("测试歌单99").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToIndex(82)
        compose.onNodeWithText("测试歌单80").assertIsDisplayed()
    }
}
