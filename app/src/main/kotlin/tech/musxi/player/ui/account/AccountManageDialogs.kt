package tech.musxi.player.ui.account

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.components.AnchoredMenu
import tech.musxi.player.ui.components.MenuHeader
import tech.musxi.player.ui.components.MenuItem
import tech.musxi.player.ui.components.MusxiConfirmDialog
import tech.musxi.player.ui.theme.identityColor
import tech.musxi.player.ui.theme.isDarkSurface

/**
 * Which account management surface is open, and for which platform. Held by the page that shows
 * [AccountManageMenu] and [AccountDialogsHost], so the account list and the per-platform page
 * behave the same way: tapping a connected row opens an anchored glass menu (重新验证 / 退出登录),
 * and only the destructive sign-out opens a centered confirmation dialog.
 */
@Stable
class AccountDialogs internal constructor() {
    internal var managing by mutableStateOf<Platform?>(null)
    internal var signingOut by mutableStateOf<Platform?>(null)

    /** Opens the 重新验证 / 退出登录 menu anchored to a connected account row. */
    fun manage(platform: Platform) {
        managing = platform
    }

    internal fun dismissMenu() {
        managing = null
    }

    internal fun requestSignOut(platform: Platform) {
        managing = null
        signingOut = platform
    }
}

@Composable
fun rememberAccountDialogs(): AccountDialogs = remember { AccountDialogs() }

/**
 * Anchored glass menu for a connected account row: 重新验证 and 退出登录. Replaces the old first-layer
 * AlertDialog so non-destructive choices stay lightweight (spec section 9).
 */
@Composable
fun AccountManageMenu(
    expanded: Boolean,
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    onReverify: () -> Unit,
    onSignOut: () -> Unit,
) {
    AnchoredMenu(expanded = expanded, onDismiss = onDismiss, preferAbove = false) {
        MenuHeader(title, subtitle)
        MenuItem(
            label = "重新验证",
            onClick = {
                onDismiss()
                onReverify()
            },
            icon = Icons.Outlined.Refresh,
        )
        MenuItem(
            label = "退出登录",
            onClick = {
                onDismiss()
                onSignOut()
            },
            icon = Icons.AutoMirrored.Outlined.Logout,
            iconTint = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * Hosts the destructive sign-out confirmation dialog behind [AccountManageMenu]. Signing out
 * clears the saved account and cached playlists, so it asks once more via [MusxiConfirmDialog].
 */
@Composable
fun AccountDialogsHost(
    dialogs: AccountDialogs,
    onReverify: (Platform) -> Unit,
    onSignOut: (Platform) -> Unit,
) {
    // Keep the last platform across the exit animation so the card fades out cleanly.
    val last = remember { arrayOfNulls<Platform>(1) }
    dialogs.signingOut?.let { last[0] = it }
    val platform = dialogs.signingOut ?: last[0] ?: return
    val dark = MaterialTheme.colorScheme.background.isDarkSurface()
    MusxiConfirmDialog(
        visible = dialogs.signingOut != null,
        title = "退出${platform.fullName()}？",
        body = "退出后将清除本机保存的账号和缓存歌单，之后需要重新登录才能同步。",
        confirmLabel = "退出",
        danger = true,
        eyebrow = platform.fullName(),
        eyebrowDot = platform.identityColor(dark),
        onDismiss = { dialogs.signingOut = null },
        onConfirm = {
            dialogs.signingOut = null
            onSignOut(platform)
        },
    )
}

internal fun Platform.fullName(): String = when (this) {
    Platform.NETEASE -> "网易云音乐"
    Platform.QQ -> "QQ音乐"
    Platform.KUGOU -> "酷狗音乐"
}
