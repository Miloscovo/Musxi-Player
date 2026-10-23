package tech.musxi.core.playback

/**
 * Coarse failure classes reported by the Media3 playback layer.
 */
enum class PlaybackFailure {
    NETWORK,
    TIMEOUT,
    SOURCE_EXPIRED,
    PERMISSION,
    UNAVAILABLE,
    RATE_LIMIT,
    AUTH_REQUIRED,
    PROTOCOL,
    /** Foreground service could not be established in the bounded start window. */
    BACKGROUND_RESTRICTED,
    UNKNOWN,
}

/** What the player should do after a failed item/request. */
enum class RecoveryAction {
    /** Ask the source resolver for a fresh URL for the same queue item. */
    REFRESH_SOURCE,

    /** Move to the next queue item. */
    SKIP_NEXT,

    /** Give up on the current queue item without any automatic retry. */
    STOP,
}

/**
 * Bounded recovery policy for the NetEase Media3 queue.
 *
 * Guarantees:
 *  - Each [reset] grants exactly one [RecoveryAction.REFRESH_SOURCE] for
 *    [PlaybackFailure.SOURCE_EXPIRED]; a second expired source in the same budget window is
 *    a [RecoveryAction.STOP].
 *  - Network-ish failures (network, timeout, rate limit, auth required, protocol, unknown) never
 *    trigger an automatic retry of the request; they are always [RecoveryAction.STOP]. The caller
 *    may only retry after an explicit user action, which must call [reset] first.
 *  - [PlaybackFailure.PERMISSION] and [PlaybackFailure.UNAVAILABLE] skip to the next item when one
 *    exists, otherwise stop.
 *
 * [reset] is expected to be called only when the player switches to a different queue item or the
 * user explicitly asks to retry. The class is intentionally not thread-safe; call it from the
 * player's single owning thread (e.g. the application looper).
 */
class QueueRecoveryPolicy {

    private var sourceRefreshAvailable: Boolean = true

    /** Restores the one-shot recovery budget. Called on item change or explicit user retry. */
    fun reset() {
        sourceRefreshAvailable = true
    }

    /** Decides the next action for [failure] given whether a next queue item exists. */
    fun decide(failure: PlaybackFailure, hasNext: Boolean): RecoveryAction =
        when (failure) {
            PlaybackFailure.SOURCE_EXPIRED -> {
                if (sourceRefreshAvailable) {
                    sourceRefreshAvailable = false
                    RecoveryAction.REFRESH_SOURCE
                } else {
                    RecoveryAction.STOP
                }
            }
            PlaybackFailure.PERMISSION,
            PlaybackFailure.UNAVAILABLE -> if (hasNext) RecoveryAction.SKIP_NEXT else RecoveryAction.STOP
            PlaybackFailure.NETWORK,
            PlaybackFailure.TIMEOUT,
            PlaybackFailure.RATE_LIMIT,
            PlaybackFailure.AUTH_REQUIRED,
            PlaybackFailure.PROTOCOL,
            PlaybackFailure.BACKGROUND_RESTRICTED,
            PlaybackFailure.UNKNOWN -> RecoveryAction.STOP
        }
}
