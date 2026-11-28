package tech.musxi.player.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tech.musxi.accounts.NeteaseWebStage
import tech.musxi.accounts.NeteaseWebUiState
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.AccountState
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.account.AccountDetailPage
import tech.musxi.player.ui.account.AccountPageActions
import tech.musxi.player.ui.account.NeteaseWebLoginSheetBody
import tech.musxi.player.ui.account.toRowUi
import tech.musxi.player.ui.components.GlassHost
import tech.musxi.player.ui.components.MusxiSheet
import tech.musxi.player.ui.components.SheetDetent
import tech.musxi.player.ui.me.meAccountRowOf
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/**
 * Design preview sheet for NetEase web login: renders the real [MusxiSheet] inside [GlassHost]
 * over the account detail page, with an honest neutral WebView placeholder (no fake platform logo).
 */
@Composable
fun NeteaseWebLoginDesignSheet(
    accent: Accent,
    mode: ThemeMode,
    transparency: Boolean = true,
    state: NeteaseWebUiState = NeteaseWebUiState(
        stage = NeteaseWebStage.READY,
        host = "y.music.163.com",
        progress = 100,
        canSubmit = true,
        canReload = true,
    ),
) {
    val dark = mode != ThemeMode.LIGHT
    val accountState = AccountState(Platform.NETEASE, AccountPhase.DISCONNECTED)
    MusxiTheme(accent = accent, mode = mode) {
        GlassHost(
            enabled = transparency,
            overlay = {
                MusxiSheet(
                    visible = true,
                    onDismiss = {},
                    initialDetent = SheetDetent.FULL,
                ) {
                    NeteaseWebLoginSheetBody(
                        state = state,
                        dark = dark,
                        onClose = {},
                        onReload = {},
                        onSubmit = {},
                        onUseScanner = {},
                    ) {
                        OfficialWebViewPlaceholder()
                    }
                }
            },
        ) {
            AccountDetailPage(
                platform = Platform.NETEASE,
                row = accountState.toRowUi(),
                identity = meAccountRowOf(accountState),
                dark = dark,
                actions = AccountPageActions(onConnectNeteaseScanner = {}),
                onBack = {},
            )
        }
    }
}

/** Neutral wireframe placeholder representing the live official WebView (`y.music.163.com/m/login`). */
@Composable
private fun OfficialWebViewPlaceholder() {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xFFF3F4F6)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Language,
                contentDescription = null,
                tint = Color(0xFF7A7D85),
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "网易云官方网页承载区",
            style = MaterialTheme.typography.titleSmall,
            color = Color(0xFF2B2D30),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "真机由 WebView 直接加载 y.music.163.com/m/login\n手机号、验证码与条款跳转均为官方网页原生交互",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF7A7D85),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        // Neutral skeleton rows illustrating the embedded webpage area without mimicking brand UI.
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFFF4F5F7)),
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFFF4F5F7)),
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Box(
                Modifier
                    .fillMaxWidth(0.55f)
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE8EAEE)),
            )
        }
    }
}
