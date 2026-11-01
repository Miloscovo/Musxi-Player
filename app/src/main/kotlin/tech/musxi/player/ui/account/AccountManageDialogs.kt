package tech.musxi.player.ui.account

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import tech.musxi.core.account.Platform

/**
 * Which of the two account dialogs is open, and for which platform. Held by the page that shows
 * [AccountDialogsHost], so the account list and the per-platform page ask the same questions.
 */
@Stable
class AccountDialogs internal constructor() {
    internal var managing by mutableStateOf<Platform?>(null)
    internal var signingOut by mutableStateOf<Platform?>(null)

    /** Opens the 重新验证 / 退出登录 choice of a connected account. */
    fun manage(platform: Platform) {
        managing = platform
    }
}

@Composable
fun rememberAccountDialogs(): AccountDialogs = remember { AccountDialogs() }

/**
 * The "管理" dialog of a connected account and the sign-out confirmation behind it. Signing out is
 * destructive (it clears the saved account and cached playlists), so it asks once more.
 */
@Composable
fun AccountDialogsHost(
    dialogs: AccountDialogs,
    onReverify: (Platform) -> Unit,
    onSignOut: (Platform) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    dialogs.managing?.let { platform ->
        AlertDialog(
            onDismissRequest = { dialogs.managing = null },
            title = { Text(platform.fullName()) },
            text = { Text("重新验证会向平台确认登录是否仍然有效；退出后本机保存的账号和缓存歌单会被清除。") },
            confirmButton = {
                TextButton(onClick = { dialogs.managing = null; onReverify(platform) }) { Text("重新验证") }
            },
            dismissButton = {
                TextButton(onClick = { dialogs.managing = null; dialogs.signingOut = platform }) {
                    Text("退出登录", color = cs.error)
                }
            },
        )
    }
    dialogs.signingOut?.let { platform ->
        AlertDialog(
            onDismissRequest = { dialogs.signingOut = null },
            title = { Text("退出${platform.fullName()}？") },
            text = { Text("之后需要重新登录才能同步歌单和收藏。") },
            confirmButton = {
                TextButton(onClick = { dialogs.signingOut = null; onSignOut(platform) }) {
                    Text("退出", color = cs.error)
                }
            },
            dismissButton = { TextButton(onClick = { dialogs.signingOut = null }) { Text("取消") } },
        )
    }
}

internal fun Platform.fullName(): String = when (this) {
    Platform.NETEASE -> "网易云音乐"
    Platform.QQ -> "QQ音乐"
    Platform.KUGOU -> "酷狗音乐"
}
