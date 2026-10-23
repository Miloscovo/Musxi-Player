package tech.musxi.player.ui.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tech.musxi.core.account.AccountError
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.AccountProfile
import tech.musxi.core.account.AccountState
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.account.AccountRowCard
import tech.musxi.player.ui.account.solidActionPlatform
import tech.musxi.player.ui.account.AccountRowUi
import tech.musxi.player.ui.account.toRowUi
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.ThemeMode

/**
 * Account sheet: the three platform cards across every [AccountPhase]. The rows are built through
 * the same `toRowUi` mapper the live screen uses and rendered by the same [AccountRowCard], so the
 * sheet cannot promise an action the real screen does not offer.
 */
@Composable
fun AccountSheet(accent: Accent, mode: ThemeMode) {
    MusxiTheme(accent = accent, mode = mode) {
        val cs = MaterialTheme.colorScheme
        val dark = mode != ThemeMode.LIGHT
        Surface(Modifier.fillMaxSize(), color = cs.background) {
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
                Spacer(Modifier.height(Space.section))

                val rows = sampleRows(dark)
                val solidPlatform = solidActionPlatform(rows)
                rows.forEach { row ->
                    AccountRowCard(
                        row = row,
                        dark = dark,
                        solidAction = row.platform == solidPlatform,
                        onAction = {},
                    )
                    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.45f))
                }
                Spacer(Modifier.height(Space.section * 2))
                // Two explicit lines; a single wrapping sentence orphaned "平台。" on its own row.
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
    }
}

/**
 * Every branch of the mapper, one platform each: the three states a real user meets first, then
 * the failure states that are easiest to get wrong.
 */
private fun sampleRows(dark: Boolean): List<AccountRowUi> = listOf(
    state(Platform.NETEASE, AccountPhase.CONNECTED, "云村用户 8842").toRowUi(),
    state(Platform.QQ, AccountPhase.REVALIDATING, "阿青").toRowUi(),
    state(Platform.KUGOU, AccountPhase.WAITING_CONFIRMATION).toRowUi(),
    state(Platform.NETEASE, AccountPhase.RETRYABLE_ERROR, "云村用户 8842", AccountError.NETWORK).toRowUi(),
    state(Platform.QQ, AccountPhase.REAUTH_REQUIRED).toRowUi(),
    state(Platform.KUGOU, AccountPhase.VERIFYING).toRowUi(),
    state(Platform.NETEASE, AccountPhase.DISCONNECTED).toRowUi(),
)

private fun state(
    platform: Platform,
    phase: AccountPhase,
    name: String? = null,
    error: AccountError? = null,
) = AccountState(
    platform = platform,
    phase = phase,
    profile = name?.let { AccountProfile(platform, "sample-$it", it) },
    error = error,
)
