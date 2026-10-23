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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.PlaylistSummary
import tech.musxi.player.ui.library.LibraryContent
import tech.musxi.player.ui.library.LibraryFilter
import tech.musxi.player.ui.library.PlaylistUiState
import tech.musxi.player.ui.player.MiniPlayer
import tech.musxi.player.ui.player.MiniPlayerUi
import tech.musxi.player.ui.shell.BarShape
import tech.musxi.player.ui.shell.ShellDock
import tech.musxi.player.ui.shell.ShellTab
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.ThemeMode

/**
 * Library sheet: the real tab, with the real chrome under it.
 *
 * Until now the only picture of this screen was the theme sheet, which drew a cover-card grid that
 * the shipped page never had. Reviewing a page nobody has built wastes everyone's round.
 */
@Composable
fun LibrarySheet(
    accent: Accent,
    mode: ThemeMode,
    state: PlaylistUiState,
    mini: MiniPlayerUi = sampleMiniPlaying(),
    tab: ShellTab = ShellTab.LIBRARY,
) {
    MusxiTheme(accent = accent, mode = mode) {
        val cs = MaterialTheme.colorScheme
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    LibraryContent(state = state, onSelectFilter = {})
                }
                // The same dock the app ships: two coexisting chrome layouts in one set of
                // screenshots was itself flagged as an inconsistency.
                Column(Modifier.padding(horizontal = Space.page)) {
                    ShellDock(mini = mini, selected = tab, onSelect = {})
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/** A populated library: enough rows to judge rhythm, with one long title to test ellipsis. */
fun libraryLoaded() = sampleLibraryLoaded().copy(
    filter = LibraryFilter.NETEASE,
    items = listOf(
        playlist("我喜欢的音乐", 746, Platform.NETEASE),
        playlist("私人雷达", 28, Platform.NETEASE),
        playlist("周杰伦 · 全专辑精选合集（2000-2024 重制版）", 412, Platform.NETEASE),
        playlist("夜读", 41, Platform.NETEASE),
        playlist("通勤", 63, Platform.NETEASE),
        playlist("国语九十年代", 128, Platform.NETEASE),
    ),
    accountVerified = true,
    fromCache = true,
)

/** An empty library: the state that most often ships with no design at all. */
fun libraryEmptyZero() = PlaylistUiState(
    filter = LibraryFilter.NETEASE,
    accountVerified = true,
)

/** No account yet: a different sentence and a different single action. */
fun libraryEmptyDisconnected() = PlaylistUiState(
    filter = LibraryFilter.NETEASE,
    accountVerified = false,
)

/** A failed refresh: the account is fine, the network is not. */
fun libraryError() = libraryLoaded().copy(error = "网络异常", items = emptyList())

private fun playlist(title: String, count: Int, platform: Platform) = PlaylistSummary(
    key = MediaKey(platform, "p-${title.hashCode()}"),
    title = title,
    coverUrl = null,
    trackCount = count,
)
