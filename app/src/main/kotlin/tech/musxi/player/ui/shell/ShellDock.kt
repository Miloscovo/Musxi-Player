package tech.musxi.player.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import tech.musxi.player.ui.player.MiniPlayer
import tech.musxi.player.ui.player.MiniPlayerUi
import tech.musxi.player.ui.theme.chromeBarColor

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
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(cs.surfaceContainerHigh),
    ) {
        if (mini.visible) {
            // The player sits inside the dock, so it must not draw its own container.
            MiniPlayer(
                ui = mini,
                onExpand = onExpand,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                embedded = true,
            )
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
        }
        ShellBottomBar(
            selected = selected,
            onSelect = onSelect,
            shape = BarShape.FULL_WIDTH,
            embedded = true,
        )
    }
}

/** Container colour used when the dock replaces both capsules. */
@Composable
fun dockColor() = MaterialTheme.colorScheme.chromeBarColor()
