package tech.musxi.player.ui.me

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.core.settings.ThemeMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.components.RowSpec
import tech.musxi.player.ui.design.meMixedAccountStates
import tech.musxi.player.ui.design.meSampleUi
import tech.musxi.player.ui.theme.MusxiTheme

/**
 * What the "我的" page does when touched: which callback fires with which value, that the page
 * keeps no copy of any setting, and that rows grow with the font scale. The window is tall enough
 * to hold the whole page, so no test needs to scroll.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1500dp-xhdpi", application = DesignTestApplication::class)
class MePageTest {
    @get:Rule val compose = createComposeRule()

    /** Sample page: auto-switch on, relaxed duration off, translation off, 128 MB of cache. */
    private var ui by mutableStateOf(meSampleUi())
    private var fontScale by mutableStateOf(1f)

    private fun setPage(actions: MeActions = MeActions(), appVersion: String = "") {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MusxiTheme { MeScreen(ui = ui, actions = actions, appVersion = appVersion) }
            }
        }
    }

    private fun hasState(text: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    // --- switches ------------------------------------------------------------------------------

    @Test fun anOnSwitchReportsOffAndTheRowKeepsTheCallersValue() {
        val seen = mutableListOf<Boolean>()
        setPage(MeActions(onSetAutoSwitchSource = { seen += it }))
        val row = compose.onNodeWithText("放不了时自动换到其他平台")
        row.assert(hasState("已开启")).performClick()
        assertEquals(listOf(false), seen)
        // Nothing was saved, so the page must still show the old value: it holds no state of its own.
        row.assert(hasState("已开启"))
    }

    @Test fun anOffSwitchReportsOn() {
        val seen = mutableListOf<Boolean>()
        setPage(MeActions(onSetShowTranslation = { seen += it }))
        compose.onNodeWithText("显示翻译").assert(hasState("已关闭")).performClick()
        assertEquals(listOf(true), seen)
    }

    @Test fun theSwitchFollowsTheCallersNewValue() {
        setPage(MeActions(onSetRelaxDuration = { on ->
            ui = ui.copy(playback = ui.playback.copy(relaxDuration = on))
        }))
        val row = compose.onNodeWithText("放宽时长匹配")
        row.assert(hasState("已关闭")).performClick()
        row.assert(hasState("已开启"))
    }

    @Test fun everySwitchRowIsASwitch() {
        setPage()
        for (title in listOf("放不了时自动换到其他平台", "放宽时长匹配", "颜色跟随封面", "显示翻译", "透明效果")) {
            compose.onNodeWithText(title)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        }
    }

    // --- rows that open something ---------------------------------------------------------------

    @Test fun rowsThatOpenAPageReportIt() {
        val opened = mutableListOf<String>()
        setPage(
            MeActions(
                onOpenAccount = { opened += it.name },
                onOpenQuality = { opened += "quality" },
                onOpenFontSize = { opened += "font" },
                onOpenDarkMode = { opened += "dark" },
                onOpenAccent = { opened += "accent" },
            ),
        )
        // An account row is found by its first line: the nickname, or the platform when nobody is known.
        for (title in listOf("云村用户 8842", "酷狗", "默认音质", "字号", "深色模式", "主题色")) {
            compose.onNodeWithText(title).performClick()
        }
        assertEquals(listOf("NETEASE", "KUGOU", "quality", "font", "dark", "accent"), opened)
    }

    @Test fun onlyRowsThatNeedTheUserSpeakAtTheRightEdge() {
        setPage()
        // Sample: a member, a non-member and a platform not connected yet.
        compose.onNodeWithText("连接").assertExists()
        compose.onNodeWithText("需要重新登录").assertDoesNotExist()
        compose.onNodeWithText("网易云 · 会员").assertExists()
        compose.runOnIdle { ui = ui.copy(accounts = meMixedAccountStates().map(::meAccountRowOf)) }
        compose.onNodeWithText("需要重新登录").assertExists()
        compose.onNodeWithText("登录已过期").assertExists()
        compose.onNodeWithText("网络异常").assertExists()
        compose.onNodeWithText("连接").assertDoesNotExist()
    }

    @Test fun anAccountRowStillSaysEverythingAtDoubleFontSize() {
        setPage()
        val row = compose.onNodeWithText("云村用户 8842")
        val normal = row.fetchSemanticsNode().size.height
        compose.runOnIdle { ui = ui.copy(accounts = meMixedAccountStates().map(::meAccountRowOf)) }
        compose.runOnIdle { fontScale = 2f }
        compose.waitForIdle()
        // Status moves under the second line; nothing is dropped and the row grows.
        compose.onNodeWithText("需要重新登录").assertExists()
        compose.onNodeWithText("云村用户 8842").assertExists()
        val large = compose.onNodeWithText("云村用户 8842").fetchSemanticsNode().size.height
        assertTrue("account row stayed ${normal}px at font scale 2.0 (now ${large}px)", large > normal)
    }

    @Test fun theCacheRowClearsOnlyWhenThereIsSomethingToClear() {
        var cleared = 0
        setPage(MeActions(onClearCache = { cleared++ }))
        compose.onNodeWithText("缓存").performClick()
        assertEquals(1, cleared)

        compose.runOnIdle { ui = ui.copy(storage = MeStorageUi("0 KB", clearable = false)) }
        compose.onNodeWithText("缓存").assertHasNoClickAction().performClick()
        assertEquals("an empty cache must not start a clear", 1, cleared)
    }

    // --- layout --------------------------------------------------------------------------------

    @Test fun aRowIsNeverShorterThanTheSharedRowMinimum() {
        setPage()
        for (title in listOf("字号", "默认音质", "云村用户 8842", "酷狗")) {
            compose.onNodeWithText(title).assertHeightIsAtLeast(RowSpec.minHeight)
        }
    }

    @Test fun rowsGrowWithTheFontScaleInsteadOfClipping() {
        setPage()
        val row = compose.onNodeWithText("放不了时自动换到其他平台")
        val normal = row.fetchSemanticsNode().size.height
        compose.runOnIdle { fontScale = 2f }
        compose.waitForIdle()
        val large = row.fetchSemanticsNode().size.height
        assertTrue("row stayed ${normal}px at font scale 2.0 (now ${large}px)", large > normal)
    }

    @Test fun aboutGroupNeedsAVersion() {
        setPage(appVersion = "")
        compose.onNodeWithText("当前版本").assertDoesNotExist()
    }

    @Test fun aboutGroupShowsTheVersionItIsGiven() {
        setPage(appVersion = "0.2.0-alpha01")
        compose.onNodeWithText("0.2.0-alpha01").assertExists()
    }

    // --- dark mode sub-page --------------------------------------------------------------------

    @Test fun darkModePageReportsOnlyARealChange() {
        val picked = mutableListOf<ThemeMode>()
        var backs = 0
        compose.setContent {
            MusxiTheme {
                DarkModePage(
                    selected = ThemeMode.SYSTEM,
                    onSelect = { picked += it },
                    onBack = { backs++ },
                )
            }
        }
        compose.onNodeWithText("跟随系统").assertIsSelected().performClick()
        compose.onNodeWithText("深色").assertIsNotSelected().performClick()
        compose.onNodeWithText("浅色").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(listOf(ThemeMode.DARK, ThemeMode.LIGHT), picked)
        assertEquals(1, backs)
    }
}
