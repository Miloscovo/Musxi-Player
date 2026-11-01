package tech.musxi.player.ui.account

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.components.SectionCard
import tech.musxi.player.ui.me.MeAccountRow
import tech.musxi.player.ui.theme.Space

/**
 * What one platform's account page can do. These are the callbacks of the account list, handed over
 * as they are: connecting, re-login and the web login keep the flows they already have.
 */
class AccountPageActions(
    val onConnect: (Platform) -> Unit = {},
    val onCancel: (Platform) -> Unit = {},
    val onRetry: (Platform) -> Unit = {},
    val onRelogin: (Platform) -> Unit = {},
    val onReverify: (Platform) -> Unit = {},
    val onSignOut: (Platform) -> Unit = {},
    /** Netease only: the gallery-QR fallback when the web page cannot be used. Null hides it. */
    val onConnectNeteaseScanner: (() -> Unit)? = null,
)

/**
 * The page behind a row of the "我的" account group: back bar, the platform's name, the same
 * account row as the old account screen (status and its one action) and the privacy note. A
 * connected account's action opens the shared 重新验证 / 退出登录 dialogs. Signing out leaves the
 * user here, on the row that now offers to connect again.
 */
@Composable
fun AccountDetailPage(
    platform: Platform,
    row: AccountRowUi?,
    /** The same platform's row on the "我的" page: avatar, name and grey line come from it. */
    identity: MeAccountRow?,
    dark: Boolean,
    actions: AccountPageActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Room for the floating dock, so the last line can scroll above it. */
    bottomInset: Dp = 0.dp,
) {
    val cs = MaterialTheme.colorScheme
    val dialogs = rememberAccountDialogs()
    Column(modifier.fillMaxSize().padding(horizontal = Space.page)) {
        // 56dp bar with the 48dp back target, the same as the other sub-pages.
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, "返回",
                    tint = cs.onSurface, modifier = Modifier.size(24.dp),
                )
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(platform.fullName(), style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
            Spacer(Modifier.height(Space.section))
            if (row != null && identity != null) {
                SectionCard {
                    AccountRowCard(
                        identity = identity,
                        row = row,
                        onAction = {
                            when (row.action) {
                                AccountAction.CONNECT -> actions.onConnect(platform)
                                AccountAction.CANCEL -> actions.onCancel(platform)
                                AccountAction.RETRY -> actions.onRetry(platform)
                                AccountAction.RELOGIN -> actions.onRelogin(platform)
                                AccountAction.MANAGE -> dialogs.manage(platform)
                                AccountAction.NONE -> Unit
                            }
                        },
                    )
                }
                // Netease's primary login is the web page; the scanner stays reachable, quietly, for
                // the phones where the page will not load. Only offered while there is nothing to cancel.
                val scanner = actions.onConnectNeteaseScanner
                if (scanner != null && platform == Platform.NETEASE && row.action == AccountAction.CONNECT) {
                    TextButton(onClick = scanner) {
                        Text(
                            "网易云网页登录打不开？改用扫码登录",
                            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
                        )
                    }
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
            Spacer(Modifier.height(Space.section + bottomInset))
        }
    }
    AccountDialogsHost(dialogs, onReverify = actions.onReverify, onSignOut = actions.onSignOut)
}
