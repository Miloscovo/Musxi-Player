package tech.musxi.core.recommendation

import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*

class RecommendationsEnabledTest {
    @get:Rule val temporary = TemporaryFolder()
    private val platform = Platform.NETEASE
    private fun song(id: String = "1", platform: Platform = this.platform) =
        SongSummary(MediaKey(platform, id), "Song $id", listOf("Artist"), "Album", 123_000)

    private class Store(private val events: MutableList<String>) : RecommendationStore {
        var snapshot: RecommendationSnapshot? = null
        var writes = 0
        override fun read(owner: String): RecommendationSnapshot? {
            events += "read:" + if (snapshot == null) "empty" else "filled"
            return snapshot
        }
        override fun write(owner: String, snapshot: RecommendationSnapshot) {
            events += "write"
            this.snapshot = snapshot; writes++
        }
        override fun clear() { events += "clear"; snapshot = null }
    }

    private class Harness(enabled: Boolean = true) {
        var supplier = enabled
        var now = Instant.parse("2026-11-11T03:00:00Z").toEpochMilli()
        var elapsed = 100_000L
        var deferred = false
        var failClear = false
        var clears = 0
        val events = mutableListOf<String>()
        val identityCalls = mutableListOf<Platform>()
        val accesses = mutableListOf<Platform>()
        val identities = Platform.entries.associateWith<Platform, LibraryCacheIdentity?> {
            LibraryCacheIdentity(it.name.first().lowercase().repeat(64), 1, true)
        }.toMutableMap()
        val stores = Platform.entries.associateWith { Store(events) }
        val requests = mutableListOf<Triple<Platform, () -> Boolean, (Result<List<SongSummary>>) -> Unit>>()
        val disk = ArrayDeque<() -> Unit>()
        val browser = RecommendationsBrowser({ target -> identities[target].also { identityCalls += target } },
            { target, _, action -> accesses += target; action(stores.getValue(target)) },
            RecommendationFetch { target, current, result -> requests += Triple(target, current, result) },
            { action -> if (deferred) disk.addLast(action) else action() }, { it() }, { now }, { elapsed },
            { supplier }, {
                clears++
                if (failClear) throw java.io.IOException("private disk path")
                stores.values.forEach { it.clear() }
            })
        fun state(platform: Platform = Platform.NETEASE) = browser.state.value.getValue(platform)
        fun drain() { while (disk.isNotEmpty()) disk.removeFirst()() }
        fun totalWrites() = stores.values.sumOf { it.writes }
    }

    private fun assertIdleDisabled(state: RecommendationState) {
        assertFalse(state.loading)
        assertFalse(state.refreshing)
        assertNull(state.snapshot)
        assertNull(state.error)
    }

    private fun assertDisabled(failure: Throwable?) {
        val reported = failure as? LibraryPageFailure
        assertNotNull("Expected a library failure", reported)
        assertEquals("recommendations_disabled", reported!!.category)
    }

    @Test fun coldStartDisabledClearsOnceAndBlocksEveryEntrypointWithoutTouchingAccounts() {
        val h = Harness(enabled = false)
        assertEquals(1, h.clears)
        assertTrue(h.identityCalls.isEmpty())
        assertTrue(h.accesses.isEmpty())
        assertTrue(h.requests.isEmpty())

        Platform.entries.forEach { h.browser.open(it) }
        Platform.entries.forEach { h.browser.refresh(it) }
        h.browser.accountChanged()
        h.browser.setEnabled(false)

        Platform.entries.forEach { target ->
            val state = h.state(target)
            assertIdleDisabled(state)
            assertEquals(0L, state.revision)
            assertDisabled(h.browser.queue(target, expectedRevision = state.revision).exceptionOrNull())
        }
        assertEquals(1, h.clears)
        assertTrue(h.identityCalls.isEmpty())
        assertTrue(h.accesses.isEmpty())
        assertTrue(h.requests.isEmpty())
        assertEquals(0, h.totalWrites())
    }

    @Test fun disablingWhileAllThreePlatformsAreInFlightDropsCallbacksAndSnapshots() {
        val h = Harness()
        Platform.entries.forEach { h.browser.open(it) }
        assertEquals(3, h.requests.size)
        Platform.entries.forEach { assertTrue(h.state(it).loading); assertTrue(h.state(it).refreshing) }
        val revisions = Platform.entries.associateWith { h.state(it).revision }

        h.browser.setEnabled(false)

        Platform.entries.forEach { target ->
            val state = h.state(target)
            assertIdleDisabled(state)
            assertFalse(state.storageError)
            assertEquals(revisions.getValue(target) + 1, state.revision)
        }
        assertEquals(1, h.clears)
        h.requests.forEach { assertFalse(it.second()) }

        h.requests[0].third(Result.success(listOf(song(platform = h.requests[0].first))))
        h.requests[1].third(Result.failure(LibraryPageFailure("risk")))
        h.requests[2].third(Result.success(listOf(song(platform = h.requests[2].first))))

        Platform.entries.forEach { target ->
            val state = h.state(target)
            assertIdleDisabled(state)
            assertDisabled(h.browser.queue(target, expectedRevision = state.revision).exceptionOrNull())
        }
        assertEquals(0, h.totalWrites())
    }

    @Test fun queuedWriteAndReadAreFencedByTheErasureThatDisableEnqueues() {
        val h = Harness()
        h.deferred = true
        h.browser.open(platform)
        assertEquals(1, h.disk.size)
        h.drain()
        h.requests.single().third(Result.success(listOf(song("old"))))
        assertEquals(1, h.disk.size)

        h.browser.setEnabled(false)
        h.browser.setEnabled(true)
        h.elapsed += RecommendationsPolicy.MIN_REQUEST_INTERVAL_MS
        h.browser.open(platform)
        // The pending network write, the erasure, then the new read share one disk lane.
        assertEquals(3, h.disk.size)

        h.drain()

        assertNull(h.state().snapshot)
        assertEquals(0, h.stores.getValue(platform).writes)
        assertFalse(h.events.contains("write"))
        assertNull(h.stores.getValue(platform).snapshot)
        assertEquals("Cleared storage must be what the next open reads", "read:empty", h.events.last())
        assertEquals(1, h.clears)
        assertEquals(2, h.requests.size)
        assertTrue(h.state().loading)

        h.requests.last().third(Result.success(listOf(song("new"))))
        assertEquals(1, h.disk.size)
        h.drain()

        assertEquals(LibrarySource.NETWORK, h.state().source)
        assertEquals("new", h.state().snapshot!!.songs.single().key.id)
        assertEquals(1, h.stores.getValue(platform).writes)
        h.browser.queue(platform, expectedRevision = h.state().revision).getOrThrow()
    }

    @Test fun disablingRemovesLoadedSnapshotsOnAllPlatformsWhileOneIsRefreshing() {
        val h = Harness()
        Platform.entries.forEach { target ->
            h.browser.open(target)
            h.requests.last().third(Result.success(listOf(song(platform = target))))
            assertNotNull(h.state(target).snapshot)
        }
        h.elapsed += RecommendationsPolicy.MIN_REQUEST_INTERVAL_MS
        h.browser.refresh(platform)
        val refreshing = h.requests.last()
        val revisions = Platform.entries.associateWith { h.state(it).revision }
        val writes = h.totalWrites()

        h.browser.setEnabled(false)
        refreshing.third(Result.success(listOf(song("late"))))

        Platform.entries.forEach { target ->
            assertIdleDisabled(h.state(target))
            assertNull(h.stores.getValue(target).snapshot)
            assertTrue(h.state(target).revision > revisions.getValue(target))
            assertDisabled(h.browser.queue(target, expectedRevision = revisions.getValue(target)).exceptionOrNull())
        }
        assertEquals(writes, h.totalWrites())
    }

    @Test fun disabledQueuedReadCannotPublishCachedSnapshotAfterReEnabling() {
        val h = Harness()
        h.stores.getValue(platform).snapshot = RecommendationSnapshot(platform, listOf(song("old")), h.now)
        h.deferred = true
        h.browser.open(platform)
        h.browser.setEnabled(false)
        h.browser.setEnabled(true)
        h.browser.open(platform)

        h.drain()

        assertEquals(1, h.accesses.size)
        assertEquals("read:empty", h.events.last())
        assertNull(h.state().snapshot)
        assertEquals(1, h.requests.size)
    }

    @Test fun reEnablingNeverFetchesByItselfAndKeepsTheRequestThrottle() {
        val h = Harness()
        h.browser.open(platform)
        h.requests.single().third(Result.success(listOf(song())))
        assertEquals(1, h.stores.getValue(platform).writes)

        h.browser.setEnabled(false)
        h.browser.setEnabled(true)

        assertEquals(1, h.requests.size)
        assertEquals(1, h.clears)
        assertNull(h.state().snapshot)

        h.browser.open(platform)
        assertEquals(1, h.requests.size)
        assertEquals("rate", h.state().error)
        assertTrue(h.state().retryAfterMs > 0)
        assertFalse(h.state().loading)

        h.elapsed += RecommendationsPolicy.MIN_REQUEST_INTERVAL_MS
        h.browser.refresh(platform)
        assertEquals(2, h.requests.size)
        h.requests.last().third(Result.success(listOf(song("fresh"))))
        assertEquals("fresh", h.state().snapshot!!.songs.single().key.id)
    }

    @Test fun supplierFalseBlocksBeforeTheSettingsNotificationArrives() {
        val h = Harness()
        h.browser.open(platform)
        val request = h.requests.single()
        val revision = h.state().revision

        h.supplier = false

        assertFalse(request.second())
        h.browser.open(platform)
        h.browser.refresh(platform)
        h.browser.accountChanged()
        assertEquals(1, h.requests.size)
        assertEquals(revision, h.state().revision)
        assertDisabled(h.browser.queue(platform, expectedRevision = revision).exceptionOrNull())

        request.third(Result.success(listOf(song("late"))))
        assertNull(h.state().snapshot)
        assertNull(h.state().error)
        assertEquals(0, h.stores.getValue(platform).writes)
    }

    @Test fun coldStartDisabledErasesTheRealPlatformFilesAndStaysOffline() {
        val owner = "a".repeat(64)
        val dirs = Platform.entries.associateWith { temporary.newFolder(it.name) }
        val stores = Platform.entries.associateWith { FileRecommendationStore(dirs.getValue(it), it) }
        val at = Instant.parse("2026-11-11T02:00:00Z").toEpochMilli()
        Platform.entries.forEach { target ->
            stores.getValue(target).write(owner, RecommendationSnapshot(target, listOf(song(platform = target)), at))
            assertTrue(File(dirs.getValue(target), "daily.bin").exists())
        }

        val requests = mutableListOf<Platform>()
        val browser = RecommendationsBrowser({ LibraryCacheIdentity(owner, 1, true) },
            { target, _, action -> action(stores.getValue(target)) },
            RecommendationFetch { target, _, _ -> requests += target },
            { it() }, { it() }, { at }, { 0L }, { false }, { stores.values.forEach { it.clear() } })

        Platform.entries.forEach { target ->
            assertNull(stores.getValue(target).read(owner))
            assertFalse(File(dirs.getValue(target), "daily.bin").exists())
        }
        Platform.entries.forEach { target ->
            browser.open(target); browser.refresh(target)
            val state = browser.state.value.getValue(target)
            assertFalse(state.loading)
            assertFalse(state.refreshing)
            assertNull(state.snapshot)
            assertDisabled(browser.queue(target, expectedRevision = state.revision).exceptionOrNull())
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun failedErasureOnlyReportsStorageTroubleAndLeavesTheDisabledStateClean() {
        val h = Harness()
        h.browser.open(platform)
        h.requests.single().third(Result.success(listOf(song())))
        assertNotNull(h.state().snapshot)

        h.failClear = true
        h.browser.setEnabled(false)

        Platform.entries.forEach { target ->
            val state = h.state(target)
            assertTrue(state.storageError)
            assertNull(state.error)
            assertNull(state.snapshot)
            assertFalse(state.loading)
            assertFalse(state.refreshing)
            assertDisabled(h.browser.queue(target, expectedRevision = state.revision).exceptionOrNull())
        }
    }

    @Test fun completedDisableCannotBeFollowedByAnAlreadyValidatedQueueSubmission() {
        val h = Harness()
        h.browser.open(platform)
        h.requests.single().third(Result.success(listOf(song())))
        val revision = h.state().revision
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disabling = CountDownLatch(1)
        val disabled = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val playing = workers.submit<Result<Unit>> {
                h.browser.submitQueue(platform, expectedRevision = revision) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val setting = workers.submit {
                disabling.countDown()
                h.browser.setEnabled(false)
                disabled.countDown()
            }
            assertTrue(disabling.await(5, TimeUnit.SECONDS))
            // A successful settings command cannot outrun the queue it already validated.
            assertFalse(disabled.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(playing.get(5, TimeUnit.SECONDS).isSuccess)
            setting.get(5, TimeUnit.SECONDS)
            var submissions = 0
            val rejected = h.browser.submitQueue(platform, expectedRevision = revision) { submissions++ }
            assertDisabled(rejected.exceptionOrNull())
            assertEquals(0, submissions)
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
}
