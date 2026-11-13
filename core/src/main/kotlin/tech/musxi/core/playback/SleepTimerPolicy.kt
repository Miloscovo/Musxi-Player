package tech.musxi.core.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SleepTimerMode { OFF, AFTER_DURATION, AFTER_DURATION_FINISH_TRACK, END_OF_TRACK }
enum class SleepTimerPhase { OFF, COUNTING, WAITING_FOR_TRACK_END }

data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.OFF,
    val phase: SleepTimerPhase = SleepTimerPhase.OFF,
    val deadlineElapsedMs: Long? = null,
    val revision: Long = 0,
) {
    val active: Boolean get() = phase != SleepTimerPhase.OFF
}

/** Main-thread policy. Monotonic wall time includes pauses/buffering; sources do not identify tracks. */
class SleepTimerPolicy {
    private val mutable = MutableStateFlow(SleepTimerState())
    val state = mutable.asStateFlow()
    private var target: String? = null

    fun startDuration(now: Long, durationMs: Long, finishTrack: Boolean) {
        require(now >= 0 && durationMs in 1..MAX_DURATION_MS && now <= Long.MAX_VALUE - durationMs)
        target = null
        mutable.value = SleepTimerState(
            if (finishTrack) SleepTimerMode.AFTER_DURATION_FINISH_TRACK else SleepTimerMode.AFTER_DURATION,
            SleepTimerPhase.COUNTING, now + durationMs, mutable.value.revision + 1,
        )
    }

    fun finishCurrent(ticket: String) {
        require(ticket.isNotBlank())
        target = ticket
        mutable.value = SleepTimerState(SleepTimerMode.END_OF_TRACK, SleepTimerPhase.WAITING_FOR_TRACK_END,
            revision = mutable.value.revision + 1)
    }

    /** True means pause once. The timer is consumed before the caller touches the player. */
    fun tick(now: Long, currentTicket: String?, canFinish: Boolean): Boolean {
        val current = mutable.value
        if (current.phase != SleepTimerPhase.COUNTING || now < (current.deadlineElapsedMs ?: Long.MAX_VALUE)) return false
        if (current.mode == SleepTimerMode.AFTER_DURATION || currentTicket == null || !canFinish) {
            cancel()
            return true
        }
        target = currentTicket
        mutable.value = current.copy(phase = SleepTimerPhase.WAITING_FOR_TRACK_END, revision = current.revision + 1)
        return false
    }

    /** Leaving a bound slot (also a duplicate song) stops; source/quality replacement retains its slot. */
    fun trackChanged(currentTicket: String?): Boolean {
        if (mutable.value.phase != SleepTimerPhase.WAITING_FOR_TRACK_END || currentTicket == target) return false
        cancel()
        return true
    }

    fun trackEnded(ticket: String?): Boolean {
        if (mutable.value.phase != SleepTimerPhase.WAITING_FOR_TRACK_END || ticket == null || ticket != target) return false
        cancel()
        return true
    }

    /** Explicit queue replacement supersedes a bound track; an unexpired countdown follows playback. */
    fun queueReplaced() {
        if (mutable.value.phase == SleepTimerPhase.WAITING_FOR_TRACK_END) cancel()
    }

    fun cancel() {
        target = null
        mutable.value = SleepTimerState(revision = mutable.value.revision + 1)
    }

    companion object { const val MAX_DURATION_MS = 24L * 60 * 60 * 1000 }
}
