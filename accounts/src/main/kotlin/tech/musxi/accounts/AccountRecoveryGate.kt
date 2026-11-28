package tech.musxi.accounts

import tech.musxi.core.account.AccountError
import tech.musxi.core.account.AccountPhase
import tech.musxi.core.account.AccountState
import tech.musxi.core.account.Platform

internal class AccountRecoveryGate(
    private val now: () -> Long,
    private val cooldownMs: Long = 60_000L,
) {
    init {
        require(cooldownMs > 0) { "cooldownMs must be positive" }
    }

    private val lastAttemptAt = mutableMapOf<Platform, Long>()
    private val lastAcceptedGeneration = mutableMapOf<Platform, Long>()

    fun recordAttempt(platform: Platform) {
        lastAttemptAt[platform] = now()
    }

    fun delayUntilAllowed(state: AccountState): Long? =
        reachability(state)?.remainingMs

    fun accept(state: AccountState): Boolean {
        val reachability = reachability(state) ?: return false
        if (reachability.remainingMs != 0L) return false

        lastAttemptAt[state.platform] = reachability.time
        lastAcceptedGeneration[state.platform] = state.generation
        return true
    }

    private fun reachability(state: AccountState): Reachability? {
        if (state.phase != AccountPhase.RETRYABLE_ERROR ||
            state.error != AccountError.NETWORK ||
            state.profile == null
        ) {
            return null
        }

        val time = now()
        val platform = state.platform
        if (lastAcceptedGeneration[platform] == state.generation) return null

        val previousAttempt = lastAttemptAt[platform] ?: return Reachability(time, 0L)
        if (time < previousAttempt) return null

        return Reachability(time, (cooldownMs - (time - previousAttempt)).coerceAtLeast(0L))
    }

    private data class Reachability(val time: Long, val remainingMs: Long)
}
