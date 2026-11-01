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
        var now = 200L
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
        }, { io.add(it) }, { delivery.add(it) }, platform = platform, now = { now })
        fun flush() { while (io.isNotEmpty() || delivery.isNotEmpty()) {
            while (io.isNotEmpty()) io.removeFirst().invoke()
            while (delivery.isNotEmpty()) delivery.removeFirst().invoke()
        } }
    }

    private fun kugouPlaylistPage(title: String) = Page(
        listOf(PlaylistSummary(MediaKey(Platform.KUGOU, "9"), title, null, 2)), null, 1)

    private fun emptyPlaylistPage() = Page<PlaylistSummary>(emptyList(), null, 0)

    private fun assertKugouPageRetained(
        f: Fixture,
        title: String,
        fetchedAt: Long,
    ) {
        val state = f.browser.state.value
        assertEquals(title, (state.content as LibraryContent.Playlists).page.items.single().title)
        assertEquals(fetchedAt, state.fetchedAtEpochMs)
        assertTrue(state.stale)
        assertEquals("incomplete", state.error)
        assertFalse(state.refreshing)
    }

    @Test fun clearingCachedPagesDropsVisibleContentAndRejectsPendingNetworkWrite() {
        val f = Fixture(); f.store.playlists = saved
        f.browser.playlists(); f.flush()
        assertNotNull(f.browser.state.value.content)
        f.browser.clearCachedPages()
        f.store.clear()
        f.playlistCalls.single()(Result.success(fresh)); f.flush()
        assertNull(f.browser.state.value.content)
        assertEquals(0, f.store.writes)
        assertFalse(f.browser.state.value.refreshing)
        assertEquals(1, f.playlistCalls.size)
        f.browser.playlists(); f.flush()
        assertEquals(2, f.playlistCalls.size)
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

    @Test fun repeatedOpenWithinThirtySecondsShowsDiskThenSkipsNetwork() {
        val f = Fixture(); f.store.playlists = saved
        f.browser.playlists(); f.flush()
        f.playlistCalls.single()(Result.success(fresh)); f.flush()
        f.now = 10_000L

        f.browser.playlists(); f.flush()

        assertEquals(1, f.playlistCalls.size)
        assertEquals(LibrarySource.CACHE, f.browser.state.value.source)
        assertEquals("Fresh", (f.browser.state.value.content as LibraryContent.Playlists).page.items.single().title)
        assertFalse(f.browser.state.value.refreshing)
    }

    @Test fun networkSuccessExpiresAtThirtySeconds() {
        val f = Fixture()
        f.browser.playlists(); f.flush()
        f.playlistCalls.single()(Result.success(fresh)); f.flush()

        f.now = 30_200L
        f.browser.playlists(); f.flush()

        assertEquals(2, f.playlistCalls.size)
        assertTrue(f.browser.state.value.refreshing)
    }

    @Test fun failedNetworkDoesNotThrottleNextOpen() {
        val f = Fixture()
        f.browser.playlists(); f.flush()
        f.playlistCalls.single()(Result.failure(LibraryPageFailure("network")))

        f.browser.playlists(); f.flush()

        assertEquals(2, f.playlistCalls.size)
        assertTrue(f.browser.state.value.refreshing)
    }

    @Test fun refreshAndRetryBypassRecentNetworkSuccess() {
        val f = Fixture()
        f.browser.playlists(); f.flush()
        f.playlistCalls.single()(Result.success(fresh)); f.flush()

        f.browser.refresh(); f.flush()
        assertEquals(2, f.playlistCalls.size)
        f.playlistCalls.last()(Result.success(fresh)); f.flush()

        f.browser.retry(); f.flush()
        assertEquals(3, f.playlistCalls.size)
    }

    @Test fun accountGenerationChangeBypassesRecentNetworkSuccess() {
        val f = Fixture()
        f.browser.playlists(); f.flush()
        f.playlistCalls.single()(Result.success(fresh)); f.flush()

        f.identity = f.identity!!.copy(generation = 2)
        f.browser.accountChanged(); f.flush()

        assertEquals(2, f.playlistCalls.size)
        assertTrue(f.browser.state.value.refreshing)
    }

    @Test fun invalidationBypassesRecentNetworkSuccess() {
        val f = Fixture()
        f.browser.playlists(); f.flush()
        f.playlistCalls.single()(Result.success(fresh)); f.flush()

        f.browser.invalidate(f.identity!!); f.flush()

        assertEquals(2, f.playlistCalls.size)
        assertTrue(f.browser.state.value.refreshing)
    }

    @Test fun diskCacheHitIsNotNetworkSuccess() {
        val f = Fixture(); f.store.playlists = saved
        f.browser.playlists(refresh = false); f.flush()
        assertTrue(f.playlistCalls.isEmpty())

        f.browser.playlists(); f.flush()

        assertEquals(1, f.playlistCalls.size)
        assertTrue(f.browser.state.value.refreshing)
    }

    @Test fun rejectedOldQueryResponseCannotThrottleNewQuery() {
        val f = Fixture()
        f.browser.playlists(); f.flush()
        val old = f.playlistCalls.single()
        f.browser.tracks(playlist); f.flush()

        old(Result.success(fresh)); f.flush()
        f.browser.playlists(); f.flush()

        assertEquals(2, f.playlistCalls.size)
        assertTrue(f.browser.state.value.refreshing)
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

    @Test fun kugouInitialEmptyHomeRetainsSavedDiskPageWithoutWriting() {
        val f = Fixture(Platform.KUGOU)
        val savedHome = SavedLibraryPage(kugouPlaylistPage("Saved"), 100L)
        f.store.playlists = savedHome

        f.browser.playlists(); f.flush()
        f.playlistCalls.single()(Result.success(emptyPlaylistPage())); f.flush()

        assertKugouPageRetained(f, "Saved", 100L)
        assertEquals(savedHome, f.store.playlists)
        assertEquals(0, f.store.writes)
        assertEquals(LibrarySource.CACHE, f.browser.state.value.source)
    }

    @Test fun kugouEmptyHomeRetainsVisiblePageWhenDiskCacheIsMissing() {
        val f = Fixture(Platform.KUGOU)
        f.browser.playlists(); f.flush()
        f.playlistCalls.single()(Result.success(kugouPlaylistPage("Fresh"))); f.flush()
        assertEquals(1, f.store.writes)

        f.store.playlists = null
        f.now = 201L
        f.browser.refresh(); f.flush()
        f.playlistCalls.last()(Result.success(emptyPlaylistPage())); f.flush()

        assertKugouPageRetained(f, "Fresh", 200L)
        assertNull(f.store.playlists)
        assertEquals(1, f.store.writes)
        assertEquals(LibrarySource.NETWORK, f.browser.state.value.source)
    }

    @Test fun kugouManualRetryWithinMinuteAcceptsRepeatedEmpty() {
        val immediate = Fixture(Platform.KUGOU)
        immediate.store.playlists = SavedLibraryPage(kugouPlaylistPage("Saved"), 100L)
        immediate.browser.playlists(); immediate.flush()
        immediate.playlistCalls.single()(Result.success(emptyPlaylistPage())); immediate.flush()
        assertKugouPageRetained(immediate, "Saved", 100L)

        immediate.browser.retry(); immediate.flush()
        immediate.playlistCalls.last()(Result.success(emptyPlaylistPage())); immediate.flush()
        assertKugouEmptyHomeAccepted(immediate, 200L)

        val atBoundary = Fixture(Platform.KUGOU)
        atBoundary.store.playlists = SavedLibraryPage(kugouPlaylistPage("Saved"), 100L)
        atBoundary.browser.playlists(); atBoundary.flush()
        atBoundary.playlistCalls.single()(Result.success(emptyPlaylistPage())); atBoundary.flush()
        atBoundary.now = 60_200L
        atBoundary.browser.refresh(); atBoundary.flush()
        atBoundary.playlistCalls.last()(Result.success(emptyPlaylistPage())); atBoundary.flush()
        assertKugouEmptyHomeAccepted(atBoundary, 60_200L)
    }

    @Test fun kugouOrdinaryOpenAndOverdueRetryDoNotConfirmEmpty() {
        val ordinary = Fixture(Platform.KUGOU)
        ordinary.store.playlists = SavedLibraryPage(kugouPlaylistPage("Saved"), 100L)
        ordinary.browser.playlists(); ordinary.flush()
        ordinary.playlistCalls.single()(Result.success(emptyPlaylistPage())); ordinary.flush()

        ordinary.now = 201L
        ordinary.browser.playlists(); ordinary.flush()
        assertEquals(2, ordinary.playlistCalls.size)
        ordinary.playlistCalls.last()(Result.success(emptyPlaylistPage())); ordinary.flush()
        assertKugouPageRetained(ordinary, "Saved", 100L)
        assertEquals(0, ordinary.store.writes)

        val overdue = Fixture(Platform.KUGOU)
        overdue.store.playlists = SavedLibraryPage(kugouPlaylistPage("Saved"), 100L)
        overdue.browser.playlists(); overdue.flush()
        overdue.playlistCalls.single()(Result.success(emptyPlaylistPage())); overdue.flush()

        overdue.now = 60_202L
        overdue.browser.retry(); overdue.flush()
        overdue.playlistCalls.last()(Result.success(emptyPlaylistPage())); overdue.flush()
        assertKugouPageRetained(overdue, "Saved", 100L)
        assertEquals(0, overdue.store.writes)
    }

    @Test fun kugouInvalidationRestartsEmptyConfirmation() {
        val f = Fixture(Platform.KUGOU)
        f.store.playlists = SavedLibraryPage(kugouPlaylistPage("Saved"), 100L)
        f.browser.playlists(); f.flush()
        f.playlistCalls.single()(Result.success(emptyPlaylistPage())); f.flush()

        f.now = 201L
        f.browser.invalidate(f.identity!!)
        f.browser.retry(); f.flush()
        assertEquals(3, f.playlistCalls.size)
        f.playlistCalls.last()(Result.success(emptyPlaylistPage())); f.flush()

        assertKugouPageRetained(f, "Saved", 100L)
        assertEquals(0, f.store.writes)
    }

    @Test fun kugouNetworkAndNonEmptySuccessRestartEmptyConfirmation() {
        val failed = Fixture(Platform.KUGOU)
        val savedHome = SavedLibraryPage(kugouPlaylistPage("Saved"), 100L)
        failed.store.playlists = savedHome
        failed.browser.playlists(); failed.flush()
        failed.playlistCalls.single()(Result.success(emptyPlaylistPage())); failed.flush()

        failed.now = 201L
        failed.browser.retry(); failed.flush()
        failed.playlistCalls.last()(Result.failure(LibraryPageFailure("network"))); failed.flush()
        assertEquals("network", failed.browser.state.value.error)

        failed.now = 202L
        failed.browser.retry(); failed.flush()
        failed.playlistCalls.last()(Result.success(emptyPlaylistPage())); failed.flush()
        assertKugouPageRetained(failed, "Saved", 100L)
        assertEquals(savedHome, failed.store.playlists)
        assertEquals(0, failed.store.writes)

        val nonEmpty = Fixture(Platform.KUGOU)
        nonEmpty.store.playlists = savedHome
        nonEmpty.browser.playlists(); nonEmpty.flush()
        nonEmpty.playlistCalls.single()(Result.success(emptyPlaylistPage())); nonEmpty.flush()

        nonEmpty.now = 201L
        nonEmpty.browser.retry(); nonEmpty.flush()
        nonEmpty.playlistCalls.last()(Result.success(kugouPlaylistPage("Fresh"))); nonEmpty.flush()
        assertEquals("Fresh", (nonEmpty.browser.state.value.content as LibraryContent.Playlists).page.items.single().title)
        assertEquals(1, nonEmpty.store.writes)

        nonEmpty.now = 202L
        nonEmpty.browser.retry(); nonEmpty.flush()
        nonEmpty.playlistCalls.last()(Result.success(emptyPlaylistPage())); nonEmpty.flush()
        assertKugouPageRetained(nonEmpty, "Fresh", 201L)
        assertEquals("Fresh", nonEmpty.store.playlists!!.page.items.single().title)
        assertEquals(1, nonEmpty.store.writes)
    }

    @Test fun kugouGenerationChangeAndCacheClearRestartEmptyConfirmation() {
        val generation = Fixture(Platform.KUGOU)
        generation.store.playlists = SavedLibraryPage(kugouPlaylistPage("Saved"), 100L)
        generation.browser.playlists(); generation.flush()
        generation.playlistCalls.single()(Result.success(emptyPlaylistPage())); generation.flush()

        generation.now = 201L
        generation.identity = generation.identity!!.copy(generation = 2)
        generation.browser.accountChanged(); generation.flush()
        generation.browser.retry(); generation.flush()
        generation.playlistCalls.last()(Result.success(emptyPlaylistPage())); generation.flush()
        assertKugouPageRetained(generation, "Saved", 100L)
        assertEquals(0, generation.store.writes)

        val cleared = Fixture(Platform.KUGOU)
        cleared.store.playlists = SavedLibraryPage(kugouPlaylistPage("Saved"), 100L)
        cleared.browser.playlists(); cleared.flush()
        cleared.playlistCalls.single()(Result.success(emptyPlaylistPage())); cleared.flush()

        cleared.now = 201L
        cleared.browser.clearCachedPages()
        cleared.browser.refresh(); cleared.flush()
        cleared.playlistCalls.last()(Result.success(emptyPlaylistPage())); cleared.flush()
        assertKugouPageRetained(cleared, "Saved", 100L)
        assertEquals(0, cleared.store.writes)
    }

    @Test fun emptyResultsOutsideProtectedInitialHomeAreAcceptedImmediately() {
        val noCache = Fixture(Platform.KUGOU)
        noCache.browser.playlists(); noCache.flush()
        noCache.playlistCalls.single()(Result.success(emptyPlaylistPage())); noCache.flush()
        assertTrue((noCache.browser.state.value.content as LibraryContent.Playlists).page.items.isEmpty())
        assertEquals(LibrarySource.NETWORK, noCache.browser.state.value.source)
        assertFalse(noCache.browser.state.value.stale)
        assertEquals(1, noCache.store.writes)

        val emptyDisk = Fixture(Platform.KUGOU)
        emptyDisk.store.playlists = SavedLibraryPage(emptyPlaylistPage(), 100L)
        emptyDisk.browser.playlists(); emptyDisk.flush()
        emptyDisk.playlistCalls.single()(Result.success(emptyPlaylistPage())); emptyDisk.flush()
        assertTrue((emptyDisk.browser.state.value.content as LibraryContent.Playlists).page.items.isEmpty())
        assertFalse(emptyDisk.browser.state.value.stale)
        assertEquals(1, emptyDisk.store.writes)

        val otherPlatform = Fixture(Platform.NETEASE)
        otherPlatform.store.playlists = saved
        otherPlatform.browser.playlists(); otherPlatform.flush()
        otherPlatform.playlistCalls.single()(Result.success(emptyPlaylistPage())); otherPlatform.flush()
        assertTrue((otherPlatform.browser.state.value.content as LibraryContent.Playlists).page.items.isEmpty())
        assertFalse(otherPlatform.browser.state.value.stale)
        assertEquals(1, otherPlatform.store.writes)

        val laterPage = Fixture(Platform.KUGOU)
        laterPage.store.playlists = SavedLibraryPage(kugouPlaylistPage("Saved"), 100L)
        laterPage.browser.playlists(offset = 30); laterPage.flush()
        laterPage.playlistCalls.single()(Result.success(emptyPlaylistPage())); laterPage.flush()
        assertTrue((laterPage.browser.state.value.content as LibraryContent.Playlists).page.items.isEmpty())
        assertEquals(30, laterPage.browser.state.value.query.offset)
        assertFalse(laterPage.browser.state.value.stale)
        assertEquals(1, laterPage.store.writes)

        val trackPage = Fixture(Platform.KUGOU)
        val playlist = MediaKey(Platform.KUGOU, "9")
        trackPage.trackCalls.clear()
        trackPage.browser.tracks(playlist); trackPage.flush()
        trackPage.trackCalls.single()(Result.success(Page(emptyList(), null, 0))); trackPage.flush()
        assertTrue((trackPage.browser.state.value.content as LibraryContent.Tracks).page.items.isEmpty())
        assertEquals(playlist, trackPage.browser.state.value.query.playlist)
        assertFalse(trackPage.browser.state.value.stale)
    }

    private fun assertKugouEmptyHomeAccepted(f: Fixture, fetchedAt: Long) {
        val state = f.browser.state.value
        assertTrue((state.content as LibraryContent.Playlists).page.items.isEmpty())
        assertEquals(LibrarySource.NETWORK, state.source)
        assertEquals(fetchedAt, state.fetchedAtEpochMs)
        assertFalse(state.stale)
        assertNull(state.error)
        assertTrue(f.store.playlists!!.page.items.isEmpty())
        assertEquals(fetchedAt, f.store.playlists!!.fetchedAtEpochMs)
        assertEquals(1, f.store.writes)
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

    @Test fun invalidationRejectsChangedAndUnverifiedIdentityWithoutCancel() {
        val f = Fixture()
        val identity = f.identity!!
        f.identity = identity.copy(generation = 2)
        f.browser.invalidate(identity)
        f.identity = identity.copy(verified = false)
        f.browser.invalidate(identity)
        assertEquals(0L, f.browser.state.value.invalidationRevision)
        assertFalse(f.browser.state.value.stale)
    }

    @Test fun deferredInvalidationMarksCacheStaleWithoutBlockingFavoriteWrites() {
        val f = Fixture()
        f.store.playlists = saved
        f.browser.playlists(); f.flush()
        f.playlistCalls.single()(Result.success(fresh)); f.flush()
        val count = f.playlistCalls.size
        f.browser.invalidate(f.identity!!, refreshNow = false); f.flush()
        assertTrue(f.browser.state.value.stale)
        assertFalse(f.browser.state.value.refreshing)
        assertEquals(count, f.playlistCalls.size)
        f.browser.playlists(); f.flush()
        assertEquals(count + 1, f.playlistCalls.size)
    }

    @Test fun invalidationSkipsCacheAndLateOldRequestCannotOverwrite() {
        val f = Fixture()
        f.store.playlists = saved
        f.browser.playlists()
        f.flush()
        val old = f.playlistCalls.single()
        val identity = f.identity!!

        f.browser.invalidate(identity)
        assertTrue(f.browser.state.value.stale)
        assertEquals(1L, f.browser.state.value.invalidationRevision)
        assertEquals("Saved", (f.browser.state.value.content as LibraryContent.Playlists).page.items.single().title)
        assertEquals(2, f.playlistCalls.size)
        old(Result.success(fresh))
        assertEquals("Saved", (f.browser.state.value.content as LibraryContent.Playlists).page.items.single().title)
        assertEquals(2, f.playlistCalls.size)
    }

    @Test fun invalidationFailureKeepsContentAndStaleThenOrdinaryOpenCannotUseOldCache() {
        val f = Fixture()
        f.store.playlists = saved
        f.browser.playlists()
        f.flush()
        f.playlistCalls.single()(Result.success(fresh))
        f.flush()
        val identity = f.identity!!

        f.browser.invalidate(identity)
        f.playlistCalls.last()(Result.failure(LibraryPageFailure("network")))
        var state = f.browser.state.value
        assertEquals("Fresh", (state.content as LibraryContent.Playlists).page.items.single().title)
        assertEquals(LibrarySource.NETWORK, state.source)
        assertEquals(200L, state.fetchedAtEpochMs)
        assertEquals("network", state.error)
        assertTrue(state.stale)
        assertEquals(1L, state.invalidationRevision)

        f.store.playlists = saved
        f.browser.playlists(refresh = false)
        f.flush()
        state = f.browser.state.value
        assertEquals("Saved", (state.content as LibraryContent.Playlists).page.items.single().title)
        assertTrue(state.stale)
        assertEquals(LibrarySource.CACHE, state.source)
    }

    @Test fun successfulInvalidationClearsStaleAndKeepsRevisionAcrossOpen() {
        val f = Fixture()
        f.store.playlists = saved
        f.browser.playlists()
        f.flush()
        waitForSuccessfulNetwork(f, "Fresh")
        val identity = f.identity!!

        f.browser.invalidate(identity)
        f.playlistCalls.last()(Result.success(fresh))
        f.flush()
        var state = f.browser.state.value
        assertFalse(state.stale)
        assertEquals(1L, state.invalidationRevision)

        f.browser.playlists()
        f.flush()
        state = f.browser.state.value
        assertEquals(1L, state.invalidationRevision)
        assertFalse(state.stale)
        f.playlistCalls.last()(Result.success(fresh))
        f.flush()
        assertEquals(1L, f.browser.state.value.invalidationRevision)
        assertFalse(f.browser.state.value.stale)

        f.identity = identity.copy(generation = 2, verified = false)
        f.browser.accountChanged()
        assertEquals(1L, f.browser.state.value.invalidationRevision)
    }

    @Test fun invalidationBeforeAccountCollectorRunsNeverRetainsOtherOwnersTracks() {
        val f = Fixture()
        f.browser.tracks(playlist); f.flush()
        f.trackCalls.single()(Result.success(Page(listOf(SongSummary(
            MediaKey(Platform.NETEASE, "1"), "Private old song", emptyList(), "", 1000)), null, 1)))
        f.flush()
        val old = f.trackCalls.single()
        f.identity = LibraryCacheIdentity("b".repeat(64), 2, true)
        // Favorites can deliver before the account StateFlow collector.
        f.browser.invalidate(f.identity!!)
        assertNull(f.browser.state.value.content)
        assertNull(f.browser.state.value.query.playlist)
        assertEquals(1L, f.browser.state.value.invalidationRevision)
        assertEquals(1, f.playlistCalls.size)
        old(Result.success(Page(emptyList(), null, 0))); f.flush()
        assertNull(f.browser.state.value.content)
    }

    @Test fun anotherOwnerDoesNotInheritPreviousOwnersStaleFlag() {
        val f = Fixture()
        f.browser.playlists(); f.flush()
        f.browser.invalidate(f.identity!!)
        assertTrue(f.browser.state.value.stale)
        f.identity = LibraryCacheIdentity("b".repeat(64), 2, true)
        f.browser.accountChanged(); f.flush()
        assertFalse(f.browser.state.value.stale)
        assertNull(f.browser.state.value.content)
        assertEquals(1L, f.browser.state.value.invalidationRevision)
    }

    @Test fun failedCacheWriteAfterInvalidationCannotMakeOldDiskPageFresh() {
        val f = Fixture(); f.store.playlists = saved
        f.browser.playlists(); f.flush()
        f.browser.invalidate(f.identity!!)
        f.store.fail = true
        f.playlistCalls.last()(Result.success(fresh)); f.flush()
        assertFalse(f.browser.state.value.stale)
        assertTrue(f.browser.state.value.storageError)
        assertEquals("Fresh", (f.browser.state.value.content as LibraryContent.Playlists).page.items.single().title)
        f.store.fail = false
        f.browser.playlists(refresh = false); f.flush()
        assertTrue(f.browser.state.value.stale)
        assertEquals("Saved", (f.browser.state.value.content as LibraryContent.Playlists).page.items.single().title)
    }

    private fun waitForSuccessfulNetwork(f: Fixture, title: String) {
        f.playlistCalls.last()(Result.success(fresh))
        f.flush()
        assertEquals(title, ((f.browser.state.value.content as LibraryContent.Playlists).page.items.single()).title)
    }

}
