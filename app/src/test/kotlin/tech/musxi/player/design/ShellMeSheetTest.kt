package tech.musxi.player.design

import android.graphics.BitmapFactory
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.core.account.AccountState
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.design.ShellMePage
import tech.musxi.player.ui.design.ShellMeSheet
import tech.musxi.player.ui.design.meCommonAccountStates
import tech.musxi.player.ui.design.meMixedAccountStates
import tech.musxi.player.ui.design.miniPlayingSwapped
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File

/**
 * The "我的" tab inside the shell: the real page, the dark-mode picker after it has been pushed, and a
 * platform's account page, each over the dock. Every shot first asserts through the semantics tree
 * that the page it names is the one on screen, so a picture of the wrong state fails instead of
 * being filed as evidence. Under Robolectric the glass draws its solid fallback, which is expected.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class ShellMeSheetTest {
    @get:Rule val compose = createComposeRule()

    // --- the page itself ------------------------------------------------------------------------

    @Test fun pageLight() = shot("shell_me_light", ThemeMode.LIGHT, ShellMePage.ROOT) { onRootPage() }

    @Test fun pageDark() = shot("shell_me_dark", ThemeMode.DARK, ShellMePage.ROOT) { onRootPage() }

    /** The whole page in one picture; the dock floats over its bottom edge, as it would when scrolled to the end. */
    @Test @Config(qualifiers = "w360dp-h1500dp-xhdpi")
    fun pageFull() = shot("shell_me_full_light", ThemeMode.LIGHT, ShellMePage.ROOT, dockBand = false) {
        onRootPage()
        compose.onNodeWithText("关于").assertExists()
    }

    // --- 深色模式 pushed -------------------------------------------------------------------------

    @Test fun darkModePushed() = shot("shell_me_dark_mode_light", ThemeMode.LIGHT, ShellMePage.DARK_MODE) {
        onDarkModePage()
    }

    @Test fun darkModePushedDark() = shot("shell_me_dark_mode_dark", ThemeMode.DARK, ShellMePage.DARK_MODE) {
        onDarkModePage()
    }

    // --- a platform's page ----------------------------------------------------------------------

    @Test fun accountConnected() = shot(
        "shell_me_account_netease_light", ThemeMode.LIGHT, ShellMePage.ACCOUNT, Platform.NETEASE,
    ) {
        compose.onNodeWithText("网易云音乐").assertExists()
        compose.onNodeWithText("云村用户 8842").assertExists()
    }

    @Test fun accountConnectedDark() = shot(
        "shell_me_account_netease_dark", ThemeMode.DARK, ShellMePage.ACCOUNT, Platform.NETEASE,
    ) {
        compose.onNodeWithText("网易云音乐").assertExists()
        compose.onNodeWithText("云村用户 8842").assertExists()
    }

    @Test fun accountDisconnected() = shot(
        "shell_me_account_kugou_light", ThemeMode.LIGHT, ShellMePage.ACCOUNT, Platform.KUGOU,
    ) {
        compose.onNodeWithText("酷狗音乐").assertExists()
        compose.onNodeWithText("连接").assertExists()
    }

    @Test fun accountExpired() = shot(
        "shell_me_account_expired_light", ThemeMode.LIGHT, ShellMePage.ACCOUNT, Platform.KUGOU,
        accounts = meMixedAccountStates(),
    ) {
        compose.onNodeWithText("酷狗音乐").assertExists()
        compose.onNodeWithText("重新登录").assertExists()
    }

    // --- assertions on what is showing ----------------------------------------------------------

    private fun onRootPage() {
        compose.onNodeWithText("深色模式").assertExists()
        compose.onNodeWithText("曲库").assertExists()
    }

    private fun onDarkModePage() {
        compose.onNodeWithContentDescription("返回").assertExists()
        compose.onNodeWithText("浅色").assertExists()
        // The page it was pushed over is out of the accessibility tree, so none of its rows answer.
        compose.onNodeWithText("放宽时长匹配").assertDoesNotExist()
    }

    /**
     * [dockBand] checks the bottom 14% of the picture for dock pixels: anything that is not the page
     * colour there is the dock, so a dock squeezed out of the layout fails.
     */
    private fun shot(
        name: String,
        mode: ThemeMode,
        page: ShellMePage,
        platform: Platform = Platform.NETEASE,
        accounts: List<AccountState> = meCommonAccountStates(),
        dockBand: Boolean = true,
        shows: () -> Unit,
    ) {
        compose.setContent { ShellMeSheet(Accent.MIST, mode, miniPlayingSwapped(), page, platform, accounts) }
        compose.waitForIdle()
        shows()

        val out = File("build/design/$name.png")
        compose.onRoot().captureRoboImage(out.path)
        // Without -Proborazzi.test.record=true nothing is written; the full test run must still pass.
        if (!out.exists()) return
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertTrue("$name failed to decode", bitmap != null)
        val background = parse(if (mode == ThemeMode.LIGHT) "#FAFAFA" else "#121212")
        val samples = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(samples, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val share = samples.count { it == background } / samples.size.toFloat()
        assertTrue("$name: page colour only ${(share * 100).toInt()}%", share > 0.05f)
        if (!dockBand) return
        val bandTop = (bitmap.height * 0.86f).toInt()
        var chrome = 0
        for (y in bandTop until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (samples[y * bitmap.width + x] != background) chrome++
            }
        }
        assertTrue("$name: dock band almost empty ($chrome px)", chrome > bitmap.width * 4)
    }

    private fun parse(hex: String): Int = (0xFF000000L or hex.removePrefix("#").toLong(16)).toInt()
}
