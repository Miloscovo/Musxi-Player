package tech.musxi.player.ui.player

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tech.musxi.core.lyrics.LyricsState
import tech.musxi.playback.PlaybackState

/**
 * The part of the playback state that moves on its own: the service republishes the whole state
 * about twice a second while playing, and only these fields differ between two such reports.
 *
 * The shell keeps this apart from the rest of the state (spec section 12): if the shell read the
 * position, every report would recompose the whole screen. Only the leaves that draw the position
 * (the mini row's ring, the player's seek bar and times, the lyrics playhead) read this, through a
 * State or a lambda, so a tick costs one small redraw.
 */
@Immutable
data class PlaybackProgress(
    val positionMs: Long = 0,
    /** elapsedRealtime of the position read; zero when there is no sample. */
    val sampledAtElapsedMs: Long = 0,
    val speed: Float = 1f,
    /** The position may be carried forward between samples (see [PlaybackState.canExtrapolatePosition]). */
    val canExtrapolate: Boolean = false,
    val durationMs: Long = 0,
) {
    /** 0..1, clamped; 0 while the duration is unknown. */
    val fraction: Float get() = progressFraction(positionMs, durationMs)
}

/** The one progress formula, shared by the mini row, the player page and their samples. */
internal fun progressFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

fun playbackProgressOf(state: PlaybackState): PlaybackProgress = PlaybackProgress(
    positionMs = state.positionMs,
    sampledAtElapsedMs = state.positionSampledAtElapsedMs,
    speed = state.playbackSpeed,
    canExtrapolate = state.canExtrapolatePosition,
    durationMs = state.durationMs,
)

/**
 * The state with the moving position taken out, so two reports that differ only in where the song
 * is compare equal. Everything else stays, including the duration, the speed and the flags that
 * decide whether the position may advance.
 */
fun slowPlaybackStateOf(state: PlaybackState): PlaybackState =
    if (state.positionMs == 0L && state.positionSampledAtElapsedMs == 0L) state
    else state.copy(positionMs = 0, positionSampledAtElapsedMs = 0)

/** Emits only when something other than the position changed. */
fun Flow<PlaybackState>.withoutPosition(): Flow<PlaybackState> = map(::slowPlaybackStateOf).distinctUntilChanged()

/** Emits only when the position, its sample, the speed, the duration or the advance flag changed. */
fun Flow<PlaybackState>.positionOnly(): Flow<PlaybackProgress> = map(::playbackProgressOf).distinctUntilChanged()

/**
 * The lyrics state without the per-word sweep. The browser re-samples the active word with every
 * playback report, but the page draws the sweep from [LyricsPlayhead]; the shell only needs the
 * active line, which changes once per line instead of twice a second.
 */
fun lyricsWithoutWordProgress(state: LyricsState): LyricsState =
    if (state.activeWordIndex == -1 && state.activeWordProgress == 0f) state
    else state.copy(activeWordIndex = -1, activeWordProgress = 0f)

fun Flow<LyricsState>.withoutWordProgress(): Flow<LyricsState> =
    map(::lyricsWithoutWordProgress).distinctUntilChanged()
