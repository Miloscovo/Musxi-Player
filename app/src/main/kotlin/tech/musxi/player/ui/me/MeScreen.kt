package tech.musxi.player.ui.me

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.musxi.core.playback.PreferredAudioQuality
import tech.musxi.core.settings.LyricsFontSize
import tech.musxi.core.settings.ThemeMode
import tech.musxi.player.ui.account.AboutSection
import tech.musxi.player.ui.account.AboutUpdateUi
import tech.musxi.player.ui.account.UpdateActions
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.shell.PageTopBar
import tech.musxi.player.ui.shell.largeTitle
import tech.musxi.player.ui.shell.rememberTitleProbe
import tech.musxi.player.ui.theme.Space

/**
 * The "我的" page as a grouped list: 账号, 播放, 歌词, 外观, 存储, 关于. It owns no setting: every
 * value comes from [ui] and every change leaves through [actions], so the caller decides what is
 * saved, which account page opens and whether clearing the cache asks first.
 *
 * 默认音质, 字号, 深色模式 and 主题色 are picked from a small menu beside their row ([MePickerRow]);
 * which of those menus is open is the one thing the page keeps. The account page's [AboutSection]
 * parameters are reused as they are.
 */
@Composable
fun MeScreen(
    ui: MeUi,
    actions: MeActions,
    modifier: Modifier = Modifier,
    /** Room for the floating dock: the page scrolls underneath it. */
    bottomInset: Dp = 0.dp,
    /** Empty hides the 关于 group. */
    appVersion: String = "",
    update: AboutUpdateUi = AboutUpdateUi(
        developmentBuild = false, checking = false, checkNote = null, release = null,
        autoCheck = null, badge = false,
    ),
    updateActions: UpdateActions = UpdateActions(),
    /** Hoisted so tapping the "我的" tab again can scroll back to the top. */
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
) {
    // The only thing the page remembers: which menu is open. The values themselves come from [ui].
    var openPicker by remember { mutableStateOf<MePicker?>(null) }
    val picker = OpenPicker(openPicker) { openPicker = it }
    // Once the title has gone under the shell's top bar, the bar says "我的" (spec section 10).
    val titleProbe = rememberTitleProbe()
    PageTopBar("我的", titleProbe)
    Column(
        modifier.fillMaxSize().verticalScroll(scrollState)
            .padding(horizontal = Space.page),
    ) {
        Spacer(Modifier.height(Space.section))
        Text(
            "我的", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.largeTitle(titleProbe),
        )
        Spacer(Modifier.height(Space.section))

        MeAccountsGroup(ui.accounts, actions.onOpenAccount)
        Spacer(Modifier.height(Space.section))
        PlaybackGroup(ui.playback, actions, picker)
        Spacer(Modifier.height(Space.section))
        HomeGroup(ui, actions, picker)
        Spacer(Modifier.height(Space.section))
        LyricsGroup(ui.lyrics, actions, picker)
        Spacer(Modifier.height(Space.section))
        AppearanceGroup(ui.appearance, actions, picker)
        Spacer(Modifier.height(Space.section))
        LocalAudioGroup(ui.appearance, actions)
        Spacer(Modifier.height(Space.section))
        StorageGroup(ui.storage, actions)
        if (appVersion.isNotEmpty()) {
            Spacer(Modifier.height(Space.section))
            // The heading sits above the card like every other group; AboutSection draws none of its own.
            MeGroup(title = "关于") {
                Column(Modifier.padding(vertical = 4.dp)) {
                    AboutSection(version = appVersion, ui = update, actions = updateActions, showHeading = false)
                }
            }
        }
        // Clears the floating dock, so the last group can scroll above it.
        Spacer(Modifier.height(Space.section + bottomInset))
    }
}

/** Which picker menu is open, if any; one at a time. */
private class OpenPicker(val current: MePicker?, val open: (MePicker?) -> Unit) {
    fun expanded(picker: MePicker) = current == picker
    fun expand(picker: MePicker) = open(picker)
    fun dismiss() = open(null)
}

/**
 * What the library home shows above the playlists, and 锁定平台: someone who uses one source opens
 * on it, and a sideways swipe on the library walks the tabs instead of the platforms.
 */
@Composable
private fun HomeGroup(ui: MeUi, actions: MeActions, picker: OpenPicker) {
    MeGroup(
        title = "曲库首页",
        footer = "锁定后打开就是这个平台，曲库左右滑直接切换搜索和我的",
    ) {
        MeSwitchRow(
            "每日推荐", ui.playback.recommendations, actions.onSetRecommendations,
            subtitle = "在曲库首页显示每日推荐和猜你喜欢",
        )
        MeDivider()
        MePickerRow(
            "锁定平台", tech.musxi.player.ui.library.LibraryLock.entries, ui.libraryLock, { it.label },
            expanded = picker.expanded(MePicker.LIBRARY_LOCK),
            onExpand = { picker.expand(MePicker.LIBRARY_LOCK) },
            onDismiss = picker::dismiss,
            onSelect = actions.onSelectLibraryLock,
        )
    }
}

@Composable
private fun PlaybackGroup(playback: MePlaybackUi, actions: MeActions, picker: OpenPicker) {
    MeGroup(title = "播放") {
        MeSwitchRow("放不了时自动换到其他平台", playback.autoSwitchSource, actions.onSetAutoSwitchSource)
        MeDivider()
        MeSwitchRow(
            "放宽时长匹配", playback.relaxDuration, actions.onSetRelaxDuration,
            subtitle = "换源时接受时长略有出入的版本",
        )
        MeDivider()
        MePickerRow(
            "默认音质", PreferredAudioQuality.entries, playback.quality, ::qualityLabel,
            expanded = picker.expanded(MePicker.QUALITY),
            onExpand = { picker.expand(MePicker.QUALITY) },
            onDismiss = picker::dismiss,
            onSelect = actions.onSelectQuality,
        )
    }
}

@Composable
private fun LyricsGroup(lyrics: MeLyricsUi, actions: MeActions, picker: OpenPicker) {
    MeGroup(title = "歌词") {
        MeSwitchRow("颜色跟随封面", lyrics.followCover, actions.onSetFollowCover)
        MeDivider()
        MeSwitchRow("显示翻译", lyrics.showTranslation, actions.onSetShowTranslation)
        MeDivider()
        MePickerRow(
            "字号", LyricsFontSize.entries, lyrics.fontSize, ::fontSizeLabel,
            expanded = picker.expanded(MePicker.FONT_SIZE),
            onExpand = { picker.expand(MePicker.FONT_SIZE) },
            onDismiss = picker::dismiss,
            onSelect = actions.onSelectFontSize,
            choiceSample = { size -> LyricsSizeSample(size) },
        )
        MeDivider()
        // Off by default: Bluetooth and car screens read the same title, so they show lyrics too.
        MeSwitchRow(
            "媒体卡片显示歌词",
            lyrics.mediaCardLyrics,
            actions.onSetMediaCardLyrics,
            subtitle = "锁屏和通知栏的卡片标题换成当前歌词；蓝牙、车机上的歌名也会跟着变",
        )
    }
}

@Composable
private fun AppearanceGroup(appearance: MeAppearanceUi, actions: MeActions, picker: OpenPicker) {
    MeGroup(title = "外观") {
        MePickerRow(
            "深色模式", ThemeMode.entries, appearance.themeMode, ::themeModeLabel,
            expanded = picker.expanded(MePicker.THEME_MODE),
            onExpand = { picker.expand(MePicker.THEME_MODE) },
            onDismiss = picker::dismiss,
            onSelect = actions.onSelectThemeMode,
        )
        MeDivider()
        MeSwitchRow("透明效果", appearance.transparency, actions.onSetTransparency)
        MeDivider()
        MePickerRow(
            "主题色", Accent.entries, appearance.accent, { it.label },
            expanded = picker.expanded(MePicker.ACCENT),
            onExpand = { picker.expand(MePicker.ACCENT) },
            onDismiss = picker::dismiss,
            onSelect = actions.onSelectAccent,
            rowLeading = { AccentDot(appearance.accent, 10.dp) },
            choiceLeading = { accent -> AccentDot(accent, 12.dp) },
        )
        MeDivider()
        tech.musxi.player.ui.wallpaper.WallpaperSettingRow(tech.musxi.player.ui.wallpaper.WallpaperKind.INTERFACE)
        MeDivider()
        tech.musxi.player.ui.wallpaper.WallpaperSettingRow(tech.musxi.player.ui.wallpaper.WallpaperKind.PLAYER)
    }
}

/**
 * 本地音频's own settings, apart from 外观: the fill row comes and goes with the switch, and 外观
 * keeps a fixed set of rows (theme, glass, colour, wallpaper) that do not shift under the finger.
 */
@Composable
private fun LocalAudioGroup(appearance: MeAppearanceUi, actions: MeActions) {
    MeGroup(title = "本地音频") {
        MeSwitchRow(
            "读取本地封面", appearance.localArtwork, actions.onSetLocalArtwork,
            subtitle = if (appearance.localArtwork) "用文件自带的封面，视频取第一帧" else "不读取，只显示你手动设置的封面",
        )
        // Turning it back on only shows the covers already read; older recordings wait for this tap.
        val fill = actions.onFillLocalArtwork
        if (appearance.localArtwork && fill != null) {
            MeDivider()
            MeValueRow(
                "为已导入的条目补取封面", appearance.localArtworkFill ?: "",
                onClick = if (appearance.localArtworkFill == null) fill else null,
                chevron = false,
            )
        }
    }
}

@Composable
private fun StorageGroup(storage: MeStorageUi, actions: MeActions) {
    MeGroup(
        title = "存储",
        // Two explicit lines; one sentence left "响。" alone on a third line.
        footer = "点按清除封面、歌词和曲库缓存。\n账号与设置不受影响。",
    ) {
        MeValueRow(
            "缓存", storage.cacheText,
            onClick = if (storage.clearable) actions.onClearCache else null,
            chevron = false,
        )
    }
}
