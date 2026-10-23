package tech.musxi.player.ui.design

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.shell.ShellDock
import tech.musxi.player.ui.shell.ShellTab
import tech.musxi.player.ui.theme.Space
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary
import tech.musxi.player.ui.search.SearchPhase
import tech.musxi.player.ui.search.SearchScreen
import tech.musxi.player.ui.search.SearchUiState
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/**
 * Search page sheet. One image per result state, because the states are exactly what is easy to
 * get wrong: an empty page must not look like a failure, and a failure must offer one clear way
 * back. Uses the real [SearchScreen], so the sheet cannot drift from the shipped layout.
 */
@Composable
fun SearchSheet(accent: Accent, mode: ThemeMode, state: SearchUiState) {
    MusxiTheme(accent = accent, mode = mode) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    SearchScreen(
                        state = state,
                        onKeywordsChange = {},
                        onPlatformSelect = {},
                        onSearch = {},
                        onLoadMore = {},
                        onRetry = {},
                    )
                }
                // The same dock as every other tab: a page without chrome was the last place
                // where the shell could still look like two different apps.
                Column(Modifier.padding(horizontal = Space.page)) {
                    ShellDock(
                        mini = sampleMiniPlaying(),
                        selected = ShellTab.SEARCH,
                        onSelect = {},
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/** Idle: nothing typed yet. The page must read as ready, not as if something failed to load. */
fun idleState() = SearchUiState(phase = SearchPhase.IDLE)

/** Results: a normal first page on the default platform. */
fun resultsState() = SearchUiState(
    keywords = "夜に駆ける",
    platform = Platform.NETEASE,
    items = searchSampleSongs().take(6),
    phase = SearchPhase.RESULTS,
    total = 128,
    canLoadMore = true,
)

/** Further page in flight under a visible list: the footer must not push the rows around. */
fun loadingMoreState() = resultsState().copy(loadingMore = true)

/** Last page reached: the footer states that plainly instead of showing a dead "load more". */
fun allLoadedState() = resultsState().copy(canLoadMore = false, total = 6)

/**
 * Long title, four artists and a long album in one row: the shape most likely to break the row.
 * Cover and duration must keep their space; the text column is what yields.
 */
fun longTextState() = SearchUiState(
    keywords = "wherever you are",
    platform = Platform.KUGOU,
    items = listOf(
        SongSummary(
            key = MediaKey(Platform.KUGOU, "long-1"),
            title = "Wherever You Are (Live at Budokan 2024 Remastered Edition)",
            artists = listOf("ONE OK ROCK", "Taka", "Toru", "Ryota", "Tomoya"),
            album = "残響リファレンス 15th Anniversary Deluxe Edition",
            durationMs = 5_412_000,
        ),
        SongSummary(
            key = MediaKey(Platform.KUGOU, "long-2"),
            title = "Clock Strikes",
            artists = listOf("ONE OK ROCK"),
            album = "<unknown>",
            durationMs = 232_000,
        ),
    ),
    phase = SearchPhase.RESULTS,
    total = 2,
    canLoadMore = false,
)

/** Empty: a real "we searched and found nothing", which is not an error. */
fun emptyState() = SearchUiState(
    keywords = "zzzzzz",
    platform = Platform.QQ,
    phase = SearchPhase.EMPTY,
)

/** Loading: first page in flight with no items to show yet. */
fun loadingState() = SearchUiState(
    keywords = "夜に駆ける",
    platform = Platform.KUGOU,
    phase = SearchPhase.LOADING,
    loading = true,
)

/** Error: one retry action, plus wording that says whether retrying can help. */
fun errorState() = SearchUiState(
    keywords = "夜に駆ける",
    platform = Platform.NETEASE,
    phase = SearchPhase.ERROR,
    errorText = "网络连接异常，请稍后重试",
)
