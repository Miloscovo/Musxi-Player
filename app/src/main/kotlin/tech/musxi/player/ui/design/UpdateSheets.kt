package tech.musxi.player.ui.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import tech.musxi.core.update.LatestUpdate
import tech.musxi.core.update.UpdateCheckPhase
import tech.musxi.core.update.UpdateCheckState
import tech.musxi.core.update.UpdateDownloadPhase
import tech.musxi.core.update.UpdateDownloadState
import tech.musxi.core.update.UpdateFailure
import tech.musxi.core.update.UpdateState
import tech.musxi.core.update.UpdateVersion
import tech.musxi.player.ui.account.AboutSection
import tech.musxi.player.ui.components.SectionGroup
import tech.musxi.player.ui.account.UpdateActions
import tech.musxi.player.ui.account.aboutUpdateUiOf
import tech.musxi.player.ui.shell.ShellDock
import tech.musxi.player.ui.shell.ShellTab
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.ThemeMode

/**
 * Update sheet: the "关于" group of the account page for one backend state. The state goes through
 * the same [aboutUpdateUiOf] mapper and the same [AboutSection] as the live page, so the sheet cannot
 * show a step the real page does not have. [expanded] and [confirming] seed the two local toggles
 * that a tap would set; [dock] adds the tab bar, which is where the red dot lives.
 */
@Composable
fun UpdateSheet(
    accent: Accent,
    mode: ThemeMode,
    state: UpdateState,
    expanded: Boolean = false,
    confirming: Boolean = false,
    dock: Boolean = false,
) {
    MusxiTheme(accent = accent, mode = mode) {
        val cs = MaterialTheme.colorScheme
        // Fixed zone, so the release date in the sheet does not depend on the machine rendering it.
        val ui = aboutUpdateUiOf(state, ZoneId.of("Asia/Shanghai"))
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState())
                        .padding(horizontal = Space.page),
                ) {
                    Spacer(Modifier.height(Space.section))
                    Text("我的", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
                    Spacer(Modifier.height(Space.section))
                    SectionGroup(title = "关于") {
                        AboutSection(
                            version = state.current.versionName, ui = ui, actions = UpdateActions(),
                            initiallyExpanded = expanded, initiallyConfirming = confirming,
                            showHeading = false,
                        )
                    }
                    Spacer(Modifier.height(Space.section))
                }
                if (dock) {
                    Column(Modifier.padding(horizontal = Space.page)) {
                        ShellDock(
                            mini = miniHidden(), selected = ShellTab.ME, onSelect = {},
                            badges = if (ui.badge) setOf(ShellTab.ME) else emptySet(),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

private const val SAMPLE_NOTES = "## 新增\n- 应用内检查更新，发现新版本后可直接下载并安装\n" +
    "- 歌词页双击可回到当前行\n- 队列支持按平台筛选\n\n## 修复\n- 修复歌单缓存刷新后不更新的问题\n" +
    "- 换源面板显示每个平台的失败原因\n\n**完整记录**见 [Release 页面](https://github.com/qwt1011/Musxi-Player-Android/releases)。"

private fun sampleLatest(newer: Boolean = true, canDownload: Boolean = true) = LatestUpdate(
    version = "0.2.1", tag = "v0.2.1", prerelease = false, notes = SAMPLE_NOTES,
    publishedAt = "2026-10-28T08:30:00Z",
    releasePage = "https://github.com/qwt1011/Musxi-Player-Android/releases/tag/v0.2.1",
    manifestAvailable = true, bytes = 19_000_000L, newer = newer, canDownload = canDownload,
)

private fun releaseState(download: UpdateDownloadState = UpdateDownloadState(), metered: Boolean = false) =
    UpdateState(
        current = UpdateVersion("0.2.0", 2), developmentBuild = false,
        check = UpdateCheckState(UpdateCheckPhase.SUCCEEDED), latest = sampleLatest(),
        download = download, meteredNetwork = metered,
    )

private fun download(phase: UpdateDownloadPhase, received: Long = 19_000_000L, reason: UpdateFailure? = null) =
    UpdateDownloadState(phase, "0.2.1", received, 19_000_000L, reason)

/** Development build after a manual check: "开发版本", the official latest, no release, no switch. */
fun updateDevelopmentState() = UpdateState(
    current = UpdateVersion("0.2.0-alpha01", 2), developmentBuild = true,
    check = UpdateCheckState(UpdateCheckPhase.SUCCEEDED), latest = sampleLatest(newer = false, canDownload = false),
)

fun updateFoundState() = releaseState()

fun updateMeteredState() = releaseState(metered = true)

fun updateDownloadingState() =
    releaseState(download(UpdateDownloadPhase.DOWNLOADING, received = 8_000_000L))

fun updateReadyState() = releaseState(download(UpdateDownloadPhase.READY))

fun updatePermissionState() =
    releaseState(download(UpdateDownloadPhase.NEEDS_PERMISSION, reason = UpdateFailure.PERMISSION_DENIED))

fun updateFailedState() =
    releaseState(download(UpdateDownloadPhase.FAILED, received = 0, reason = UpdateFailure.CHECKSUM_MISMATCH))
