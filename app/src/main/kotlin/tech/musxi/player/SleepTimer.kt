package tech.musxi.player

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Process-wide sleep timer: pauses playback when the deadline passes. Survives leaving the player. */
object SleepTimer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableDeadline = MutableStateFlow<Long?>(null)
    private var job: Job? = null

    /** [SystemClock.elapsedRealtime] at which [start]'s callback runs; null while no timer is set. */
    val deadline: StateFlow<Long?> = mutableDeadline.asStateFlow()

    /** Longest single wait. `delay` does not count time the device spent suspended. */
    private const val MAX_STEP_MS = 15_000L

    /** Replaces any running timer. [onFire] runs once, on the main thread, when time is up. */
    fun start(minutes: Int, onFire: () -> Unit) {
        job?.cancel()
        val target = SystemClock.elapsedRealtime() + minutes.coerceAtLeast(0) * 60_000L
        mutableDeadline.value = target
        job = scope.launch {
            // Waiting in short steps and re-reading the clock keeps a long suspend from pushing the
            // stop minutes past the deadline the user was shown.
            while (true) {
                val left = target - SystemClock.elapsedRealtime()
                if (left <= 0) break
                delay(minOf(left, MAX_STEP_MS))
            }
            try {
                onFire()
            } finally {
                // Only clear our own deadline: a newer start() has already replaced it.
                if (mutableDeadline.value == target) mutableDeadline.value = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        mutableDeadline.value = null
    }
}

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
