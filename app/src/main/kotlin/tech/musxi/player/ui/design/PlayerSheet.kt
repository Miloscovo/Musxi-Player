package tech.musxi.player.ui.design

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.player.FullPlayer
import tech.musxi.player.ui.player.PlayerStatus
import tech.musxi.player.ui.player.PlayerUi
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode
import tech.musxi.playback.PlaybackRepeatMode

/**
 * Full player sheet. Uses the real [FullPlayer] so the sheet cannot drift from the shipped layout.
 * The seven states are rendered one per image because that is exactly what is easy to get wrong:
 * a status must read as a status, and the page must not move when one appears.
 */
@Composable
fun PlayerSheet(accent: Accent, mode: ThemeMode, ui: PlayerUi) {
    MusxiTheme(accent = accent, mode = mode) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            FullPlayer(ui = ui, dark = mode != ThemeMode.LIGHT)
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
