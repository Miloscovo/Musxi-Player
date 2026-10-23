package tech.musxi.playback

/** Main-thread-only gate from foreground-service establishment to the first raw player start. */
internal class ForegroundPlaybackGate(
    private val now: () -> Long,
    private val isForeground: () -> Boolean,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val changed: () -> Unit,
    private val ready: () -> Unit,
    private val denied: () -> Unit,
    private val timeoutMs: Long = 3_000,
) {
    var pending = false
        private set

    private var generation = 0L
    private var deadline = 0L
    private var cancelTimer: (() -> Unit)? = null

    fun start(isCurrent: () -> Boolean) {
        val token = ++generation
        cancelPoll()
        deadline = deadlineFrom(now())
        pending = true
        changed()
        poll(token, isCurrent)
    }

    fun cancel() {
        ++generation
        cancelPoll()
        if (!pending) return
        pending = false
        changed()
    }

    private fun poll(token: Long, isCurrent: () -> Boolean) {
        if (token != generation || !pending) return

        val current = isCurrent()
        if (token != generation || !pending) return
        if (!current) {
            cancelRequest(token)
            return
        }

        val foreground = isForeground()
        if (token != generation || !pending) return
        if (foreground) {
            // Keep the session's BUFFERING/play intent until raw play has been submitted.
            // Publishing pending=false first can demote the service before ready() takes focus.
            finish(token, keepPendingUntilTerminal = true) {
                if (isCurrent()) {
                    if (isForeground()) ready() else denied()
                }
            }
            return
        }

        val currentTime = now()
        if (token != generation || !pending) return
        if (currentTime >= deadline) {
            finish(token, terminal = denied)
            return
        }

        val remaining = deadline - currentTime
        schedulePoll(token, isCurrent, minOf(POLL_INTERVAL_MS, remaining))
    }

    private fun schedulePoll(token: Long, isCurrent: () -> Boolean, delayMs: Long) {
        val callback = {
            if (token == generation && pending) {
                cancelTimer = null
                poll(token, isCurrent)
            }
        }
        val cancel = schedule(delayMs, callback)
        if (token == generation && pending) cancelTimer = cancel else cancel()
    }

    private fun cancelRequest(token: Long) {
        if (token != generation || !pending) return
        ++generation
        cancelPoll()
        pending = false
        changed()
    }

    private fun finish(token: Long, keepPendingUntilTerminal: Boolean = false, terminal: () -> Unit) {
        if (token != generation || !pending) return
        ++generation
        val completedGeneration = generation
        cancelPoll()
        if (keepPendingUntilTerminal) {
            terminal()
            if (generation != completedGeneration) return
            pending = false
            changed()
            return
        }
        pending = false
        changed()
        if (generation == completedGeneration && !pending) terminal()
    }

    private fun cancelPoll() {
        val cancel = cancelTimer
        cancelTimer = null
        cancel?.invoke()
    }

    private fun deadlineFrom(startedAt: Long): Long {
        if (timeoutMs <= 0) return startedAt
        val remaining = Long.MAX_VALUE - startedAt
        return if (timeoutMs >= remaining) Long.MAX_VALUE else startedAt + timeoutMs
    }

    private companion object {
        const val POLL_INTERVAL_MS = 25L
    }
}
