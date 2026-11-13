package tech.musxi.player.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.components.GlassStyle
import tech.musxi.player.ui.components.glass
import tech.musxi.player.ui.components.sheetGlass
import tech.musxi.player.ui.player.MiniPlayer
import tech.musxi.player.ui.player.MiniPlayerUi
import tech.musxi.player.ui.player.MiniRowHeight
import tech.musxi.player.ui.player.unclippedBoundsInRoot
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.isDarkSurface

/**
 * The alternative to two stacked capsules: one dock holding the player and the tabs.
 *
 * The two-capsule version made the bottom of every screen the heaviest region — two floating
 * containers of different widths, one above the other, competing for the same job. A dock reads as
 * one piece of the device instead of two cards that happen to be near each other.
 *
 * Kept behind a flag until it has been looked at: it is the one change in this pass that would
 * overturn an already approved layout.
 */
@Composable
fun ShellDock(
    mini: MiniPlayerUi,
    selected: ShellTab,
    onSelect: (ShellTab) -> Unit,
    onExpand: () -> Unit = {},
    onPlayPause: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    notice: DockNoticeUi? = null,
    onNoticeAction: () -> Unit = {},
    /** Tabs that carry a small attention dot; handed to the tab bar unchanged. */
    badges: Set<ShellTab> = emptySet(),
    /**
     * Set when the continuous player draws the mini row above the dock: the row's place stays empty
     * here and its bounds (root pixels) are reported instead.
     */
    onMiniBounds: ((Rect) -> Unit)? = null,
    /**
     * Folded by scrolling (spec section 10): one row, the current tab's icon beside the mini player.
     * Only with a mini player; without one there is nothing to make room for.
     */
    collapsed: Boolean = false,
    /** A tap on the folded row's tab icon: the tabs come back. */
    onExpandTabs: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    // Keeps the last notice on screen while its exit animation runs after notice becomes null.
    val lastNotice = remember { mutableStateOf(notice) }
    LaunchedEffect(notice) { if (notice != null) lastNotice.value = notice }
    val dark = cs.background.isDarkSurface()
    val shape = MaterialTheme.shapes.extraLarge
    // Regular glass over the page (spec section 3): blur, tint, sheen, highlight stroke and shadow
    // all come from Modifier.glass. The shell mounts a GlassHost; outside one (the design sheets)
    // sheetGlass() is the solid fallback in the theme's own colours.
    val container = Modifier.fillMaxWidth().glass(sheetGlass(), shape, GlassStyle.REGULAR)
    Column(container) {
        AnimatedVisibility(
            visible = notice != null,
            enter = fadeIn(Motion.enter()) + expandVertically(Motion.standard()),
            exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.standard()),
        ) {
            val ui = notice ?: lastNotice.value ?: return@AnimatedVisibility
            Column {
                DockNotice(ui = ui, onAction = onNoticeAction)
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = cs.outlineVariant.copy(alpha = if (dark) 0.5f else 0.35f),
                )
            }
        }
        val folded = collapsed && mini.visible
        if (mini.visible) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(
                    visible = folded,
                    enter = fadeIn(Motion.enter()) + expandHorizontally(Motion.release()),
                    exit = fadeOut(Motion.exit()) + shrinkHorizontally(Motion.release()),
                ) {
                    FoldedTab(selected, selected in badges, onExpandTabs)
                }
                Box(Modifier.weight(1f)) {
                    if (onMiniBounds != null) {
                        Spacer(
                            Modifier.fillMaxWidth().height(MiniRowHeight)
                                .onGloballyPositioned { onMiniBounds(it.unclippedBoundsInRoot()) },
                        )
                    } else {
                        // The player sits inside the dock, so it must not draw its own container.
                        MiniPlayer(
                            ui = mini,
                            onExpand = onExpand,
                            onPlayPause = onPlayPause,
                            onPrevious = onPrevious,
                            onNext = onNext,
                            embedded = true,
                            compact = folded,
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = !folded,
            enter = fadeIn(Motion.enter()) + expandVertically(Motion.release()),
            exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.release()),
        ) {
            Column {
                if (mini.visible) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = cs.outlineVariant.copy(alpha = if (dark) 0.5f else 0.35f),
                    )
                }
                ShellBottomBar(
                    selected = selected,
                    onSelect = onSelect,
                    shape = BarShape.FULL_WIDTH,
                    embedded = true,
                    badges = badges,
                )
            }
        }
    }
}

/**
 * The folded dock's tab: the current tab's icon in its selected capsule, as on the full bar, so it
 * reads as the same control. A tap brings the three tabs back.
 */
@Composable
private fun FoldedTab(tab: ShellTab, badge: Boolean, onExpand: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .padding(start = 6.dp)
            .size(width = 56.dp, height = MiniRowHeight)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onExpand)
            .semantics { contentDescription = "${tab.label}，展开标签栏" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width = 48.dp, height = 32.dp).clip(CircleShape)
                .background(cs.primary.copy(alpha = 0.12f))
                .indication(interaction, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(tab.selectedIcon, null, tint = cs.primary, modifier = Modifier.size(24.dp))
            if (badge) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 8.dp)
                        .size(8.dp).clip(CircleShape).background(cs.error),
                )
            }
        }
    }
}

/**
 * Solid dock colour: pure white in light, the raised dark surface in dark. No accent is mixed in.
 * The dock container itself is glass now; this is the colour the player surface grows out of as it
 * expands and the fill of the notice pill, so both keep matching the solid fallback of the glass.
 */
@Composable
fun dockColor(): Color {
    val cs = MaterialTheme.colorScheme
    return if (cs.background.isDarkSurface()) cs.surfaceContainerHigh else Color.White
}
