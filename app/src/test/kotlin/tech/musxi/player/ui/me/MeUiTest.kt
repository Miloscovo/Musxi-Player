package tech.musxi.player.ui.me

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
import tech.musxi.core.playback.PreferredAudioQuality
import tech.musxi.core.settings.AppSettings
import tech.musxi.core.settings.LyricsColorMode
import tech.musxi.core.settings.LyricsFontSize
import tech.musxi.core.settings.LyricsTranslationPreference
import tech.musxi.core.settings.ThemeMode
import tech.musxi.player.ui.theme.Accent

/**
 * The pure side of the "我的" page: account lines, labels, the cache row and the switch mappings.
 * The phase set is walked exhaustively on purpose, like the account page's own test: a new phase
 * must show up here instead of silently rendering as an empty line.
 */
class MeUiTest {

    private fun state(
        phase: AccountPhase,
        name: String? = null,
        error: AccountError? = null,
        level: MembershipLevel = MembershipLevel.UNKNOWN,
        platform: Platform = Platform.KUGOU,
    ) = AccountState(
        platform = platform,
        phase = phase,
        profile = name?.let { AccountProfile(platform, "id-$it", it) },
        error = error,
        membership = Membership(level),
    )

    // --- account lines -------------------------------------------------------------------------

    @Test fun disconnectedIsOneLineWithAConnectAction() {
        val row = meAccountRowOf(state(AccountPhase.DISCONNECTED))
        assertNull(row.name)
        assertEquals("酷狗", row.title)
        assertNull("the platform is the title; nothing to add under it", row.subtitle)
        assertEquals("连接", row.status)
        assertNull(row.badge)
        assertFalse("not connected yet is not a fault", row.attention)
    }

    @Test fun waitingNamesTheAppAndStaysQuietAtTheRightEdge() {
        val row = meAccountRowOf(state(AccountPhase.WAITING_CONFIRMATION, platform = Platform.KUGOU))
        assertEquals("在酷狗里确认后返回", row.hint)
        assertEquals("在酷狗里确认后返回", row.subtitle)
        assertNull("the user acts in the other app, not here", row.status)
        assertFalse(row.attention)
        assertNull(meAccountRowOf(state(AccountPhase.PREPARING)).status)
    }

    @Test fun verifyingWithoutNameExplainsItself() {
        val row = meAccountRowOf(state(AccountPhase.VERIFYING))
        assertNull(row.name)
        assertEquals("正在确认登录状态", row.hint)
        assertNull("a short wait has nothing to act on", row.status)
    }

    @Test fun verifyingWithNameShowsThePlatformOnly() {
        val row = meAccountRowOf(state(AccountPhase.VERIFYING, name = "阿青", level = MembershipLevel.YES))
        assertEquals("阿青", row.title)
        assertNull(row.hint)
        assertNull(row.status)
        assertNull("membership is not settled right after a login", row.badge)
        assertEquals("酷狗", row.subtitle)
    }

    @Test fun connectedShowsNicknameOverPlatformAndMembership() {
        val row = meAccountRowOf(state(AccountPhase.CONNECTED, name = "阿青", level = MembershipLevel.YES))
        assertEquals("阿青", row.name)
        assertEquals("阿青", row.title)
        assertNull(row.hint)
        assertNull("a healthy row says nothing at the right edge", row.status)
        assertEquals(MembershipBadge.MEMBER, row.badge)
        assertEquals("酷狗 · 会员", row.subtitle)
        assertFalse(row.attention)
    }

    @Test fun membershipWordFollowsThePlatform() {
        val member = meAccountRowOf(state(AccountPhase.CONNECTED, "云村用户 8842", platform = Platform.NETEASE, level = MembershipLevel.YES))
        assertEquals("云村用户 8842", member.title)
        assertEquals("网易云 · 会员", member.subtitle)
        val non = meAccountRowOf(state(AccountPhase.CONNECTED, "阿青", platform = Platform.QQ, level = MembershipLevel.NO))
        assertEquals("QQ音乐 · 非会员", non.subtitle)
        val unknown = meAccountRowOf(state(AccountPhase.CONNECTED, "阿青", platform = Platform.QQ))
        assertEquals("unknown membership writes the platform only", "QQ音乐", unknown.subtitle)
    }

    @Test fun connectedWithoutNicknameStillHasASecondLine() {
        val row = meAccountRowOf(state(AccountPhase.CONNECTED))
        assertNull(row.name)
        assertEquals("酷狗", row.title)
        assertEquals("已连接", row.hint)
        assertEquals("已连接", row.subtitle)
        assertNull("not said twice", row.status)
    }

    @Test fun revalidatingKeepsTheKnownMembershipAndStaysQuiet() {
        val row = meAccountRowOf(state(AccountPhase.REVALIDATING, name = "阿青", level = MembershipLevel.NO))
        assertNull(row.status)
        assertEquals(MembershipBadge.NON_MEMBER, row.badge)
        assertEquals("酷狗 · 非会员", row.subtitle)
    }

    @Test fun networkErrorNeedsAttentionAndKeepsTheName() {
        val row = meAccountRowOf(
            state(AccountPhase.RETRYABLE_ERROR, name = "阿青", error = AccountError.TIMEOUT, level = MembershipLevel.NO),
        )
        assertEquals("阿青", row.name)
        assertEquals("确认超时，可重试", row.hint)
        assertEquals("网络异常", row.status)
        assertTrue(row.attention)
        assertEquals(MembershipBadge.NON_MEMBER, row.badge)
        assertEquals("a sentence about the state outranks the membership word", "酷狗 · 确认超时，可重试", row.subtitle)
    }

    @Test fun expiredSessionNeedsAttentionAndShowsNoMembership() {
        val row = meAccountRowOf(
            state(AccountPhase.REAUTH_REQUIRED, name = "阿青", error = AccountError.EXPIRED, level = MembershipLevel.YES),
        )
        assertNull(row.name)
        assertEquals("酷狗", row.title)
        assertEquals("登录已过期", row.hint)
        assertEquals("登录已过期", row.subtitle)
        assertEquals("需要重新登录", row.status)
        assertTrue(row.attention)
        assertNull("a signed-out account has no membership to show", row.badge)
    }

    @Test fun theThreeRowsOfTheBrief() {
        val normal = meAccountRowOf(state(AccountPhase.CONNECTED, "云村用户 8842", platform = Platform.NETEASE, level = MembershipLevel.YES))
        assertEquals(Triple("云村用户 8842", "网易云 · 会员", null), Triple(normal.title, normal.subtitle, normal.status))
        val broken = meAccountRowOf(state(AccountPhase.REAUTH_REQUIRED, error = AccountError.EXPIRED))
        assertEquals(Triple("酷狗", "登录已过期", "需要重新登录"), Triple(broken.title, broken.subtitle, broken.status))
        val fresh = meAccountRowOf(state(AccountPhase.DISCONNECTED))
        assertEquals(Triple("酷狗", null, "连接"), Triple(fresh.title, fresh.subtitle, fresh.status))
    }

    @Test fun unknownMembershipShowsNoMark() {
        assertNull(membershipBadgeOf(state(AccountPhase.CONNECTED, name = "阿青", level = MembershipLevel.UNKNOWN)))
    }

    @Test fun membershipNeedsAKnownAccount() {
        assertNull(membershipBadgeOf(state(AccountPhase.CONNECTED, level = MembershipLevel.YES)))
    }

    @Test fun everyPhaseHasATitleAndNeverSaysTheSameThingTwice() {
        // The right edge speaks only where the user can act; every other phase leaves it empty.
        val speaking = setOf(AccountPhase.DISCONNECTED, AccountPhase.RETRYABLE_ERROR, AccountPhase.REAUTH_REQUIRED)
        for (phase in AccountPhase.entries) {
            for (name in listOf(null, "阿青")) {
                val row = meAccountRowOf(state(phase, name = name))
                assertTrue("$phase/$name has no title", row.title.isNotBlank())
                if (phase != AccountPhase.DISCONNECTED) {
                    assertNotNull("$phase/$name has nothing on the second line", row.subtitle)
                }
                assertNotEquals("$phase/$name repeats itself", row.subtitle, row.status)
                assertEquals("$phase/$name", phase in speaking, row.status != null)
            }
        }
    }

    // --- labels --------------------------------------------------------------------------------

    @Test fun labelsCoverEveryValueInOrder() {
        assertEquals(listOf("标准", "高品", "无损", "最高"), PreferredAudioQuality.entries.map(::qualityLabel))
        assertEquals(listOf("小", "中", "大"), LyricsFontSize.entries.map(::fontSizeLabel))
        assertEquals(listOf("跟随系统", "浅色", "深色"), ThemeMode.entries.map(::themeModeLabel))
    }

    // --- cache ---------------------------------------------------------------------------------

    @Test fun cacheSizeUsesTheRightUnit() {
        val kb = 1024L
        val mb = kb * 1024L
        assertEquals("0 KB", cacheSizeText(0))
        assertEquals("0 KB", cacheSizeText(-5))
        assertEquals("1 KB", cacheSizeText(1))
        assertEquals("1 KB", cacheSizeText(kb))
        assertEquals("2 KB", cacheSizeText(kb + 1))
        assertEquals("1023 KB", cacheSizeText(1023 * kb))
        assertEquals("1.0 MB", cacheSizeText(mb))
        assertEquals("1.5 MB", cacheSizeText(mb + mb / 2))
        assertEquals("10 MB", cacheSizeText(10 * mb))
        assertEquals("128 MB", cacheSizeText(128 * mb))
        assertEquals("1023 MB", cacheSizeText(1023 * mb))
        assertEquals("1.0 GB", cacheSizeText(1024 * mb))
        assertEquals("3.5 GB", cacheSizeText(3584 * mb))
    }

    @Test fun justUnderAMegabyteNeverPrintsAThousandAndTwentyFourKilobytes() {
        assertEquals("1.0 MB", cacheSizeText(1024L * 1024L - 1))
    }

    private fun usage(bytes: Long) = CacheUsage(CacheKind.entries.associateWith { bytes / CacheKind.entries.size })

    @Test fun measuredCacheShowsItsSizeAndCanBeCleared() {
        val total = 3L * 42L * 1024L * 1024L
        val ui = meStorageUiOf(CacheMaintenanceState(result = CacheOperationResult(usage(total), emptySet())))
        assertEquals("126 MB", ui.cacheText)
        assertTrue(ui.clearable)
    }

    @Test fun emptyCacheHasNothingToClear() {
        val ui = meStorageUiOf(CacheMaintenanceState(result = CacheOperationResult(usage(0), emptySet())))
        assertEquals("0 KB", ui.cacheText)
        assertFalse(ui.clearable)
    }

    @Test fun unreadableCacheIsUnknownNotZero() {
        val partial = CacheUsage(mapOf(CacheKind.COVERS to 5L, CacheKind.LYRICS to 5L))
        val ui = meStorageUiOf(CacheMaintenanceState(result = CacheOperationResult(partial, setOf(CacheKind.LIBRARY_PAGES))))
        assertEquals("未知", ui.cacheText)
        assertTrue("clearing does not need the size", ui.clearable)
        assertEquals(MeStorageUi("未知", clearable = true), meStorageUiOf(CacheMaintenanceState()))
    }

    @Test fun aRunningJobDisablesTheRow() {
        assertEquals(
            MeStorageUi("正在清理…", clearable = false),
            meStorageUiOf(CacheMaintenanceState(busy = true, clearing = true)),
        )
        assertEquals(
            MeStorageUi("计算中…", clearable = false),
            meStorageUiOf(CacheMaintenanceState(busy = true, clearing = false)),
        )
    }

    // --- switches ------------------------------------------------------------------------------

    @Test fun automaticTranslationCountsAsOn() {
        assertTrue(showTranslationOf(LyricsTranslationPreference.AUTO))
        assertTrue(showTranslationOf(LyricsTranslationPreference.SHOW))
        assertFalse(showTranslationOf(LyricsTranslationPreference.HIDE))
    }

    @Test fun turningTranslationOnReturnsToAutomatic() {
        assertEquals(LyricsTranslationPreference.AUTO, translationPreferenceOf(true))
        assertEquals(LyricsTranslationPreference.HIDE, translationPreferenceOf(false))
    }

    @Test fun lyricsColourModeFollowsTheSwitch() {
        assertEquals(LyricsColorMode.COVER, lyricsColorModeOf(true))
        assertEquals(LyricsColorMode.FIXED, lyricsColorModeOf(false))
    }

    // --- the whole page ------------------------------------------------------------------------

    @Test fun defaultSettingsGiveTheDefaultPage() {
        val ui = meUiOf(emptyList(), AppSettings(), Accent.MIST, CacheMaintenanceState())
        assertEquals(MePlaybackUi(autoSwitchSource = true, relaxDuration = false, quality = PreferredAudioQuality.STANDARD), ui.playback)
        assertEquals(MeLyricsUi(followCover = true, showTranslation = true, fontSize = LyricsFontSize.MEDIUM), ui.lyrics)
        assertEquals(MeAppearanceUi(ThemeMode.SYSTEM, transparency = true, accent = Accent.MIST), ui.appearance)
    }

    @Test fun everySettingReachesItsRow() {
        val settings = AppSettings(
            themeMode = ThemeMode.DARK,
            autoSwitchSource = false,
            relaxSourceDuration = true,
            transparencyEnabled = false,
            defaultQuality = PreferredAudioQuality.LOSSLESS,
            lyricsFontSize = LyricsFontSize.LARGE,
            lyricsColorMode = LyricsColorMode.FIXED,
            lyricsTranslationPreference = LyricsTranslationPreference.HIDE,
        )
        val ui = meUiOf(emptyList(), settings, Accent.AMBER, CacheMaintenanceState())
        assertEquals(MePlaybackUi(autoSwitchSource = false, relaxDuration = true, quality = PreferredAudioQuality.LOSSLESS), ui.playback)
        assertEquals(MeLyricsUi(followCover = false, showTranslation = false, fontSize = LyricsFontSize.LARGE), ui.lyrics)
        assertEquals(MeAppearanceUi(ThemeMode.DARK, transparency = false, accent = Accent.AMBER), ui.appearance)
    }

    @Test fun accountsKeepTheOrderTheyAreGiven() {
        val order = listOf(Platform.NETEASE, Platform.QQ, Platform.KUGOU)
        val ui = meUiOf(
            order.map { state(AccountPhase.DISCONNECTED, platform = it) },
            AppSettings(), Accent.MIST, CacheMaintenanceState(),
        )
        assertEquals(order, ui.accounts.map { it.platform })
    }
}
