package tech.musxi.player.ui.player

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Where the song is between two playback updates, so the word sweep can move every frame.
 *
 * The playback state reports the position about twice a second, together with the
 * `SystemClock.elapsedRealtime()` at which it was read and the playback speed. This class carries
 * that sample forward on the same clock while the backend says the position may be extrapolated,
 * and holds it otherwise (paused, buffering, suppressed, failed, ended or disconnected).
 *
 * Two rules keep the sweep honest. A small step backwards between two samples is jitter, not a
 * seek: the sweep holds still instead of un-filling a word the listener has just heard. And it
 * never runs more than [MAX_LEAD_MS] past the last sample, so a stall the state has not reported
 * yet cannot run the sweep ahead of the voice.
 *
 * Frame timestamps are never used for the arithmetic: their base can differ from elapsedRealtime
 * after the screen has been off. A frame only decides when to read [positionNow] again.
 */
@Stable
class LyricsPlayhead(private val now: () -> Long = SystemClock::elapsedRealtime) {
    /** True while the position advances between samples. Frames are only drawn then. */
    var playing by mutableStateOf(false)
        private set

    /** Bumped by every new sample, so a paused line redraws once after a seek. */
    var revision by mutableLongStateOf(0L)
        private set

    private var anchorPositionMs = 0L
    private var anchorAtMs = 0L
    private var speed = 1f
    private var lastSampledAt: Long? = null
    private var lastPosition: Long? = null

    /**
     * Called with every playback state. [sampledAtMs] is the elapsedRealtime of the position read;
     * zero (no sample yet, or disconnected) anchors at the time of this call. A repeat of the same
     * sample is ignored, or each repeat would pull the estimate back.
     */
    fun update(positionMs: Long, sampledAtMs: Long, speed: Float, canAdvance: Boolean) {
        if (sampledAtMs == lastSampledAt && positionMs == lastPosition && canAdvance == playing) return
        lastSampledAt = sampledAtMs
        lastPosition = positionMs
        val at = if (sampledAtMs > 0) sampledAtMs else now()
        val estimate = positionAt(at)
        anchorPositionMs = if (canAdvance && playing && positionMs < estimate && estimate - positionMs < HOLD_MS) {
            estimate
        } else {
            positionMs
        }
        anchorAtMs = at
        this.speed = if (speed.isFinite() && speed > 0f) speed else 1f
        playing = canAdvance
        revision++
    }

    /** The estimated position now, in track milliseconds. */
    fun positionNow(): Long = positionAt(now())

    private fun positionAt(at: Long): Long =
        if (!playing) anchorPositionMs
        else anchorPositionMs + ((at - anchorAtMs).coerceIn(0L, MAX_LEAD_MS) * speed).toLong()

    private companion object {
        /** Below this a backwards sample is jitter; above it, a seek. */
        const val HOLD_MS = 300L

        /** Two missed reports' worth: a longer silence is a stall, and the sweep waits for it. */
        const val MAX_LEAD_MS = 1_200L
    }
}
