package tech.musxi.player.ui.design

import androidx.compose.runtime.Composable
import tech.musxi.core.settings.LyricsFontSize
import tech.musxi.player.ui.lockscreen.LockScreenActions
import tech.musxi.player.ui.lockscreen.LockScreenPage
import tech.musxi.player.ui.lockscreen.LockScreenUi
import tech.musxi.player.ui.player.LyricsUi
import tech.musxi.player.ui.player.playerPalette
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/**
 * Design sheets for Musxi's own lock-screen page (plan C). The real [LockScreenPage] with invented
 * songs: lyrics come from the lyrics sheet's invented lines, never a real song's text.
 */
@Composable
fun LockScreenSheet(
    mode: ThemeMode,
    ui: LockScreenUi,
    progress: Float = 0.44f,
    fontSize: LyricsFontSize = LyricsFontSize.MEDIUM,
    accentCurrent: Boolean = true,
) {
    MusxiTheme(accent = Accent.MIST, mode = mode) {
        val dark = mode != ThemeMode.LIGHT
        LockScreenPage(
            ui = ui,
            palette = playerPalette(ui.coverSeed, dark),
            actions = LockScreenActions(),
            progress = { progress },
            fontSize = fontSize,
            accentCurrent = accentCurrent,
        )
    }
}

private fun base(
    lyrics: LyricsUi?,
    title: String = "晴天",
    subtitle: String = "周杰伦",
    coverSeed: String = "晴天",
    playing: Boolean = true,
    hasPrevious: Boolean = true,
    hasNext: Boolean = true,
) = LockScreenUi(
    time = "21:47",
    date = "11月11日 星期三",
    title = title,
    subtitle = subtitle,
    coverSeed = coverSeed,
    coverUrl = null,
    playing = playing,
    buffering = false,
    hasPrevious = hasPrevious,
    hasNext = hasNext,
    durationMs = 269_000,
    lyrics = lyrics,
)

/** Timed lyrics, single language, mid-song. */
fun lockScreenTimed() = base(lyricsTimed())

/** Original plus translation: the case that decides how much the page can hold. */
fun lockScreenBilingual() = base(lyricsBilingual(), title = "Rain on the Window", subtitle = "Sample Artist", coverSeed = "雨窗")

/** Paused: the button shows play and nothing else changes. */
fun lockScreenPaused() = base(lyricsTimed(), playing = false)

/** No lyrics for this song: the cover takes the middle. */
fun lockScreenNoLyrics() = base(lyricsEmpty(), title = "无歌词的纯音乐", subtitle = "某某乐团", coverSeed = "纯音乐")

/** A local recording (an audiobook chapter): no lyrics ever, the cover and the chapter name. */
fun lockScreenLocal() = base(
    lyrics = null,
    title = "第三夜 · 雨声与低语",
    subtitle = "某某社团",
    coverSeed = "第三夜",
    hasNext = true,
)
