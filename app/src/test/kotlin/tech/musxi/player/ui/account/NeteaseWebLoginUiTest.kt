package tech.musxi.player.ui.account

import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.accounts.NeteaseWebStage
import tech.musxi.accounts.NeteaseWebUiState
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.theme.MusxiTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class NeteaseWebLoginUiTest {
    @get:Rule val compose = createComposeRule()

    private var state by mutableStateOf(readyState())

    private fun show(
        onClose: () -> Unit = {},
        onReload: () -> Unit = {},
        onSubmit: () -> Unit = {},
        onUseScanner: (() -> Unit)? = {},
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                MusxiTheme {
                    Column(Modifier.fillMaxSize()) {
                        NeteaseWebLoginSheetBody(
                            state = state,
                            dark = false,
                            onClose = onClose,
                            onReload = onReload,
                            onSubmit = onSubmit,
                            onUseScanner = onUseScanner,
                            webContent = { Box(Modifier.fillMaxSize()) },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun preparingDisablesSubmitAndReload() {
        state = NeteaseWebUiState(
            stage = NeteaseWebStage.PREPARING,
            host = "y.music.163.com",
            progress = 0,
            canSubmit = false,
            canReload = false,
        )
        show()
        compose.onNodeWithText("正在准备安全环境…").assertExists()
        compose.onNodeWithTag(NeteaseWebSubmitTag).assertIsNotEnabled()
        compose.onNodeWithTag(NeteaseWebReloadTag).assertIsNotEnabled()
        compose.onNodeWithTag(NeteaseWebNoticeTag).assertDoesNotExist()
    }

    @Test fun readyTriggersSubmitReloadAndClose() {
        var closes = 0
        var reloads = 0
        var submits = 0
        show(onClose = { closes++ }, onReload = { reloads++ }, onSubmit = { submits++ })

        compose.onNodeWithTag(NeteaseWebSubmitTag).assertIsEnabled().performClick()
        compose.onNodeWithTag(NeteaseWebReloadTag).assertIsEnabled().performClick()
        compose.onNodeWithTag(NeteaseWebCloseTag).performClick()

        assertEquals(1, submits)
        assertEquals(1, reloads)
        assertEquals(1, closes)
    }

    @Test fun blockedNavigationOffersScannerFallback() {
        var scannerClicks = 0
        state = readyState(NeteaseWebStage.NAVIGATION_BLOCKED)
        show(onUseScanner = { scannerClicks++ })
        compose.onNodeWithTag(NeteaseWebNoticeTag).assertExists()
        compose.onNodeWithText("改用相册扫码登录").assertIsDisplayed().performClick()
        assertEquals(1, scannerClicks)
    }

    @Test fun cookiesMissingShowsNoticeWithoutScanner() {
        state = readyState(NeteaseWebStage.COOKIES_MISSING)
        show()
        compose.onNodeWithTag(NeteaseWebNoticeTag).assertExists()
        compose.onNodeWithText("还没有检测到登录完成，请先在上方网页中登录，再点击验证。").assertExists()
        compose.onNodeWithText("改用相册扫码登录").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun narrowLargeFontKeepsControlsAndWebAreaUsable() {
        state = readyState(NeteaseWebStage.NAVIGATION_BLOCKED)
        show(fontScale = 2f)

        compose.onNodeWithTag(NeteaseWebCloseTag)
            .assertWidthIsAtLeast(44.dp)
            .assertHeightIsAtLeast(44.dp)
        compose.onNodeWithTag(NeteaseWebCardTag).assertHeightIsAtLeast(120.dp)
        compose.onNodeWithTag(NeteaseWebSubmitTag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val scanner = compose.onNodeWithText("改用相册扫码登录").getUnclippedBoundsInRoot()
        val submit = compose.onNodeWithTag(NeteaseWebSubmitTag).getUnclippedBoundsInRoot()
        assertTrue("Scanner action overlaps the submit button", scanner.bottom <= submit.top)
    }

    @Test fun secureFlagRestoresOnlyTheFlagItAdded() {
        val window = Robolectric.buildActivity(android.app.Activity::class.java).get().window
        assertTrue(acquireSecureFlag(window))
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        restoreSecureFlag(window, addedByLogin = true)
        assertFalse(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test fun secureFlagPreservesAHostFlag() {
        val window = Robolectric.buildActivity(android.app.Activity::class.java).get().window
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        assertFalse(acquireSecureFlag(window))
        restoreSecureFlag(window, addedByLogin = false)
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test fun largeFontErrorNoticeLeavesSubmitVisible() {
        state = readyState(NeteaseWebStage.COOKIE_UNAVAILABLE)
        show(fontScale = 2f)

        compose.onNodeWithTag(NeteaseWebNoticeTag).assertIsDisplayed()
        compose.onNodeWithText("改用相册扫码登录").assertIsDisplayed()
        compose.onNodeWithTag(NeteaseWebSubmitTag).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag(NeteaseWebCardTag).assertHeightIsAtLeast(120.dp)
        val submit = compose.onNodeWithTag(NeteaseWebSubmitTag).getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue("Submit falls below the window at 200% font", submit.bottom <= root.bottom + 1.dp)
    }
}

private fun readyState(stage: NeteaseWebStage = NeteaseWebStage.READY) = NeteaseWebUiState(
    stage = stage,
    host = "y.music.163.com",
    progress = 100,
    canSubmit = true,
    canReload = true,
)
