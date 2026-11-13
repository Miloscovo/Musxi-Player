package tech.musxi.player.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.components.ALL_LINES
import tech.musxi.player.ui.components.CardRow
import tech.musxi.player.ui.components.ProgressArc
import tech.musxi.player.ui.components.RowDivider
import tech.musxi.player.ui.components.RowSpec
import tech.musxi.player.ui.components.RowText
import tech.musxi.player.ui.components.SwitchRow

/** Test handle of the "新版本" row. */
internal const val UpdateEntryTag = "update-entry"

private const val RELEASE_PAGE_PREFIX = "https://github.com/qwt1011/Musxi-Player-Android/"
private val ReleasePagePath = Regex("""[A-Za-z0-9._/-]+""")

/**
 * Only release pages of this repository may be opened from the app. The rest of the URL is limited
 * to plain path characters, so "@", query, fragment, whitespace or ".." cannot point elsewhere.
 */
fun isTrustedReleasePage(url: String): Boolean {
    if (!url.startsWith(RELEASE_PAGE_PREFIX)) return false
    val rest = url.substring(RELEASE_PAGE_PREFIX.length)
    return ReleasePagePath.matches(rest) && !rest.contains("..")
}

/**
 * "关于" group driven by [AboutUpdateUi]: version, manual check, one row for a newer release, and the
 * daily auto-check switch. Rows keep the height, type sizes and hairlines of the account rows above.
 *
 * The release's notes and download steps live in [UpdateSheet], opened by the row through
 * [UpdateActions.onShowRelease]. The row only says how far the download is, so the steps have one
 * place to be acted on.
 *
 * [initiallyExpanded] and [initiallyConfirming] are no longer read: the notes and the metered-data
 * question moved into the sheet. They stay only so existing callers keep compiling.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AboutSection(
    version: String,
    ui: AboutUpdateUi,
    actions: UpdateActions,
    initiallyExpanded: Boolean = false,
    initiallyConfirming: Boolean = false,
    /** False when the caller draws the group heading itself (the "我的" page does, above its card). */
    showHeading: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    Column {
        if (showHeading) {
            Text("关于", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
        }
        CardRow(onClick = null) {
            RowText("当前版本", subtitle = if (ui.developmentBuild) "开发版本" else null)
            Spacer(Modifier.width(RowSpec.trailGap))
            Text(version, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        RowDivider()
        CardRow(onClick = actions.onCheck, enabled = !ui.checking) {
            RowText("检查更新", titleLines = ALL_LINES)
            Spacer(Modifier.width(RowSpec.trailGap))
            if (ui.checking) ProgressArc(size = 14.dp)
            else ui.checkNote?.let { StatusText(it) }
        }
        ui.release?.let { release ->
            RowDivider()
            ReleaseEntryRow(releaseEntryOf(release), actions.onShowRelease)
        }
        ui.autoCheck?.let { enabled ->
            RowDivider()
            SwitchRow(
                title = "自动检查更新",
                checked = enabled,
                onCheckedChange = actions.onSetAutoCheck,
                subtitle = "每天一次",
            )
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** "新版本 v0.2.1" over its date and size, or over where the download is. The whole row opens the sheet. */
@Composable
private fun ReleaseEntryRow(entry: ReleaseEntryUi, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardRow(onClick = onClick, modifier = Modifier.testTag(UpdateEntryTag)) {
        RowText(
            entry.title,
            subtitle = entry.note,
            subtitleColor = if (entry.warning) cs.error else cs.onSurfaceVariant,
        )
        Spacer(Modifier.width(RowSpec.trailGap))
        ViewPill()
    }
}

/**
 * Reads as a small button, but only draws: the row is the one press target, so a tap beside the
 * pill does the same thing and there is no tile inside a tile. A faint ink tint rather than the
 * accent, which this page keeps for selection.
 */
@Composable
private fun ViewPill() {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.heightIn(min = 28.dp).clip(CircleShape)
            .background(cs.onSurface.copy(alpha = 0.07f))
            .padding(horizontal = 14.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("查看", style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
    }
}
