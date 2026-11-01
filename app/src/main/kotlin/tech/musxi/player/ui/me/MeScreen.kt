package tech.musxi.player.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.account.AboutSection
import tech.musxi.player.ui.account.AboutUpdateUi
import tech.musxi.player.ui.account.UpdateActions
import tech.musxi.player.ui.theme.LocalMusxi
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.tones

/**
 * The "我的" page as a grouped list: 账号, 播放, 歌词, 外观, 存储, 关于. It owns no state: every
 * value comes from [ui] and every change leaves through [actions], so the caller decides what is
 * saved, which sub-page opens and whether clearing the cache asks first.
 *
 * Not wired into the shell yet; the account page's [AboutSection] parameters are reused as they are.
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
    Column(
        modifier.fillMaxSize().verticalScroll(scrollState)
            .padding(horizontal = Space.page),
    ) {
        Spacer(Modifier.height(Space.section))
        Text("我的", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(Space.section))

        MeAccountsGroup(ui.accounts, actions.onOpenAccount)
        Spacer(Modifier.height(Space.section))
        PlaybackGroup(ui.playback, actions)
        Spacer(Modifier.height(Space.section))
        LyricsGroup(ui.lyrics, actions)
        Spacer(Modifier.height(Space.section))
        AppearanceGroup(ui.appearance, actions)
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

@Composable
private fun PlaybackGroup(playback: MePlaybackUi, actions: MeActions) {
    MeGroup(title = "播放") {
        MeSwitchRow("放不了时自动换到其他平台", playback.autoSwitchSource, actions.onSetAutoSwitchSource)
        MeDivider()
        MeSwitchRow(
            "放宽时长匹配", playback.relaxDuration, actions.onSetRelaxDuration,
            subtitle = "换源时接受时长略有出入的版本",
        )
        MeDivider()
        MeValueRow("默认音质", qualityLabel(playback.quality), actions.onOpenQuality)
    }
}

@Composable
private fun LyricsGroup(lyrics: MeLyricsUi, actions: MeActions) {
    MeGroup(title = "歌词") {
        MeSwitchRow("颜色跟随封面", lyrics.followCover, actions.onSetFollowCover)
        MeDivider()
        MeSwitchRow("显示翻译", lyrics.showTranslation, actions.onSetShowTranslation)
        MeDivider()
        MeValueRow("字号", fontSizeLabel(lyrics.fontSize), actions.onOpenFontSize)
    }
}

@Composable
private fun AppearanceGroup(appearance: MeAppearanceUi, actions: MeActions) {
    val dark = LocalMusxi.current.dark
    MeGroup(title = "外观") {
        MeValueRow("深色模式", themeModeLabel(appearance.themeMode), actions.onOpenDarkMode)
        MeDivider()
        MeSwitchRow("透明效果", appearance.transparency, actions.onSetTransparency)
        MeDivider()
        MeValueRow(
            "主题色", appearance.accent.label, actions.onOpenAccent,
            leading = {
                // The accent as it looks in the current brightness, so the dot matches the page it sits on.
                Box(
                    Modifier.size(10.dp).clip(CircleShape)
                        .background(appearance.accent.tones(dark).primary),
                )
            },
        )
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
