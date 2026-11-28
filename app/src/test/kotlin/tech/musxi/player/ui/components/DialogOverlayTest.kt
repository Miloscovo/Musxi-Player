package tech.musxi.player.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.player.SongInfoSheet
import tech.musxi.player.ui.theme.MusxiTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = DesignTestApplication::class)
class DialogOverlayTest {

    @get:Rule val compose = createComposeRule()

    @Test fun confirmDialogHoistsIntoOverlayAndReportsOpenState() {
        val overlay = DialogOverlayState()
        var visible by mutableStateOf(false)
        var confirmed = 0
        var dismissed = 0

        compose.setContent {
            MusxiTheme {
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalDialogOverlay provides overlay) {
                        MusxiConfirmDialog(
                            visible = visible,
                            title = "清除缓存？",
                            body = "封面、歌词和曲库缓存会被清除。",
                            note = "账号登录状态不受影响。",
                            confirmLabel = "清除",
                            danger = true,
                            eyebrow = "存储维护",
                            eyebrowDot = Color(0xFFE60026),
                            onDismiss = {
                                dismissed++
                                visible = false
                            },
                            onConfirm = {
                                confirmed++
                                visible = false
                            },
                        )
                    }
                    DialogOverlayLayer(overlay)
                }
            }
        }

        assertFalse(overlay.open)
        compose.onNodeWithText("清除缓存？").assertDoesNotExist()

        visible = true
        compose.waitForIdle()
        assertTrue(overlay.open)
        compose.onNodeWithText("存储维护").assertIsDisplayed()
        compose.onNodeWithText("清除缓存？").assertIsDisplayed()
        compose.onNodeWithText("封面、歌词和曲库缓存会被清除。").assertIsDisplayed()
        compose.onNodeWithText("账号登录状态不受影响。").assertIsDisplayed()

        compose.onNodeWithText("清除").performClick()
        compose.waitForIdle()
        assertEquals(1, confirmed)
        assertEquals(0, dismissed)
        assertFalse(overlay.open)
        compose.onNodeWithText("清除缓存？").assertDoesNotExist()
    }

    @Test fun busyConfirmDialogLocksButtonsAndShowsBusyLabel() {
        var dismissed = 0
        var confirmed = 0

        compose.setContent {
            MusxiTheme {
                MusxiConfirmDialog(
                    visible = true,
                    title = "删除歌单？",
                    body = "此操作不可撤回。",
                    confirmLabel = "删除",
                    busyLabel = "正在删除…",
                    danger = true,
                    busy = true,
                    onDismiss = { dismissed++ },
                    onConfirm = { confirmed++ },
                )
            }
        }

        compose.onNodeWithText("取消").assertIsNotEnabled()
        compose.onNodeWithText("正在删除…").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("正在删除…").performClick()
        assertEquals(0, dismissed)
        assertEquals(0, confirmed)
    }

    @Test fun textFieldDialogSupportsClearValidationAndAuxiliaryAction() {
        var confirmed: String? = null
        var restored = 0

        compose.setContent {
            MusxiTheme {
                var value by remember { mutableStateOf(TextFieldValue("通勤夜听")) }
                val empty = value.text.isBlank()
                MusxiTextFieldDialog(
                    visible = true,
                    title = "重命名",
                    value = value,
                    onValueChange = { value = it },
                    confirmLabel = "保存",
                    onDismiss = {},
                    onConfirm = { confirmed = value.text },
                    confirmEnabled = !empty,
                    placeholder = "输入名称",
                    helperText = "只修改显示名称",
                    errorText = if (empty) "请输入名称" else null,
                    counterText = "${value.text.length} / 80",
                    auxiliaryLabel = "恢复原名",
                    onAuxiliary = { restored++ },
                )
            }
        }

        compose.onNodeWithText("4 / 80").assertIsDisplayed()
        compose.onNodeWithText("保存").assertIsEnabled()

        compose.onNodeWithText("恢复原名").performClick()
        assertEquals(1, restored)

        compose.onNodeWithContentDescription("清空").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("请输入名称").assertIsDisplayed()
        compose.onNodeWithText("0 / 80").assertIsDisplayed()
        compose.onNodeWithText("保存").assertIsNotEnabled()
        assertEquals(null, confirmed)
    }

    @Test fun forceSolidBypassesOverlayAndRendersInPlace() {
        val overlay = DialogOverlayState()
        compose.setContent {
            MusxiTheme {
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalDialogOverlay provides overlay) {
                        MusxiConfirmDialog(
                            visible = true,
                            title = "书签名称",
                            body = "在 Sheet 之上保持实色。",
                            confirmLabel = "保存",
                            forceSolid = true,
                            onDismiss = {},
                            onConfirm = {},
                        )
                    }
                    DialogOverlayLayer(overlay)
                }
            }
        }

        assertFalse(overlay.open)
        assertTrue(overlay.entries.isEmpty())
        compose.onNodeWithText("书签名称").assertIsDisplayed()
    }

    @Test fun songInfoSheetShowsMetadataRows() {
        var visible by mutableStateOf(true)
        compose.setContent {
            MusxiTheme {
                SongInfoSheet(
                    visible = visible,
                    title = "晴天",
                    rows = listOf("歌手" to "周杰伦", "专辑" to "叶惠美", "音质" to "无损"),
                    onDismiss = { visible = false },
                )
            }
        }

        compose.onNodeWithText("晴天").assertIsDisplayed()
        compose.onNodeWithText("歌曲信息").assertIsDisplayed()
        compose.onNodeWithText("周杰伦").assertIsDisplayed()
        compose.onNodeWithText("叶惠美").assertIsDisplayed()
        compose.onNodeWithText("无损").assertIsDisplayed()
    }

    @Test fun largeFont200PercentStacksDialogButtonsAndKeeps44dpClearTarget() {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                MusxiTheme {
                    var value by remember { mutableStateOf(TextFieldValue("通勤夜听")) }
                    MusxiTextFieldDialog(
                        visible = true,
                        title = "新建歌单",
                        value = value,
                        onValueChange = { value = it },
                        confirmLabel = "新建并加入",
                        busyLabel = "正在新建…",
                        busy = true,
                        helperText = "创建后自动加入「晴天」",
                        counterText = "4 / 80",
                        onDismiss = {},
                        onConfirm = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val cancelBounds = compose.onNodeWithText("取消").getUnclippedBoundsInRoot()
        val busyBounds = compose.onNodeWithText("正在新建…").getUnclippedBoundsInRoot()
        assertTrue("Expected stacked dialog buttons at 200% font scale", busyBounds.top >= cancelBounds.bottom)
        compose.onNodeWithText("创建后自动加入「晴天」").assertIsDisplayed()
        compose.onNodeWithText("4 / 80").assertIsDisplayed()
    }

    @Test fun clearButtonTouchTargetMeets44dpMinimum() {
        compose.setContent {
            MusxiTheme {
                var value by remember { mutableStateOf(TextFieldValue("测试")) }
                MusxiDialogTextField(value = value, onValueChange = { value = it })
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("清空")
            .assertWidthIsAtLeast(44.dp)
            .assertHeightIsAtLeast(44.dp)
    }
}
