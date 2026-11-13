package tech.musxi.player.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.library.LibraryContent
import tech.musxi.player.ui.player.FullPlayer
import tech.musxi.player.ui.player.LyricsUi
import tech.musxi.player.ui.player.PlayerStatus
import tech.musxi.player.ui.player.PlayerSurface
import tech.musxi.player.ui.player.PlayerUi
import tech.musxi.player.ui.player.fadesWithPlayer
import tech.musxi.player.ui.player.rememberPlayerProgress
import tech.musxi.player.ui.shell.ShellDock
import tech.musxi.player.ui.shell.ShellTab
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.ThemeMode
import tech.musxi.playback.PlaybackRepeatMode

/**
 * Full player sheet. Uses the real [FullPlayer] so the sheet cannot drift from the shipped layout.
 * The seven states are rendered one per image because that is exactly what is easy to get wrong:
 * a status must read as a status, and the page must not move when one appears.
 */
@Composable
fun PlayerSheet(accent: Accent, mode: ThemeMode, ui: PlayerUi, findSources: Boolean = false) {
    MusxiTheme(accent = accent, mode = mode) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            FullPlayer(ui = ui, dark = mode != ThemeMode.LIGHT, onFindSources = if (findSources) ({}) else null)
        }
    }
}

/**
 * The continuous player frozen at progress [p] (0 = mini row in the dock, 1 = full page): the real
 * dock under the real surface, so a half-way picture is the same layout a finger drags through.
 */
@Composable
fun PlayerSurfaceSheet(
    accent: Accent,
    mode: ThemeMode,
    ui: PlayerUi,
    p: Float,
    lyrics: LyricsUi? = null,
    wallpaper: tech.musxi.player.ui.player.WallpaperLook? = null,
) {
    MusxiTheme(accent = accent, mode = mode) {
        val dark = mode != ThemeMode.LIGHT
        val mini = sampleMiniPlaying()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                var miniRect by remember { mutableStateOf<Rect?>(null) }
                val progress = rememberPlayerProgress(expanded = p >= 1f, onExpandedChange = {}, initialProgress = p)
                LibraryContent(state = sampleLibraryLoaded(), onSelectFilter = {})
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(horizontal = Space.page)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .fadesWithPlayer(progress),
                ) {
                    ShellDock(
                        mini = mini, selected = ShellTab.LIBRARY, onSelect = {},
                        onMiniBounds = { if (miniRect != it) miniRect = it },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                PlayerSurface(
                    progress = progress, mini = mini, ui = ui, dark = dark, miniRect = miniRect,
                    nowPlaying = null, previous = null, next = null, coverShown = lyrics == null,
                    onPlayPause = {}, onPrevious = {}, onNext = {}, wallpaper = wallpaper,
                ) { surface ->
                    FullPlayer(ui = ui, dark = dark, lyrics = lyrics, surface = surface)
                }
            }
        }
    }
}

/** The neutral case: nothing to explain, so the third line names the platform. */
fun playerNormal() = base()

/** Cross-platform swap: the third line has to say both platforms, in that order. */
fun playerSwapped() = base(
    queuePlatform = Platform.QQ,
    sourcePlatform = Platform.NETEASE,
)

/** Paused by another app: not a failure, so it must not be red. */
fun playerInterrupted() = base(status = PlayerStatus.INTERRUPTED, playing = false)

/** No audio route: the fix lives in the output tool below, hence the hint wording. */
fun playerNoOutput() = base(status = PlayerStatus.NO_OUTPUT, playing = false)

/** This track cannot play and a swap is already searching. */
fun playerFailedSwapping() = base(status = PlayerStatus.FAILED, playing = false, swapSearching = true)

/** Queue ended with repeat off. The position must sit at the end, or the picture contradicts the text. */
fun playerEnded() = base(status = PlayerStatus.ENDED, playing = false, hasNext = false, queueIndex = 11)
    .copy(positionMs = 269_000)

/** Buffering: the button carries a static arc and no text is spent on it. */
fun playerBuffering() = base(buffering = true, playing = false)

/** Same as [playerNormal] but with shuffle on, list repeat and a different cover seed. */
fun playerModes() = base(
    coverSeed = "夜的第七章",
    title = "夜的第七章",
    artist = "周杰伦",
    album = "依然范特西",
    shuffle = true,
    repeat = PlaybackRepeatMode.ALL,
)

private fun base(
    title: String = "晴天",
    artist: String = "周杰伦",
    album: String = "叶惠美",
    coverSeed: String = "晴天",
    queuePlatform: Platform? = Platform.NETEASE,
    sourcePlatform: Platform? = null,
    status: PlayerStatus = PlayerStatus.NORMAL,
    playing: Boolean = true,
    buffering: Boolean = false,
    swapSearching: Boolean = false,
    repeat: PlaybackRepeatMode = PlaybackRepeatMode.OFF,
    shuffle: Boolean = false,
    queueIndex: Int = 2,
    queueSize: Int = 12,
    hasNext: Boolean = true,
) = PlayerUi(
    title = title,
    artist = artist,
    album = album,
    coverSeed = coverSeed,
    queuePlatform = queuePlatform,
    sourcePlatform = sourcePlatform,
    positionMs = 118_000,
    durationMs = 269_000,
    playing = playing,
    buffering = buffering,
    repeat = repeat,
    shuffle = shuffle,
    queueIndex = queueIndex,
    queueSize = queueSize,
    status = status,
    swapSearching = swapSearching,
    hasPrevious = queueIndex > 0,
    hasNext = hasNext,
)
