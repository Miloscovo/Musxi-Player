package tech.musxi.player.ui.me

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import tech.musxi.core.settings.LockScreenLyricsMode
import tech.musxi.player.lockscreen.LockScreenLyricsPhase
import tech.musxi.player.lockscreen.LockScreenLyricsState
import tech.musxi.player.ui.components.ChoiceRow
import tech.musxi.player.ui.components.RowDivider
import tech.musxi.player.ui.components.SectionGroup
import tech.musxi.player.ui.components.ValueRow
import tech.musxi.player.ui.theme.Space

/**
 * Pure display state for the "我的 → 锁屏歌词" settings sub-page.
 * Only [overlayGranted] is detectable via Android API; OEM permissions (后台弹出界面, 锁屏显示)
 * are intentionally never fabricated as granted or denied.
 */
data class LockScreenLyricsSettingsUi(
    val mode: LockScreenLyricsMode = LockScreenLyricsMode.OFF,
    val overlayGranted: Boolean = false,
    val phase: LockScreenLyricsPhase = LockScreenLyricsPhase.DISABLED,
)

fun lockScreenLyricsSettingsUiOf(
    mode: LockScreenLyricsMode,
    state: LockScreenLyricsState,
): LockScreenLyricsSettingsUi = LockScreenLyricsSettingsUi(
    mode = mode,
    overlayGranted = state.environment.overlayGranted,
    phase = state.phase,
)

/** Honest footer for the system permissions card; never claims OEM toggles are confirmed on. */
internal fun lockScreenPermissionsFooter(phase: LockScreenLyricsPhase): String {
    val base = "OPPO / 一加 / 真我（ColorOS）等机型还需在「应用信息 → 权限管理」中手动开启「后台弹出界面」和「锁屏显示」。系统不会向应用公开这两项开关状态，此处无法自动检测。"
    val extra = when (phase) {
        LockScreenLyricsPhase.UNCONFIRMED, LockScreenLyricsPhase.FAILED ->
            "\n\n上次亮屏未能确认显示锁屏歌词页，请检查以上三项系统权限是否均已开启。"
        LockScreenLyricsPhase.HOST_UNAVAILABLE ->
            "\n\n当前版本暂不可用锁屏歌词页。"
        else -> ""
    }
    return base + extra
}

class LockScreenLyricsSettingsActions(
    val onSelectMode: (LockScreenLyricsMode) -> Unit = {},
    val onOpenOverlayPermission: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onRefreshPermissions: () -> Unit = {},
)

/**
 * Sub-page behind "我的 → 歌词 → 锁屏歌词".
 *
 * Presents the three mutually exclusive modes (关闭 / 媒体卡片歌词 / 锁屏歌词页). When 锁屏歌词页
 * is selected, shows the honest three-item system permission guide and refreshes detectable
 * permission state on every [Lifecycle.Event.ON_RESUME].
 */
@Composable
fun LockScreenLyricsSettingsPage(
    ui: LockScreenLyricsSettingsUi,
    actions: LockScreenLyricsSettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    val cs = MaterialTheme.colorScheme
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, actions.onRefreshPermissions) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) actions.onRefreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier.fillMaxSize().padding(horizontal = Space.page)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, "返回",
                    tint = cs.onSurface, modifier = Modifier.size(24.dp),
                )
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text("锁屏歌词", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
            Spacer(Modifier.height(Space.section))

            SectionGroup(
                title = "显示方式",
                footer = "锁屏歌词页的字号跟随「我的 → 歌词 → 字号」设置，并在锁屏显示时略微放大，便于远距离阅读。",
            ) {
                ChoiceRow(
                    label = "关闭",
                    subtitle = "不在锁屏显示歌词",
                    selected = ui.mode == LockScreenLyricsMode.OFF,
                    onSelect = { actions.onSelectMode(LockScreenLyricsMode.OFF) },
                )
                RowDivider()
                ChoiceRow(
                    label = "媒体卡片歌词",
                    subtitle = "将锁屏和通知栏媒体卡片标题替换为当前歌词；蓝牙、车机上的歌名也会跟着变",
                    selected = ui.mode == LockScreenLyricsMode.CARD,
                    onSelect = { actions.onSelectMode(LockScreenLyricsMode.CARD) },
                )
                RowDivider()
                ChoiceRow(
                    label = "锁屏歌词页",
                    subtitle = "亮屏时在锁屏界面显示 Musxi 歌词页；任意方向滑动可返回系统锁屏",
                    selected = ui.mode == LockScreenLyricsMode.PAGE,
                    onSelect = { actions.onSelectMode(LockScreenLyricsMode.PAGE) },
                )
            }

            if (ui.mode == LockScreenLyricsMode.PAGE) {
                Spacer(Modifier.height(Space.section))
                SectionGroup(
                    title = "系统权限",
                    footer = lockScreenPermissionsFooter(ui.phase),
                ) {
                    ValueRow(
                        title = "悬浮窗权限",
                        subtitle = "允许在锁屏界面上方显示页面",
                        value = if (ui.overlayGranted) "已允许" else "未允许",
                        valueColor = if (ui.overlayGranted) cs.onSurfaceVariant else cs.primary,
                        onClick = actions.onOpenOverlayPermission,
                    )
                    RowDivider()
                    ValueRow(
                        title = "后台弹出界面",
                        subtitle = "允许亮屏时从后台展示锁屏歌词页",
                        value = "需在系统设置确认",
                        onClick = actions.onOpenAppSettings,
                    )
                    RowDivider()
                    ValueRow(
                        title = "锁屏显示",
                        subtitle = "允许页面在手机锁屏状态下显示",
                        value = "需在系统设置确认",
                        onClick = actions.onOpenAppSettings,
                    )
                }
            }

            Spacer(Modifier.height(Space.section + bottomInset))
        }
    }
}
