package tech.musxi.player.ui.me

import tech.musxi.core.account.Platform
import tech.musxi.core.playback.PreferredAudioQuality
import tech.musxi.core.settings.LyricsFontSize
import tech.musxi.core.settings.ThemeMode
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.displayName

/** Membership word after the platform name. Unknown membership says nothing, so it has no value here. */
enum class MembershipBadge(val label: String) { MEMBER("会员"), NON_MEMBER("非会员") }

/**
 * One platform line of the "账号" group, derived by [meAccountRowOf]. Plain data with no Compose
 * types, so every account phase can be tested without a screen.
 *
 * The row reads in two lines. [title] is the nickname, or the platform when nobody is known;
 * [subtitle] is one grey line: the platform and then membership or a sentence about the state. The
 * right edge speaks only when the user has to do something ([status]).
 */
data class MeAccountRow(
    val platform: Platform,
    /** Verified nickname. Null while the platform has not told us who is signed in. */
    val name: String?,
    /** Sentence about a state that is not the usual one ("登录已过期"). Null when the state needs none. */
    val hint: String?,
    /** Membership word for the second line. Null when unknown or when [hint] takes its place. */
    val badge: MembershipBadge?,
    /** What the right edge says, only when the user can act on it ("连接", "需要重新登录"). Null otherwise. */
    val status: String?,
    /** True when a signed-in account needs the user (expired session, network error). */
    val attention: Boolean,
    /** The account's picture; only set while someone is signed in, so [name] is set too. */
    val avatarUrl: String? = null,
) {
    /** First line, and the letter drawn while there is no picture. */
    val title: String get() = name ?: platform.displayName

    /** Someone is signed in: their picture (or their first letter), else the default avatar. */
    val signedIn: Boolean get() = name != null

    /**
     * Second line. The platform is named here only when the first line is a nickname; otherwise the
     * first line already is the platform. A sentence about the state outranks the membership word.
     */
    val subtitle: String?
        get() = listOfNotNull(
            platform.displayName.takeIf { name != null },
            hint ?: badge?.label,
        ).joinToString(" · ").ifEmpty { null }
}

data class MePlaybackUi(
    val autoSwitchSource: Boolean = true,
    val relaxDuration: Boolean = false,
    val quality: PreferredAudioQuality = PreferredAudioQuality.STANDARD,
    /** 每日推荐 / 猜你喜欢 on the library home; on by default. */
    val recommendations: Boolean = true,
)

data class MeLyricsUi(
    val followCover: Boolean = true,
    val showTranslation: Boolean = true,
    val fontSize: LyricsFontSize = LyricsFontSize.MEDIUM,
    /** The system media card (lock screen, notification shade) shows the current lyric line as its title. */
    val mediaCardLyrics: Boolean = false,
)

data class MeAppearanceUi(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val transparency: Boolean = true,
    val accent: Accent = Accent.MIST,
    /** 本地封面: read covers from local files (on), or show only covers the user picked (off). */
    val localArtwork: Boolean = true,
    /** "补取中 12/40" while covers are being filled in for old recordings; null when idle. */
    val localArtworkFill: String? = null,
)

/** [cacheText] is ready to print ("128 MB", "计算中…"); [clearable] is false while there is nothing to clear or a job runs. */
data class MeStorageUi(
    val cacheText: String = "未知",
    val clearable: Boolean = true,
)

/** Everything the "我的" page shows. Built from backend state by [meUiOf]; never written back. */
data class MeUi(
    val accounts: List<MeAccountRow> = emptyList(),
    val playback: MePlaybackUi = MePlaybackUi(),
    val lyrics: MeLyricsUi = MeLyricsUi(),
    val appearance: MeAppearanceUi = MeAppearanceUi(),
    val storage: MeStorageUi = MeStorageUi(),
    /** 锁定平台: the library's one platform for single-source users, or NONE. */
    val libraryLock: tech.musxi.player.ui.library.LibraryLock = tech.musxi.player.ui.library.LibraryLock.NONE,
)

/**
 * Command callbacks of the page. The page keeps no copy of any setting: a switch or a picker shows
 * [MeUi] and reports the value the user asked for, so a failed save simply leaves the row where it
 * was. The `onSelect…` callbacks fire only for a value other than the current one. [onOpenAccount]
 * pushes the platform's page; asking before [onClearCache] runs is the caller's job.
 */
class MeActions(
    val onOpenAccount: (Platform) -> Unit = {},
    val onSetAutoSwitchSource: (Boolean) -> Unit = {},
    val onSetRelaxDuration: (Boolean) -> Unit = {},
    val onSetRecommendations: (Boolean) -> Unit = {},
    val onSelectLibraryLock: (tech.musxi.player.ui.library.LibraryLock) -> Unit = {},
    val onSelectQuality: (PreferredAudioQuality) -> Unit = {},
    val onSetFollowCover: (Boolean) -> Unit = {},
    val onSetShowTranslation: (Boolean) -> Unit = {},
    val onSelectFontSize: (LyricsFontSize) -> Unit = {},
    val onSetMediaCardLyrics: (Boolean) -> Unit = {},
    val onSelectThemeMode: (ThemeMode) -> Unit = {},
    val onSetTransparency: (Boolean) -> Unit = {},
    val onSelectAccent: (Accent) -> Unit = {},
    val onSetLocalArtwork: (Boolean) -> Unit = {},
    /** Fills in covers for recordings imported without one; null hides the row. */
    val onFillLocalArtwork: (() -> Unit)? = null,
    val onClearCache: () -> Unit = {},
)
