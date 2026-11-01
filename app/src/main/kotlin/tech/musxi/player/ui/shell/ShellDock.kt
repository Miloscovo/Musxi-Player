package tech.musxi.player.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
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
        if (mini.visible) {
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
                )
            }
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
