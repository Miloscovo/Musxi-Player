package tech.musxi.player.ui.account

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.components.OpticallyCentredText
import tech.musxi.player.ui.components.PlatformTag
import tech.musxi.player.ui.components.ProgressArc
import tech.musxi.player.ui.theme.displayName
import tech.musxi.player.ui.theme.identityColor

/**
 * One account row, drawn as a line on paper rather than as a card.
 *
 * The previous version stacked three decorations for one fact — a filled card, a 44dp circle holding
 * two characters of the platform name, and a coloured pill — so a page of seven rows became seven
 * grey slabs. A row only needs: which platform (a 6dp dot, the mark used everywhere else), what it
 * is called now, and one action.
 *
 * Only one action on the page is allowed to be solid: the caller decides which row is the most
 * urgent. Everything else is a text button.
 */
@Composable
fun AccountRowCard(
    row: AccountRowUi,
    dark: Boolean,
    solidAction: Boolean,
    onAction: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        // 64dp like every other list row. The row has no artwork, so its text starts at the page
        // margin instead of a cover slot — the one documented exception to the shared row grid.
        Modifier.fillMaxWidth().height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // One shared tag, so this row and every other place cannot drift apart again.
                PlatformTag(row.platform, dark, showName = false)
                Spacer(Modifier.width(6.dp))
                Text(
                    row.platform.displayName, style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                AccountStatusMark(row.phase, row.pill)
            }
            Spacer(Modifier.height(3.dp))
            // Known identity wins; otherwise one sentence. Never both repeating the status.
            if (row.name != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.name, style = MaterialTheme.typography.bodySmall, color = cs.onSurface,
                        maxLines = 1,
                    )
                    if (row.detail != null) {
                        Text(
                            " · ${row.detail}", style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant, maxLines = 1,
                        )
                    }
                }
            } else {
                Text(
                    row.detail.orEmpty(), style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant, maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // Fixed slot so a filled pill and a text button end on the same right edge.
        Box(Modifier.widthIn(min = 88.dp), contentAlignment = Alignment.CenterEnd) {
            Action(row, solidAction, onAction)
        }
    }
}

@Composable
private fun Action(row: AccountRowUi, solid: Boolean, onAction: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val label = row.actionLabel
    when {
        row.action == AccountAction.NONE || label == null -> Unit
        solid -> Button(
            onClick = onAction,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(label, style = MaterialTheme.typography.labelLarge) }

        else -> TextButton(
            onClick = onAction,
            colors = ButtonDefaults.textButtonColors(contentColor = cs.onSurfaceVariant),
        ) { Text(label, style = MaterialTheme.typography.labelLarge) }
    }
}

/**
 * Status without a pill, and without a second dot.
 *
 * The platform mark is already a dot; a status dot next to it made two circles of similar size and
 * similar colour mean different things — the worst case being the Netease red platform dot beside a
 * red error dot on the same row. Problems therefore use a bar glyph, and "disconnected" uses a ring.
 */
@Composable
private fun AccountStatusMark(phase: AccountPhase, text: String) {
    val cs = MaterialTheme.colorScheme
    val color = when (phase) {
        AccountPhase.CONNECTED -> cs.primary
        AccountPhase.RETRYABLE_ERROR, AccountPhase.REAUTH_REQUIRED -> cs.error
        else -> cs.onSurfaceVariant
    }
    Row(
        Modifier.padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (phase) {
            AccountPhase.CONNECTED -> Icon(Icons.Filled.Check, null, tint = color, modifier = Modifier.size(12.dp))
            AccountPhase.VERIFYING, AccountPhase.REVALIDATING -> ProgressArc(
                modifier = Modifier.size(10.dp), color = color, size = 10.dp, strokeWidth = 1.5.dp,
            )
            AccountPhase.WAITING_CONFIRMATION, AccountPhase.PREPARING -> WaitingGlyph(color)
            // Same visual weight as the other marks, but unmistakably a warning, so it never reads
            // as a second platform dot. Drawn by hand so the family stays consistent.
            AccountPhase.RETRYABLE_ERROR, AccountPhase.REAUTH_REQUIRED -> WarningGlyph(color)

            // Hollow ring: "nothing here yet", the opposite of the filled identity dot.
            AccountPhase.DISCONNECTED -> Canvas(Modifier.size(7.dp)) {
                drawCircle(
                    color = color,
                    radius = size.minDimension / 2 - 0.75.dp.toPx(),
                    style = Stroke(width = 1.2.dp.toPx()),
                )
            }
        }
        OpticallyCentredText(text, MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Waiting on the user elsewhere: a clock outline, so it is never confused with a plain dot. */
@Composable
private fun WaitingGlyph(color: Color) {
    Canvas(Modifier.size(10.dp)) {
        val stroke = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(color = color, radius = size.minDimension / 2 - stroke.width, style = stroke)
        drawLine(
            color = color,
            start = Offset(size.width / 2, size.height / 2),
            end = Offset(size.width / 2, size.height * 0.24f),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** An exclamation mark: a warning in the same 10dp family as the clock and the arc. */
@Composable
private fun WarningGlyph(color: Color) {
    Canvas(Modifier.size(10.dp)) {
        val w = 1.4.dp.toPx()
        drawLine(
            color = color,
            start = Offset(size.width / 2, size.height * 0.12f),
            end = Offset(size.width / 2, size.height * 0.62f),
            strokeWidth = w,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = color,
            radius = w / 2,
            center = Offset(size.width / 2, size.height * 0.88f),
        )
    }
}
