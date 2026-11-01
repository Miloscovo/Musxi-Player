package tech.musxi.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's one press treatment for list rows.
 *
 * Material's default `clickable` paints a square-cornered ripple across the whole row, edge to edge.
 * Next to rounded cards and a pill tab bar that square read as unfinished. Here the press is a
 * rounded tile that reaches [bleed] past the row's own edges, so the content keeps its page margin
 * while the highlight sits just outside it, the way the reference apps do it.
 *
 * Compose padding cannot be negative, so the bleed is a layout step: the row is measured [bleed]
 * wider on each side and placed back by the same amount. Its footprint in the parent is unchanged;
 * only the clipped, pressable area grows.
 */
fun Modifier.pressableRow(
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role = Role.Button,
    bleed: Dp = 8.dp,
    corner: Dp = 14.dp,
): Modifier = this
    .layout { measurable, constraints ->
        val extra = bleed.roundToPx()
        val wide = if (constraints.hasBoundedWidth) {
            constraints.copy(
                minWidth = constraints.minWidth + extra * 2,
                maxWidth = constraints.maxWidth + extra * 2,
            )
        } else constraints
        val placeable = measurable.measure(wide)
        val width = (placeable.width - extra * 2).coerceAtLeast(0)
        layout(width, placeable.height) { placeable.place(-extra, 0) }
    }
    .clip(RoundedCornerShape(corner))
    .clickable(enabled = enabled, role = role, onClick = onClick)
    .padding(horizontal = bleed)

/**
 * "Load more" for every paged list: a centred tonal pill rather than a full-width line of blue text.
 *
 * The text link pressed as a full-width square and sat left-aligned on some pages and centred on
 * others. One component fixes both. While [loading], the pill keeps its place and shows a small
 * ring with "正在读取…", so the list does not jump when the next page arrives.
 */
@Composable
fun LoadMorePill(
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(36.dp).clip(CircleShape)
                .background(cs.surfaceContainerHigh)
                .clickable(enabled = !loading, role = Role.Button, onClick = onClick)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                ProgressArc(color = cs.onSurfaceVariant, size = 14.dp, strokeWidth = 1.6.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (loading) "正在读取…" else "加载更多",
                style = MaterialTheme.typography.labelLarge,
                color = if (loading) cs.onSurfaceVariant else cs.onSurface,
            )
        }
    }
}
