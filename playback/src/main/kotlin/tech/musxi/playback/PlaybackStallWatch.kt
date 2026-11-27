package tech.musxi.playback

/** One main-thread deadline for the current, unsuppressed buffering slot. */
internal class PlaybackStallWatch(
    private val now: () -> Long,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val snapshot: () -> Wait?,
    private val expired: (Kind) -> Unit,
) {
    enum class Kind(val timeoutMs: Long) { PREPARE(45_000), BUFFER(60_000) }
    data class Wait(val slot: String, val kind: Kind)

    private var waiting: Wait? = null
    private var generation = 0L
    private var deadline = 0L
    private var cancel: (() -> Unit)? = null

    fun observe() {
        val next = snapshot()
        if (next != waiting) {
            clear()
            if (next == null) return
            waiting = next
            deadline = now() + next.kind.timeoutMs
            schedulePoll(generation, next)
        } else if (next != null && now() >= deadline) {
            checkDeadline(generation, next)
        }
    }

    fun clear() {
        ++generation
        waiting = null
        cancel?.invoke()
        cancel = null
    }

    private fun schedulePoll(token: Long, wait: Wait) {
        cancel = schedule((deadline - now()).coerceAtLeast(0)) { checkDeadline(token, wait) }
    }

    private fun checkDeadline(token: Long, wait: Wait) {
        if (token != generation) return
        if (snapshot() != wait) { observe(); return }
        if (now() < deadline) { schedulePoll(token, wait); return }
        clear()
        expired(wait.kind)
    }
}
