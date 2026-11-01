package tech.musxi.playback

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary
import tech.musxi.core.playback.SourceCandidate
import tech.musxi.core.playback.PlaybackFailure

class AutomaticSourcePreparationTest {
    private fun candidate(platform: Platform, id: String) = SourceCandidate(
        song = SongSummary(MediaKey(platform, id), id, emptyList(), "", 1_000),
        accountGeneration = 7,
    )

    @Test
    fun recordingFailureTriesTheNextCandidateWithoutLosingTheOtherPlatform() = runBlocking {
        val candidates = listOf(
            candidate(Platform.NETEASE, "denied"),
            candidate(Platform.NETEASE, "available"),
            candidate(Platform.QQ, "qq"),
        )
        val called = mutableListOf<String>()
        val prepared = prepareAutomaticAlternatives(candidates) { source ->
            called += source.song.key.id
            if (source.song.key.id == "denied") throw PlaybackSourceException(PlaybackFailure.PERMISSION)
            delay(10)
            source.song.key.id
        }
        assertEquals(setOf("denied", "available", "qq"), called.toSet())
        assertEquals(listOf("available", "qq"), prepared.keys.map { it.id })
    }

    @Test
    fun retriesRespectTheSharedBudgetAndNeverRetryAccountFailures() = runBlocking {
        val candidates = listOf(
            candidate(Platform.NETEASE, "rate-limited"),
            candidate(Platform.NETEASE, "must-not-request"),
            candidate(Platform.QQ, "denied"),
            candidate(Platform.QQ, "denied-again"),
            candidate(Platform.QQ, "over-budget"),
        )
        val called = mutableListOf<String>()
        val prepared = prepareAutomaticAlternatives(candidates) { source ->
            called += source.song.key.id
            throw PlaybackSourceException(if (source.song.key.id == "rate-limited") PlaybackFailure.RATE_LIMIT else PlaybackFailure.PERMISSION)
        }
        assertTrue(prepared.isEmpty())
        assertEquals(listOf("rate-limited", "denied", "denied-again"), called)
    }

    @Test
    fun preparesFirstCandidateFromOnlyTwoPlatformsConcurrently() = runBlocking {
        val candidates = listOf(
            candidate(Platform.NETEASE, "netease-1"),
            candidate(Platform.NETEASE, "netease-2"),
            candidate(Platform.QQ, "qq-1"),
            candidate(Platform.KUGOU, "kugou-1"),
        )
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val started = mutableListOf<String>()

        val prepared = prepareAutomaticAlternatives(candidates) { source ->
            started += source.song.key.id
            maxActive.set(maxOf(maxActive.get(), active.incrementAndGet()))
            delay(30)
            active.decrementAndGet()
            source.song.key.id
        }

        assertEquals(listOf("netease-1", "qq-1"), started)
        assertEquals(2, maxActive.get())
        assertEquals(listOf("netease-1", "qq-1"), prepared.keys.map { it.id })
    }

    @Test
    fun onePlatformFailureDoesNotHideTheOtherResult() = runBlocking {
        val candidates = listOf(
            candidate(Platform.NETEASE, "netease-1"),
            candidate(Platform.NETEASE, "netease-2"),
            candidate(Platform.QQ, "qq-1"),
        )
        val called = mutableListOf<String>()

        val prepared = prepareAutomaticAlternatives(candidates) { source ->
            called += source.song.key.id
            if (source.song.key.platform == Platform.NETEASE) error("unavailable")
            delay(10)
            "qq-result"
        }

        assertEquals(listOf("netease-1", "qq-1"), called)
        assertEquals(setOf(MediaKey(Platform.QQ, "qq-1")), prepared.keys)
        assertEquals("qq-result", prepared.getValue(MediaKey(Platform.QQ, "qq-1")))
    }

    @Test
    fun candidateCancellationIsIsolatedUnlessTheCallerWasCancelled() = runBlocking {
        val candidates = listOf(
            candidate(Platform.NETEASE, "netease-1"),
            candidate(Platform.QQ, "qq-1"),
        )

        val prepared = prepareAutomaticAlternatives(candidates) { source ->
            if (source.song.key.platform == Platform.NETEASE) {
                throw CancellationException("candidate-local cancellation")
            }
            delay(10)
            "qq-result"
        }

        assertEquals(setOf(MediaKey(Platform.QQ, "qq-1")), prepared.keys)
    }

    @Test
    fun graceWindowKeepsInputOrderWhenLaterCandidateFinishesFirst() = runBlocking {
        val candidates = listOf(
            candidate(Platform.NETEASE, "netease-1"),
            candidate(Platform.QQ, "qq-1"),
        )
        val finished = mutableListOf<String>()

        val prepared = prepareAutomaticAlternatives(candidates, graceMs = 100) { source ->
            delay(if (source.song.key.platform == Platform.NETEASE) 40 else 5)
            finished += source.song.key.id
            source.song.key.id
        }

        assertEquals(listOf("qq-1", "netease-1"), finished)
        assertEquals(listOf(Platform.NETEASE, Platform.QQ), prepared.keys.map { it.platform })
    }

    @Test
    fun overallTimeoutReturnsCompletedWorkAndCancelsSlowCandidate() = runBlocking {
        var slowCancelled = false
        val candidates = listOf(
            candidate(Platform.NETEASE, "netease-fast"),
            candidate(Platform.QQ, "qq-slow"),
        )
        val startedAt = System.nanoTime()

        val prepared = prepareAutomaticAlternatives(
            candidates,
            timeoutMs = 80,
            graceMs = 1_000,
        ) { source ->
            if (source.song.key.platform == Platform.NETEASE) {
                delay(10)
                "fast-result"
            } else {
                try {
                    delay(1_000)
                    "slow-result"
                } finally {
                    slowCancelled = true
                }
            }
        }

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(setOf(MediaKey(Platform.NETEASE, "netease-fast")), prepared.keys)
        assertEquals("fast-result", prepared.getValue(MediaKey(Platform.NETEASE, "netease-fast")))
        assertTrue("helper must stop at its overall deadline", elapsedMs < 500)
        assertTrue("unfinished preparation must be cancelled", slowCancelled)
    }

    @Test
    fun parentCancellationCancelsPreparationAndIsNotSwallowed() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var helperObservedCancellation = false
        var preparationCancelled = false
        val job = launch {
            try {
                prepareAutomaticAlternatives(
                    listOf(candidate(Platform.NETEASE, "netease-1")),
                    timeoutMs = 5_000,
                    graceMs = 100,
                ) {
                    started.complete(Unit)
                    try {
                        delay(5_000)
                        "unexpected"
                    } finally {
                        preparationCancelled = true
                    }
                }
            } catch (cancelled: CancellationException) {
                helperObservedCancellation = true
                throw cancelled
            }
        }

        started.await()
        delay(20)
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(helperObservedCancellation)
        assertTrue(preparationCancelled)
    }
}
