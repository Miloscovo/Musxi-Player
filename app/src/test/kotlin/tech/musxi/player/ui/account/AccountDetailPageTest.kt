package tech.musxi.player.ui.account

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.core.account.AccountError
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.AccountProfile
import tech.musxi.core.account.AccountState
import tech.musxi.core.account.Platform
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.me.meAccountRowOf
import tech.musxi.player.ui.theme.MusxiTheme

/**
 * One platform's account page: its row is drawn like the "我的" page's row and offers each phase's
 * single action, reaching the same callback. The scanner fallback belongs to Netease only, and
 * only while there is something to connect.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class AccountDetailPageTest {
    @get:Rule val compose = createComposeRule()

    private fun state(platform: Platform, phase: AccountPhase, name: String? = null, error: AccountError? = null) =
        AccountState(
            platform = platform,
            phase = phase,
            profile = name?.let { AccountProfile(platform, "sample-$it", it) },
            error = error,
        )

    private fun show(
        platform: Platform,
        state: AccountState?,
        actions: AccountPageActions = AccountPageActions(),
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            MusxiTheme {
                AccountDetailPage(
                    platform = platform,
                    row = state?.toRowUi(),
                    identity = state?.let(::meAccountRowOf),
                    dark = false,
                    actions = actions,
                    onBack = onBack,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performClick()
        compose.waitForIdle()
    }

    @Test fun thePageIsTitledWithThePlatformsFullName() {
        // Netease and Kugou have a short name on the row and a full one in the title.
        show(Platform.NETEASE, state(Platform.NETEASE, AccountPhase.DISCONNECTED))
        compose.onNodeWithText("网易云音乐").assertExists()
        compose.onNodeWithText("网易云").assertExists()
    }

    @Test fun aDisconnectedPlatformOffersConnect() {
        val seen = mutableListOf<Platform>()
        show(Platform.QQ, state(Platform.QQ, AccountPhase.DISCONNECTED), AccountPageActions(onConnect = { seen += it }))
        click("连接")
        assertEquals(listOf(Platform.QQ), seen)
    }

    @Test fun anExpiredSessionOffersReloginNotConnect() {
        val seen = mutableListOf<Platform>()
        show(
            Platform.KUGOU, state(Platform.KUGOU, AccountPhase.REAUTH_REQUIRED, error = AccountError.EXPIRED),
            AccountPageActions(onRelogin = { seen += it }),
        )
        click("重新登录")
        assertEquals(listOf(Platform.KUGOU), seen)
    }

    @Test fun aNetworkErrorOffersRetry() {
        val seen = mutableListOf<Platform>()
        show(
            Platform.QQ, state(Platform.QQ, AccountPhase.RETRYABLE_ERROR, "阿青", AccountError.NETWORK),
            AccountPageActions(onRetry = { seen += it }),
        )
        click("重试")
        assertEquals(listOf(Platform.QQ), seen)
    }

    @Test fun aPlatformWaitingOnTheUserOffersCancel() {
        val seen = mutableListOf<Platform>()
        show(
            Platform.NETEASE, state(Platform.NETEASE, AccountPhase.WAITING_CONFIRMATION),
            AccountPageActions(onCancel = { seen += it }),
        )
        click("取消")
        assertEquals(listOf(Platform.NETEASE), seen)
    }

    @Test fun aConnectedAccountIsManagedThroughTheTwoDialogs() {
        val reverified = mutableListOf<Platform>()
        val signedOut = mutableListOf<Platform>()
        show(
            Platform.QQ, state(Platform.QQ, AccountPhase.CONNECTED, "阿青"),
            AccountPageActions(onReverify = { reverified += it }, onSignOut = { signedOut += it }),
        )
        click("阿青")
        click("重新验证")
        assertEquals(listOf(Platform.QQ), reverified)
        assertTrue(signedOut.isEmpty())

        click("阿青")
        click("退出登录")
        assertTrue("signing out asks before it acts", signedOut.isEmpty())
        click("退出")
        assertEquals(listOf(Platform.QQ), signedOut)
    }

    @Test fun theScannerFallbackIsNeteaseOnlyAndOnlyWhileDisconnected() {
        var scans = 0
        val actions = AccountPageActions(onConnectNeteaseScanner = { scans++ })
        val link = "网易云网页登录打不开？改用扫码登录"

        show(Platform.NETEASE, state(Platform.NETEASE, AccountPhase.DISCONNECTED), actions)
        click(link)
        assertEquals(1, scans)
    }

    @Test fun theScannerFallbackIsHiddenElsewhere() {
        val actions = AccountPageActions(onConnectNeteaseScanner = {})
        val link = "网易云网页登录打不开？改用扫码登录"

        show(Platform.QQ, state(Platform.QQ, AccountPhase.DISCONNECTED), actions)
        compose.onNodeWithText(link).assertDoesNotExist()
    }

    @Test fun theScannerFallbackIsHiddenOnceNeteaseIsConnected() {
        val actions = AccountPageActions(onConnectNeteaseScanner = {})
        show(Platform.NETEASE, state(Platform.NETEASE, AccountPhase.CONNECTED, "云村用户 8842"), actions)
        compose.onNodeWithText("网易云网页登录打不开？改用扫码登录").assertDoesNotExist()
    }

    @Test fun aMissingRowStillDrawsThePageAndTheBackButton() {
        var backs = 0
        show(Platform.KUGOU, null, onBack = { backs++ })
        compose.onNodeWithText("酷狗音乐").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(1, backs)
    }
}
