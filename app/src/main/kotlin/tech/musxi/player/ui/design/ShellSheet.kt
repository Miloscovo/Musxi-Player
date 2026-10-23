package tech.musxi.player.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.library.LibraryContent
import tech.musxi.player.ui.player.MiniPlayer
import tech.musxi.player.ui.player.MiniPlayerUi
import tech.musxi.player.ui.player.ProgressStyle
import tech.musxi.player.ui.shell.BarShape
import tech.musxi.player.ui.shell.ShellBottomBar
import tech.musxi.player.ui.shell.ShellDock
import tech.musxi.player.ui.shell.ShellTab
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.ThemeMode

/**
 * App shell sheet: the real library tab under the real chrome.
 *
 * This sheet used to draw its own filler rows ("32 首 · 网易云", with no artwork), so every review of
 * the shell was reviewing a page that did not exist. It now composes [LibraryContent].
 */
@Composable
fun ShellSheet(
    accent: Accent,
    mode: ThemeMode,
    mini: MiniPlayerUi,
    tab: ShellTab = ShellTab.LIBRARY,
    barShape: BarShape = BarShape.WIDE_PILL,
    progressStyle: ProgressStyle = ProgressStyle.RING,
    extraRound: Boolean = false,
    tonalButton: Boolean = true,
    dock: Boolean = true,
) {
    MusxiTheme(accent = accent, mode = mode) {
        val cs = MaterialTheme.colorScheme
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    LibraryContent(state = sampleLibraryLoaded(), onSelectFilter = {})
                }
                Column(
                    Modifier.padding(horizontal = Space.page),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (dock) {
                        ShellDock(mini = mini, selected = tab, onSelect = {})
                    } else {
                        MiniPlayer(
                            ui = mini, onExpand = {}, onPlayPause = {}, onPrevious = {}, onNext = {},
                            style = progressStyle,
                            shape = if (extraRound) MaterialTheme.shapes.extraLarge else MaterialTheme.shapes.large,
                            tonalButton = tonalButton,
                        )
                        if (mini.visible) Spacer(Modifier.height(8.dp))
                        ShellBottomBar(selected = tab, onSelect = {}, shape = barShape)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
