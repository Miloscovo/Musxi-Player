package tech.musxi.core.library

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform

class PlaylistCacheFillerTest {
    private class Store : LibraryPageStore {
        val pages = linkedMapOf<Int, SavedLibraryPage<SongSummary>>()
        var writes = 0
        var fits = true
        var fails = false
        override fun readPlaylists(owner: String, offset: Int): SavedLibraryPage<PlaylistSummary>? = null
        override fun writePlaylists(owner: String, offset: Int, value: SavedLibraryPage<PlaylistSummary>) = Unit
        override fun readTracks(owner: String, playlist: MediaKey, offset: Int) = pages[offset]
        override fun readTrackPages(owner: String, playlist: MediaKey): List<CachedTrackPage> {
            if (fails) throw java.io.IOException("private path")
            return pages.map { CachedTrackPage(it.key, it.value) }
        }
        override fun writeTracks(owner: String, playlist: MediaKey, offset: Int, value: SavedLibraryPage<SongSummary>) {
            if (offset == 0) pages.clear()
            writes++; pages[offset] = value
        }
        override fun writeTracksRetainingPages(owner: String, playlist: MediaKey, offset: Int,
            value: SavedLibraryPage<SongSummary>): Boolean {
            if (!fits) return false
            writeTracks(owner, playlist, offset, value)
            return true
        }
        override fun clear() = pages.clear()
    }

    private class Fixture(val platform: Platform = Platform.NETEASE) {
        val playlist = MediaKey(platform, "9")
        val size = if (platform == Platform.NETEASE) 50 else 30
        var identity: LibraryCacheIdentity? = LibraryCacheIdentity("a".repeat(64), 1, true)
        val store = Store()
        val io = ArrayDeque<() -> Unit>()
        val delivery = ArrayDeque<() -> Unit>()
        val calls = ArrayDeque<Pair<Int, (Result<Page<SongSummary>>) -> Unit>>()
        val browser = LibraryBrowser({ identity }, { expected, work ->
            if (expected != identity) throw LibraryPageFailure("account_changed")
            work(store)
        }, object : LibraryPageFetch {
            override fun playlists(offset: Int, result: (Result<Page<PlaylistSummary>>) -> Unit) = Unit
            override fun tracks(playlist: MediaKey, offset: Int, result: (Result<Page<SongSummary>>) -> Unit) {
                calls.add(offset to result)
            }
        }, { io.add(it) }, { delivery.add(it) }, now = { 1000 }, platform = platform)
        fun page(offset: Int, total: Int): Page<SongSummary> {
            val end = minOf(offset + size, total)
            return Page((offset until end).map {
                SongSummary(MediaKey(platform, "${it + 1}"), "Song $it", listOf("Artist"), "Album", 100)
            }, end.takeIf { it < total }, total)
        }
        fun cached(offset: Int, total: Int) { store.pages[offset] = SavedLibraryPage(page(offset, total), 1) }
        fun flush() {
            while (io.isNotEmpty() || delivery.isNotEmpty()) {
                while (io.isNotEmpty()) io.removeFirst()()
                while (delivery.isNotEmpty()) delivery.removeFirst()()
            }
        }
        fun respond(total: Int) { val call = calls.removeFirst(); call.second(Result.success(page(call.first, total))); flush() }
        val state get() = browser.fillState.value
    }

    @Test fun resumesFirstHoleThenReusesLaterCachedPagesAndSearchSeesEveryCommit() {
        val f = Fixture(); f.cached(0, 170); f.cached(100, 170)
        val browseBefore = f.browser.state.value
        val flow = f.browser.fillPlaylist(f.playlist); f.flush()
        assertSame(flow, f.browser.fillState)
        assertEquals(100, f.state.loadedCount)
        assertEquals(50, f.calls.first().first)
        f.respond(170)
        assertEquals(150, f.state.loadedCount)
        assertEquals(150, f.calls.first().first)
        assertEquals(1L, f.state.pagesRevision)
        assertEquals(1, CachedPlaylistSearch.search(f.store.readTrackPages("", f.playlist), "Song 75").items.size)
        f.respond(170)
        assertTrue(f.state.complete); assertFalse(f.state.running)
        assertEquals(170, f.state.loadedCount); assertEquals(2L, f.state.pagesRevision)
        assertEquals(browseBefore, f.browser.state.value)
        assertEquals(2, f.store.writes)
    }

    @Test fun completeAndEmptyCachedPlaylistsDoNotFetchOrRewriteTimestamps() {
        for (total in listOf(0, 25)) {
            val f = Fixture(); f.cached(0, total)
            f.browser.fillPlaylist(f.playlist); f.flush()
            assertTrue(f.state.complete); assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.writes)
            assertEquals(1L, f.store.pages.getValue(0).fetchedAtEpochMs)
        }
    }

    @Test fun sameRunningPlaylistIsIdempotent() {
        val f = Fixture(); f.browser.fillPlaylist(f.playlist); f.flush()
        f.browser.fillPlaylist(f.playlist); f.flush()
        assertEquals(1, f.calls.size)
        f.respond(10); assertTrue(f.state.complete)
    }

    @Test fun cancelBeforeReadAndAfterResponseRejectsQueuedWork() {
        val f = Fixture(); f.browser.fillPlaylist(f.playlist); f.browser.cancelFill(); f.flush()
        assertTrue(f.calls.isEmpty()); assertTrue(f.state.cancelled)
        f.browser.fillPlaylist(f.playlist); f.flush()
        f.calls.removeFirst().second(Result.success(f.page(0, 100)))
        f.browser.cancelFill(); f.flush()
        assertEquals(0, f.store.writes); assertFalse(f.state.running); assertNull(f.state.error)
    }

    @Test fun cancelAndRestartRejectsLateCallbackButRetainsEarlierCommittedPage() {
        val f = Fixture(); f.browser.fillPlaylist(f.playlist); f.flush(); f.respond(100)
        val late = f.calls.removeFirst().second
        f.browser.cancelFill(); f.browser.fillPlaylist(f.playlist); f.flush()
        late(Result.success(f.page(50, 100))); f.flush()
        assertEquals(1, f.store.writes); assertEquals(50, f.calls.first().first)
        f.respond(100); assertTrue(f.state.complete)
    }

    @Test fun accountChangeWithoutNotificationStillStopsAndRejectsOldResponse() {
        val f = Fixture(); f.cached(0, 100); f.browser.fillPlaylist(f.playlist); f.flush()
        val late = f.calls.removeFirst().second
        f.identity = f.identity!!.copy(generation = 2)
        late(Result.success(f.page(50, 100))); f.flush()
        assertEquals("account_changed", f.state.error); assertFalse(f.state.running)
        assertEquals(0, f.store.writes); assertEquals(1, f.store.pages.size)
    }

    @Test fun accountNotificationStopsBeforePendingDiskDelivery() {
        val f = Fixture(); f.browser.fillPlaylist(f.playlist)
        f.identity = null; f.browser.accountChanged(); f.flush()
        assertEquals("account_changed", f.state.error); assertFalse(f.state.running)
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.writes)
    }

    @Test fun initialAccountDeliveryForTheSameIdentityDoesNotCancelFreshFill() {
        val f = Fixture(); f.browser.fillPlaylist(f.playlist)
        f.browser.accountChanged(); f.flush()
        assertTrue(f.state.running); assertFalse(f.state.cancelled)
        f.respond(10); assertTrue(f.state.complete)
    }

    @Test fun unavailableAndUnverifiedAccountsCannotStartNetwork() {
        val f = Fixture(); f.identity = null
        f.browser.fillPlaylist(f.playlist); f.flush()
        assertEquals("account_unavailable", f.state.error)
        f.identity = LibraryCacheIdentity("a", 1, false)
        f.browser.fillPlaylist(f.playlist); f.flush()
        assertEquals("account_unverified", f.state.error); assertTrue(f.calls.isEmpty())
    }

    @Test fun networkAndRateErrorsStopImmediatelyAndKeepPriorPagesWithoutAutomaticRetry() {
        for (code in listOf("network", "rate", "risk", "timeout")) {
            val f = Fixture(); f.cached(0, 100); f.browser.fillPlaylist(f.playlist); f.flush()
            f.calls.removeFirst().second(Result.failure(LibraryPageFailure(code))); f.flush()
            assertEquals(code, f.state.error); assertFalse(f.state.running); assertEquals(50, f.state.loadedCount)
            assertEquals(1, f.store.pages.size); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun totalChangeAndNonAdvancingPageAreRejectedBeforePersistence() {
        val f = Fixture(); f.cached(0, 100); f.browser.fillPlaylist(f.playlist); f.flush()
        f.respond(101)
        assertEquals("incomplete", f.state.error); assertEquals(0, f.store.writes)
        f.browser.fillPlaylist(f.playlist); f.flush()
        f.calls.removeFirst().second(Result.success(f.page(50, 100).copy(nextOffset = 50))); f.flush()
        assertEquals("incomplete", f.state.error); assertEquals(0, f.store.writes)
    }

    @Test fun nextAtKnownTotalIsIncompleteWithoutAnExtraRequestOrCacheWrite() {
        val f = Fixture()
        val malformed = f.page(0, 50).copy(nextOffset = 50)
        f.store.pages[0] = SavedLibraryPage(malformed, 1)
        f.browser.fillPlaylist(f.playlist); f.flush()
        assertEquals("incomplete", f.state.error); assertTrue(f.calls.isEmpty())
        f.store.clear(); f.browser.fillPlaylist(f.playlist); f.flush()
        f.calls.removeFirst().second(Result.success(malformed)); f.flush()
        assertEquals("incomplete", f.state.error); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
    }

    @Test fun manualRefreshClearAndMutationFenceLateFillWrites() {
        for (action in listOf<(Fixture) -> Unit>(
            { it.browser.tracks(it.playlist) }, { it.browser.clearCachedPages() },
            { it.browser.invalidate(it.identity!!, refreshNow = false) }, { it.browser.cancel() })) {
            val f = Fixture(); f.browser.fillPlaylist(f.playlist); f.flush()
            val late = f.calls.removeFirst().second
            action(f); late(Result.success(f.page(0, 100))); f.flush()
            assertFalse(f.state.running); assertEquals(0, f.store.writes)
        }
    }

    @Test fun startingFillFencesPendingManualWrite() {
        val f = Fixture(); f.browser.tracks(f.playlist); f.flush()
        f.calls.removeFirst().second(Result.success(f.page(0, 100)))
        f.browser.fillPlaylist(f.playlist); f.flush()
        assertEquals(0, f.store.writes); assertEquals(0, f.calls.first().first)
    }

    @Test fun fiveThousandLimitIsPageAlignedAndNeverTruncatesANormalCachePage() {
        for (platform in Platform.entries) {
            val f = Fixture(platform); val total = 5001
            val prefix = if (platform == Platform.NETEASE) 4950 else 4980
            for (offset in 0 until prefix step f.size) f.cached(offset, total)
            f.browser.fillPlaylist(f.playlist); f.flush()
            if (platform == Platform.NETEASE) f.respond(total)
            assertEquals("fill_limit", f.state.error); assertFalse(f.state.complete)
            assertFalse(f.state.running); assertTrue(f.state.loadedCount <= 5000); assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun exactlyFiveThousandCompletesOnQqFinalShortPage() {
        val f = Fixture(Platform.QQ)
        for (offset in 0 until 4980 step 30) f.cached(offset, 5000)
        f.browser.fillPlaylist(f.playlist); f.flush(); f.respond(5000)
        assertTrue(f.state.complete); assertEquals(5000, f.state.loadedCount); assertNull(f.state.error)
    }

    @Test fun storageFailureAndCapacityFailureAreSafeAndDoNotDiscardPrefix() {
        val f = Fixture(); f.store.fails = true
        f.browser.fillPlaylist(f.playlist); f.flush()
        assertEquals("storage", f.state.error); assertTrue(f.calls.isEmpty())
        f.store.fails = false; f.cached(0, 100); f.store.fits = false
        f.browser.fillPlaylist(f.playlist); f.flush(); f.respond(100)
        assertEquals("cache_limit", f.state.error); assertEquals(1, f.store.pages.size)
        assertEquals(0, f.store.writes); assertEquals(50, f.state.loadedCount)
    }

    @Test fun externalCacheChangeCannotSpliceResponseIntoAnotherHead() {
        val f = Fixture(); f.cached(0, 100); f.browser.fillPlaylist(f.playlist); f.flush()
        f.store.pages[0] = SavedLibraryPage(f.page(0, 10), 2000)
        f.respond(100)
        assertEquals("cache_changed", f.state.error); assertEquals(0, f.store.writes)
    }
}
