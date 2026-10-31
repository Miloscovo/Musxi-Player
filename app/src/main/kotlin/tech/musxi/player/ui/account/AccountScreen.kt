package tech.musxi.player.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.theme.Space

/**
 * Account screen: three platforms as lines on a page, separated by hairlines.
 *
 * One solid accent button at most. The order below is "how stuck is the user", so a page with an
 * expired session and a disconnected platform shows one solid action, not two competing ones.
 */
@Composable
fun AccountScreen(
    state: AccountUiState,
    dark: Boolean,
    /** Room for the floating dock: the page scrolls underneath it. */
    bottomInset: Dp = 0.dp,
    onConnect: (Platform) -> Unit,
    onCancel: (Platform) -> Unit,
    onRetry: (Platform) -> Unit,
    onRelogin: (Platform) -> Unit,
    onReverify: (Platform) -> Unit,
    onSignOut: (Platform) -> Unit,
    /** Netease only: the gallery-QR fallback when the web page cannot be used. Null hides it. */
    onConnectNeteaseScanner: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    var managing by remember { mutableStateOf<Platform?>(null) }
    var signingOut by remember { mutableStateOf<Platform?>(null) }
    val solidRow = solidActionPlatform(state.rows)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = Space.page),
    ) {
        Spacer(Modifier.height(Space.section))
        Text("我的", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            "三个平台各自独立登录，本机保存，可随时断开",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        state.rows.forEach { row ->
            AccountRowCard(
                row = row,
                dark = dark,
                solidAction = row.platform == solidRow,
                onAction = {
                    when (row.action) {
                        AccountAction.CONNECT -> onConnect(row.platform)
                        AccountAction.CANCEL -> onCancel(row.platform)
                        AccountAction.RETRY -> onRetry(row.platform)
                        AccountAction.RELOGIN -> onRelogin(row.platform)
                        AccountAction.MANAGE -> managing = row.platform
                        AccountAction.NONE -> Unit
                    }
                },
            )
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.45f))
        }

        // Netease's primary login is the web page; the scanner stays reachable, quietly, for the
        // phones where the page will not load. Only offered while there is nothing to cancel.
        val netease = state.rows.firstOrNull { it.platform == Platform.NETEASE }
        if (onConnectNeteaseScanner != null && netease?.action == AccountAction.CONNECT) {
            TextButton(onClick = onConnectNeteaseScanner) {
                Text(
                    "网易云网页登录打不开？改用扫码登录",
                    style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(Space.section))
        Text(
            "账号信息仅加密保存在本机，不会上传。",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        Text(
            "播放由本机直连平台。",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.section))
    }

    managing?.let { platform ->
        AlertDialog(
            onDismissRequest = { managing = null },
            title = { Text(platform.fullName()) },
            text = { Text("重新验证会向平台确认登录是否仍然有效；退出后本机保存的账号和缓存歌单会被清除。") },
            confirmButton = {
                TextButton(onClick = { managing = null; onReverify(platform) }) { Text("重新验证") }
            },
            dismissButton = {
                TextButton(onClick = { managing = null; signingOut = platform }) {
                    Text("退出登录", color = cs.error)
                }
            },
        )
    }
    signingOut?.let { platform ->
        AlertDialog(
            onDismissRequest = { signingOut = null },
            title = { Text("退出${platform.fullName()}？") },
            text = { Text("之后需要重新登录才能同步歌单和收藏。") },
            confirmButton = {
                TextButton(onClick = { signingOut = null; onSignOut(platform) }) {
                    Text("退出", color = cs.error)
                }
            },
            dismissButton = { TextButton(onClick = { signingOut = null }) { Text("取消") } },
        )
    }
}

private fun Platform.fullName(): String = when (this) {
    Platform.NETEASE -> "网易云音乐"
    Platform.QQ -> "QQ音乐"
    Platform.KUGOU -> "酷狗音乐"
}
