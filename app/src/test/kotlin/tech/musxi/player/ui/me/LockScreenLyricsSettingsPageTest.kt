package tech.musxi.player.ui.me

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.core.settings.LockScreenLyricsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.lockscreen.LockScreenLyricsEnvironment
import tech.musxi.player.lockscreen.LockScreenLyricsPhase
import tech.musxi.player.lockscreen.LockScreenLyricsState
import tech.musxi.player.ui.theme.MusxiTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h900dp-xhdpi", application = DesignTestApplication::class)
class LockScreenLyricsSettingsPageTest {
    @get:Rule val compose = createComposeRule()

    private var ui by mutableStateOf(LockScreenLyricsSettingsUi())
    private var fontScale by mutableStateOf(1f)

    private fun setPage(
        actions: LockScreenLyricsSettingsActions = LockScreenLyricsSettingsActions(),
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MusxiTheme {
                    LockScreenLyricsSettingsPage(ui = ui, actions = actions, onBack = onBack)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun threeModesAreMutuallyExclusiveAndPermissionsAppearOnlyForPage() {
        val selected = mutableListOf<LockScreenLyricsMode>()
        setPage(LockScreenLyricsSettingsActions(onSelectMode = { selected += it }))

        compose.onNodeWithText("关闭").assertIsSelected()
        compose.onNodeWithText("媒体卡片歌词").assertIsNotSelected()
        compose.onNodeWithText("锁屏歌词页").assertIsNotSelected()
        compose.onNodeWithText("系统权限").assertDoesNotExist()

        compose.onNodeWithText("媒体卡片歌词").performClick()
        compose.onNodeWithText("锁屏歌词页").performClick()
        assertEquals(listOf(LockScreenLyricsMode.CARD, LockScreenLyricsMode.PAGE), selected)

        ui = ui.copy(mode = LockScreenLyricsMode.CARD)
        compose.waitForIdle()
        compose.onNodeWithText("系统权限").assertDoesNotExist()

        ui = LockScreenLyricsSettingsUi(
            mode = LockScreenLyricsMode.PAGE,
            overlayGranted = false,
            phase = LockScreenLyricsPhase.NEEDS_PERMISSION,
        )
        compose.waitForIdle()
        compose.onNodeWithText("系统权限").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("悬浮窗权限").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("未允许").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("后台弹出界面").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("锁屏显示").performScrollTo().assertIsDisplayed()
    }

    @Test fun permissionRowsRouteToOverlayAndAppSettingsAndShowHonestStates() {
        var overlayOpened = 0
        var appOpened = 0
        ui = lockScreenLyricsSettingsUiOf(
            mode = LockScreenLyricsMode.PAGE,
            state = LockScreenLyricsState(
                phase = LockScreenLyricsPhase.UNCONFIRMED,
                environment = LockScreenLyricsEnvironment(enabled = true, hostAvailable = true, overlayGranted = true),
            ),
        )
        setPage(
            LockScreenLyricsSettingsActions(
                onOpenOverlayPermission = { overlayOpened++ },
                onOpenAppSettings = { appOpened++ },
            ),
        )

        compose.onNodeWithText("悬浮窗权限").performScrollTo().performClick()
        compose.onNodeWithText("已允许").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("后台弹出界面").performScrollTo().performClick()
        compose.onNodeWithText("锁屏显示").performScrollTo().performClick()

        assertEquals(1, overlayOpened)
        assertEquals(2, appOpened)
        assertTrue(lockScreenPermissionsFooter(LockScreenLyricsPhase.UNCONFIRMED).contains("上次亮屏未能确认显示"))
        assertTrue(lockScreenPermissionsFooter(LockScreenLyricsPhase.NEEDS_PERMISSION).contains("无法自动检测"))
    }

    @Test fun largeFont200PercentRemainsReadableOn360dp() {
        fontScale = 2f
        ui = LockScreenLyricsSettingsUi(
            mode = LockScreenLyricsMode.PAGE,
            overlayGranted = false,
            phase = LockScreenLyricsPhase.NEEDS_PERMISSION,
        )
        setPage()
        compose.onNodeWithText("锁屏歌词页").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("悬浮窗权限").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("后台弹出界面").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("锁屏显示").performScrollTo().assertIsDisplayed()
    }
}
