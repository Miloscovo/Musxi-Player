package tech.musxi.core.account

import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Serializes state transitions; platform I/O belongs to the repository. */
class AccountCoordinator {
    private val gate = Any()
    private var generation = 0L
    private val active = mutableMapOf<Platform, Long>()
    private val cancellationStates = mutableMapOf<Platform, AccountState>()
    private val mutableStates = MutableStateFlow<Map<Platform, AccountState>>(
        Collections.unmodifiableMap(Platform.entries.associateWith {
            AccountState(it, AccountPhase.DISCONNECTED)
        })
    )
    val states: StateFlow<Map<Platform, AccountState>> = mutableStates.asStateFlow()

    fun begin(platform: Platform): Long = synchronized(gate) {
        val previous = state(platform)
        // Superseding a live attempt must retain its original cancellation destination.
        if (platform !in active) cancellationStates[platform] = previous
        val token = nextGeneration()
        active[platform] = token
        publish(previous.copy(phase = AccountPhase.PREPARING, error = null, generation = token))
        token
    }

    fun isCurrent(platform: Platform, token: Long): Boolean = synchronized(gate) {
        current(platform, token)
    }

    fun awaiting(platform: Platform, token: Long): Boolean = synchronized(gate) {
        if (!current(platform, token) || state(platform).phase != AccountPhase.PREPARING) return false
        publish(state(platform).copy(phase = AccountPhase.WAITING_CONFIRMATION))
        true
    }

    fun verifying(platform: Platform, token: Long): Boolean = synchronized(gate) {
        if (!current(platform, token)) return false
        val previous = state(platform)
        if (previous.phase != AccountPhase.PREPARING &&
            previous.phase != AccountPhase.WAITING_CONFIRMATION &&
            previous.phase != AccountPhase.REVALIDATING) return false
        publish(previous.copy(phase = AccountPhase.VERIFYING))
        true
    }

    fun connected(platform: Platform, token: Long, profile: AccountProfile): Boolean = synchronized(gate) {
        if (!current(platform, token) || profile.platform != platform) return false
        complete(platform)
        publish(AccountState(platform, AccountPhase.CONNECTED, profile, generation = token))
        true
    }

    fun fail(platform: Platform, token: Long, error: AccountError): Boolean = synchronized(gate) {
        if (!current(platform, token)) return false
        complete(platform)
        publish(state(platform).copy(
            phase = if (error == AccountError.EXPIRED) AccountPhase.REAUTH_REQUIRED
                else AccountPhase.RETRYABLE_ERROR,
            error = error
        ))
        true
    }

    fun cancel(platform: Platform): Long = synchronized(gate) {
        val previous = cancellationStates[platform] ?: state(platform)
        val token = nextGeneration()
        complete(platform)
        publish(previous.copy(generation = token))
        token
    }

    fun disconnect(platform: Platform): Long = synchronized(gate) {
        val token = nextGeneration()
        complete(platform)
        publish(AccountState(platform, AccountPhase.DISCONNECTED, generation = token))
        token
    }

    /** A platform request can explicitly reject an already completed, saved session. */
    fun invalidate(platform: Platform, expectedGeneration: Long): Boolean = synchronized(gate) {
        val previous = state(platform)
        if (previous.generation != expectedGeneration || previous.phase != AccountPhase.CONNECTED) return false
        complete(platform)
        publish(previous.copy(phase = AccountPhase.REAUTH_REQUIRED, error = AccountError.EXPIRED,
            generation = nextGeneration()))
        true
    }

    /** Even a null profile represents pending storage loading, not a completed disconnect. */
    fun restore(platform: Platform, profile: AccountProfile?): Long = synchronized(gate) {
        require(profile == null || profile.platform == platform) { "Profile platform mismatch" }
        val token = nextGeneration()
        val restored = AccountState(platform, AccountPhase.REVALIDATING, profile, generation = token)
        active[platform] = token
        // A cancelled restore has no live verifier; leave a retryable state so repositories
        // that guard active phases can start a fresh restore without trusting this identity.
        cancellationStates[platform] = restored.copy(phase = AccountPhase.RETRYABLE_ERROR)
        publish(restored)
        token
    }

    private fun current(platform: Platform, token: Long): Boolean = active[platform] == token
    private fun state(platform: Platform): AccountState = mutableStates.value.getValue(platform)
    private fun nextGeneration(): Long = Math.incrementExact(generation).also { generation = it }

    private fun complete(platform: Platform) {
        active.remove(platform)
        cancellationStates.remove(platform)
    }

    private fun publish(state: AccountState) {
        mutableStates.value = Collections.unmodifiableMap(mutableStates.value + (state.platform to state))
    }
}
