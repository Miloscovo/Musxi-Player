package tech.musxi.core.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform

/**
 * Display-order behaviour of [PlaylistCatalog] driven by an explicit [LibraryBrowser] state,
 * with immediate fake IO and an in-memory order file. No Flow collector, network or disk.
 */
class PlaylistCatalogTest {
    private val ownerA = LibraryCacheIdentity("a".repeat(64), 1, true)
    private val ownerB = LibraryCacheIdentity("b".repeat(64), 2, true)

    @Test fun serverOrderIsDefaultAndLikedSongsStayFirst() {
        val f = Fixture()
        f.open(page(listOf(liked("9"), row("2"), row("1")), null))
        assertEquals(listOf("9", "2", "1"), ids(f))
        assertFalse(f.catalog.state.value.customOrder)
        assertTrue(f.catalog.state.value.complete)
        assertTrue(f.catalog.state.value.nextOffset == null)
    }

    @Test fun customOrderAppliesToLoadedRowsAndSurvivesLoadingMore() {
        val f = Fixture()
        f.open(page(listOf(liked("9"), row("1"), row("2")), 3))
        assertEquals(listOf("9", "1", "2"), ids(f))

        assertTrue(f.move("2", "1").isSuccess)
        assertEquals(listOf("9", "2", "1"), ids(f))
        assertTrue(f.catalog.state.value.customOrder)
        assertEquals(listOf("2", "1"), saved(f, ownerA))

        f.more(page(listOf(row("3")), null))
        // Ranked rows keep the saved order; an unranked row appended by paging stays after them.
        assertEquals(listOf("9", "2", "1", "3"), ids(f))
        assertTrue(f.catalog.state.value.complete)
    }

    @Test fun rawOffsetsAreIndependentOfDisplayOrder() {
        val f = Fixture()
        f.open(page(listOf(row("1"), row("2"), row("3")), 3))
        assertTrue(f.move("3", "1").isSuccess)
        assertEquals(listOf("3", "1", "2"), ids(f))

        f.more(page(listOf(row("4")), null))
        // Pagination asked the server for the raw continuation, never a display index.
        assertEquals(listOf(0, 3), f.playlistRequests)
        assertEquals(listOf("3", "1", "2", "4"), ids(f))
    }

    @Test fun refreshKeepsTheSavedOrderAndTheRawPageChain() {
        val f = Fixture()
        f.open(page(listOf(row("1"), row("2")), 2))
        assertTrue(f.move("2", "1").isSuccess)
        f.more(page(listOf(row("3")), null))
        assertEquals(listOf("2", "1", "3"), ids(f))

        f.advance()
        f.catalog.refresh()
        f.sync()
        f.respond(page(listOf(row("1"), row("2"), row("4")), null))
        assertEquals(listOf(0, 2, 0), f.playlistRequests)
        assertEquals(listOf("2", "1", "4"), ids(f))
        assertTrue(f.catalog.state.value.customOrder)
        assertEquals(listOf("2", "1"), saved(f, ownerA))
    }

    @Test fun sameAccountReopenAndRefreshNeverPublishATemporaryEmptyOrMissingLikedRow() {
        for (platform in Platform.entries) {
            val f = Fixture(platform)
            val rows = listOf(liked("9"), row("1")).map { it.copy(key = MediaKey(platform, it.key.id)) }
            val first = page(rows, null)
            f.open(first)
            val observed = mutableListOf<PlaylistCatalogState>()
            val scope = CoroutineScope(Dispatchers.Unconfined)
            scope.launch { f.catalog.state.collect { observed += it } }
            try {
                // A menu/detail navigation reopens the home catalog. Hold cache IO to expose
                // the intermediate state, then also check every synchronous Flow emission.
                f.catalog.cancel()
                f.catalog.open()
                assertEquals(platform.name, rows, f.catalog.state.value.items)
                f.sync()
                assertEquals(rows, f.catalog.state.value.items)
                f.catalog.refresh()
                assertEquals(rows, f.catalog.state.value.items)
                f.sync()
                f.respond(first)
                assertTrue("$platform published a blank home: $observed", observed.all { it.items == rows })
            } finally { scope.cancel() }
        }
    }

    @Test fun refreshFailureKeepsVisibleRowsButAConfirmedEmptyPrefixReplacesThem() {
        val f = Fixture()
        val first = page(listOf(liked("9"), row("1")), null)
        f.open(first)
        f.advance()
        f.catalog.refresh(); f.sync()
        f.fail("network")
        assertEquals(first.items, f.catalog.state.value.items)
        assertEquals("network", f.catalog.state.value.error)
        assertFalse(f.catalog.state.value.loading)
        f.catalog.refresh(); f.sync()
        f.respond(page(emptyList(), null))
        assertTrue(f.catalog.state.value.items.isEmpty())
        assertTrue(f.catalog.state.value.complete)
        assertNull(f.catalog.state.value.error)
    }

    @Test fun reopenDisplaySnapshotCannotProveCompletionOrAuthorizeOrderingAndNeverCrossesAccounts() {
        val f = Fixture()
        val first = page(listOf(liked("9"), row("1"), row("2")), 3)
        f.open(first)
        f.more(page(listOf(row("3")), null))
        f.catalog.open(false) // Pause before the asynchronous first-page cache read.
        assertEquals(first.items + row("3"), f.catalog.state.value.items)
        assertFalse("display rows are not a rebuilt page chain", f.catalog.state.value.complete)
        assertNull("display-only rows must not start an automatic page-0 request", f.catalog.state.value.nextOffset)
        f.catalog.loadMore()
        assertEquals(listOf(0, 3), f.playlistRequests)
        var moved: Result<Unit>? = null
        f.catalog.moveToStart(key("2"), f.catalog.state.value.revision) { moved = it }
        assertEquals("invalid_request", failure(requireNotNull(moved)))
        assertEquals(0, f.orders.writeAttempts)
        f.identity = ownerB
        f.catalog.accountChanged()
        assertTrue("the old owner's display snapshot must disappear immediately", f.catalog.state.value.items.isEmpty())
        f.sync()
        assertTrue(f.catalog.state.value.items.isEmpty())
        f.respond(page(listOf(row("3")), null))
        assertEquals(listOf("3"), ids(f))
    }

    @Test fun explicitCacheCleanupAndOpenCatalogInvalidationDiscardAnyReopenDisplaySnapshot() {
        for (invalidate in listOf(false, true)) {
            val f = Fixture()
            f.open(page(listOf(liked("9"), row("1")), null))
            f.catalog.open(false)
            assertTrue(f.catalog.state.value.items.isNotEmpty())
            if (invalidate) f.catalog.invalidate(ownerA) else f.catalog.clearCachedPages()
            assertTrue("explicit invalidation=$invalidate must clear visible rows", f.catalog.state.value.items.isEmpty())
        }
    }

    @Test fun closedCatalogInvalidationKeepsDisplayUntilFreshPrefixWithoutAuthorizingPagingOrOrdering() {
        for (platform in Platform.entries) {
            val f = Fixture(platform)
            fun rows(vararg ids: String) = ids.map { row(it).copy(key = MediaKey(platform, it)) }
            val original = rows("1", "2", "3")
            f.open(page(original.take(2), 2))
            f.more(page(original.drop(2), null))
            f.catalog.cancel()
            val observed = mutableListOf<PlaylistCatalogState>()
            val scope = CoroutineScope(Dispatchers.Unconfined)
            scope.launch { f.catalog.state.collect { observed += it } }
            try {
                repeat(2) { f.catalog.invalidate(ownerA) }
                assertEquals(original, f.catalog.state.value.items)
                assertFalse(f.catalog.state.value.complete)
                assertFalse(f.catalog.state.value.loading)
                assertNull(f.catalog.state.value.nextOffset)
                f.catalog.loadMore()
                var moved: Result<Unit>? = null
                f.catalog.moveToStart(MediaKey(platform, "2"), f.catalog.state.value.revision) { moved = it }
                assertEquals("invalid_request", failure(requireNotNull(moved)))
                assertEquals(0, f.orders.writeAttempts)
                assertEquals(listOf(0, 2), f.playlistRequests)

                f.catalog.open()
                f.sync() // Stale disk prefix must not replace the full display snapshot.
                assertTrue(f.catalog.state.value.loading)
                assertTrue("$platform published a blank catalog: $observed", observed.all { it.items == original })
                f.fail("network")
                assertEquals(original, f.catalog.state.value.items)
                assertEquals("network", f.catalog.state.value.error)
                assertFalse(f.catalog.state.value.complete)
                f.catalog.open()
                f.sync()
                val fresh = rows("4")
                f.respond(page(fresh, null))
                assertEquals(fresh, f.catalog.state.value.items)
                assertTrue(f.catalog.state.value.complete)
            } finally { scope.cancel() }
        }
    }

    @Test fun closedInvalidationSnapshotIsClearedByCacheCleanupAndCannotCrossAccounts() {
        for (switchAccount in listOf(false, true)) {
            val f = Fixture()
            f.open(page(listOf(row("1")), null))
            f.catalog.cancel()
            f.catalog.invalidate(ownerA)
            assertEquals(listOf("1"), ids(f))
            if (switchAccount) {
                // Invalidation can precede the account collector's notification.
                f.identity = ownerB
                f.catalog.invalidate(ownerB)
            } else f.catalog.clearCachedPages()
            assertTrue(f.catalog.state.value.items.isEmpty())
            f.catalog.open()
            assertTrue(f.catalog.state.value.items.isEmpty())
        }
    }

    @Test fun partialPagesNeverDropSavedIdsThatAreStillUnloaded() {
        val f = Fixture()
        // "99" was ordered earlier but is not part of the first page.
        f.orders.files[ownerA.owner] = listOf(key("99"), key("2"))
        f.open(page(listOf(row("1"), row("2")), 2))
        assertEquals(listOf("2", "1"), ids(f))
        assertFalse(f.catalog.state.value.complete)

        // Moving to the end would need the complete listing, so it is refused instead of guessed.
        assertEquals("incomplete", failure(f.move("1", null)))

        assertTrue(f.move("1", "2").isSuccess)
        // The unloaded id survives; it is neither deleted nor reordered by a partial page.
        assertEquals(listOf("99", "1", "2"), saved(f, ownerA))
        assertEquals(listOf("1", "2"), ids(f))
    }

    @Test fun likedSongsStayFixedAtTheFrontAndAreNeverMovable() {
        val f = Fixture()
        // Legacy order data that mentions the liked list must still not lift it into the middle.
        f.orders.files[ownerA.owner] = listOf(key("3"), key("9"), key("1"))
        f.open(page(listOf(liked("9"), row("1"), row("3")), null))
        assertEquals(listOf("9", "3", "1"), ids(f))
        assertEquals(0, f.orders.writeAttempts)

        assertEquals("invalid_request", failure(f.move("9", "1")))
        assertEquals("invalid_request", failure(f.move("1", "9")))
        assertEquals("invalid_request", failure(f.moveToStart("9")))

        assertTrue(f.moveToStart("1").isSuccess)
        assertEquals(listOf("9", "1", "3"), ids(f))
        assertEquals(listOf("1", "3", "9"), saved(f, ownerA))
    }

    @Test fun staleRevisionIsRejectedBeforeAnyStorageWrite() {
        val f = Fixture()
        f.open(page(listOf(row("1"), row("2")), null))
        val stale = f.catalog.state.value.revision

        assertTrue(f.move("2", "1").isSuccess)
        val attempts = f.orders.writeAttempts
        assertTrue(f.catalog.state.value.revision != stale)

        assertEquals("conflict", failure(f.move("1", "2", stale)))
        assertEquals(attempts, f.orders.writeAttempts)
        assertEquals(listOf("2", "1"), saved(f, ownerA))
    }

    @Test fun failedWriteLeavesTheInMemoryOrderUntouched() {
        val f = Fixture()
        f.open(page(listOf(row("1"), row("2")), null))
        f.orders.failWrites = true

        assertEquals("storage", failure(f.move("2", "1")))
        assertEquals(listOf("1", "2"), ids(f))
        assertFalse(f.catalog.state.value.customOrder)
        assertTrue(f.catalog.state.value.storageError)
        assertTrue(f.orders.files.isEmpty())

        f.orders.failWrites = false
        assertTrue(f.move("2", "1").isSuccess)
        assertEquals(listOf("2", "1"), ids(f))
        assertFalse(f.catalog.state.value.storageError)
    }

    @Test fun resetOrderReturnsToTheServerOrder() {
        val f = Fixture()
        f.open(page(listOf(liked("9"), row("1"), row("2")), null))
        assertTrue(f.move("2", "1").isSuccess)
        assertEquals(listOf("9", "2", "1"), ids(f))

        var reset: Result<Unit>? = null
        f.catalog.resetOrder(f.catalog.state.value.revision) { reset = it }
        f.sync()
        assertTrue(reset!!.isSuccess)
        assertEquals(listOf("9", "1", "2"), ids(f))
        assertFalse(f.catalog.state.value.customOrder)
        assertEquals(emptyList<String>(), saved(f, ownerA))
    }

    @Test fun switchingAccountsDropsTheLateWriteAndKeepsOrdersSeparate() {
        val f = Fixture()
        f.orders.files[ownerA.owner] = listOf(key("2"), key("1"))
        f.orders.files[ownerB.owner] = listOf(key("5"))
        f.open(page(listOf(row("1"), row("2"), row("3")), null))
        assertEquals(listOf("2", "1", "3"), ids(f))

        f.orders.deferWrites = true
        var moved: Result<Unit>? = null
        f.catalog.move(key("3"), key("1"), f.catalog.state.value.revision) { moved = it }
        assertEquals(1, f.orders.deferredCount())
        assertNull(moved)

        f.identity = ownerB
        f.catalog.accountChanged()
        f.sync()
        f.respond(page(listOf(row("5"), row("6")), null))
        assertEquals(ownerB, f.catalog.state.value.identity)
        assertEquals(listOf("5", "6"), ids(f))

        // The stale storage result arrives after the switch and must not be adopted.
        f.orders.completeDeferred()
        f.sync()
        assertNotNull(moved)
        assertEquals("account_changed", (moved!!.exceptionOrNull() as LibraryPageFailure).category)
        assertEquals(listOf("2", "1"), saved(f, ownerA))
        assertEquals(listOf("5"), saved(f, ownerB))
        assertEquals(listOf("5", "6"), ids(f))
    }

    @Test fun accountSwitchReloadsEachOwnersOwnOrder() {
        val f = Fixture()
        f.orders.files[ownerA.owner] = listOf(key("2"))
        f.orders.files[ownerB.owner] = listOf(key("4"))
        f.open(page(listOf(row("1"), row("2")), null))
        assertEquals(listOf("2", "1"), ids(f))

        f.identity = ownerB
        f.catalog.accountChanged(); f.sync()
        f.respond(page(listOf(row("4"), row("3")), null))
        assertEquals(listOf("4", "3"), ids(f))

        f.identity = ownerA
        f.catalog.accountChanged(); f.sync()
        f.respond(page(listOf(row("1"), row("2")), null))
        assertEquals(listOf("2", "1"), ids(f))
    }

    @Test fun cacheCleanupKeepsDurableOrderAndNewInstanceLoadsIt() {
        val f = Fixture()
        f.open(page(listOf(row("1"), row("2")), null))
        assertTrue(f.move("2", "1").isSuccess)
        f.catalog.clearCachedPages(); f.sync()
        assertTrue(f.catalog.state.value.items.isEmpty())
        assertEquals(listOf("2", "1"), saved(f, ownerA))
        f.advance()
        f.catalog.open(); f.sync(); f.respond(page(listOf(row("1"), row("2")), null))
        assertEquals(listOf("2", "1"), ids(f))
    }

    @Test fun previousAccountsVisibleBrowserPageCannotBeAcceptedDuringAccountDelivery() {
        val f = Fixture()
        f.open(page(listOf(row("1")), null))
        f.identity = ownerB
        // Browser's account collector has not run yet; its visible state still belongs to A.
        f.sync()
        assertEquals(ownerB, f.catalog.state.value.identity)
        assertTrue(f.catalog.state.value.items.isEmpty())
    }

    @Test fun serverEventsQueueBehindAnAcceptedMoveAndAreNeverDroppedAsBusy() {
        val f = Fixture()
        f.open(page(listOf(row("1"), row("2")), null))
        f.orders.deferWrites = true
        var move: Result<Unit>? = null
        f.catalog.moveToStart(key("2"), f.catalog.state.value.revision) { move = it }
        assertTrue(f.catalog.state.value.savingOrder)
        var created: Result<Unit>? = null
        var deleted: Result<Unit>? = null
        f.catalog.created(key("3")) { created = it }
        f.catalog.deleted(key("1")) { deleted = it }
        assertNull(created); assertNull(deleted)
        f.orders.completeDeferred(); f.sync()
        assertTrue(move!!.isSuccess)
        assertTrue(created!!.isSuccess)
        assertTrue(deleted!!.isSuccess)
        assertEquals(listOf("3", "2"), saved(f, ownerA))
        assertFalse(f.catalog.state.value.savingOrder)
    }

    @Test fun refreshDuringDeferredMoveRejectsItsCapturedRevision() {
        val f = Fixture()
        f.open(page(listOf(row("1"), row("2")), null))
        f.orders.deferWrites = true
        var moved: Result<Unit>? = null
        f.catalog.moveToStart(key("2"), f.catalog.state.value.revision) { moved = it }
        f.advance(); f.catalog.refresh(); f.sync()
        f.respond(page(listOf(row("1"), row("2"), row("3")), null))
        f.orders.completeDeferred(); f.sync()
        assertEquals("conflict", failure(moved!!))
        assertEquals(listOf("1", "2", "3"), ids(f))
        assertFalse(f.catalog.state.value.customOrder)
    }

    @Test fun malformedTotalsAndPageContinuationsAreRefused() {
        val cases = listOf(
            Page(listOf(row("1")), 2, null),
            Page(listOf(row("1")), null, 2),
            Page(listOf(row("1")), 1, 1),
            Page(listOf(row("1")), null, 20_001),
        )
        for (bad in cases) {
            val f = Fixture()
            f.open(bad)
            assertEquals("protocol", f.catalog.state.value.error)
            assertFalse(f.catalog.state.value.complete)
            assertTrue(f.catalog.state.value.items.isEmpty())
        }
    }

    @Test fun continuationWithADuplicateIdOrChangedTotalCannotCompleteTheCatalog() {
        for (last in listOf(Page(listOf(row("1")), null, 2), Page(listOf(row("2")), 2, 3))) {
            val f = Fixture()
            f.open(Page(listOf(row("1")), 1, 2))
            f.more(last)
            assertEquals("protocol", f.catalog.state.value.error)
            assertEquals(listOf("1"), ids(f))
            assertFalse(f.catalog.state.value.complete)
        }
    }

    @Test fun kugouShortPageKeepsItsPageNumberContinuationUntilAnEmptyPageConfirmsCompletion() {
        val f = Fixture(platform = Platform.KUGOU, stride = 30)
        val rows = (1..4).map { row(it.toString()).copy(key = MediaKey(Platform.KUGOU, it.toString())) }
        f.open(Page(rows, 30, null))
        assertNull(f.catalog.state.value.error)
        assertEquals(listOf("1", "2", "3", "4"), ids(f))
        assertEquals(30, f.catalog.state.value.nextOffset)
        assertFalse(f.catalog.state.value.complete)
        f.more(Page(emptyList(), null, null))
        assertEquals(listOf(0, 30), f.playlistRequests)
        assertNull(f.catalog.state.value.error)
        assertTrue(f.catalog.state.value.complete)
        assertEquals(listOf("1", "2", "3", "4"), ids(f))
    }

    @Test fun pageNumberContinuationStillRejectsWrongStrideAndDuplicateIds() {
        val one = row("1").copy(key = MediaKey(Platform.KUGOU, "1"))
        val f = Fixture(platform = Platform.KUGOU, stride = 30)
        f.open(Page(listOf(one), 1, null))
        assertEquals("protocol", f.catalog.state.value.error)
        assertTrue(f.catalog.state.value.items.isEmpty())
        val duplicate = Fixture(platform = Platform.KUGOU, stride = 30)
        duplicate.open(Page(listOf(one), 30, null))
        duplicate.more(Page(listOf(one), null, null))
        assertEquals("protocol", duplicate.catalog.state.value.error)
        assertFalse(duplicate.catalog.state.value.complete)
    }

    @Test fun immediateRefreshRefetchesTheEvictedTerminalPageDespiteRecentSuccess() {
        val f = Fixture(platform = Platform.KUGOU, stride = 30)
        val first = Page(listOf(row("1").copy(key = MediaKey(Platform.KUGOU, "1"))), 30, null)
        f.open(first)
        f.more(Page(emptyList(), null, null))
        assertTrue(f.catalog.state.value.complete)
        repeat(3) {
            // Stay inside the 30-second success window. Production disk storage discards
            // all continuation pages when a fresh page 0 is committed.
            f.catalog.refresh(); f.sync(); f.respond(first)
            assertEquals(30, f.catalog.state.value.nextOffset)
            f.catalog.loadMore(); f.sync()
            assertTrue("A missing cached continuation must still fetch", f.catalog.state.value.loading)
            f.respond(Page(emptyList(), null, null))
            assertTrue(f.catalog.state.value.complete)
            assertFalse(f.catalog.state.value.loading)
            repeat(3) { f.catalog.loadMore(); f.sync() }
        }
        assertEquals(listOf(0, 30, 0, 30, 0, 30, 0, 30), f.playlistRequests)
    }

    private fun failure(result: Result<Unit>) =
        (result.exceptionOrNull() as? LibraryPageFailure)?.category

    private fun ids(f: Fixture) = f.catalog.state.value.items.map { it.key.id }
    private fun saved(f: Fixture, identity: LibraryCacheIdentity) =
        f.orders.files.getValue(identity.owner).map { it.id }
    private fun key(id: String) = MediaKey(Platform.NETEASE, id)
    private fun row(id: String) = PlaylistSummary(key(id), "P$id", null, 1, PlaylistKind.CREATED)
    private fun liked(id: String) = PlaylistSummary(key(id), "我喜欢的音乐", null, 1, PlaylistKind.LIKED)
    private fun page(rows: List<PlaylistSummary>, next: Int?) = Page(rows, next, null)

    private inner class Fixture(private val platform: Platform = Platform.NETEASE, stride: Int? = null) {
        var identity: LibraryCacheIdentity? = ownerA
        var now = 1_000L
        val cache = Cache()
        private val io = ArrayDeque<() -> Unit>()
        private val delivery = ArrayDeque<() -> Unit>()
        val playlistRequests = mutableListOf<Int>()
        private val pending = ArrayDeque<(Result<Page<PlaylistSummary>>) -> Unit>()
        private val browser = LibraryBrowser({ identity }, { expected, action ->
            if (!expected.sameAttempt(identity)) throw LibraryPageFailure("account_changed")
            action(cache)
        }, object : LibraryPageFetch {
            override fun playlists(offset: Int, result: (Result<Page<PlaylistSummary>>) -> Unit) {
                playlistRequests += offset
                pending += result
            }
            override fun tracks(playlist: MediaKey, offset: Int,
                result: (Result<Page<SongSummary>>) -> Unit) = throw AssertionError("unused")
        }, { io += it }, { delivery += it }, now = { this@Fixture.now }, platform = platform)
        val orders = Orders { identity }
        val catalog = PlaylistCatalog(platform, browser, { identity }, orders.access, continuationStride = stride)

        fun open(first: Page<PlaylistSummary>) { catalog.open(); sync(); respond(first) }
        fun more(next: Page<PlaylistSummary>) { catalog.loadMore(); sync(); respond(next) }
        fun advance() = run { now += 60_000L }
        fun move(id: String, before: String?, revision: Long = catalog.state.value.revision) =
            mutable(id, before, revision)

        private fun mutable(id: String, before: String?, revision: Long): Result<Unit> {
            var result: Result<Unit>? = null
            catalog.move(key(id), before?.let(::key), revision) { result = it }
            sync()
            return requireNotNull(result) { "move must complete synchronously with immediate IO" }
        }

        fun moveToStart(id: String, revision: Long = catalog.state.value.revision): Result<Unit> {
            var result: Result<Unit>? = null
            catalog.moveToStart(key(id), revision) { result = it }
            sync()
            return requireNotNull(result)
        }

        fun respond(value: Page<PlaylistSummary>) {
            assertTrue("expected a pending playlist request", pending.isNotEmpty())
            pending.removeFirst().invoke(Result.success(value))
            sync()
        }

        fun fail(category: String) {
            assertTrue("expected a pending playlist request", pending.isNotEmpty())
            pending.removeFirst().invoke(Result.failure(LibraryPageFailure(category)))
            sync()
        }

        fun sync() {
            while (io.isNotEmpty() || delivery.isNotEmpty()) {
                while (io.isNotEmpty()) io.removeFirst().invoke()
                while (delivery.isNotEmpty()) delivery.removeFirst().invoke()
            }
            catalog.accept(browser.state.value)
        }
    }

    private class Cache : LibraryPageStore {
        private val playlists = mutableMapOf<Pair<String, Int>, SavedLibraryPage<PlaylistSummary>>()
        override fun readPlaylists(owner: String, offset: Int) = playlists[owner to offset]
        override fun writePlaylists(owner: String, offset: Int, value: SavedLibraryPage<PlaylistSummary>) {
            // Match FileLibraryPageStore: a new prefix invalidates the cached tail.
            if (offset == 0) playlists.keys.removeAll { it.first == owner }
            playlists[owner to offset] = value
        }
        override fun readTracks(owner: String, playlist: MediaKey, offset: Int): SavedLibraryPage<SongSummary>? = null
        override fun writeTracks(owner: String, playlist: MediaKey, offset: Int,
            value: SavedLibraryPage<SongSummary>) = Unit
        override fun clear() = playlists.clear()
    }

    private class Orders(private val identity: () -> LibraryCacheIdentity?) {
        val files = mutableMapOf<String, List<MediaKey>>()
        var failWrites = false
        var deferWrites = false
        var writeAttempts = 0
        private val deferred = ArrayDeque<() -> Unit>()

        val access = PlaylistOrderAccess { expected, change, result ->
            if (change == null) {
                result(Result.success(files[expected.owner] ?: emptyList()))
                return@PlaylistOrderAccess
            }
            val mutate = change
            writeAttempts++
            val task = {
                // Storage rechecks ownership before it writes, then reports on the UI executor.
                if (expected != identity()) {
                    result(Result.failure(LibraryPageFailure("account_changed")))
                } else {
                    val saved = files[expected.owner] ?: emptyList()
                    runCatching { mutate(saved).toList() }.fold({ next ->
                        if (failWrites) {
                            result(Result.failure(LibraryPageFailure("storage")))
                        } else {
                            files[expected.owner] = next
                            result(Result.success(next))
                        }
                    }, { error ->
                        result(Result.failure(error as? LibraryPageFailure ?: LibraryPageFailure("storage")))
                    })
                }
            }
            if (deferWrites) deferred += task else task()
        }

        fun deferredCount() = deferred.size
        fun completeDeferred() = run { while (deferred.isNotEmpty()) deferred.removeFirst().invoke() }
    }
}
