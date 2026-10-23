package tech.musxi.player.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.library.NumberStyle
import tech.musxi.player.ui.library.TrackListContent
import tech.musxi.player.ui.library.TrackListUiState
import tech.musxi.player.ui.library.TrackRowStyle
import tech.musxi.player.ui.player.MiniPlayerUi
import tech.musxi.player.ui.shell.ShellDock
import tech.musxi.player.ui.shell.ShellTab
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.ThemeMode

/**
 * Playlist detail sheet: [TrackListContent] itself, with the dock it shares the page with.
 *
 * The three form candidates (overlay / second level / hero cover) were settled in favour of this
 * one, so they are no longer drawn here — they live in `docs/design/candidates-20261021/`. What
 * this sheet still carries is the two recorded row alternates, because a decision can be revisited
 * only while the thing can still be looked at.
 */
@Composable
fun TrackListSheet(
    accent: Accent,
    mode: ThemeMode,
    rowStyle: TrackRowStyle = TrackRowStyle.NUMBERED,
    numberStyle: NumberStyle = NumberStyle.FLUSH_SMALL,
    state: TrackListUiState = trackListSample(),
    mini: MiniPlayerUi = sampleMiniPlaying(),
    nowPlayingId: String? = state.items.firstOrNull()?.key?.stableId,
) {
    MusxiTheme(accent = accent, mode = mode) {
        val dark = mode != ThemeMode.LIGHT
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                var dockHeight by remember { mutableIntStateOf(0) }
                val dockInset: Dp = with(LocalDensity.current) { dockHeight.toDp() } + 16.dp
                TrackListContent(
                    state = state,
                    dark = dark,
                    onBack = {},
                    onPlayAll = {},
                    onSongClick = {},
                    onLoadMore = {},
                    onRetry = {},
                    nowPlayingId = nowPlayingId,
                    bottomInset = dockInset,
                    rowStyle = rowStyle,
                    numberStyle = numberStyle,
                )
                // The same dock the app ships: the page's whole premise is that this stays visible
                // while a track is picked, so a sheet without it would review a page that is not
                // the one being built.
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(horizontal = Space.page)
                        .onSizeChanged { dockHeight = it.height },
                ) {
                    ShellDock(mini = mini, selected = ShellTab.LIBRARY, onSelect = {})
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
