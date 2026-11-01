package tech.musxi.player.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.player.MiniPlayer
import tech.musxi.player.ui.player.MiniPlayerUi
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
) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.isDarkSurface()
    val shape = MaterialTheme.shapes.extraLarge
    // Light: a white card that floats with a soft, neutral black shadow. Dark: no shadow,
    // a hairline outline instead.
    val container = Modifier.fillMaxWidth()
        .then(
            if (dark) Modifier else Modifier.shadow(
                elevation = 12.dp, shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.12f),
                clip = false,
            ),
        )
        .clip(shape)
        .background(dockColor())
        .then(if (dark) Modifier.border(1.dp, cs.outlineVariant.copy(alpha = 0.5f), shape) else Modifier)
    Column(container) {
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
        )
    }
}

/**
 * Container colour used when the dock replaces both capsules: pure white in light, the raised dark
 * surface in dark. No accent is mixed in.
 */
@Composable
fun dockColor(): Color {
    val cs = MaterialTheme.colorScheme
    return if (cs.background.isDarkSurface()) cs.surfaceContainerHigh else Color.White
}
