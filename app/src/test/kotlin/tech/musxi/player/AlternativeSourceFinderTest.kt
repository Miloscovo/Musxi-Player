package tech.musxi.player

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*
import tech.musxi.core.playback.*

class AlternativeSourceFinderTest {
    private val original = song(Platform.QQ, "original")
    private val netease = song(Platform.NETEASE, "1")
    private val kugou = song(Platform.KUGOU, "2")
    private fun song(p: Platform, id: String) = SongSummary(MediaKey(p, id), "Same song", listOf("Artist"), "Album", 180_000)
    private fun clock() = System.nanoTime() / 1_000_000

    @Test fun fastEligibleSourceDoesNotWaitForAnotherPlatformsTimeout() = runBlocking {
        var slowCancelled = false
        val finder = AlternativeSourceFinder({ 1 }, { original }, { p, _ ->
            if (p == Platform.NETEASE) try { awaitCancellation() } finally { slowCancelled = true }
            else listOf(kugou)
        }, ::clock, priorityGraceMs = 20)
        val found = withTimeout(2000) { finder.find(original) }
        assertEquals(listOf(kugou), found.candidates.map { it.song })
        assertTrue(found.partial)
        assertTrue(slowCancelled)
    }

    @Test fun priorityWithinGraceIsStableEvenWhenLowerPriorityFinishesFirst() = runBlocking {
        val high = CompletableDeferred<List<SongSummary>>()
        val lowReady = CompletableDeferred<Unit>()
        val finder = AlternativeSourceFinder({ 1 }, { original }, { p, _ ->
            if (p == Platform.NETEASE) high.await() else { lowReady.complete(Unit); listOf(kugou) }
        }, ::clock, priorityGraceMs = 1000)
        val pending = async { finder.find(original) }
        lowReady.await(); yield()
        assertFalse(pending.isCompleted)
        high.complete(listOf(netease))
        assertEquals(listOf(netease, kugou), pending.await().candidates.map { it.song })
    }

    @Test fun uncertainCandidatesAreFilteredBeforeManualChoice() = runBlocking {
        val high = CompletableDeferred<List<SongSummary>>()
        val lowReady = CompletableDeferred<Unit>()
        val version = kugou.copy(title = "Same song (Live)")
        val finder = AlternativeSourceFinder({ 1 }, { original }, { p, _ ->
            if (p == Platform.NETEASE) high.await() else { lowReady.complete(Unit); listOf(version) }
        }, ::clock, priorityGraceMs = 0)
        val pending = async { finder.find(original, mode = SourceSelectionMode.MANUAL) }
        lowReady.await(); yield()
        assertFalse(pending.isCompleted)
        high.complete(emptyList())
        val found = pending.await()
        assertTrue(found.candidates.isEmpty())
        val selection = SourceSelection { 0 }
        val token = selection.begin(original, { error("must remain manual") }, {})
        selection.offer(token, found)
        assertEquals(SourceSelectionPhase.FAILED, selection.state.value.phase)
    }

    @Test fun cancellationStopsDeliveryAndCancelsBothSearches() = runBlocking {
        var started = 0; var cancelled = 0; var delivered = false
        val ready = CompletableDeferred<Unit>()
        val finder = AlternativeSourceFinder({ 1 }, { original }, { _, _ ->
            started++; if (started == 2) ready.complete(Unit)
            try { awaitCancellation() } finally { cancelled++ }
        }, ::clock)
        val job = launch { finder.find(original); delivered = true }
        ready.await(); job.cancelAndJoin()
        assertEquals(2, cancelled); assertFalse(delivered)
    }

    @Test fun changedOriginalAccountRejectsLateResults() = runBlocking {
        var owner = 1L
        val finder = AlternativeSourceFinder({ if (it == Platform.QQ) owner else 1 }, { original }, { p, _ ->
            owner = 2
            if (p == Platform.NETEASE) listOf(netease) else listOf(kugou)
        }, ::clock)
        try { finder.find(original); fail("stale original") }
        catch (expected: LibraryPageFailure) { assertEquals("account_changed", expected.category) }
    }

    @Test fun remembersOnlyConfirmedSuccessAndForgetsFailedMapping() = runBlocking {
        var searches = 0
        val finder = AlternativeSourceFinder({ 1 }, { original }, { p, _ ->
            searches++; if (p == Platform.NETEASE) listOf(netease) else listOf(kugou)
        }, ::clock)
        finder.find(original); assertFalse(finder.lastCacheHit)
        finder.remember(original, 1, SourceCandidate(kugou, 1))
        val before = searches
        assertEquals(listOf(kugou), finder.find(original).candidates.map { it.song })
        assertTrue(finder.lastCacheHit); assertEquals(before, searches)
        finder.forget(original.key, netease.key)
        finder.find(original); assertTrue(finder.lastCacheHit)
        finder.forget(original.key, kugou.key)
        finder.find(original); assertFalse(finder.lastCacheHit); assertTrue(searches > before)
    }

    @Test fun cachedMappingExpiresAndRejectsChangedAccountsOrMetadata() = runBlocking {
        var time = 0L; var sourceGeneration = 1L
        val finder = AlternativeSourceFinder({ if (it == Platform.KUGOU) sourceGeneration else 1 }, { original }, { _, _ -> emptyList() }, { time })
        fun remember() = finder.remember(original, 1, SourceCandidate(kugou, sourceGeneration))
        remember(); sourceGeneration = 2
        assertTrue(finder.find(original).candidates.isEmpty()); assertFalse(finder.lastCacheHit)
        remember(); time = 900_001
        assertTrue(finder.find(original).candidates.isEmpty()); assertFalse(finder.lastCacheHit)
        remember()
        assertTrue(finder.find(original.copy(album = "Live")).candidates.isEmpty()); assertFalse(finder.lastCacheHit)
    }

    @Test fun manuallyChosenDifferentVersionNeverBecomesAutomaticMapping() = runBlocking {
        val finder = AlternativeSourceFinder({ 1 }, { original }, { _, _ -> emptyList() }, ::clock)
        finder.remember(original, 1, SourceCandidate(kugou.copy(album = "Live"), 1))
        assertTrue(finder.find(original).candidates.isEmpty()); assertFalse(finder.lastCacheHit)
    }

    @Test fun failedOrStaleTargetCannotEraseAnotherPlatformsEligibleResult() = runBlocking {
        var neteaseGeneration = 1L
        val finder = AlternativeSourceFinder({ if (it == Platform.NETEASE) neteaseGeneration else 1 }, { original }, { p, _ ->
            if (p == Platform.NETEASE) { neteaseGeneration = 2; listOf(netease) } else listOf(kugou)
        }, ::clock)
        assertEquals(listOf(kugou), finder.find(original).candidates.map { it.song })
    }

    @Test fun retryExcludesFailedFastSourceAndWaitsForAnotherPlatform() = runBlocking {
        val high = CompletableDeferred<List<SongSummary>>()
        val lowReady = CompletableDeferred<Unit>()
        val finder = AlternativeSourceFinder({ 1 }, { original }, { p, _ ->
            if (p == Platform.NETEASE) high.await() else { lowReady.complete(Unit); listOf(kugou) }
        }, ::clock, priorityGraceMs = 0)
        finder.remember(original, 1, SourceCandidate(kugou, 1))
        val pending = async { finder.find(original, setOf(kugou.key.stableId)) }
        lowReady.await(); yield()
        assertFalse(pending.isCompleted)
        high.complete(listOf(netease))
        assertEquals(listOf(netease), pending.await().candidates.map { it.song })
        assertFalse(finder.lastCacheHit)
    }

    @Test fun manualModeWaitsForEveryPlatformAndReportsSafeProgress() = runBlocking {
        val slow = CompletableDeferred<List<SongSummary>>()
        val progress = mutableListOf<SourceSearchProgress>()
        val finder = AlternativeSourceFinder({ 1 }, { original }, { platform, _ ->
            if (platform == Platform.KUGOU) slow.await() else listOf(netease)
        }, ::clock)
        val pending = async {
            finder.find(
                original = original,
                mode = SourceSelectionMode.MANUAL,
                onProgress = { progress += it },
            )
        }
        repeat(20) { yield() }
        assertFalse(pending.isCompleted)
        slow.completeExceptionally(LibraryPageFailure("network"))
        val found = withTimeout(1000) { pending.await() }
        assertEquals(listOf(netease), found.candidates.map { it.song })
        assertEquals(SourceSearchCompletion.PARTIAL, found.completion)
        assertTrue(found.partial)
        assertEquals(SourceSearchStatus.FAILURE, found.platformProgress.first { it.platform == Platform.KUGOU }.status)
        assertEquals("network", found.platformProgress.first { it.platform == Platform.KUGOU }.error)
        assertEquals(SourceSearchStatus.COMPLETE, found.platformProgress.first { it.platform == Platform.NETEASE }.status)
        assertTrue(progress.any { it.platform == Platform.KUGOU && it.status == SourceSearchStatus.SEARCHING })
        assertTrue(progress.any { it.completedPlatforms == 2 })
    }

    @Test fun manualModeBypassesCachedAutoMapping() = runBlocking {
        var searches = 0
        val finder = AlternativeSourceFinder({ 1 }, { original }, { _, _ ->
            searches++
            emptyList()
        }, ::clock)
        finder.remember(original, 1, SourceCandidate(netease, 1))
        val found = finder.find(original, mode = SourceSelectionMode.MANUAL)
        assertTrue(found.candidates.isEmpty())
        assertFalse(finder.lastCacheHit)
        assertEquals(2, searches)
    }

    @Test fun manualModeReportsUnavailablePlatformInsteadOfOmittingIt() = runBlocking {
        var searches = 0
        val finder = AlternativeSourceFinder(
            generation = { platform -> 1L.takeIf { platform == Platform.NETEASE || platform == original.key.platform } },
            song = { original },
            search = { _, _ -> searches++; emptyList() },
            now = ::clock,
        )
        val found = finder.find(original, mode = SourceSelectionMode.MANUAL)
        assertEquals(1, searches)
        assertEquals(2, found.platformProgress.size)
        val unavailable = found.platformProgress.first { it.platform == Platform.KUGOU }
        assertEquals(SourceSearchStatus.FAILURE, unavailable.status)
        assertEquals("account_unavailable", unavailable.error)
        assertEquals(1, unavailable.completedPlatforms)
        assertEquals(2, unavailable.totalPlatforms)
        assertEquals(SourceSearchCompletion.PARTIAL, found.completion)
    }

    @Test fun staleRevisionStopsBeforeSearchOrDelivery() = runBlocking {
        var revision = 8L; var calls = 0
        val finder = AlternativeSourceFinder({ 1 }, { original }, { _, _ ->
            calls++
            listOf(netease)
        }, ::clock)
        try {
            finder.find(original, revision = 7, currentRevision = { revision })
            fail("stale revision")
        } catch (expected: LibraryPageFailure) {
            assertEquals("stale_revision", expected.category)
        }
        assertEquals(0, calls)
    }
}
