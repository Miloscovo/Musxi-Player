package tech.musxi.player.ui.me

import java.util.Locale
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.AccountState
import tech.musxi.core.account.MembershipLevel
import tech.musxi.core.cache.CacheMaintenanceState
import tech.musxi.core.playback.PreferredAudioQuality
import tech.musxi.core.settings.AppSettings
import tech.musxi.core.settings.LyricsColorMode
import tech.musxi.core.settings.LyricsFontSize
import tech.musxi.core.settings.LyricsTranslationPreference
import tech.musxi.core.settings.ThemeMode
import tech.musxi.player.ui.account.toRowUi
import tech.musxi.player.ui.theme.Accent

/** Phases in which the account is signed in, so the membership last reported is still true. */
private val SignedInPhases = setOf(
    AccountPhase.CONNECTED, AccountPhase.REVALIDATING, AccountPhase.RETRYABLE_ERROR,
)

/**
 * One account line. The nickname and the sentences about a state come from the account page's own
 * mapper (`toRowUi`), so the two pages cannot describe the same state differently; this only decides
 * what goes where.
 *
 * Normal rows say nothing at the right edge. It speaks only when the user can act on it: a platform
 * not connected yet ("连接"), a network error, an expired login. A signed-in row keeps the membership
 * word on its second line; a state with a sentence of its own ("登录已过期") shows that instead.
 */
fun meAccountRowOf(state: AccountState): MeAccountRow {
    val row = state.toRowUi()
    val phase = state.phase
    val name = row.name
    var hint = when (phase) {
        AccountPhase.DISCONNECTED -> null
        AccountPhase.REAUTH_REQUIRED -> "登录已过期"
        else -> row.detail
    }
    // A signed-in row whose nickname is missing would otherwise have an empty second line.
    if (phase != AccountPhase.DISCONNECTED && name == null && hint == null) hint = row.pill
    val status = when (phase) {
        AccountPhase.DISCONNECTED -> "连接"
        AccountPhase.RETRYABLE_ERROR -> row.pill
        AccountPhase.REAUTH_REQUIRED -> "需要重新登录"
        else -> null
    }
    return MeAccountRow(
        platform = state.platform,
        name = name,
        hint = hint,
        badge = membershipBadgeOf(state),
        status = status,
        attention = phase == AccountPhase.RETRYABLE_ERROR || phase == AccountPhase.REAUTH_REQUIRED,
        // The picture belongs to whoever the row names; an expired login names nobody.
        avatarUrl = state.profile?.avatarUrl?.takeIf { name != null },
    )
}

/** A mark only when we know who is signed in and what the platform last said about membership. */
fun membershipBadgeOf(state: AccountState): MembershipBadge? {
    if (state.profile == null || state.phase !in SignedInPhases) return null
    return when (state.membership.level) {
        MembershipLevel.YES -> MembershipBadge.MEMBER
        MembershipLevel.NO -> MembershipBadge.NON_MEMBER
        MembershipLevel.UNKNOWN -> null
    }
}

/** Short names for the right side of a row; quality follows the wording of the player's menu. */
fun qualityLabel(quality: PreferredAudioQuality): String = when (quality) {
    PreferredAudioQuality.STANDARD -> "标准"
    PreferredAudioQuality.HIGH -> "高品"
    PreferredAudioQuality.LOSSLESS -> "无损"
    PreferredAudioQuality.BEST -> "最高"
}

fun fontSizeLabel(size: LyricsFontSize): String = when (size) {
    LyricsFontSize.SMALL -> "小"
    LyricsFontSize.MEDIUM -> "中"
    LyricsFontSize.LARGE -> "大"
}

fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}

/**
 * The stored preference has three values, the switch two. Automatic counts as on: it shows the
 * translation whenever the lyrics language calls for one. Turning the switch on again returns to
 * automatic, never to a forced "always show".
 */
fun showTranslationOf(preference: LyricsTranslationPreference): Boolean =
    preference != LyricsTranslationPreference.HIDE

fun translationPreferenceOf(show: Boolean): LyricsTranslationPreference =
    if (show) LyricsTranslationPreference.AUTO else LyricsTranslationPreference.HIDE

fun lyricsColorModeOf(followCover: Boolean): LyricsColorMode =
    if (followCover) LyricsColorMode.COVER else LyricsColorMode.FIXED

private const val KB = 1024L
private const val MB = KB * 1024L
private const val GB = MB * 1024L

/**
 * "128 MB": whole megabytes from 10 MB, one decimal below that, kilobytes under a megabyte. Rounded
 * up under a megabyte so a few bytes never read as "0 KB".
 */
fun cacheSizeText(bytes: Long): String {
    val size = bytes.coerceAtLeast(0L)
    val kilobytes = (size + KB - 1) / KB
    return when {
        size == 0L -> "0 KB"
        kilobytes < 1024L -> "$kilobytes KB"
        size < 10L * MB -> String.format(Locale.US, "%.1f MB", size / MB.toDouble())
        size < GB -> "${size / MB} MB"
        else -> String.format(Locale.US, "%.1f GB", size / GB.toDouble())
    }
}

/**
 * The "缓存" row. A job in flight disables the row; a total the backend could not read is "未知",
 * never 0, and can still be cleared; a measured 0 has nothing to clear.
 */
fun meStorageUiOf(state: CacheMaintenanceState): MeStorageUi {
    if (state.busy) {
        return MeStorageUi(if (state.clearing) "正在清理…" else "计算中…", clearable = false)
    }
    val total = state.result?.usage?.totalBytes
    return when (total) {
        null -> MeStorageUi("未知", clearable = true)
        0L -> MeStorageUi(cacheSizeText(0L), clearable = false)
        else -> MeStorageUi(cacheSizeText(total), clearable = true)
    }
}

/** The whole page from backend state. [accounts] keeps the order the caller gives. */
fun meUiOf(
    accounts: List<AccountState>,
    settings: AppSettings,
    accent: Accent,
    cache: CacheMaintenanceState,
): MeUi = MeUi(
    accounts = accounts.map(::meAccountRowOf),
    playback = MePlaybackUi(
        autoSwitchSource = settings.autoSwitchSource,
        relaxDuration = settings.relaxSourceDuration,
        quality = settings.defaultQuality,
    ),
    lyrics = MeLyricsUi(
        followCover = settings.lyricsColorMode == LyricsColorMode.COVER,
        showTranslation = showTranslationOf(settings.lyricsTranslationPreference),
        fontSize = settings.lyricsFontSize,
    ),
    appearance = MeAppearanceUi(
        themeMode = settings.themeMode,
        transparency = settings.transparencyEnabled,
        accent = accent,
    ),
    storage = meStorageUiOf(cache),
)
