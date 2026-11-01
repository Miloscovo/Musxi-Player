package tech.musxi.player.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.components.RowDivider
import tech.musxi.player.ui.components.RowSpec
import tech.musxi.player.ui.components.SectionGroup
import tech.musxi.player.ui.me.MeAccountRow
import tech.musxi.player.ui.theme.Space

/**
 * Account screen: three platforms as lines on a page, separated by hairlines. No longer shown in
 * the app: the "我的" page and the per-platform [AccountDetailPage] replaced it.
 *
 * One solid accent button at most. The order below is "how stuck is the user", so a page with an
 * expired session and a disconnected platform shows one solid action, not two competing ones.
 */
@Composable
fun AccountScreen(
    state: AccountUiState,
    /** The "我的" page's rows: each card's avatar, name and grey line, by platform. */
    identities: List<MeAccountRow>,
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
    /** Empty hides the About group. */
    appVersion: String = "",
    update: AboutUpdateUi = AboutUpdateUi(
        developmentBuild = false, checking = false, checkNote = null, release = null,
        autoCheck = null, badge = false,
    ),
    updateActions: UpdateActions = UpdateActions(),
) {
    val cs = MaterialTheme.colorScheme
    val dialogs = rememberAccountDialogs()
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

        SectionGroup(title = null) {
            state.rows.forEachIndexed { index, row ->
                val identity = identities.firstOrNull { it.platform == row.platform } ?: return@forEachIndexed
                // The line starts at the text, not under the avatar.
                if (index > 0) RowDivider(start = RowSpec.textStart(RowSpec.avatar))
                AccountRowCard(
                    identity = identity,
                    row = row,
                    onAction = {
                        when (row.action) {
                            AccountAction.CONNECT -> onConnect(row.platform)
                            AccountAction.CANCEL -> onCancel(row.platform)
                            AccountAction.RETRY -> onRetry(row.platform)
                            AccountAction.RELOGIN -> onRelogin(row.platform)
                            AccountAction.MANAGE -> dialogs.manage(row.platform)
                            AccountAction.NONE -> Unit
                        }
                    },
                )
            }
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
        if (appVersion.isNotEmpty()) {
            Spacer(Modifier.height(Space.section))
            SectionGroup(title = "关于") {
                AboutSection(
                    version = appVersion, ui = update, actions = updateActions, showHeading = false,
                )
            }
        }
        // Clears the floating dock, so the last group can scroll above it.
        Spacer(Modifier.height(Space.section + bottomInset))
    }

    AccountDialogsHost(dialogs, onReverify = onReverify, onSignOut = onSignOut)
}
