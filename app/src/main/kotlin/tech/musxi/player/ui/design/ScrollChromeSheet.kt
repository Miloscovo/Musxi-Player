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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.account.AboutUpdateUi
import tech.musxi.player.ui.components.GlassHost
import tech.musxi.player.ui.library.LibraryContent
import tech.musxi.player.ui.library.LibraryDensity
import tech.musxi.player.ui.library.TrackListContent
import tech.musxi.player.ui.me.MeActions
import tech.musxi.player.ui.me.MeScreen
import tech.musxi.player.ui.player.MiniPlayerUi
import tech.musxi.player.ui.search.SearchScreen
import tech.musxi.player.ui.shell.LocalScrollChrome
import tech.musxi.player.ui.shell.ScrollChrome
import tech.musxi.player.ui.shell.ShellDock
import tech.musxi.player.ui.shell.ShellTab
import tech.musxi.player.ui.shell.ShellTopBar
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.ThemeMode

/** The four pages the scroll linkage applies to (spec section 10). */
enum class ScrollChromePage(val tab: ShellTab) {
    LIBRARY(ShellTab.LIBRARY),
    SEARCH(ShellTab.SEARCH),
    ME(ShellTab.ME),
    PLAYLIST(ShellTab.LIBRARY),
}

/**
 * A page under the shell's scroll linkage, built the way the shell builds it: the page is the glass
 * host's content and reports its scrolling to a [ScrollChrome]; the top bar and the dock are the
 * overlay. A test drags the page to see the dock fold and the small title come in.
 */
@Composable
fun ScrollChromeSheet(
    accent: Accent,
    mode: ThemeMode,
    page: ScrollChromePage,
    mini: MiniPlayerUi = sampleMiniPlaying(),
) {
    MusxiTheme(accent = accent, mode = mode) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val chrome = remember { ScrollChrome() }
            chrome.density = LocalDensity.current.density
            val folded = chrome.collapsed && mini.visible
            var dockFull by remember { mutableIntStateOf(0) }
            val inset = with(LocalDensity.current) { dockFull.toDp() } + 16.dp
            GlassHost(
                overlay = {
                    ShellTopBar(chrome, Modifier.align(Alignment.TopCenter))
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .padding(horizontal = Space.page)
                            .onSizeChanged { if (!folded) dockFull = it.height },
                    ) {
                        ShellDock(
                            mini = mini, selected = page.tab, onSelect = {},
                            collapsed = folded, onExpandTabs = chrome::expand,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                },
            ) {
                CompositionLocalProvider(LocalScrollChrome provides chrome) {
                    Box(Modifier.fillMaxSize().nestedScroll(chrome.connection)) {
                        when (page) {
                            ScrollChromePage.LIBRARY -> LibraryContent(
                                state = libraryLoaded(), onSelectFilter = {}, home = libraryHomeSample(),
                                bottomInset = inset, initialDensity = LibraryDensity.LIST,
                            )
                            ScrollChromePage.SEARCH -> SearchScreen(
                                state = resultsState(), onKeywordsChange = {}, onPlatformSelect = {},
                                onSearch = {}, onLoadMore = {}, onRetry = {}, bottomInset = inset,
                            )
                            ScrollChromePage.ME -> SampleAvatars {
                                MeScreen(
                                    ui = meSampleUi(accent), actions = MeActions(), bottomInset = inset,
                                    appVersion = "0.2.0",
                                    update = AboutUpdateUi(
                                        developmentBuild = false, checking = false, checkNote = "已是最新",
                                        release = null, autoCheck = true, badge = false,
                                    ),
                                )
                            }
                            ScrollChromePage.PLAYLIST -> TrackListContent(
                                state = trackListSample(), dark = mode != ThemeMode.LIGHT,
                                onBack = {}, onPlayAll = {}, onSongClick = {}, onLoadMore = {}, onRetry = {},
                                nowPlayingId = trackListSample().items.firstOrNull()?.key?.stableId,
                                bottomInset = inset, onSearch = {},
                            )
                        }
                    }
                }
            }
        }
    }
}
