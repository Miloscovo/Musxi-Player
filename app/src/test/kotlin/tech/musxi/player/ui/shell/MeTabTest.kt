package tech.musxi.player.ui.shell

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.core.account.Platform
import tech.musxi.core.cache.CacheKind
import tech.musxi.core.cache.CacheOperationResult
import tech.musxi.core.cache.CacheUsage
import tech.musxi.core.settings.ThemeMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.account.AboutUpdateUi
import tech.musxi.player.ui.account.AccountPageActions
import tech.musxi.player.ui.account.UpdateActions
import tech.musxi.player.ui.account.toRowUi
import tech.musxi.player.ui.design.meCommonAccountStates
import tech.musxi.player.ui.design.meSampleUi
import tech.musxi.player.ui.nav.NavStackState
import tech.musxi.player.ui.nav.rememberNavStackState
import tech.musxi.player.ui.theme.MusxiTheme

/**
 * What the "我的" tab does when touched: which rows push a page onto its stack, which callback each
 * switch reaches, that clearing the cache asks first, and that the three rows whose page is not
 * built yet only say so. The window is tall enough to hold the whole page, so nothing scrolls.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1500dp-xhdpi", application = DesignTestApplication::class)
class MeTabTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var nav: NavStackState
    private var dispatcher: OnBackPressedDispatcher? = null

    /** Sample accounts: Netease connected, QQ connected, Kugou not connected. */
    private val rows = meCommonAccountStates().map { it.toRowUi() }

    private fun setTab(
        actions: MeTabActions = MeTabActions(),
        accountActions: AccountPageActions = AccountPageActions(),
    ) {
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            nav = rememberNavStackState(MeRootKey, maxDepth = 2)
            MusxiTheme {
                MeTab(
                    nav = nav,
                    ui = meSampleUi(),
                    accountRows = rows,
                    dark = false,
                    actions = actions,
                    accountActions = accountActions,
                    appVersion = "0.2.0-alpha01",
                    update = AboutUpdateUi(
                        developmentBuild = false, checking = false, checkNote = null, release = null,
                        autoCheck = null, badge = false,
                    ),
                    updateActions = UpdateActions(),
                    bottomInset = 0.dp,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performClick()
        compose.waitForIdle()
    }

    // --- rows that open a page ------------------------------------------------------------------

    @Test fun theDarkModeRowPushesThePickerAndReportsTheChoice() {
        val picked = mutableListOf<ThemeMode>()
        setTab(MeTabActions(onSetThemeMode = { picked += it }))
        assertEquals(1, nav.depth)

        click("深色模式")
        assertEquals(listOf(MeRootKey, MeDarkModeKey), nav.stack.keys)

        // The sample is on "跟随系统": picking it again is not a change, picking the others is.
        click("跟随系统")
        click("深色")
        click("浅色")
        assertEquals(listOf(ThemeMode.DARK, ThemeMode.LIGHT), picked)
        assertEquals("choosing must not close the picker", 2, nav.depth)
    }

    @Test fun backFromAPickerReturnsToThePage() {
        setTab()
        click("深色模式")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        assertEquals(1, nav.depth)

        click("深色模式")
        compose.runOnIdle { dispatcher!!.onBackPressed() }
        compose.waitForIdle()
        assertEquals("system back pops the sub-page", 1, nav.depth)
    }

    @Test fun anAccountRowPushesThatPlatformsPage() {
        setTab()
        click("酷狗")
        assertEquals(listOf(MeRootKey, meAccountKey(Platform.KUGOU)), nav.stack.keys)
        compose.onNodeWithText("酷狗音乐").assertExists()
    }

    @Test fun thePlatformPageKeepsTheConnectFlowOfTheOldAccountScreen() {
        val connected = mutableListOf<Platform>()
        setTab(accountActions = AccountPageActions(onConnect = { connected += it }))
        click("酷狗")
        // Kugou is not connected in the sample: its one action is 连接.
        click("连接")
        assertEquals(listOf(Platform.KUGOU), connected)
    }

    @Test fun signingOutAsksOnceMoreAndOnlyThenCalls() {
        val signedOut = mutableListOf<Platform>()
        val reverified = mutableListOf<Platform>()
        setTab(accountActions = AccountPageActions(
            onSignOut = { signedOut += it }, onReverify = { reverified += it },
        ))
        // Opened through the stack: a connected row leads with the nickname, not the platform name.
        compose.runOnIdle { nav.push(meAccountKey(Platform.NETEASE)) }
        compose.waitForIdle()
        click("云村用户 8842")
        click("退出登录")
        assertTrue("the first tap only opens the confirmation", signedOut.isEmpty())
        click("取消")
        assertTrue(signedOut.isEmpty())

        click("云村用户 8842")
        click("重新验证")
        assertEquals(listOf(Platform.NETEASE), reverified)

        click("云村用户 8842")
        click("退出登录")
        click("退出")
        assertEquals(listOf(Platform.NETEASE), signedOut)
    }

    // --- switches -------------------------------------------------------------------------------

    @Test fun everySwitchReachesItsCallbackWithTheValueTheUserAskedFor() {
        val seen = mutableListOf<String>()
        setTab(
            MeTabActions(
                onSetAutoSwitchSource = { seen += "auto=$it" },
                onSetRelaxDuration = { seen += "relax=$it" },
                onSetFollowCover = { seen += "cover=$it" },
                onSetShowTranslation = { seen += "translation=$it" },
                onSetTransparency = { seen += "glass=$it" },
            ),
        )
        for (title in listOf(
            "放不了时自动换到其他平台", "放宽时长匹配", "颜色跟随封面", "显示翻译", "透明效果",
        )) click(title)
        // The sample: auto-switch, follow cover and glass are on; relaxed duration and translation off.
        assertEquals(
            listOf("auto=false", "relax=true", "cover=false", "translation=true", "glass=false"),
            seen,
        )
    }

    // --- rows whose page is not built yet -------------------------------------------------------

    @Test fun qualityFontSizeAndAccentOnlySayComingSoon() {
        var soon = 0
        setTab(MeTabActions(onComingSoon = { soon++ }))
        for (title in listOf("默认音质", "字号", "主题色")) click(title)
        assertEquals(3, soon)
        assertEquals("no page is pushed for them", 1, nav.depth)
    }

    // --- cache ----------------------------------------------------------------------------------

    @Test fun clearingTheCacheAsksFirstAndCancelLeavesItAlone() {
        var cleared = 0
        setTab(MeTabActions(onClearCache = { cleared++ }))

        click("缓存")
        compose.onNodeWithText("清除缓存？").assertExists()
        assertEquals("tapping the row only asks", 0, cleared)

        click("取消")
        compose.onNodeWithText("清除缓存？").assertDoesNotExist()
        assertEquals(0, cleared)

        click("缓存")
        click("清除")
        compose.onNodeWithText("清除缓存？").assertDoesNotExist()
        assertEquals(1, cleared)
    }

    // --- page keys and the cache sentence -------------------------------------------------------

    @Test fun anAccountKeyNamesItsPlatformAndNothingElseDoes() {
        for (platform in Platform.entries) {
            assertEquals(platform, meAccountPlatform(meAccountKey(platform)))
        }
        assertNull(meAccountPlatform(MeRootKey))
        assertNull(meAccountPlatform(MeDarkModeKey))
        assertNull(meAccountPlatform("me/account/WEIBO"))
    }

    @Test fun aFullyClearedCacheSaysSo() {
        val result = CacheOperationResult(measured(0, 0, 0), failed = emptySet())
        assertEquals("缓存已清除", cacheClearNotice(result))
    }

    @Test fun aPartialFailureNamesWhatIsLeftAndHowMuchItHolds() {
        val result = CacheOperationResult(
            measured(0, 12 * MIB, 0), failed = setOf(CacheKind.LYRICS),
        )
        assertEquals("歌词缓存没能清除，剩余占用 12 MB", cacheClearNotice(result))
    }

    @Test fun severalFailuresAreListedInTheOrderOfTheKinds() {
        val result = CacheOperationResult(
            measured(3 * MIB, 0, 5 * MIB), failed = setOf(CacheKind.LIBRARY_PAGES, CacheKind.COVERS),
        )
        assertEquals("封面、曲库缓存没能清除，剩余占用 8.0 MB", cacheClearNotice(result))
    }

    @Test fun aTotalThatCannotBeReadIsUnknownNeverZero() {
        // Lyrics could not even be measured, so the three kinds do not add up to a total.
        val usage = CacheUsage(mapOf(CacheKind.COVERS to 0L, CacheKind.LIBRARY_PAGES to 0L))
        val result = CacheOperationResult(usage, failed = setOf(CacheKind.LYRICS))
        assertEquals("歌词缓存没能清除，剩余占用未知", cacheClearNotice(result))
    }

    private fun measured(covers: Long, lyrics: Long, library: Long) = CacheUsage(
        mapOf(
            CacheKind.COVERS to covers,
            CacheKind.LYRICS to lyrics,
            CacheKind.LIBRARY_PAGES to library,
        ),
    )

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
