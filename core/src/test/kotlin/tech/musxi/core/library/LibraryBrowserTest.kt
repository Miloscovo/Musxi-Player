package tech.musxi.core.library

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform

class LibraryBrowserTest {
    private val playlist = MediaKey(Platform.NETEASE, "9")
    private val saved = SavedLibraryPage(Page(listOf(PlaylistSummary(playlist, "Saved", null, 2)), null, 1), 100L)
    private val fresh = Page(listOf(PlaylistSummary(playlist, "Fresh", null, 3)), null, 1)

    private class Store : LibraryPageStore {
        var playlists: SavedLibraryPage<PlaylistSummary>? = null
        var tracks: SavedLibraryPage<SongSummary>? = null
        var writes = 0
        var fail = false
        override fun readPlaylists(owner: String, offset: Int): SavedLibraryPage<PlaylistSummary>? {
            if (fail) throw java.io.IOException("private disk detail")
            return playlists
        }
        override fun writePlaylists(owner: String, offset: Int, value: SavedLibraryPage<PlaylistSummary>) {
            if (fail) throw java.io.IOException("private disk detail")
            writes++; playlists = value
        }
        override fun readTracks(owner: String, playlist: MediaKey, offset: Int) = tracks
        override fun writeTracks(owner: String, playlist: MediaKey, offset: Int, value: SavedLibraryPage<SongSummary>) { writes++; tracks = value }
        override fun clear() { playlists = null; tracks = null }
    }

    private class Fixture(val platform: Platform = Platform.NETEASE) {
        var identity: LibraryCacheIdentity? = LibraryCacheIdentity("a".repeat(64), 1, true)
        val store = Store()
        val io = ArrayDeque<() -> Unit>()
        val delivery = ArrayDeque<() -> Unit>()
        val playlistCalls = mutableListOf<(Result<Page<PlaylistSummary>>) -> Unit>()
        val trackCalls = mutableListOf<(Result<Page<SongSummary>>) -> Unit>()
        val browser = newBrowser()
        fun newBrowser() = LibraryBrowser({ identity }, { expected, action ->
            if (!expected.sameAttempt(identity)) throw LibraryPageFailure("account_changed")
            action(store)
        }, object : LibraryPageFetch {
            override fun playlists(offset: Int, result: (Result<Page<PlaylistSummary>>) -> Unit) { playlistCalls.add(result) }
            override fun tracks(playlist: MediaKey, offset: Int, result: (Result<Page<SongSummary>>) -> Unit) { trackCalls.add(result) }
        }, { io.add(it) }, { delivery.add(it) }, { 200L }, platform)
        fun flush() { while (io.isNotEmpty() || delivery.isNotEmpty()) {
            while (io.isNotEmpty()) io.removeFirst().invoke()
            while (delivery.isNotEmpty()) delivery.removeFirst().invoke()
        } }
    }

    @Test fun cachedPageAppearsBeforeRefreshAndErrorKeepsItVisible() {
        val f = Fixture(); f.store.playlists = saved
        f.browser.playlists(); f.flush()
        assertEquals(LibrarySource.CACHE, f.browser.state.value.source)
        assertEquals(100L, f.browser.state.value.fetchedAtEpochMs)
        assertTrue(f.browser.state.value.refreshing)
        f.playlistCalls.single()(Result.failure(LibraryPageFailure("network")))
        val state = f.browser.state.value
        assertFalse(state.refreshing); assertEquals("network", state.error)
        assertEquals("Saved", (state.content as LibraryContent.Playlists).page.items.single().title)
        assertTrue(state.accountVerified)
        assertEquals(0, f.store.writes)
    }

    @Test fun successfulRefreshReplacesCacheAndSurvivesAnotherBrowserInstance() {
        val f = Fixture(); f.store.playlists = saved
        f.browser.playlists(); f.flush()
        f.playlistCalls.single()(Result.success(fresh)); f.flush()
        assertEquals(LibrarySource.NETWORK, f.browser.state.value.source)
        assertEquals(200L, f.browser.state.value.fetchedAtEpochMs)
        assertFalse(f.browser.state.value.refreshing)
        val restored = f.newBrowser()
        restored.playlists(refresh = false); f.flush()
        assertEquals(LibrarySource.CACHE, restored.state.value.source)
        assertEquals("Fresh", (restored.state.value.content as LibraryContent.Playlists).page.items.single().title)
        assertEquals(1, f.playlistCalls.size)
    }

    @Test fun unverifiedSavedIdentityCanBrowseButNeverStartsNetworkOrMarksLoginVerified() {
        val f = Fixture(); f.identity = f.identity!!.copy(verified = false); f.store.playlists = saved
        f.browser.accountChanged(); f.flush()
        assertNotNull(f.browser.state.value.content)
        assertEquals(LibrarySource.CACHE, f.browser.state.value.source)
        assertFalse(f.browser.state.value.accountVerified)
        assertEquals("account_unverified", f.browser.state.value.error)
        assertTrue(f.playlistCalls.isEmpty())
        f.identity = f.identity!!.copy(verified = true)
        f.browser.accountChanged(); f.flush()
        assertTrue(f.browser.state.value.accountVerified)
        assertTrue(f.browser.state.value.refreshing)
        assertEquals(1, f.playlistCalls.size)
    }

    @Test fun disconnectBeforeDiskDeliveryCannotExposePreviousOwner() {
        val f = Fixture(); f.store.playlists = saved
        f.browser.playlists(); f.io.removeFirst().invoke()
        f.identity = null; f.store.clear(); f.browser.accountChanged()
        f.flush()
        assertNull(f.browser.state.value.content)
        assertFalse(f.browser.state.value.accountVerified)
        assertEquals("account_unavailable", f.browser.state.value.error)
        assertTrue(f.playlistCalls.isEmpty())
    }

    @Test fun oldNetworkResponseAfterSwitchCannotWriteOrReplaceNewOwnerState() {
        val f = Fixture(); f.browser.playlists(); f.flush()
        val old = f.playlistCalls.single()
        f.identity = LibraryCacheIdentity("b".repeat(64), 2, true)
        f.store.clear(); f.browser.accountChanged(); f.flush()
        old(Result.success(fresh)); f.flush()
        assertNull(f.browser.state.value.content)
        assertEquals(0, f.store.writes)
        f.playlistCalls.last()(Result.success(Page(emptyList(), null, 0))); f.flush()
        assertTrue((f.browser.state.value.content as LibraryContent.Playlists).page.items.isEmpty())
    }

    @Test fun canceledOrSupersededRequestCannotPersistAnAlreadyScheduledWrite() {
        val f = Fixture(); f.browser.playlists(); f.flush()
        f.playlistCalls.single()(Result.success(fresh))
        f.browser.tracks(playlist)
        f.flush()
        assertEquals(0, f.store.writes)
        assertEquals(playlist, f.browser.state.value.query.playlist)
        f.trackCalls.single()(Result.success(Page(emptyList(), null, 0)))
        f.browser.cancel(); f.flush()
        assertEquals(0, f.store.writes)
        assertFalse(f.browser.state.value.refreshing)
    }

    @Test fun diskFailureDoesNotHideSuccessfulNetworkPageOrBecomeLoginFailure() {
        val f = Fixture(); f.store.fail = true
        f.browser.playlists(); f.flush()
        assertTrue(f.browser.state.value.storageError)
        f.playlistCalls.single()(Result.success(fresh)); f.flush()
        val state = f.browser.state.value
        assertTrue(state.storageError); assertNull(state.error)
        assertEquals(LibrarySource.NETWORK, state.source)
        assertEquals("Fresh", (state.content as LibraryContent.Playlists).page.items.single().title)
        assertTrue(state.accountVerified)
    }

    @Test fun missingCacheAndFailedRequestAreNotReportedAsSuccessfulEmptyCollection() {
        val f = Fixture(); f.browser.playlists(); f.flush()
        f.playlistCalls.single()(Result.failure(IllegalStateException("private response")))
        assertNull(f.browser.state.value.content)
        assertNull(f.browser.state.value.source)
        assertEquals("unclassified", f.browser.state.value.error)
        assertFalse(f.browser.state.value.refreshing)
    }

    @Test fun blockedDiskReadCannotBlockCancelOrAccountChangeAndCannotDeliverOldData() {
        val f = Fixture(); f.store.playlists = saved
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor()
        val callbacks = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
        val browser = LibraryBrowser({ f.identity }, { _, action ->
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            action(f.store)
        }, object : LibraryPageFetch {
            override fun playlists(offset: Int, result: (Result<Page<PlaylistSummary>>) -> Unit) { fail("canceled read cannot fetch") }
            override fun tracks(playlist: MediaKey, offset: Int, result: (Result<Page<SongSummary>>) -> Unit) { fail("not requested") }
        }, { worker.execute(it) }, { callbacks.add(it) })
        try {
            browser.playlists()
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            caller.submit {
                browser.cancel()
                f.identity = null
                browser.accountChanged()
            }.get(1, java.util.concurrent.TimeUnit.SECONDS)
            assertNull(browser.state.value.content)
            release.countDown()
            worker.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS)
            while (callbacks.isNotEmpty()) callbacks.poll().invoke()
            assertNull(browser.state.value.content)
            assertEquals("account_unavailable", browser.state.value.error)
        } finally { release.countDown(); worker.shutdownNow(); caller.shutdownNow() }
    }

    @Test fun paginationAndMetadataAreImmutableAndLateOtherPageCannotReplaceSelection() {
        val f = Fixture(); f.browser.playlists(); f.flush()
        val old = f.playlistCalls.single()
        f.browser.tracks(playlist, 50); f.flush()
        val artists = mutableListOf("Artist")
        val songs = mutableListOf(SongSummary(MediaKey(Platform.NETEASE, "1"), "Song", artists, "Album", 1000))
        f.trackCalls.single()(Result.success(Page(songs, 100, 101)))
        artists.clear(); songs.clear(); f.flush()
        old(Result.success(fresh)); f.flush()
        val state = f.browser.state.value
        val page = (state.content as LibraryContent.Tracks).page
        assertEquals(50, state.query.offset); assertEquals(100, page.nextOffset); assertEquals(101, page.total)
        assertEquals(listOf("Artist"), page.items.single().artists)
        try { (page.items as MutableList).clear(); fail("must be immutable") } catch (_: UnsupportedOperationException) { }
    }
    @Test fun kugouShortPageContinuationAndFailedRefreshPreserveMetadata() {
        val f = Fixture(Platform.KUGOU)
        val key = MediaKey(Platform.KUGOU, "9")
        f.store.playlists = SavedLibraryPage(Page(listOf(PlaylistSummary(key, "Saved", null, 746)), 30, null), 100L)
        f.browser.playlists(); f.flush()
        val state = f.browser.state.value
        assertEquals(30, (state.content as LibraryContent.Playlists).page.nextOffset)
        assertNull(state.content.page.total)
        f.playlistCalls.single()(Result.failure(LibraryPageFailure("network")))
        assertEquals(LibrarySource.CACHE, f.browser.state.value.source)
        assertEquals(100L, f.browser.state.value.fetchedAtEpochMs)
        f.browser.tracks(key); f.flush()
        f.trackCalls.single()(Result.success(Page(listOf(SongSummary(
            MediaKey(Platform.KUGOU, "a".repeat(32) + "_0_0"), "Song", emptyList(), "", 1000)), 30, 746)))
        f.flush()
        assertEquals(LibrarySource.NETWORK, f.browser.state.value.source)
        assertEquals(key, f.browser.state.value.query.playlist)
    }

    @Test fun switchingOwnerWhileViewingTracksReturnsToOwnPlaylistsAndRejectsOldTrackResponse() {
        val f = Fixture(Platform.KUGOU)
        f.browser.tracks(MediaKey(Platform.KUGOU, "9")); f.flush()
        val stale = f.trackCalls.single()
        f.identity = LibraryCacheIdentity("b".repeat(64), 2, true)
        f.browser.accountChanged(); f.flush()
        stale(Result.success(Page(emptyList(), null, 0))); f.flush()
        assertNull(f.browser.state.value.query.playlist)
        assertNull(f.browser.state.value.content)
        assertEquals(1, f.playlistCalls.size)
        assertEquals(0, f.store.writes)
    }

    @Test fun platformsBrowseAndFailIndependentlyAndRejectCrossPlatformTrackQuery() {
        val netease = Fixture(); val kugou = Fixture(Platform.KUGOU)
        netease.store.playlists = saved
        netease.browser.playlists(); netease.flush()
        kugou.browser.playlists(); kugou.flush()
        kugou.playlistCalls.single()(Result.failure(LibraryPageFailure("network")))
        assertTrue(netease.browser.state.value.refreshing)
        assertNull(netease.browser.state.value.error)
        assertEquals(LibrarySource.CACHE, netease.browser.state.value.source)
        netease.identity = null; netease.browser.accountChanged(); netease.flush()
        assertEquals("network", kugou.browser.state.value.error)
        assertTrue(kugou.browser.state.value.accountVerified)
        try { kugou.browser.tracks(playlist); fail("cross-platform query must fail") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, kugou.trackCalls.size)
    }

    @Test fun qqFailedRefreshAndRevalidationRetainPageButOwnerSwitchRejectsLateResponse() {
        val f = Fixture(Platform.QQ); val key = MediaKey(Platform.QQ, "0_201")
        f.store.tracks = SavedLibraryPage(Page(listOf(SongSummary(MediaKey(Platform.QQ, "A0000000000001"),
            "Saved", emptyList(), "", 1000)), 3, 4), 100L)
        f.browser.tracks(key, 2); f.flush()
        f.trackCalls.last()(Result.failure(LibraryPageFailure("network")))
        assertEquals(LibrarySource.CACHE, f.browser.state.value.source)
        assertEquals(100L, f.browser.state.value.fetchedAtEpochMs)
        assertEquals("network", f.browser.state.value.error)
        f.identity = f.identity!!.copy(generation = 2, verified = false)
        f.browser.accountChanged(); f.flush()
        assertEquals(1, f.trackCalls.size)
        assertEquals(key, f.browser.state.value.query.playlist)
        assertFalse(f.browser.state.value.accountVerified)
        f.identity = f.identity!!.copy(verified = true)
        f.browser.accountChanged(); f.flush()
        val stale = f.trackCalls.last()
        f.identity = LibraryCacheIdentity("b".repeat(64), 3, true)
        f.store.clear(); f.browser.accountChanged(); f.flush()
        stale(Result.success(Page(emptyList(), null, 0))); f.flush()
        assertNull(f.browser.state.value.content); assertNull(f.browser.state.value.query.playlist)
        assertEquals(0, f.store.writes)
        try { f.browser.tracks(playlist); fail("cross-platform query") } catch (_: IllegalArgumentException) { }
    }

    @Test fun transientIdentityLoadingPreservesSameOwnerQueryButDoesNotForgetOwnerAcrossSwitch() {
        val f = Fixture(Platform.KUGOU); val key = MediaKey(Platform.KUGOU, "9")
        val original = f.identity!!
        f.browser.tracks(key, 30); f.flush()
        f.identity = null; f.browser.accountChanged(); f.flush()
        assertNull(f.browser.state.value.content)
        f.identity = original.copy(generation = 2, verified = false)
        f.browser.accountChanged(); f.flush()
        assertEquals(key, f.browser.state.value.query.playlist)
        assertEquals(30, f.browser.state.value.query.offset)
        f.identity = f.identity!!.copy(verified = true)
        f.browser.accountChanged(); f.flush()
        assertEquals(2, f.trackCalls.size)
        f.identity = null; f.browser.accountChanged(); f.flush()
        f.identity = LibraryCacheIdentity("b".repeat(64), 3, true)
        f.browser.accountChanged(); f.flush()
        assertNull(f.browser.state.value.query.playlist)
        assertEquals(0, f.browser.state.value.query.offset)
        assertEquals(1, f.playlistCalls.size)
    }

}
