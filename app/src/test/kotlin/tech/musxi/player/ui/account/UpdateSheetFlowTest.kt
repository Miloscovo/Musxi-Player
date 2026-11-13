package tech.musxi.player.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.core.update.LatestUpdate
import tech.musxi.core.update.UpdateCheckPhase
import tech.musxi.core.update.UpdateCheckState
import tech.musxi.core.update.UpdateDownloadPhase
import tech.musxi.core.update.UpdateDownloadState
import tech.musxi.core.update.UpdateFailure
import tech.musxi.core.update.UpdateState
import tech.musxi.core.update.UpdateVersion
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.components.SectionGroup
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.ThemeMode

/**
 * The "关于" card's one row for a newer release, and the sheet it opens, in every step a user acts
 * on. States go through [aboutUpdateUiOf] like the live page, so the copy checked here is the copy
 * the backend's states really produce.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class UpdateSheetFlowTest {
    @get:Rule val compose = createComposeRule()

    private val zone = ZoneId.of("Asia/Shanghai")

    private val notes = "## 新增\n- 检查到新版本后，在“关于”里点“查看”打开更新面板\n- 歌词页双击可回到当前行\n" +
        "- 队列支持按平台筛选\n- 本地音频按文件夹记住上次播放的位置\n\n## 修复\n- 修复歌单缓存刷新后不更新的问题\n" +
        "- 换源面板显示每个平台的失败原因\n- 修复账号切换后部分保存结果丢失\n- 修复深色模式下进度条对比度不足\n\n" +
        "**完整记录**见 [Release 页面](https://github.com/qwt1011/Musxi-Player-Android/releases)。"

    private fun state(
        download: UpdateDownloadState = UpdateDownloadState(),
        metered: Boolean = false,
        ignored: Boolean = false,
        releaseNotes: String = notes,
    ) = UpdateState(
        current = UpdateVersion("0.2.0", 5), developmentBuild = false,
        check = UpdateCheckState(UpdateCheckPhase.SUCCEEDED),
        latest = LatestUpdate(
            version = "0.2.1", tag = "v0.2.1", prerelease = false, notes = releaseNotes,
            publishedAt = "2026-11-08T08:30:00Z",
            releasePage = "https://github.com/qwt1011/Musxi-Player-Android/releases/tag/v0.2.1",
            manifestAvailable = true, bytes = 19_000_000L, ignored = ignored, newer = true, canDownload = true,
        ),
        download = download, meteredNetwork = metered,
    )

    private fun download(phase: UpdateDownloadPhase, received: Long = 19_000_000L, reason: UpdateFailure? = null) =
        UpdateDownloadState(phase, "0.2.1", received, 19_000_000L, reason)

    private fun about(state: UpdateState) = aboutUpdateUiOf(state, zone)

    /** The "关于" card as the "我的" page draws it: heading above, rows in a card. */
    @androidx.compose.runtime.Composable
    private fun AboutCard(ui: AboutUpdateUi, actions: UpdateActions) {
        Column(Modifier.padding(horizontal = Space.page, vertical = Space.section)) {
            SectionGroup(title = "关于") {
                AboutSection(version = "0.2.0", ui = ui, actions = actions, showHeading = false)
            }
        }
    }

    private fun showAbout(ui: AboutUpdateUi, actions: UpdateActions = UpdateActions()) {
        compose.setContent {
            MusxiTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { AboutCard(ui, actions) }
            }
        }
        compose.waitForIdle()
    }

    /** The sheet open over the card that opened it. */
    private fun showSheet(state: UpdateState, actions: UpdateActions = UpdateActions(), mode: ThemeMode = ThemeMode.LIGHT) {
        val ui = about(state)
        compose.setContent {
            MusxiTheme(mode = mode) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AboutCard(ui, UpdateActions())
                    UpdateSheet(visible = true, release = ui.release, actions = actions, onDismiss = {})
                }
            }
        }
        compose.waitForIdle()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) =
        captureScreenRoboImage("build/design/$name.png", RoborazziOptions(taskType = RoborazziTaskType.Record))

    @Test fun theCardKeepsOneRowThatOpensTheSheet() {
        var opened = 0
        showAbout(about(state()), UpdateActions(onShowRelease = { opened++ }))
        compose.onNodeWithText("新版本 v0.2.1").assertExists()
        compose.onNodeWithText("2026年11月8日 · 18.1 MB").assertExists()
        compose.onNodeWithText("查看").assertExists()
        // The notes and the download button are in the sheet only.
        compose.onNodeWithText("下载并安装").assertDoesNotExist()
        compose.onNodeWithText("队列支持按平台筛选").assertDoesNotExist()
        capture("update_sheet_about_entry")
        compose.onNodeWithTag(UpdateEntryTag).performClick()
        assertEquals(1, opened)
    }

    @Test fun theRowReportsTheDownload() {
        showAbout(about(state(download(UpdateDownloadPhase.DOWNLOADING, received = 7_980_000L))))
        compose.onNodeWithText("正在下载 42%").assertExists()
        capture("update_sheet_about_downloading")
    }

    @Test fun idleDownloadsTheShownVersion() {
        val downloads = mutableListOf<String>()
        showSheet(state(), UpdateActions(onDownload = { downloads += it }))
        compose.onNodeWithText("发现新版本 v0.2.1").assertExists()
        compose.onNodeWithText("队列支持按平台筛选").assertExists()
        compose.onNodeWithText("忽略此版本").assertExists()
        compose.onNodeWithText("在 GitHub 查看").assertExists()
        capture("update_sheet_idle")
        compose.onNodeWithText("下载并安装").performClick()
        assertEquals(listOf("0.2.1"), downloads)
    }

    @Test fun meteredAsksBeforeDownloading() {
        val downloads = mutableListOf<String>()
        showSheet(state(metered = true), UpdateActions(onDownload = { downloads += it }))
        compose.onNodeWithText("下载并安装").performClick()
        compose.waitForIdle()
        assertTrue("no download before the answer", downloads.isEmpty())
        compose.onNodeWithText("正在使用移动数据，约 18 MB，继续下载？").assertExists()
        compose.onNodeWithText("下载并安装").assertDoesNotExist()
        capture("update_sheet_metered_confirm")
        compose.onNodeWithText("继续下载").performClick()
        assertEquals(listOf("0.2.1"), downloads)
    }

    @Test fun meteredQuestionCanBeTakenBack() {
        val downloads = mutableListOf<String>()
        showSheet(state(metered = true), UpdateActions(onDownload = { downloads += it }))
        compose.onNodeWithText("下载并安装").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("下载并安装").assertExists()
        assertTrue(downloads.isEmpty())
    }

    @Test fun downloadingShowsProgressAndCancels() {
        var cancelled = 0
        showSheet(state(download(UpdateDownloadPhase.DOWNLOADING, received = 8_000_000L)), UpdateActions(onCancelDownload = { cancelled++ }))
        compose.onNodeWithText("已下载 7.6 / 18.1 MB").assertExists()
        compose.onNodeWithText("42%").assertExists()
        capture("update_sheet_downloading")
        compose.onNodeWithText("取消下载").performClick()
        assertEquals(1, cancelled)
    }

    @Test fun readyInstalls() {
        var installs = 0
        showSheet(state(download(UpdateDownloadPhase.READY)), UpdateActions(onInstall = { installs++ }))
        compose.onNodeWithText("安装时应用会关闭，播放会停止").assertExists()
        capture("update_sheet_ready")
        compose.onNodeWithText("安装").performClick()
        assertEquals(1, installs)
    }

    @Test fun aReadyDownloadCanBeDropped() {
        var cancels = 0
        showSheet(state(download(UpdateDownloadPhase.READY)), UpdateActions(onCancelDownload = { cancels++ }))
        compose.onNodeWithText("删除安装包").performClick()
        assertEquals(1, cancels)
    }

    @Test fun aShortReleaseKeepsItsButtonUnderTheNotes() {
        // Two lines of notes: the button follows them instead of sitting at the bottom of an empty page.
        showSheet(state(releaseNotes = "## 修复\n- 修复歌词偶尔不滚动的问题"))
        val note = compose.onNodeWithText("修复歌词偶尔不滚动的问题").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithText("下载并安装").fetchSemanticsNode().boundsInRoot
        assertTrue("button ${button.top} should follow notes ${note.bottom}", button.top - note.bottom < 200f)
        capture("update_sheet_short")
    }

    @Test fun permissionExplainsBeforeSettings() {
        var opened = 0
        showSheet(
            state(download(UpdateDownloadPhase.NEEDS_PERMISSION, reason = UpdateFailure.PERMISSION_DENIED)),
            UpdateActions(onOpenInstallSettings = { opened++ }),
        )
        compose.onNodeWithText("系统要求先开启“允许安装未知应用”，才能安装更新。").assertExists()
        capture("update_sheet_permission")
        compose.onNodeWithText("去设置").performClick()
        assertEquals(1, opened)
    }

    @Test fun failureOffersRetryInRed() {
        val downloads = mutableListOf<String>()
        showSheet(
            state(download(UpdateDownloadPhase.FAILED, received = 0, reason = UpdateFailure.CHECKSUM_MISMATCH)),
            UpdateActions(onDownload = { downloads += it }),
        )
        // Once on the card behind and once in the sheet.
        assertEquals(2, compose.onAllNodesWithText("文件校验失败").fetchSemanticsNodes().size)
        compose.onNodeWithText("重试").performClick()
        assertEquals(listOf("0.2.1"), downloads)
    }

    @Test fun ignoredReleaseCanBeUnignored() {
        var cleared = 0
        showSheet(state(ignored = true), UpdateActions(onClearIgnore = { cleared++ }))
        compose.onNodeWithText("忽略此版本").assertDoesNotExist()
        compose.onNodeWithText("取消忽略").performClick()
        assertEquals(1, cleared)
    }

    @Test fun aReleaseThatGoesAwayClosesTheSheet() {
        var release by mutableStateOf(about(state()).release)
        var dismissed = 0
        compose.setContent {
            MusxiTheme {
                UpdateSheet(visible = true, release = release, actions = UpdateActions(), onDismiss = { dismissed++ })
            }
        }
        compose.waitForIdle()
        assertEquals(0, dismissed)
        release = null
        compose.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test fun idleDark() {
        showSheet(state(), mode = ThemeMode.DARK)
        compose.onNodeWithText("下载并安装").assertExists()
        capture("update_sheet_idle_dark")
    }
}
