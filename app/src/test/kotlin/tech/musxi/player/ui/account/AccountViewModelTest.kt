package tech.musxi.player.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.AccountError
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.AccountProfile
import tech.musxi.core.account.AccountState
import tech.musxi.core.account.Platform

/**
 * Every [AccountPhase] maps to exactly one row presentation. The phase set is exhaustive on
 * purpose: a new phase must fail to compile here rather than silently render as something else.
 */
class AccountViewModelTest {

    private fun state(
        phase: AccountPhase,
        name: String? = null,
        error: AccountError? = null,
        platform: Platform = Platform.KUGOU,
    ) = AccountState(
        platform = platform,
        phase = phase,
        profile = name?.let { AccountProfile(platform, "id-$it", it) },
        error = error,
    )

    @Test fun disconnectedOffersConnect() {
        val row = state(AccountPhase.DISCONNECTED).toRowUi()
        assertEquals("未连接", row.pill)
        assertEquals(AccountAction.CONNECT, row.action)
        assertEquals("连接", row.actionLabel)
        assertNull(row.name)
        assertEquals("连接后同步歌单与收藏", row.detail)
    }

    @Test fun preparingOffersCancel() {
        val row = state(AccountPhase.PREPARING).toRowUi()
        assertEquals("等待确认", row.pill)
        assertEquals(AccountAction.CANCEL, row.action)
        assertTrue(row.userBlocked)
        assertNull(row.name)
    }

    @Test fun waitingConfirmationOffersCancel() {
        val row = state(AccountPhase.WAITING_CONFIRMATION, platform = Platform.KUGOU).toRowUi()
        assertEquals("等待确认", row.pill)
        assertEquals(AccountAction.CANCEL, row.action)
        assertTrue(row.userBlocked)
        assertEquals("在酷狗里确认后返回", row.detail)
    }

    @Test fun waitingConfirmationNamesTheRightApp() {
        assertEquals("在 QQ 音乐里确认后返回", state(AccountPhase.WAITING_CONFIRMATION, platform = Platform.QQ).toRowUi().detail)
        assertEquals(
            "在网易云音乐里扫码确认后返回",
            state(AccountPhase.WAITING_CONFIRMATION, platform = Platform.NETEASE).toRowUi().detail,
        )
    }

    @Test fun verifyingIsQuiet() {
        val row = state(AccountPhase.VERIFYING, name = "阿青").toRowUi()
        assertEquals("验证中", row.pill)
        assertEquals(AccountAction.NONE, row.action)
        assertNull(row.actionLabel)
        assertEquals("阿青", row.name)
        assertFalse(row.userBlocked)
    }

    @Test fun connectedOffersManage() {
        val row = state(AccountPhase.CONNECTED, name = "云村用户 8842").toRowUi()
        assertEquals("已连接", row.pill)
        assertEquals(AccountAction.MANAGE, row.action)
        assertEquals("管理", row.actionLabel)
        assertEquals("云村用户 8842", row.name)
        assertNull(row.detail)
    }

    @Test fun revalidatingKeepsIdentity() {
        val row = state(AccountPhase.REVALIDATING, name = "阿青").toRowUi()
        assertEquals("验证中", row.pill)
        assertEquals(AccountAction.NONE, row.action)
        assertEquals("阿青", row.name)
    }

    @Test fun retryableErrorOffersRetry() {
        val row = state(AccountPhase.RETRYABLE_ERROR, name = "云村用户 8842", error = AccountError.NETWORK).toRowUi()
        assertEquals("网络异常", row.pill)
        assertEquals(AccountAction.RETRY, row.action)
        assertEquals("重试", row.actionLabel)
        assertEquals("云村用户 8842", row.name)
        assertEquals("暂时无法验证", row.detail)
    }

    @Test fun retryableErrorIsNeverBlamedOnTheLogin() {
        // A temporary failure must not read as a broken session, even though a name is shown.
        AccountError.entries.forEach { error ->
            val row = state(AccountPhase.RETRYABLE_ERROR, error = error).toRowUi()
            assertEquals("网络异常", row.pill)
            assertEquals(AccountAction.RETRY, row.action)
            assertTrue("$error must offer a sentence", !row.detail.isNullOrBlank())
        }
    }

    @Test fun timeoutAndRejectionAreRetryable() {
        val timeout = state(AccountPhase.RETRYABLE_ERROR, error = AccountError.TIMEOUT).toRowUi()
        assertEquals(AccountAction.RETRY, timeout.action)
        assertEquals("确认超时，可重试", timeout.detail)

        val rejected = state(AccountPhase.RETRYABLE_ERROR, error = AccountError.REJECTED).toRowUi()
        assertEquals(AccountAction.RETRY, rejected.action)
        assertEquals("平台拒绝了这次登录", rejected.detail)
    }

    @Test fun reauthRequiredOffersRelogin() {
        val row = state(AccountPhase.REAUTH_REQUIRED).toRowUi()
        assertEquals("已过期", row.pill)
        assertEquals(AccountAction.RELOGIN, row.action)
        assertEquals("重新登录", row.actionLabel)
        assertNull(row.name)
        assertEquals("重新登录以恢复同步", row.detail)
    }

    @Test fun everyPhaseHasATerminalPresentation() {
        // Guards against a phase falling through to some other branch by accident.
        val actions = AccountPhase.entries.associateWith { state(it).toRowUi() }
        assertEquals(AccountPhase.entries.size, actions.size)
        assertEquals(AccountAction.CANCEL, actions.getValue(AccountPhase.PREPARING).action)
        assertEquals(AccountAction.CANCEL, actions.getValue(AccountPhase.WAITING_CONFIRMATION).action)
        assertEquals(AccountAction.CONNECT, actions.getValue(AccountPhase.DISCONNECTED).action)
        assertEquals(AccountAction.RETRY, actions.getValue(AccountPhase.RETRYABLE_ERROR).action)
        assertEquals(AccountAction.RELOGIN, actions.getValue(AccountPhase.REAUTH_REQUIRED).action)
        assertEquals(AccountAction.MANAGE, actions.getValue(AccountPhase.CONNECTED).action)
        assertEquals(AccountAction.NONE, actions.getValue(AccountPhase.VERIFYING).action)
        assertEquals(AccountAction.NONE, actions.getValue(AccountPhase.REVALIDATING).action)
    }

    @Test fun aNameIsNeverInvented() {
        // No profile means no name: the UI falls back to a sentence instead of a placeholder.
        AccountPhase.entries.forEach { phase ->
            assertNull("$phase must not invent a name", state(phase, name = null).toRowUi().name)
        }
    }
}
