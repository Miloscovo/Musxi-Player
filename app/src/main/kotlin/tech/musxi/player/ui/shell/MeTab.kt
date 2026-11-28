package tech.musxi.player.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import tech.musxi.core.account.Platform
import tech.musxi.core.cache.CacheKind
import tech.musxi.core.cache.CacheOperationResult
import tech.musxi.core.playback.PreferredAudioQuality
import tech.musxi.core.settings.LockScreenLyricsMode
import tech.musxi.core.settings.LyricsFontSize
import tech.musxi.core.settings.ThemeMode
import tech.musxi.player.lockscreen.LockScreenLyricsState
import tech.musxi.player.ui.account.AboutUpdateUi
import tech.musxi.player.ui.account.AccountDetailPage
import tech.musxi.player.ui.account.AccountPageActions
import tech.musxi.player.ui.account.AccountRowUi
import tech.musxi.player.ui.account.UpdateActions
import tech.musxi.player.ui.components.MusxiConfirmDialog
import tech.musxi.player.ui.me.LockScreenLyricsSettingsActions
import tech.musxi.player.ui.me.LockScreenLyricsSettingsPage
import tech.musxi.player.ui.me.MeActions
import tech.musxi.player.ui.me.MeScreen
import tech.musxi.player.ui.me.MeUi
import tech.musxi.player.ui.me.cacheSizeText
import tech.musxi.player.ui.me.lockScreenLyricsSettingsUiOf
import tech.musxi.player.ui.nav.NavStackHost
import tech.musxi.player.ui.nav.NavStackState
import tech.musxi.player.ui.theme.Accent

/** Page keys of the "我的" tab's stack: root, per-platform account, and lock-screen lyrics settings. */
internal const val MeRootKey = "me"
internal const val MeLockScreenLyricsKey = "me/lockscreen-lyrics"
private const val MeAccountPrefix = "me/account/"

internal fun meAccountKey(platform: Platform): String = MeAccountPrefix + platform.name

internal fun meAccountPlatform(key: String): Platform? =
    if (key.startsWith(MeAccountPrefix)) {
        Platform.entries.firstOrNull { it.name == key.removePrefix(MeAccountPrefix) }
    } else null

/**
 * What the "我的" page changes. The shell turns each into a save on the IO thread; the page keeps
 * no copy of any value, so a save that fails leaves the switch where it was.
 */
class MeTabActions(
    val onSetAutoSwitchSource: (Boolean) -> Unit = {},
    val onSetRelaxDuration: (Boolean) -> Unit = {},
    val onSetRecommendations: (Boolean) -> Unit = {},
    val onSelectLibraryLock: (tech.musxi.player.ui.library.LibraryLock) -> Unit = {},
    val onSetFollowCover: (Boolean) -> Unit = {},
    val onSetShowTranslation: (Boolean) -> Unit = {},
    val onSelectLockScreenLyricsMode: (LockScreenLyricsMode) -> Unit = {},
    val onOpenOverlayPermission: () -> Unit = {},
    val onOpenAppPermissionSettings: () -> Unit = {},
    val onRefreshLockScreenPermissions: () -> Unit = {},
    val onSetTransparency: (Boolean) -> Unit = {},
    val onSetThemeMode: (ThemeMode) -> Unit = {},
    val onSetDefaultQuality: (PreferredAudioQuality) -> Unit = {},
    /** The lyrics page's text size only; the rest of the app follows the system font size. */
    val onSetLyricsFontSize: (LyricsFontSize) -> Unit = {},
    val onSetAccent: (Accent) -> Unit = {},
    val onSetLocalArtwork: (Boolean) -> Unit = {},
    val onFillLocalArtwork: (() -> Unit)? = null,
    /** Runs after the user has confirmed; asking first is [MeTab]'s job. */
    val onClearCache: () -> Unit = {},
)

/**
 * The "我的" tab: its page stack and everything a row opens. An account row pushes a page onto
 * [nav]; 默认音质, 字号, 深色模式 and 主题色 are picked in a menu on the page itself and go straight
 * to [actions]; the cache row asks first, because clearing it cannot be undone. State lives in the
 * caller (the stack, [ui], the account rows), so the host only decides which page is drawn.
 */
@Composable
fun MeTab(
    nav: NavStackState,
    ui: MeUi,
    accountRows: List<AccountRowUi>,
    dark: Boolean,
    actions: MeTabActions,
    accountActions: AccountPageActions,
    appVersion: String,
    update: AboutUpdateUi,
    updateActions: UpdateActions,
    bottomInset: Dp,
    /** False while the full player covers the tabs, so back belongs to the player. */
    backEnabled: Boolean = true,
    /** The root page's scroll, hoisted so tapping the tab again can return to the top. */
    scrollState: androidx.compose.foundation.ScrollState = androidx.compose.foundation.rememberScrollState(),
    /** Observable lock-screen lyrics state for the permission guide inside the sub-page. */
    lockScreenState: LockScreenLyricsState = LockScreenLyricsState(),
) {
    var askClear by remember { mutableStateOf(false) }
    val meActions = MeActions(
        onOpenAccount = { platform -> nav.push(meAccountKey(platform)) },
        onSetAutoSwitchSource = actions.onSetAutoSwitchSource,
        onSetRelaxDuration = actions.onSetRelaxDuration,
        onSetRecommendations = actions.onSetRecommendations,
        onSelectLibraryLock = actions.onSelectLibraryLock,
        onSelectQuality = actions.onSetDefaultQuality,
        onSetFollowCover = actions.onSetFollowCover,
        onSetShowTranslation = actions.onSetShowTranslation,
        onSelectFontSize = actions.onSetLyricsFontSize,
        onOpenLockScreenLyrics = { nav.push(MeLockScreenLyricsKey) },
        onSelectThemeMode = actions.onSetThemeMode,
        onSetTransparency = actions.onSetTransparency,
        onSelectAccent = actions.onSetAccent,
        onSetLocalArtwork = actions.onSetLocalArtwork,
        onFillLocalArtwork = actions.onFillLocalArtwork,
        onClearCache = { askClear = true },
    )
    val lockScreenActions = remember(actions) {
        LockScreenLyricsSettingsActions(
            onSelectMode = actions.onSelectLockScreenLyricsMode,
            onOpenOverlayPermission = actions.onOpenOverlayPermission,
            onOpenAppSettings = actions.onOpenAppPermissionSettings,
            onRefreshPermissions = actions.onRefreshLockScreenPermissions,
        )
    }
    NavStackHost(nav, backEnabled = backEnabled) { key ->
        val platform = meAccountPlatform(key)
        when {
            key == MeLockScreenLyricsKey -> LockScreenLyricsSettingsPage(
                ui = lockScreenLyricsSettingsUiOf(ui.lyrics.lockScreenLyricsMode, lockScreenState),
                actions = lockScreenActions,
                onBack = { nav.pop() },
                bottomInset = bottomInset,
            )
            platform != null -> AccountDetailPage(
                platform = platform,
                row = accountRows.firstOrNull { it.platform == platform },
                identity = ui.accounts.firstOrNull { it.platform == platform },
                dark = dark,
                actions = accountActions,
                onBack = { nav.pop() },
                bottomInset = bottomInset,
            )
            else -> MeScreen(
                ui = ui,
                actions = meActions,
                bottomInset = bottomInset,
                appVersion = appVersion,
                update = update,
                updateActions = updateActions,
                scrollState = scrollState,
            )
        }
    }
    // Destructive confirmation via the unified glass dialog (spec section 9).
    MusxiConfirmDialog(
        visible = askClear,
        title = "清除缓存？",
        body = "封面、歌词和曲库缓存会被清除，用到时再重新获取。",
        note = "账号登录状态与各项设置不受影响。",
        confirmLabel = "清除",
        danger = true,
        onDismiss = { askClear = false },
        onConfirm = {
            askClear = false
            actions.onClearCache()
        },
    )
}

/**
 * The sentence after a clear. A clear that fails for one kind still clears the others and measures
 * again, so a partial failure names what is left and how much it holds; "未知" when a total cannot be
 * read, never 0 (backend contract, cache maintenance). Only a fully successful clear says it worked.
 */
internal fun cacheClearNotice(result: CacheOperationResult): String {
    if (result.succeeded) return "缓存已清除"
    val failed = CacheKind.entries.filter { it in result.failed }.joinToString("、") { it.label() }
    // A number gets a space before it, like the other notices ("将在 15 分钟后停止播放").
    val remaining = result.usage.totalBytes?.let { " ${cacheSizeText(it)}" } ?: "未知"
    return "${failed}缓存没能清除，剩余占用$remaining"
}

private fun CacheKind.label(): String = when (this) {
    CacheKind.COVERS -> "封面"
    CacheKind.LYRICS -> "歌词"
    CacheKind.LIBRARY_PAGES -> "曲库"
}
