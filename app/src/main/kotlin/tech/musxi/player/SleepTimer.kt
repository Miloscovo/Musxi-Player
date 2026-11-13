package tech.musxi.player

import tech.musxi.core.playback.SleepTimerMode
import tech.musxi.core.playback.SleepTimerPhase
import tech.musxi.core.playback.SleepTimerState

/** "mm:ss" below an hour, "h:mm:ss" from an hour up. Partial seconds round up; negatives read as zero. */
internal fun formatRemaining(ms: Long): String {
    val total = if (ms <= 0) 0L else ms / 1000 + if (ms % 1000 > 0) 1 else 0
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

/**
 * The line under "定时停止" in the player's menu, from the service's own timer state. Null when no
 * timer runs. [noun] is what is playing, "歌曲" or "音频", so the end-of-track wording fits a
 * two-hour recording as well as a song.
 *
 * A timer that waits for the end of the track has no countdown to show: it says what it waits for.
 * A countdown that will let the track finish says so after the time, so the user is not surprised
 * when the music carries on past zero.
 */
internal fun sleepStatusText(state: SleepTimerState, nowElapsedMs: Long, noun: String): String? = when (state.phase) {
    SleepTimerPhase.OFF -> null
    SleepTimerPhase.WAITING_FOR_TRACK_END -> "播完当前${noun}后停止"
    SleepTimerPhase.COUNTING -> state.deadlineElapsedMs?.let { deadline ->
        val left = "剩余 ${formatRemaining(deadline - nowElapsedMs)}"
        if (state.mode == SleepTimerMode.AFTER_DURATION_FINISH_TRACK) "$left，到点后播完$noun" else left
    } ?: "定时停止已开启"
}

/** What the notice says after a timer command the service accepted. */
internal fun sleepStartedNotice(minutes: Int?, finishTrack: Boolean, noun: String): String = when {
    minutes == null -> "将在当前${noun}播完后停止"
    finishTrack -> "$minutes 分钟后，播完当前${noun}再停止"
    else -> "将在 $minutes 分钟后停止播放"
}
