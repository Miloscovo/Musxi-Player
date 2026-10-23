package tech.musxi.player.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    onManage: (Platform) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
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
                        AccountAction.MANAGE -> onManage(row.platform)
                        AccountAction.NONE -> Unit
                    }
                },
            )
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.45f))
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
}
