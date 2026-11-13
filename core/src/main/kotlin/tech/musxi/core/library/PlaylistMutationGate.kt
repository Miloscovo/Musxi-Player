package tech.musxi.core.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.musxi.core.account.Platform

/** One accepted management operation per platform, including all of its reconciliation reads. */
class PlaylistMutationGate {
    private val gate = Any()
    private val active = mutableSetOf<Platform>()
    private val mutable = MutableStateFlow<Set<Platform>>(emptySet())
    val busyPlatforms = mutable.asStateFlow()

    fun <T> run(platform: Platform, result: (Result<T>) -> Unit,
        work: ((Result<T>) -> Unit) -> Unit) {
        val accepted = synchronized(gate) {
            if (!active.add(platform)) false else {
                mutable.value = active.toSet()
                true
            }
        }
        if (!accepted) return result(Result.failure(LibraryPageFailure("busy")))
        var completed = false
        val finish: (Result<T>) -> Unit = { response ->
            val deliver = synchronized(gate) {
                if (completed) false else {
                    completed = true
                    active.remove(platform)
                    mutable.value = active.toSet()
                    true
                }
            }
            if (deliver) result(response)
        }
        try {
            work(finish)
        } catch (error: Exception) {
            // Do not treat an exception from the caller's completed callback as a second outcome.
            if (synchronized(gate) { completed }) throw error
            finish(Result.failure(error))
        }
    }
}
