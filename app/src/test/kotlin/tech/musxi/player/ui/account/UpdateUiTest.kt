package tech.musxi.player.ui.account

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.update.LatestUpdate
import tech.musxi.core.update.UpdateCheckPhase
import tech.musxi.core.update.UpdateCheckState
import tech.musxi.core.update.UpdateDownloadPhase
import tech.musxi.core.update.UpdateDownloadState
import tech.musxi.core.update.UpdateFailure
import tech.musxi.core.update.UpdateState
import tech.musxi.core.update.UpdateVersion

class UpdateUiTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val page = "https://github.com/qwt1011/Musxi-Player-Android/releases/tag/v0.2.1"

    private fun release(
        newer: Boolean = true, ignored: Boolean = false, canDownload: Boolean = true,
        manifest: Boolean = true, manifestFailure: UpdateFailure? = null, bytes: Long? = 19_000_000L,
        releasePage: String = page, notes: String = "- 修复问题", prerelease: Boolean = false,
    ) = LatestUpdate(
        version = "0.2.1", tag = "v0.2.1", prerelease = prerelease, notes = notes,
        publishedAt = "2026-10-28T08:30:00Z", releasePage = releasePage, manifestAvailable = manifest,
        bytes = bytes, ignored = ignored, newer = newer, canDownload = canDownload,
        manifestFailure = manifestFailure,
    )

    private fun state(
        latest: LatestUpdate? = release(), development: Boolean = false,
        check: UpdateCheckState = UpdateCheckState(UpdateCheckPhase.SUCCEEDED),
        download: UpdateDownloadState = UpdateDownloadState(), metered: Boolean = false,
        autoCheck: Boolean = true,
    ) = UpdateState(
        current = UpdateVersion("0.2.0", 2), developmentBuild = development, autoCheck = autoCheck,
        check = check, latest = latest, download = download, meteredNetwork = metered,
    )

    private fun ui(state: UpdateState) = aboutUpdateUiOf(state, shanghai)

    private fun idle(state: UpdateState) = ui(state).release!!.download as DownloadUi.Idle

    @Test
    fun developmentBuildShowsOfficialLatestWithoutOffer() {
        val ui = ui(state(development = true, latest = release(newer = false, canDownload = false)))
        assertTrue(ui.developmentBuild)
        assertEquals("官方最新 v0.2.1", ui.checkNote)
        assertNull(ui.release)
        assertNull(ui.autoCheck)
        assertFalse(ui.badge)
    }

    @Test
    fun developmentBuildNeverReportsUpToDate() {
        assertEquals("未找到官方发布", ui(state(development = true, latest = null)).checkNote)
        assertFalse(ui(state(development = true, latest = release(newer = false))).checkNote == "已是最新")
    }

    @Test
    fun developmentBuildIgnoresNewerFlagFromBackend() {
        val ui = ui(state(development = true, latest = release(newer = true, canDownload = true)))
        assertNull(ui.release)
        assertFalse(ui.badge)
        assertNull(ui.autoCheck)
    }

    @Test
    fun developmentBuildFailureWinsOverKnownLatest() {
        val failed = UpdateCheckState(UpdateCheckPhase.FAILED, UpdateFailure.NETWORK)
        assertEquals("检查失败，点按重试", ui(state(development = true, check = failed)).checkNote)
    }

    @Test
    fun releaseBuildOffersNewerRelease() {
        val ui = ui(state(release(bytes = 19_000_000L)))
        val release = ui.release!!
        assertFalse(ui.developmentBuild)
        assertNull(ui.checkNote)
        assertEquals(true, ui.autoCheck)
        assertEquals("0.2.1", release.version)
        assertEquals("发现新版本 v0.2.1", release.title)
        assertEquals("2026年10月28日 · 18.1 MB", release.meta)
        assertEquals(page, release.releasePage)
        assertTrue(ui.badge)
    }

    @Test
    fun upToDateAndMissingReleaseAreDifferentNotes() {
        assertEquals("已是最新", ui(state(release(newer = false, canDownload = false))).checkNote)
        assertEquals("未找到官方发布", ui(state(latest = null)).checkNote)
        assertNull(ui(state(release(newer = false))).release)
    }

    @Test
    fun checkStatesMapToRowNotes() {
        assertNull(ui(state(check = UpdateCheckState(UpdateCheckPhase.IDLE), latest = null)).checkNote)
        val checking = ui(state(check = UpdateCheckState(UpdateCheckPhase.CHECKING), latest = null))
        assertTrue(checking.checking)
        assertNull(checking.checkNote)
        fun failed(reason: UpdateFailure?) =
            ui(state(check = UpdateCheckState(UpdateCheckPhase.FAILED, reason), latest = null)).checkNote
        assertEquals("请求太频繁，稍后再试", failed(UpdateFailure.RATE_LIMITED))
        assertEquals("连接超时，点按重试", failed(UpdateFailure.TIMEOUT))
        assertEquals("发布信息异常，点按重试", failed(UpdateFailure.INVALID_MANIFEST))
        assertEquals("检查失败，点按重试", failed(UpdateFailure.NETWORK))
        assertEquals("检查失败，点按重试", failed(null))
    }

    @Test
    fun failedCheckKeepsEarlierReleaseVisible() {
        val ui = ui(state(check = UpdateCheckState(UpdateCheckPhase.FAILED, UpdateFailure.NETWORK)))
        assertEquals("检查失败，点按重试", ui.checkNote)
        assertEquals("0.2.1", ui.release!!.version)
    }

    @Test
    fun badgeNeedsNewerAndNotIgnored() {
        assertTrue(ui(state(release(newer = true, ignored = false))).badge)
        assertFalse(ui(state(release(newer = true, ignored = true))).badge)
        assertFalse(ui(state(release(newer = false))).badge)
        assertFalse(ui(state(latest = null)).badge)
    }

    @Test
    fun ignoredReleaseStaysVisibleAndDownloadable() {
        val ui = ui(state(release(ignored = true)))
        val release = ui.release!!
        assertTrue(release.ignored)
        assertTrue(release.meta.endsWith("已忽略提醒"))
        assertTrue((release.download as DownloadUi.Idle).canDownload)
    }

    @Test
    fun releaseDateFollowsTheGivenZone() {
        assertEquals("2026年10月28日", releaseDate("2026-10-28T08:30:00Z", shanghai))
        assertEquals("2026年10月29日", releaseDate("2026-10-28T20:30:00Z", shanghai))
        assertEquals("2026年10月28日", releaseDate("2026-10-28T20:30:00Z", ZoneId.of("UTC")))
        assertNull(releaseDate("not a date", shanghai))
    }

    @Test
    fun prereleaseAndMissingFieldsStayOutOfTheMetaLine() {
        assertEquals("预发布 · 2026年10月28日", ui(state(release(prerelease = true, bytes = null))).release!!.meta)
        val bare = release(bytes = null).copy(publishedAt = null)
        assertEquals("", ui(state(bare)).release!!.meta)
    }

    @Test
    fun onlyRepositoryReleasePagesAreOffered() {
        assertNull(ui(state(release(releasePage = "https://evil.example/releases"))).release!!.releasePage)
        assertNull(ui(state(release(releasePage = "$page?x=1"))).release!!.releasePage)
    }

    @Test
    fun meteredFlagAndConfirmTextCarryTheSize() {
        val metered = ui(state(release(bytes = 19_000_000L), metered = true)).release!!
        assertTrue(metered.metered)
        assertEquals("正在使用移动数据，约 18 MB，继续下载？", metered.confirmText)
        assertFalse(ui(state(metered = false)).release!!.metered)
        assertEquals("正在使用移动数据，约 640 KB，继续下载？",
            ui(state(release(bytes = 655_360L))).release!!.confirmText)
        assertEquals("正在使用移动数据，继续下载？", ui(state(release(bytes = null))).release!!.confirmText)
    }

    @Test
    fun autoCheckSwitchFollowsBackend() {
        assertEquals(true, ui(state(autoCheck = true)).autoCheck)
        assertEquals(false, ui(state(autoCheck = false)).autoCheck)
    }

    private fun downloadOf(
        phase: UpdateDownloadPhase, received: Long = 0, total: Long = 19_000_000L, reason: UpdateFailure? = null,
    ) = ui(state(download = UpdateDownloadState(phase, "0.2.1", received, total, reason))).release!!.download

    @Test
    fun downloadPhasesMapToSteps() {
        assertEquals(DownloadUi.Idle(canDownload = true), downloadOf(UpdateDownloadPhase.NONE))
        assertEquals(DownloadUi.Verifying, downloadOf(UpdateDownloadPhase.VERIFYING))
        assertEquals(DownloadUi.Ready, downloadOf(UpdateDownloadPhase.READY))
        assertEquals(DownloadUi.NeedsPermission, downloadOf(UpdateDownloadPhase.NEEDS_PERMISSION, reason = UpdateFailure.PERMISSION_DENIED))
        assertEquals(DownloadUi.Installing, downloadOf(UpdateDownloadPhase.INSTALLING))
    }

    @Test
    fun downloadingShowsFractionAndSize() {
        val half = downloadOf(UpdateDownloadPhase.DOWNLOADING, received = 9_500_000L) as DownloadUi.Downloading
        assertEquals(0.5f, half.fraction, 0.001f)
        assertEquals("已下载 9.1 / 18.1 MB", half.text)
        val unknown = downloadOf(UpdateDownloadPhase.DOWNLOADING, received = 100, total = 0) as DownloadUi.Downloading
        assertEquals(0f, unknown.fraction, 0f)
        val over = downloadOf(UpdateDownloadPhase.DOWNLOADING, received = 30_000_000L) as DownloadUi.Downloading
        assertEquals(1f, over.fraction, 0f)
    }

    @Test
    fun cancelledDownloadReturnsToTheButton() {
        assertEquals(
            DownloadUi.Idle(canDownload = true),
            downloadOf(UpdateDownloadPhase.FAILED, reason = UpdateFailure.CANCELLED),
        )
    }

    @Test
    fun failedDownloadShowsReasonAndKeepsRetry() {
        val failed = downloadOf(UpdateDownloadPhase.FAILED, reason = UpdateFailure.CHECKSUM_MISMATCH) as DownloadUi.Idle
        assertEquals("文件校验失败", failed.failure)
        assertTrue(failed.canDownload)
        val unknown = downloadOf(UpdateDownloadPhase.FAILED, reason = null) as DownloadUi.Idle
        assertEquals("更新失败", unknown.failure)
    }

    @Test
    fun noButtonExplainsWhy() {
        val noManifest = idle(state(release(canDownload = false, manifest = false, bytes = null)))
        assertFalse(noManifest.canDownload)
        assertEquals("此版本暂无应用内安装包", noManifest.unavailableNote)
        val broken = idle(state(release(canDownload = false, manifestFailure = UpdateFailure.INVALID_MANIFEST)))
        assertEquals("更新清单异常，暂不能在应用内安装", broken.unavailableNote)
        val old = idle(state(release(canDownload = false, manifest = true)))
        assertEquals("此版本暂不能在应用内安装", old.unavailableNote)
        assertNull(idle(state()).unavailableNote)
    }

    @Test
    fun everyFailureHasItsOwnText() {
        val texts = UpdateFailure.entries.map { downloadFailureText(it) }
        assertTrue(texts.all { it.isNotBlank() })
        assertEquals(UpdateFailure.entries.size, texts.toSet().size)
        assertEquals("网络连接失败", downloadFailureText(UpdateFailure.NETWORK))
        assertEquals("签名不符", downloadFailureText(UpdateFailure.SIGNATURE_MISMATCH))
        assertEquals("存储不足或写入失败", downloadFailureText(UpdateFailure.STORAGE))
    }

    @Test
    fun sizesUseTheUnitOfTheTotal() {
        assertEquals("18.1 MB", sizeText(19_000_000L))
        assertEquals("1.0 MB", sizeText(1_048_576L))
        assertEquals("640 KB", sizeText(655_360L))
        assertEquals("1 KB", sizeText(10L))
        assertEquals("已下载 0.0 / 18.1 MB", transferText(0, 19_000_000L))
        assertEquals("已下载 100 / 500 KB", transferText(102_400L, 512_000L))
        assertEquals("已下载 18.1 MB", transferText(19_000_000L, 0))
    }

    @Test
    fun justUpdatedNoticeAlwaysHasOneV() {
        assertEquals("已更新到 v0.2.1", justUpdatedNotice("0.2.1"))
        assertEquals("已更新到 v0.2.1", justUpdatedNotice("v0.2.1"))
        assertEquals("v0.2.1-beta", displayVersion("0.2.1-beta"))
    }

    private fun lines(notes: String) = noteLines(notes).map { it.kind to it.text }

    @Test
    fun notesKeepHeadingsItemsAndParagraphsApart() {
        assertEquals(
            listOf(
                NoteKind.HEADING to "新增",
                NoteKind.ITEM to "一",
                NoteKind.ITEM to "二",
                NoteKind.GAP to "",
                NoteKind.TEXT to "说明文字",
            ),
            lines("## 新增\n- 一\n* 二\n\n说明文字"),
        )
        assertEquals(listOf(NoteKind.ITEM to "第一", NoteKind.ITEM to "第二"), lines("1. 第一\n2) 第二"))
        assertEquals(listOf(NoteKind.ITEM to "待办", NoteKind.ITEM to "完成"), lines("- [ ] 待办\n- [x] 完成"))
    }

    @Test
    fun notesDropImagesAndKeepLinkText() {
        assertEquals(listOf(NoteKind.TEXT to "前后"), lines("前![截图](https://x.example/a.png)后"))
        assertEquals(listOf(NoteKind.TEXT to "见 发布页 了解"), lines("见 [发布页](https://github.com/x) 了解"))
    }

    @Test
    fun notesDropEmphasisHtmlAndComments() {
        assertEquals(listOf(NoteKind.TEXT to "重要 删除 代码"), lines("**重要** ~~删除~~ `代码`"))
        assertEquals(listOf(NoteKind.TEXT to "点我"), lines("<a href=\"https://x.example\">点我</a>"))
        assertEquals(listOf(NoteKind.TEXT to "前后"), lines("前<!-- 隐藏 -->后"))
        assertEquals(listOf(NoteKind.TEXT to "可见"), lines("<!--\n多行注释\n-->可见"))
        // An unclosed comment is kept as text, minus the tag-like part.
        assertEquals(listOf(NoteKind.TEXT to "a <!-- b"), lines("a <!-- b"))
    }

    @Test
    fun notesDropQuoteMarksAndRules() {
        assertEquals(listOf(NoteKind.TEXT to "引用", NoteKind.TEXT to "二层"), lines("> 引用\n>> 二层"))
        assertEquals(listOf(NoteKind.TEXT to "上", NoteKind.TEXT to "下"), lines("上\n---\n* * *\n___\n下"))
        assertEquals(listOf(NoteKind.TEXT to "--"), lines("--"))
    }

    @Test
    fun notesCollapseBlankLinesAndHandleAnyLineBreak() {
        assertEquals(
            listOf(NoteKind.TEXT to "甲", NoteKind.GAP to "", NoteKind.TEXT to "乙"),
            lines("\n\n甲\r\n\r\n\r\n乙\n\n"),
        )
        assertEquals(listOf(NoteKind.TEXT to "甲", NoteKind.TEXT to "乙"), lines("甲\r乙"))
        assertTrue(noteLines("").isEmpty())
        assertTrue(noteLines("   \n\n").isEmpty())
    }

    @Test
    fun notesStayBoundedForHostileInput() {
        val many = noteLines((1..500).joinToString("\n") { "行$it" })
        assertEquals(201, many.size)
        assertEquals(NoteLine(NoteKind.TEXT, "…"), many.last())
        val long = noteLines("字".repeat(5_000))
        assertEquals(1, long.size)
        assertEquals(601, long[0].text.length)
        // A rule of 200k characters is clipped first, then still recognised as a rule.
        assertTrue(noteLines("-".repeat(200_000)).isEmpty() || noteLines("-".repeat(200_000)).size == 1)
        assertEquals(1, noteLines("[".repeat(100_000)).size)
        assertTrue(noteLines("<!--".repeat(10_000)).size <= 201)
    }

    @Test
    fun previewJoinsLinesWithoutGaps() {
        val parsed = noteLines("## 新增\n- 一\n\n说明")
        assertEquals("新增\n• 一\n说明", notePreview(parsed))
        assertEquals("", notePreview(emptyList()))
    }
}
