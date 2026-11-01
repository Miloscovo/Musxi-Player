package tech.musxi.playback

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import tech.musxi.core.library.MediaKey
import tech.musxi.core.playback.PlaybackFailure
import tech.musxi.core.playback.SourceCandidate

private data class PreparedCandidate<T>(val index: Int, val value: T)

/**
 * Prepares two platforms concurrently, with at most three candidate attempts in total.
 * Only recording-level failures allow another candidate; caller cancellation propagates.
 */
internal suspend fun <T> prepareAutomaticAlternatives(
    candidates: List<SourceCandidate>,
    timeoutMs: Long = 8_000,
    graceMs: Long = 150,
    maxAttempts: Int = 3,
    prepare: suspend (SourceCandidate) -> T,
): Map<MediaKey, T> {
    val budget = maxAttempts.coerceIn(0, 3)
    val selected = candidates.withIndex().distinctBy { it.value.song.key }
        .groupBy { it.value.song.key.platform }.values.take(minOf(2, budget))
    if (selected.isEmpty()) return emptyMap()

    val successful = linkedMapOf<Int, T>()
    val extraAttempts = AtomicInteger((budget - selected.size).coerceAtLeast(0))
    withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) {
        supervisorScope {
            val outcomes = Channel<PreparedCandidate<T>?>(Channel.UNLIMITED)
            val jobs = selected.map { platformCandidates ->
                launch {
                    var outcome: PreparedCandidate<T>? = null
                    for ((position, indexed) in platformCandidates.withIndex()) {
                        if (position > 0) {
                            if (extraAttempts.getAndDecrement() <= 0) break
                        }
                        try {
                            outcome = PreparedCandidate(indexed.index, prepare(indexed.value))
                            break
                        } catch (cancelled: CancellationException) {
                            currentCoroutineContext().ensureActive()
                            break
                        } catch (error: Exception) {
                            if (error !is PlaybackSourceException || error.failure !in setOf(
                                    PlaybackFailure.PERMISSION, PlaybackFailure.UNAVAILABLE, PlaybackFailure.PROTOCOL)) break
                        }
                    }
                    outcomes.send(outcome)
                }
            }

            var remaining = selected.size
            var foundSuccess = false
            while (remaining > 0) {
                val outcome = outcomes.receive()
                remaining--
                if (outcome != null) {
                    successful[outcome.index] = outcome.value
                    foundSuccess = true
                    break
                }
            }

            if (foundSuccess) {
                withTimeoutOrNull(graceMs.coerceAtLeast(0)) {
                    while (remaining > 0) {
                        val outcome = outcomes.receive()
                        remaining--
                        if (outcome != null) successful[outcome.index] = outcome.value
                    }
                }
            }

            jobs.forEach { it.cancel() }
            jobs.joinAll()
            true
        }
    }

    return buildMap {
        candidates.forEachIndexed { index, candidate ->
            if (successful.containsKey(index)) put(candidate.song.key, successful.getValue(index))
        }
    }
}
