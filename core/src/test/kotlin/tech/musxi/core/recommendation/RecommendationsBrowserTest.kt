package tech.musxi.core.recommendation

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*

class RecommendationsBrowserTest {
    private val platform = Platform.NETEASE
    private fun song(id: String = "1", platform: Platform = this.platform) =
        SongSummary(MediaKey(platform, id), "Song $id", listOf("Artist"), "Album", 123_000)
    private class Store : RecommendationStore {
        var snapshot: RecommendationSnapshot? = null
        var failRead = false
        var failWrite = false
        var writes = 0
        override fun read(owner: String): RecommendationSnapshot? {
            if (failRead) throw java.io.IOException("private disk path")
            return snapshot
        }
        override fun write(owner: String, snapshot: RecommendationSnapshot) {
            if (failWrite) throw java.io.IOException("private disk path")
            this.snapshot = snapshot; writes++
        }
        override fun clear() { snapshot = null }
    }
    private class Harness {
        var now = java.time.Instant.parse("2026-11-11T03:00:00Z").toEpochMilli()
        var elapsed = 100_000L
        val identities = Platform.entries.associateWith<Platform, LibraryCacheIdentity?> {
            LibraryCacheIdentity(it.name.first().lowercase().repeat(64), 1, true)
        }.toMutableMap()
        val stores = Platform.entries.associateWith { Store() }
        val requests = mutableListOf<Triple<Platform, () -> Boolean, (Result<List<SongSummary>>) -> Unit>>()
        val disk = ArrayDeque<() -> Unit>()
        var deferred = false
        val browser = RecommendationsBrowser({ identities[it] }, { p, _, action -> action(stores.getValue(p)) },
            RecommendationFetch { p, current, result -> requests += Triple(p, current, result) },
            { action -> if (deferred) disk.addLast(action) else action() }, { it() }, { now }, { elapsed })
        fun state(platform: Platform = Platform.NETEASE) = browser.state.value.getValue(platform)
    }

    @Test fun freshCacheOpensWithoutNetworkAndManualRefreshPreservesOrder() {
        val h = Harness()
        h.stores.getValue(platform).snapshot = RecommendationSnapshot(platform, listOf(song("2"), song()), h.now - 1000)
        h.browser.open(platform)
        assertTrue(h.requests.isEmpty())
        assertEquals(LibrarySource.CACHE, h.state().source)
        assertFalse(h.state().stale)
        h.browser.refresh(platform)
        assertTrue(h.state().refreshing)
        h.requests.single().third(Result.success(listOf(song("3"), song("2"))))
        assertEquals(listOf("3", "2"), h.state().snapshot!!.songs.map { it.key.id })
        assertEquals(LibrarySource.NETWORK, h.state().source)
        assertEquals(1, h.stores.getValue(platform).writes)
    }

    @Test fun previousDateCacheRemainsVisibleWhenNetworkFailsWithoutLeakingDetails() {
        val h = Harness()
        h.stores.getValue(platform).snapshot = RecommendationSnapshot(platform, listOf(song()), h.now - 86_400_000)
        h.browser.open(platform)
        assertTrue(h.state().stale)
        assertTrue(h.state().refreshing)
        h.requests.single().third(Result.failure(IllegalStateException("cookie=private")))
        assertEquals("unclassified", h.state().error)
        assertEquals("1", h.state().snapshot!!.songs.single().key.id)
        assertFalse(h.state().loading)
        assertEquals(0, h.stores.getValue(platform).writes)
    }

    @Test fun utcPlusEightDateBoundaryRefreshesOnNextOpenWithoutBackgroundTimer() {
        val h = Harness()
        h.now = java.time.Instant.parse("2026-11-11T15:59:59Z").toEpochMilli()
        h.stores.getValue(platform).snapshot = RecommendationSnapshot(platform, listOf(song()), h.now)
        h.browser.open(platform)
        assertTrue(h.requests.isEmpty())
        h.now += 2000
        h.browser.open(platform)
        assertEquals(1, h.requests.size)
        assertTrue(h.state().stale)
        assertEquals("2026-11-11", h.state().snapshot!!.retrievedDate)
    }

    @Test fun unverifiedAccountReadsOnlyItsCacheAndCannotQueue() {
        val h = Harness()
        h.identities[platform] = h.identities.getValue(platform)!!.copy(verified = false)
        h.stores.getValue(platform).snapshot = RecommendationSnapshot(platform, listOf(song()), h.now)
        h.browser.open(platform)
        assertTrue(h.requests.isEmpty())
        assertTrue(h.state().stale)
        assertFalse(h.state().accountVerified)
        assertEquals("account_unavailable", h.state().error)
        assertTrue(h.browser.queue(platform, expectedRevision = h.state().revision).isFailure)
    }

    @Test fun noAccountDoesNotReadDiskOrStartRequest() {
        val h = Harness()
        h.identities[platform] = null
        h.browser.open(platform)
        assertEquals("account_unavailable", h.state().error)
        assertTrue(h.requests.isEmpty())
        assertNull(h.state().snapshot)
    }

    @Test fun cancellationAndLaterRefreshFenceOldCompletionAndCacheWrites() {
        val h = Harness()
        h.browser.open(platform)
        h.browser.cancel(platform)
        assertFalse(h.requests.single().second())
        h.requests.single().third(Result.success(listOf(song("old"))))
        assertNull(h.state().snapshot)
        assertEquals(0, h.stores.getValue(platform).writes)
        h.elapsed += 30_000
        h.browser.refresh(platform)
        h.requests.last().third(Result.success(listOf(song("new"))))
        assertEquals("new", h.state().snapshot!!.songs.single().key.id)
    }

    @Test fun switchingOwnerImmediatelyWithdrawsRowsAndRejectsOldNetwork() {
        val h = Harness()
        h.browser.open(platform)
        val request = h.requests.single()
        h.identities[platform] = LibraryCacheIdentity("b".repeat(64), 2, true)
        h.browser.accountChanged()
        request.third(Result.success(listOf(song())))
        assertNull(h.state().snapshot)
        assertEquals(0, h.stores.getValue(platform).writes)
        assertFalse(h.state().loading)
    }

    @Test fun clearWhileNetworkWriteIsQueuedPreventsCacheResurrection() {
        val h = Harness()
        h.browser.open(platform)
        h.deferred = true
        h.requests.single().third(Result.success(listOf(song())))
        assertEquals(1, h.disk.size)
        h.browser.clearCachedResults()
        h.disk.removeFirst()()
        assertNull(h.state().snapshot)
        assertEquals(0, h.stores.getValue(platform).writes)
    }

    @Test fun cacheWriteFailureKeepsNetworkRowsAndReportsStorageSeparately() {
        val h = Harness()
        h.stores.getValue(platform).failWrite = true
        h.browser.open(platform)
        h.requests.single().third(Result.success(listOf(song())))
        assertTrue(h.state().storageError)
        assertNull(h.state().error)
        assertEquals(1, h.state().snapshot!!.songs.size)
        assertFalse(h.state().loading)
    }

    @Test fun damagedCacheDoesNotBlockNetworkAndDuplicateCallbackCannotOverwriteSuccess() {
        val h = Harness()
        h.stores.getValue(platform).failRead = true
        h.browser.open(platform)
        val result = h.requests.single().third
        result(Result.success(listOf(song())))
        result(Result.success(listOf(song("other"))))
        assertTrue(h.state().storageError)
        assertEquals("1", h.state().snapshot!!.songs.single().key.id)
        assertEquals(1, h.stores.getValue(platform).writes)
    }

    @Test fun requestCoalescingAndRefreshThrottleAreIndependentForEachPlatform() {
        val h = Harness()
        h.browser.open(platform); h.browser.refresh(platform)
        assertEquals(1, h.requests.size)
        h.requests.single().third(Result.success(listOf(song())))
        h.browser.refresh(platform)
        assertEquals("rate", h.state().error)
        assertTrue(h.state().retryAfterMs > 0)
        h.browser.open(Platform.QQ)
        assertEquals(2, h.requests.size)
        h.requests.last().third(Result.failure(LibraryPageFailure("risk")))
        assertEquals("risk", h.state(Platform.QQ).error)
        assertEquals(1, h.state().snapshot!!.songs.size)
    }

    @Test fun queueUsesWholeOrderedSnapshotAndRejectsOldRevisionAndGeneration() {
        val h = Harness()
        h.browser.open(platform)
        h.requests.single().third(Result.success(listOf(song("2"), song(), song("3"))))
        val revision = h.state().revision
        val queue = h.browser.queue(platform, 1, revision).getOrThrow()
        assertEquals(listOf("2", "1", "3"), queue.songs.map { it.key.id })
        assertEquals(1, queue.startIndex)
        assertEquals(1L, queue.accountGeneration)
        assertTrue(h.browser.queue(platform, -1, revision).isFailure)
        assertTrue(h.browser.queue(platform, 3, revision).isFailure)
        assertTrue(h.browser.queue(platform, 0, revision - 1).isFailure)
        h.identities[platform] = h.identities.getValue(platform)!!.copy(generation = 2)
        assertTrue(h.browser.queue(platform, 0, revision).isFailure)
    }

    @Test fun emptySuccessIsDistinctFromFailureAndCannotMakeAnEmptyQueue() {
        val h = Harness()
        h.browser.open(platform)
        h.requests.single().third(Result.success(emptyList()))
        assertNotNull(h.state().snapshot)
        assertTrue(h.state().snapshot!!.songs.isEmpty())
        assertNull(h.state().error)
        assertTrue(h.browser.queue(platform, expectedRevision = h.state().revision).isFailure)
    }

    @Test fun crossPlatformAndOversizedResultsAreRejectedWithoutOverwritingCache() {
        listOf(listOf(song(platform = Platform.QQ)), List(201) { song(it.toString()) }).forEach { invalid ->
            val h = Harness()
            h.browser.open(platform)
            h.requests.single().third(Result.success(invalid))
            assertEquals("protocol", h.state().error)
            assertNull(h.state().snapshot)
            assertEquals(0, h.stores.getValue(platform).writes)
        }
    }

    @Test fun explicitRefreshDuringCacheReadIsNotLostToFreshCache() {
        val h = Harness()
        h.deferred = true
        h.stores.getValue(platform).snapshot = RecommendationSnapshot(platform, listOf(song()), h.now)
        h.browser.open(platform)
        h.browser.refresh(platform)
        assertEquals(1, h.disk.size)
        h.disk.removeFirst()()
        assertEquals(1, h.requests.size)
        assertTrue(h.state().refreshing)
        assertEquals(LibrarySource.CACHE, h.state().source)
    }

    @Test fun ownerChangeDuringQueuedCacheReadDoesNotPublishFormerOwnerRows() {
        val h = Harness()
        h.deferred = true
        h.stores.getValue(platform).snapshot = RecommendationSnapshot(platform, listOf(song()), h.now)
        h.browser.open(platform)
        h.identities[platform] = LibraryCacheIdentity("b".repeat(64), 2, true)
        h.browser.accountChanged()
        h.disk.removeFirst()()
        assertNull(h.state().snapshot)
        assertTrue(h.requests.isEmpty())
        assertFalse(h.state().loading)
    }

    @Test fun cacheClearDuringQueuedReadCannotPublishErasedRows() {
        val h = Harness()
        h.deferred = true
        h.stores.getValue(platform).snapshot = RecommendationSnapshot(platform, listOf(song()), h.now)
        h.browser.open(platform)
        h.browser.clearCachedResults()
        h.stores.getValue(platform).clear()
        h.disk.removeFirst()()
        assertNull(h.state().snapshot)
        assertTrue(h.requests.isEmpty())
        assertFalse(h.state().loading)
    }
}
