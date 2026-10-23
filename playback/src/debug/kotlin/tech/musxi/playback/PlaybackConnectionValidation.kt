package tech.musxi.playback

/**
 * Debug-only driver for connection recovery validation. It performs no UI work and keeps
 * controller, queue and acknowledgement identities inside [PlaybackConnection].
 */
object PlaybackConnectionValidation {
    enum class Gate { ALLOW, FAIL, TIMEOUT }

    enum class Phase {
        CONNECTING, CONNECTED, RETRY_WAIT, FAILED, CLOSED,
        CONNECT_BLOCKED_FAIL, CONNECT_BLOCKED_TIMEOUT, GATE_ALLOWED, GATE_EXPIRED,
        DISCONNECTED, ACK_DELAY_ARMED, ACK_DELAY_STARTED, ACK_DELIVERED, ACK_EXPIRED,
        QUEUE_COMMAND_SUBMITTED,
    }

    data class Event(val phase: Phase, val attempt: Int, val elapsedMs: Long)

    data class Summary(
        val connectionPhase: PlaybackConnectionPhase,
        val attempt: Int,
        val gate: Gate,
        val gateExpiresInMs: Long,
        val controllerCreatedCount: Int,
        val controllerReleasedCount: Int,
        val queueCommandSubmitCount: Int,
        val pendingCommandCount: Int,
        val ackDelayArmed: Boolean,
        val events: List<Event>,
    )

    /**
     * Disconnects the current controller with its saved connect token. Recovery releases the
     * controller and rejects pending queue callbacks; the service queue and playback are untouched.
     */
    fun disconnect(connection: PlaybackConnection): Boolean = connection.validationDisconnect()

    /**
     * Blocks the next controller connections for at most 60 seconds and disconnects the current
     * controller. FAIL rejects each attempt immediately; TIMEOUT leaves recovery's original
     * 10-second timer to fail the attempt.
     */
    fun blockConnections(connection: PlaybackConnection, timeout: Boolean) {
        connection.validationBlock(timeout)
    }

    /** Clears the connection gate without retrying. Call the app runner's retry path explicitly. */
    fun allowConnections(connection: PlaybackConnection) {
        connection.validationAllow()
    }

    /**
     * Arms a one-shot 2-second delay for the next real dispatch future. The runner should submit
     * an idempotent edit, wait for ACK_DELAY_STARTED, then call [disconnect]: the disconnect rejects
     * the still-pending callback once, and the delayed acknowledgement cannot deliver it again.
     */
    fun lateAckDisconnect(connection: PlaybackConnection): Boolean =
        connection.validationLateAckDisconnect()

    /** Returns fixed counters, enums and bounded timing events without tokens or media identity. */
    fun snapshot(connection: PlaybackConnection): Summary {
        val snapshot = connection.validationSnapshot()
        return Summary(
            connectionPhase = snapshot.connectionPhase,
            attempt = snapshot.attempt,
            gate = Gate.valueOf(snapshot.gate.name),
            gateExpiresInMs = snapshot.gateExpiresInMs,
            controllerCreatedCount = snapshot.controllerCreatedCount,
            controllerReleasedCount = snapshot.controllerReleasedCount,
            queueCommandSubmitCount = snapshot.queueCommandSubmitCount,
            pendingCommandCount = snapshot.pendingCommandCount,
            ackDelayArmed = snapshot.ackDelayArmed,
            events = snapshot.events.map { Event(Phase.valueOf(it.phase.name), it.attempt, it.elapsedMs) },
        )
    }
}
