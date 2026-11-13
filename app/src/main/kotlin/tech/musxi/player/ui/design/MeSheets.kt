package tech.musxi.player.ui.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tech.musxi.core.account.AccountError
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.AccountProfile
import tech.musxi.core.account.AccountState
import tech.musxi.core.account.Membership
import tech.musxi.core.account.MembershipLevel
import tech.musxi.core.account.Platform
import tech.musxi.core.cache.CacheKind
import tech.musxi.core.cache.CacheMaintenanceState
import tech.musxi.core.cache.CacheOperationResult
import tech.musxi.core.cache.CacheUsage
import tech.musxi.core.settings.AppSettings
import tech.musxi.core.settings.LyricsTranslationPreference
import tech.musxi.core.settings.ThemeMode as StoredThemeMode
import tech.musxi.player.ui.account.AboutUpdateUi
import tech.musxi.player.ui.me.MeAccountsGroup
import tech.musxi.player.ui.me.MeActions
import tech.musxi.player.ui.me.MeScreen
import tech.musxi.player.ui.me.MeUi
import tech.musxi.player.ui.me.meAccountRowOf
import tech.musxi.player.ui.me.meUiOf
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.ThemeMode

/**
 * The whole "我的" page on fake data. The rows go through the same `meUiOf` the live page will use,
 * and [MeScreen] is the real composable, so the sheet cannot show something the page does not do.
 */
@Composable
fun MeSheet(accent: Accent, mode: ThemeMode, ui: MeUi = meSampleUi(accent)) {
    MusxiTheme(accent = accent, mode = mode) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SampleAvatars {
                MeScreen(
                    ui = ui,
                    actions = MeActions(),
                    appVersion = "0.2.0-alpha01",
                    update = AboutUpdateUi(
                        developmentBuild = false, checking = false, checkNote = "已是最新", release = null,
                        autoCheck = true, badge = false,
                    ),
                )
            }
        }
    }
}

/**
 * The 账号 card with three platforms in three different states: member, network error, expired. With
 * [common] it shows what most users have instead: a member, a non-member and a platform not yet connected.
 */
@Composable
fun MeAccountsSheet(accent: Accent, mode: ThemeMode, common: Boolean = false) {
    MusxiTheme(accent = accent, mode = mode) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(horizontal = Space.page, vertical = Space.section)) {
                val states = if (common) meCommonAccountStates() else meMixedAccountStates()
                SampleAvatars { MeAccountsGroup(states.map(::meAccountRowOf)) {} }
            }
        }
    }
}

// --- samples ---------------------------------------------------------------------------------

private fun profile(platform: Platform, name: String) =
    AccountProfile(platform, "sample-$name", name, avatarUrl = sampleAvatarUrl(name))

/** What a user usually has: one platform signed in as a member, one as a non-member, one not yet. */
fun meCommonAccountStates(): List<AccountState> = listOf(
    AccountState(
        Platform.NETEASE, AccountPhase.CONNECTED, profile(Platform.NETEASE, "云村用户 8842"),
        membership = Membership(MembershipLevel.YES),
    ),
    AccountState(
        Platform.QQ, AccountPhase.CONNECTED, profile(Platform.QQ, "阿青"),
        membership = Membership(MembershipLevel.NO),
    ),
    AccountState(Platform.KUGOU, AccountPhase.DISCONNECTED),
)

/** Three states that look different from each other: connected member, network error, expired. */
fun meMixedAccountStates(): List<AccountState> = listOf(
    AccountState(
        Platform.NETEASE, AccountPhase.CONNECTED, profile(Platform.NETEASE, "云村用户 8842"),
        membership = Membership(MembershipLevel.YES),
    ),
    AccountState(
        Platform.QQ, AccountPhase.RETRYABLE_ERROR, profile(Platform.QQ, "阿青"),
        error = AccountError.NETWORK, membership = Membership(MembershipLevel.NO),
    ),
    AccountState(Platform.KUGOU, AccountPhase.REAUTH_REQUIRED, error = AccountError.EXPIRED),
)

private const val MIB = 1024L * 1024L

/** 128 MB in total, so the storage row reads like the brief's example. */
fun meSampleCache(): CacheMaintenanceState = CacheMaintenanceState(
    result = CacheOperationResult(
        CacheUsage(
            mapOf(
                CacheKind.COVERS to 96 * MIB,
                CacheKind.LYRICS to 2 * MIB,
                CacheKind.LIBRARY_PAGES to 30 * MIB,
            ),
        ),
        failed = emptySet(),
    ),
)

/** Switches in both positions: translation and relaxed duration are off, the rest on. */
fun meSampleUi(accent: Accent = Accent.MIST): MeUi = meUiOf(
    accounts = meCommonAccountStates(),
    settings = AppSettings(
        lyricsTranslationPreference = LyricsTranslationPreference.HIDE,
        themeMode = StoredThemeMode.SYSTEM,
    ),
    accent = accent,
    cache = meSampleCache(),
)
