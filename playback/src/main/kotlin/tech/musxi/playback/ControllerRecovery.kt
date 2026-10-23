package tech.musxi.playback

enum class PlaybackConnectionPhase { CONNECTING, CONNECTED, RETRY_WAIT, FAILED, CLOSED }

internal data class ControllerConnectionState(val phase: PlaybackConnectionPhase, val attempt: Int)

/** Single main-thread owner. No player commands or queue replay occur when a resource reconnects. */
internal class ControllerRecovery<C : Any>(
    private val connect: (Long, (Result<C>) -> Unit) -> (() -> Unit),
    private val release: (C) -> Unit,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val now: () -> Long,
    private val attached: (C) -> Unit,
    private val changed: (ControllerConnectionState) -> Unit,
) {
    var state = ControllerConnectionState(PlaybackConnectionPhase.FAILED, 0)
        private set
    private var epoch = 0L
    private var resource: C? = null
    private var cancelConnect: (() -> Unit)? = null
    private var cancelTimer: (() -> Unit)? = null
    private var connectedAt = 0L

    fun start() { if (state.attempt == 0 && state.phase == PlaybackConnectionPhase.FAILED) begin() }

    /** Busy/connected calls are no-ops; explicit retry starts a new finite budget. */
    fun retry() {
        if (state.phase != PlaybackConnectionPhase.FAILED) return
        state = state.copy(attempt = 0)
        begin()
    }

    fun disconnected(token: Long) {
        if (token != epoch || state.phase !in setOf(PlaybackConnectionPhase.CONNECTED, PlaybackConnectionPhase.CONNECTING)) return
        if (state.phase == PlaybackConnectionPhase.CONNECTED && now() - connectedAt >= STABLE_MS) {
            state = state.copy(attempt = 0)
        }
        failed(token)
    }

    fun close() {
        if (state.phase == PlaybackConnectionPhase.CLOSED) return
        ++epoch
        cleanup()
        publish(PlaybackConnectionPhase.CLOSED)
    }

    private fun begin() {
        val token = ++epoch
        cleanup()
        state = ControllerConnectionState(PlaybackConnectionPhase.CONNECTING, state.attempt + 1)
        changed(state)
        cancelTimer = schedule(CONNECT_TIMEOUT_MS) { failed(token) }
        try {
            val cancel = connect(token) { response -> complete(token, response) }
            if (token == epoch && state.phase == PlaybackConnectionPhase.CONNECTING) cancelConnect = cancel
            else if (token != epoch || state.phase != PlaybackConnectionPhase.CONNECTED) cancel()
        } catch (_: Exception) { failed(token) }
    }

    private fun complete(token: Long, response: Result<C>) {
        val candidate = response.getOrNull()
        if (token != epoch || state.phase != PlaybackConnectionPhase.CONNECTING) {
            if (candidate != null && candidate !== resource) release(candidate)
            return
        }
        if (candidate == null) { failed(token); return }
        cancelTimer?.invoke(); cancelTimer = null
        cancelConnect = null
        resource = candidate
        connectedAt = now()
        publish(PlaybackConnectionPhase.CONNECTED)
        try { attached(candidate) } catch (_: Exception) { failed(token) }
    }

    private fun failed(token: Long) {
        if (token != epoch || state.phase !in setOf(PlaybackConnectionPhase.CONNECTING, PlaybackConnectionPhase.CONNECTED)) return
        val next = ++epoch
        cleanup()
        if (state.attempt >= MAX_ATTEMPTS) {
            publish(PlaybackConnectionPhase.FAILED)
        } else {
            publish(PlaybackConnectionPhase.RETRY_WAIT)
            cancelTimer = schedule(if (state.attempt <= 1) 1_000 else 3_000) {
                if (epoch == next && state.phase == PlaybackConnectionPhase.RETRY_WAIT) begin()
            }
        }
    }

    private fun cleanup() {
        cancelTimer?.invoke(); cancelTimer = null
        cancelConnect?.invoke(); cancelConnect = null
        resource?.let(release); resource = null
    }

    private fun publish(phase: PlaybackConnectionPhase) {
        state = state.copy(phase = phase)
        changed(state)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val STABLE_MS = 30_000L
    }
}
