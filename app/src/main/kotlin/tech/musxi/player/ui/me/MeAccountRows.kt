package tech.musxi.player.ui.me

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.components.Avatar
import tech.musxi.player.ui.components.CardRow
import tech.musxi.player.ui.components.RowChevron
import tech.musxi.player.ui.components.RowDivider
import tech.musxi.player.ui.components.RowSpec
import tech.musxi.player.ui.components.SectionGroup
import tech.musxi.player.ui.components.isLargeFont

/**
 * The "账号" group: one line per platform, the whole line opens that account's page. Connecting,
 * re-checking and signing out live on that page, so a row carries no button of its own.
 */
@Composable
fun MeAccountsGroup(rows: List<MeAccountRow>, onOpenAccount: (Platform) -> Unit) {
    SectionGroup(
        title = "账号",
        // Two explicit lines; one wrapping sentence orphaned its last word on the account page.
        footer = "账号信息仅加密保存在本机，不会上传。\n播放由本机直连平台。",
    ) {
        rows.forEachIndexed { index, row ->
            // The line starts at the text, not under the avatar.
            if (index > 0) RowDivider(start = RowSpec.textStart(RowSpec.avatar))
            MeAccountRowView(row) { onOpenAccount(row.platform) }
        }
    }
}

/**
 * The account's avatar (the default one when nobody is signed in), the nickname (the platform when
 * nobody is known) over one grey line, and a chevron. The grey line is "平台 · 会员" or a sentence
 * about the state; the right edge says something only when the user can act on it, in the accent
 * colour.
 *
 * No fixed height: the row grows with the text. At large font scales the status moves under the
 * second line instead of squeezing the name.
 */
@Composable
private fun MeAccountRowView(row: MeAccountRow, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // At large font sizes the status leaves the right edge and sits under the second line.
    val stacked = isLargeFont()
    CardRow(onClick) {
        Avatar(row.title, row.avatarUrl, row.signedIn)
        Spacer(Modifier.width(RowSpec.leadGap))
        Column(Modifier.weight(1f)) {
            Text(
                row.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            row.subtitle?.let {
                Text(
                    it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (stacked) row.status?.let { StatusText(it) }
        }
        Spacer(Modifier.width(RowSpec.trailGap))
        if (!stacked) {
            row.status?.let {
                StatusText(it)
                Spacer(Modifier.width(RowSpec.chevronGap))
            }
        }
        RowChevron()
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
    )
}
