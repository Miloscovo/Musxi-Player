package tech.musxi.player.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.musxi.core.playback.FailureReason
import tech.musxi.core.playback.SourceSelectionMode
import tech.musxi.core.playback.SourceSelectionPhase

/** Pure mapping from source selection state to the dock notice; no Android runtime needed. */
class DockNoticeTest {

    @Test fun playerOpenHidesEveryPhase() {
        for (phase in SourceSelectionPhase.values()) {
            for (mode in SourceSelectionMode.values()) {
                assertNull("$phase/$mode", dockNoticeOf(phase, mode, playerOpen = true))
            }
        }
    }

    @Test fun autoSearchingIsBusyWithoutAction() {
        assertEquals(
            DockNoticeUi("当前音源不可用，正在其他平台找…", null, busy = true),
            dockNoticeOf(SourceSelectionPhase.SEARCHING, SourceSelectionMode.AUTO, playerOpen = false),
        )
    }

    @Test fun manualSearchingIsBusyWithoutAction() {
        assertEquals(
            DockNoticeUi("正在其他平台查找音源…", null, busy = true),
            dockNoticeOf(SourceSelectionPhase.SEARCHING, SourceSelectionMode.MANUAL, playerOpen = false),
        )
    }

    @Test fun choosingAsksForConfirmation() {
        assertEquals(
            DockNoticeUi("找到可替换的音源，需要你确认", "选择", busy = false),
            dockNoticeOf(SourceSelectionPhase.CHOOSING, SourceSelectionMode.MANUAL, playerOpen = false),
        )
        assertEquals(
            DockNoticeUi("找到可替换的音源，需要你确认", "选择", busy = false),
            dockNoticeOf(SourceSelectionPhase.CHOOSING, SourceSelectionMode.AUTO, playerOpen = false),
        )
    }

    @Test fun failedOffersReview() {
        assertEquals(
            DockNoticeUi("没找到能播的版本", "查看", busy = false),
            dockNoticeOf(SourceSelectionPhase.FAILED, SourceSelectionMode.AUTO, playerOpen = false),
        )
    }

    @Test fun failedNamesTheReasonWhenKnown() {
        assertEquals(
            DockNoticeUi("没找到能播的版本 · 需要会员", "查看", busy = false),
            dockNoticeOf(
                SourceSelectionPhase.FAILED, SourceSelectionMode.AUTO, playerOpen = false,
                reason = FailureReason.MEMBERSHIP_REQUIRED,
            ),
        )
        assertEquals(
            DockNoticeUi("没找到能播的版本 · 网络连接失败", "查看", busy = false),
            dockNoticeOf(
                SourceSelectionPhase.FAILED, SourceSelectionMode.MANUAL, playerOpen = false,
                reason = FailureReason.NETWORK,
            ),
        )
    }

    @Test fun onlyTheFailedNoticeShowsAReason() {
        for (phase in listOf(SourceSelectionPhase.SEARCHING, SourceSelectionPhase.CHOOSING)) {
            for (mode in SourceSelectionMode.values()) {
                assertEquals(
                    "$phase/$mode",
                    dockNoticeOf(phase, mode, playerOpen = false),
                    dockNoticeOf(phase, mode, playerOpen = false, reason = FailureReason.COPYRIGHT_RESTRICTED),
                )
            }
        }
        // And an open player still hides it.
        assertNull(
            dockNoticeOf(
                SourceSelectionPhase.FAILED, SourceSelectionMode.AUTO, playerOpen = true,
                reason = FailureReason.COPYRIGHT_RESTRICTED,
            ),
        )
    }

    @Test fun failedPlaybackWithoutSearchOffersOtherPlatforms() {
        assertEquals(
            DockNoticeUi("其他平台可能有能播的版本", "换个音源", busy = false, findsSources = true),
            dockNoticeOf(SourceSelectionPhase.NONE, SourceSelectionMode.AUTO, playerOpen = false, playbackFailed = true),
        )
    }

    @Test fun failedPlaybackIsQuietWhileThePlayerIsOpen() {
        assertNull(dockNoticeOf(SourceSelectionPhase.NONE, SourceSelectionMode.AUTO, playerOpen = true, playbackFailed = true))
    }

    @Test fun noneAndSelectedShowNothing() {
        for (mode in SourceSelectionMode.values()) {
            assertNull(dockNoticeOf(SourceSelectionPhase.NONE, mode, playerOpen = false))
            assertNull(dockNoticeOf(SourceSelectionPhase.SELECTED, mode, playerOpen = false))
        }
    }
}
