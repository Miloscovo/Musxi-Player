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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.musxi.core.account.AccountState
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.account.AboutUpdateUi
import tech.musxi.player.ui.account.AccountPageActions
import tech.musxi.player.ui.account.UpdateActions
import tech.musxi.player.ui.account.toRowUi
import tech.musxi.player.ui.components.GlassHost
import tech.musxi.player.ui.library.LibraryContent
import tech.musxi.player.ui.me.meAccountRowOf
import tech.musxi.player.ui.nav.NavStackState
import tech.musxi.player.ui.nav.PageStack
import tech.musxi.player.ui.shell.MeRootKey
import tech.musxi.player.ui.shell.MeTab
import tech.musxi.player.ui.shell.MeTabActions
import tech.musxi.player.ui.shell.meAccountKey
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
            if (dock) {
                // The shell's own structure: the page is the glass host's content, the dock floats over it.
                PinnedDock(mini, tab) { inset ->
                    LibraryContent(state = sampleLibraryLoaded(), onSelectFilter = {}, bottomInset = inset)
                }
            } else Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    LibraryContent(state = sampleLibraryLoaded(), onSelectFilter = {})
                }
                Column(
                    Modifier.padding(horizontal = Space.page),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MiniPlayer(
                        ui = mini, onExpand = {}, onPlayPause = {}, onPrevious = {}, onNext = {},
                        style = progressStyle,
                        shape = if (extraRound) MaterialTheme.shapes.extraLarge else MaterialTheme.shapes.large,
                        tonalButton = tonalButton,
                    )
                    if (mini.visible) Spacer(Modifier.height(8.dp))
                    ShellBottomBar(selected = tab, onSelect = {}, shape = barShape)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/**
 * The shell's glass structure on sample pages: [page] is the host's content and gets the measured
 * height of the dock as its bottom inset, the dock is the overlay, pinned to the bottom with the
 * shell's margins. Under Robolectric the glass draws its solid fallback, which is expected there.
 */
@Composable
private fun PinnedDock(mini: MiniPlayerUi, tab: ShellTab, page: @Composable (Dp) -> Unit) {
    var dockHeight by remember { mutableIntStateOf(0) }
    val inset = with(LocalDensity.current) { dockHeight.toDp() } + 16.dp
    GlassHost(
        overlay = {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(horizontal = Space.page)
                    .onSizeChanged { dockHeight = it.height },
            ) {
                ShellDock(mini = mini, selected = tab, onSelect = {})
                Spacer(Modifier.height(8.dp))
            }
        },
    ) { page(inset) }
}

/**
 * Where the "我的" tab sheet rests: its page, or a platform's page already pushed onto it. The
 * pickers (深色模式 and the rest) are menus on the page itself, so a test opens them by tapping.
 */
enum class ShellMePage { ROOT, ACCOUNT }

/**
 * The "我的" tab under the real dock: the same [MeTab] the shell draws, on fake data, with the stack
 * set to [page] (a pushed page is shown at rest, as after the push has finished). [platform] and
 * [accounts] decide which account page is shown and in which state.
 */
@Composable
fun ShellMeSheet(
    accent: Accent,
    mode: ThemeMode,
    mini: MiniPlayerUi,
    page: ShellMePage = ShellMePage.ROOT,
    platform: Platform = Platform.NETEASE,
    accounts: List<AccountState> = meCommonAccountStates(),
) {
    MusxiTheme(accent = accent, mode = mode) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val scope = rememberCoroutineScope()
            val holder = rememberSaveableStateHolder()
            val nav = remember(page, platform) {
                val root = PageStack.of(MeRootKey, maxDepth = 2)
                NavStackState(
                    scope, holder,
                    when (page) {
                        ShellMePage.ROOT -> root
                        ShellMePage.ACCOUNT -> root.push(meAccountKey(platform))
                    },
                )
            }
            PinnedDock(mini, ShellTab.ME) { inset ->
                SampleAvatars { MeTab(
                    nav = nav,
                    ui = meSampleUi(accent).copy(accounts = accounts.map(::meAccountRowOf)),
                    accountRows = accounts.map { it.toRowUi() },
                    dark = mode != ThemeMode.LIGHT,
                    actions = MeTabActions(),
                    accountActions = AccountPageActions(onConnectNeteaseScanner = {}),
                    appVersion = "0.2.0-alpha01",
                    update = AboutUpdateUi(
                        developmentBuild = false, checking = false, checkNote = "已是最新", release = null,
                        autoCheck = true, badge = false,
                    ),
                    updateActions = UpdateActions(),
                    bottomInset = inset,
                ) }
            }
        }
    }
}
