package tech.musxi.player.lab

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.theme.Motion

internal val DockHeight = 64.dp
internal val DockMargin = 12.dp

/**
 * The floating glass card with the three tabs. It sits in the glass host's overlay, above the
 * pages, so pages scroll under it and stay blurred behind it while a transition runs.
 */
@Composable
internal fun BoxScope.LabDock(selected: LabTab, onTab: (LabTab) -> Unit) {
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = DockMargin)
            .fillMaxWidth()
            .height(DockHeight)
            .labGlass(RoundedCornerShape(32.dp), LabGlassStyle.REGULAR)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabTab.entries.forEach { tab ->
            DockItem(tab, tab == selected, Modifier.weight(1f)) { onTab(tab) }
        }
    }
}

@Composable
private fun DockItem(tab: LabTab, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val pill by animateColorAsState(
        targetValue = cs.primary.copy(alpha = if (isSelected) 0.12f else 0f),
        animationSpec = if (isSelected) Motion.enter() else Motion.exit(),
        label = "dockPill",
    )
    val tint = if (isSelected) cs.primary else cs.onSurfaceVariant
    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(26.dp))
            .background(pill)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = isSelected }
            .testTag("lab-dock-${tab.name}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(tab.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Text(tab.label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

private fun LabTab.icon(): ImageVector = when (this) {
    LabTab.LIBRARY -> Icons.Rounded.LibraryMusic
    LabTab.SEARCH -> Icons.Rounded.Search
    LabTab.PROFILE -> Icons.Rounded.Person
}

/** Chooses how the system back gesture looks, so both styles can be compared on the same pages. */
@Composable
internal fun BackStyleRow(selected: BackStyle, onSelect: (BackStyle) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("返回样式", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            BackStyle.entries.forEach { style ->
                val on = style == selected
                Text(
                    style.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) cs.onPrimary else cs.onSurfaceVariant,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (on) cs.primary else cs.surfaceContainerHigh)
                        .clickable(role = Role.RadioButton) { onSelect(style) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Text(
            "从屏幕左右边缘滑动返回。再点当前标签回到根页，已在根页则滚回顶部。",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
