package tech.musxi.player.ui.account

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tech.musxi.player.ui.components.Avatar
import tech.musxi.player.ui.components.CardRow
import tech.musxi.player.ui.components.RowChevron
import tech.musxi.player.ui.components.RowSpec
import tech.musxi.player.ui.components.RowText
import tech.musxi.player.ui.me.MeAccountRow

/**
 * One platform account on its own page, drawn like its row on the "我的" page: [identity] gives the
 * avatar, the name and the grey line, built by the same `meAccountRowOf`, so the two pages cannot
 * describe one state differently. [row] gives the right edge: a chevron when the whole row opens
 * the manage dialogs, or the one action the state needs ("连接", "取消", "重试", "重新登录") in the
 * accent colour. Every action runs through [onAction].
 */
@Composable
fun AccountRowCard(
    identity: MeAccountRow,
    row: AccountRowUi,
    onAction: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val label = row.actionLabel
    CardRow(onClick = if (row.action == AccountAction.MANAGE) onAction else null) {
        Avatar(identity.title, identity.avatarUrl, identity.signedIn)
        Spacer(Modifier.width(RowSpec.leadGap))
        RowText(identity.title, subtitle = identity.subtitle, subtitleLines = 2)
        when {
            row.action == AccountAction.MANAGE -> {
                Spacer(Modifier.width(RowSpec.trailGap))
                RowChevron()
            }
            row.action != AccountAction.NONE && label != null -> {
                Spacer(Modifier.width(RowSpec.trailGap))
                TextButton(
                    onClick = onAction,
                    colors = ButtonDefaults.textButtonColors(contentColor = cs.primary),
                ) { Text(label, style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}
