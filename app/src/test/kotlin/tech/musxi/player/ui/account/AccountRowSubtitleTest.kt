package tech.musxi.player.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.musxi.core.account.AccountError
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.AccountProfile
import tech.musxi.core.account.AccountState
import tech.musxi.core.account.Membership
import tech.musxi.core.account.MembershipLevel
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.me.meAccountRowOf

/**
 * The account page draws its row from the "我的" page's row (`meAccountRowOf`), so the two pages
 * say the same thing about one account. These pin what that row says on the account page, where
 * the right edge is a button ("连接", "重试", "重新登录") that the grey line must not repeat.
 */
class AccountRowSubtitleTest {

    private fun state(
        phase: AccountPhase,
        name: String? = null,
        error: AccountError? = null,
        platform: Platform = Platform.KUGOU,
        level: MembershipLevel = MembershipLevel.UNKNOWN,
        avatar: String? = null,
    ) = AccountState(
        platform = platform,
        phase = phase,
        profile = name?.let { AccountProfile(platform, "id-$it", it, avatarUrl = avatar) },
        error = error,
        membership = Membership(level),
    )

    @Test fun aConnectedMemberIsPlatformAndMembershipNotConnected() {
        // Was "网易云 · 已连接" on the account page and "网易云 · 会员" on the "我的" page.
        val row = meAccountRowOf(state(AccountPhase.CONNECTED, "云村用户 8842", platform = Platform.NETEASE, level = MembershipLevel.YES))
        assertEquals("云村用户 8842", row.title)
        assertEquals("网易云 · 会员", row.subtitle)
    }

    @Test fun anErrorBesideANameIsThePlatformAndTheReason() {
        val row = meAccountRowOf(state(AccountPhase.RETRYABLE_ERROR, "云村用户 8842", AccountError.NETWORK, Platform.NETEASE))
        assertEquals("网易云 · 暂时无法验证", row.subtitle)
    }

    @Test fun anExpiredLoginDoesNotRepeatTheButton() {
        assertEquals("登录已过期", meAccountRowOf(state(AccountPhase.REAUTH_REQUIRED)).subtitle)
    }

    @Test fun aPlatformNotConnectedHasNoGreyLineBesideItsButton() {
        // Was "未连接 · 连接后同步歌单与收藏", which wrapped and repeated "连接".
        val row = meAccountRowOf(state(AccountPhase.DISCONNECTED))
        assertEquals("酷狗", row.title)
        assertNull(row.subtitle)
    }

    @Test fun waitingAndVerifyingSayWhatIsHappening() {
        assertEquals("在酷狗里确认后返回", meAccountRowOf(state(AccountPhase.WAITING_CONFIRMATION)).subtitle)
        assertEquals("正在确认登录状态", meAccountRowOf(state(AccountPhase.VERIFYING)).subtitle)
    }

    @Test fun thePictureBelongsToTheNamedAccountOnly() {
        val signedIn = meAccountRowOf(state(AccountPhase.CONNECTED, "阿青", avatar = "https://example.invalid/a.jpg"))
        assertEquals("https://example.invalid/a.jpg", signedIn.avatarUrl)
        assertEquals(true, signedIn.signedIn)
        val nobody = meAccountRowOf(state(AccountPhase.DISCONNECTED))
        assertNull(nobody.avatarUrl)
        assertEquals(false, nobody.signedIn)
    }
}
