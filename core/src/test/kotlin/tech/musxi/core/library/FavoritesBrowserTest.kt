package tech.musxi.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform

class FavoritesBrowserTest {
    private data class FetchCall(
        val platform: Platform,
        val isCurrent: () -> Boolean,
        val result: (Result<FavoritesSnapshot>) -> Unit,
    )

    private data class WriteCall(
        val song: SongSummary,
        val liked: Boolean,
        val isCurrent: () -> Boolean,
        val result: (Result<Unit>) -> Unit,
    )

    private class Fixture {
        val identities = Platform.entries.associateWith {
            LibraryCacheIdentity("owner-${it.name}", 1, true)
        }.toMutableMap()
        val fetches = mutableListOf<FetchCall>()
        val writes = mutableListOf<WriteCall>()
        val verifiedCallbacks = mutableListOf<Pair<Platform, LibraryCacheIdentity>>()
        var fetchThrows = false
        var writeThrows = false
        var rejectVerifiedCallbacks = false

        val browser = FavoritesBrowser(
            identity = { identities[it] },
            fetch = FavoritesFetch { platform, isCurrent, result ->
                if (fetchThrows) error("private fetch detail")
                fetches += FetchCall(platform, isCurrent, result)
            },
            write = FavoritesWrite { song, liked, isCurrent, result ->
                if (writeThrows) error("private write detail")
                writes += WriteCall(song, liked, isCurrent, result)
            },
            now = { 1234L },
            onVerifiedChange = { platform, identity ->
                if (rejectVerifiedCallbacks) error("private listener detail")
                verifiedCallbacks += platform to identity
            },
        )

        fun refresh(platform: Platform): FetchCall {
            browser.refresh(platform)
            return fetches.last()
        }

        fun song(platform: Platform, id: String = "song-1") = SongSummary(
            key = MediaKey(platform, id),
            title = "Song",
            artists = listOf("Artist"),
            album = "Album",
            durationMs = 1000,
        )

        fun keys(platform: Platform, vararg ids: String) =
            ids.mapTo(linkedSetOf()) { MediaKey(platform, it) }

        fun seed(platform: Platform, keys: Set<MediaKey>, canWrite: Boolean = true) {
            refresh(platform).result(Result.success(FavoritesSnapshot(keys, canWrite)))
            assertEquals(true, browser.state.value.getValue(platform).synced)
        }
    }

    @Test fun disconnectedFirstRefreshReportsAccountUnavailable() {
        val fixture = Fixture()
        fixture.identities.remove(Platform.QQ)
        fixture.browser.refresh(Platform.QQ)
        assertEquals("account_unavailable", fixture.browser.state.value.getValue(Platform.QQ).error)
        assertTrue(fixture.fetches.isEmpty())
    }

    @Test
    fun platformsRefreshAndCompleteIndependently() {
        val fixture = Fixture()
        val netease = fixture.refresh(Platform.NETEASE)
        val qq = fixture.refresh(Platform.QQ)

        qq.result(Result.success(FavoritesSnapshot(fixture.keys(Platform.QQ, "q1"))))

        assertTrue(fixture.browser.state.value.getValue(Platform.NETEASE).loading)
        assertTrue(fixture.browser.state.value.getValue(Platform.QQ).synced)
        assertFalse(fixture.browser.state.value.getValue(Platform.QQ).loading)

        netease.result(Result.success(FavoritesSnapshot(fixture.keys(Platform.NETEASE, "n1"))))

        assertEquals(fixture.keys(Platform.NETEASE, "n1"),
            fixture.browser.state.value.getValue(Platform.NETEASE).keys)
        assertEquals(fixture.keys(Platform.QQ, "q1"),
            fixture.browser.state.value.getValue(Platform.QQ).keys)
        assertEquals(Platform.NETEASE, netease.platform)
        assertEquals(Platform.QQ, qq.platform)
    }

    @Test
    fun delayedResultsAfterOwnerChangeAndReverificationAreRejected() {
        val fixture = Fixture()
        val platform = Platform.NETEASE
        fixture.seed(platform, fixture.keys(platform, "old"))
        val old = fixture.refresh(platform)

        fixture.identities[platform] = LibraryCacheIdentity("other-owner", 2, true)
        fixture.browser.accountChanged()
        assertTrue(fixture.browser.state.value.getValue(platform).keys.isEmpty())
        old.result(Result.success(FavoritesSnapshot(fixture.keys(platform, "old"))))
        assertTrue(fixture.browser.state.value.getValue(platform).keys.isEmpty())

        val replacement = fixture.refresh(platform)
        fixture.identities[platform] = fixture.identities.getValue(platform).copy(verified = false)
        fixture.browser.accountChanged()
        replacement.result(Result.success(FavoritesSnapshot(fixture.keys(platform, "new"))))
        assertTrue(fixture.browser.state.value.getValue(platform).keys.isEmpty())

        fixture.identities[platform] = fixture.identities.getValue(platform).copy(verified = true)
        fixture.browser.accountChanged()
        replacement.result(Result.success(FavoritesSnapshot(fixture.keys(platform, "new"))))
        assertTrue(fixture.browser.state.value.getValue(platform).keys.isEmpty())
    }

    @Test
    fun unverifiedIdentityNeverStartsHttp() {
        val fixture = Fixture()
        val platform = Platform.KUGOU
        fixture.identities[platform] = fixture.identities.getValue(platform).copy(verified = false)

        fixture.browser.refresh(platform)

        assertTrue(fixture.fetches.isEmpty())
        assertEquals("account_unverified", fixture.browser.state.value.getValue(platform).error)
        assertFalse(fixture.browser.state.value.getValue(platform).loading)
    }

    @Test
    fun refreshFailurePreservesKnownGoodKeysAndSyncedState() {
        val fixture = Fixture()
        val platform = Platform.QQ
        val oldKeys = fixture.keys(platform, "saved")
        fixture.seed(platform, oldKeys)

        fixture.refresh(platform).result(Result.failure(LibraryPageFailure("network")))

        val state = fixture.browser.state.value.getValue(platform)
        assertEquals(oldKeys, state.keys)
        assertTrue(state.synced)
        assertEquals("network", state.error)
        assertFalse(state.loading)
    }

    @Test
    fun successfulEmptySnapshotIsDistinctFromRefreshFailure() {
        val fixture = Fixture()
        val platform = Platform.NETEASE
        fixture.seed(platform, fixture.keys(platform, "saved"))

        fixture.refresh(platform).result(Result.success(FavoritesSnapshot(emptySet())))

        val state = fixture.browser.state.value.getValue(platform)
        assertTrue(state.keys.isEmpty())
        assertTrue(state.synced)
        assertNull(state.error)
        assertEquals(1234L, state.syncedAtEpochMs)
    }

    @Test
    fun settingTheCurrentValueIsIdempotent() {
        val fixture = Fixture()
        val platform = Platform.NETEASE
        val song = fixture.song(platform)
        fixture.seed(platform, fixture.keys(platform, song.key.id))

        fixture.browser.setLiked(song, true)

        assertTrue(fixture.writes.isEmpty())
        assertNull(fixture.browser.state.value.getValue(platform).pending)
        assertTrue(fixture.browser.state.value.getValue(platform).synced)
    }

    @Test
    fun successfulWriteIsPublishedOnlyAfterVerificationFetch() {
        val fixture = Fixture()
        val platform = Platform.NETEASE
        val song = fixture.song(platform, "new")
        fixture.seed(platform, fixture.keys(platform, "saved"))

        fixture.browser.setLiked(song, true)
        assertEquals(song.key, fixture.browser.state.value.getValue(platform).pending)
        val write = fixture.writes.single()
        write.result(Result.success(Unit))

        assertEquals(2, fixture.fetches.size)
        assertFalse(song.key in fixture.browser.state.value.getValue(platform).keys)
        fixture.fetches.last().result(Result.success(
            FavoritesSnapshot(fixture.keys(platform, "saved", "new"))
        ))

        val state = fixture.browser.state.value.getValue(platform)
        assertTrue(song.key in state.keys)
        assertTrue(state.synced)
        assertFalse(state.loading)
        assertNull(state.pending)
        assertNull(state.error)
        assertTrue(write.isCurrent().not())
    }

    @Test
    fun postWriteFetchFailureKeepsOldKeysButMarksWriteUnverified() {
        val fixture = Fixture()
        val platform = Platform.NETEASE
        val oldKeys = fixture.keys(platform, "saved")
        val song = fixture.song(platform, "new")
        fixture.seed(platform, oldKeys)

        fixture.browser.setLiked(song, true)
        fixture.writes.single().result(Result.success(Unit))
        fixture.fetches.last().result(Result.failure(LibraryPageFailure("network")))

        val state = fixture.browser.state.value.getValue(platform)
        assertEquals(oldKeys, state.keys)
        assertFalse(state.synced)
        assertEquals("write_unverified", state.error)
        assertNull(state.pending)
        assertEquals(1, fixture.writes.size)
        assertEquals(2, fixture.fetches.size)
    }

    @Test
    fun oppositeVerificationResultCannotPublishOptimisticKeys() {
        val fixture = Fixture()
        val platform = Platform.NETEASE
        val oldKeys = fixture.keys(platform, "saved")
        val song = fixture.song(platform, "new")
        fixture.seed(platform, oldKeys)

        fixture.browser.setLiked(song, true)
        fixture.writes.single().result(Result.success(Unit))
        fixture.fetches.last().result(Result.success(FavoritesSnapshot(oldKeys)))

        val state = fixture.browser.state.value.getValue(platform)
        assertEquals(oldKeys, state.keys)
        assertFalse(state.synced)
        assertEquals("write_unverified", state.error)
    }

    @Test
    fun writeServiceFailureMarksStateUnverifiedAndClearsPending() {
        val fixture = Fixture()
        val platform = Platform.NETEASE
        val oldKeys = fixture.keys(platform, "saved")
        fixture.seed(platform, oldKeys)

        fixture.browser.setLiked(fixture.song(platform, "new"), true)
        fixture.writes.single().result(Result.failure(LibraryPageFailure("timeout")))

        val state = fixture.browser.state.value.getValue(platform)
        assertEquals(oldKeys, state.keys)
        assertFalse(state.synced)
        assertEquals("timeout", state.error)
        assertNull(state.pending)
        assertFalse(state.loading)
    }

    @Test
    fun activeWriteMakesPlatformBusyAndCannotBeCanceledByRefresh() {
        val fixture = Fixture()
        val platform = Platform.NETEASE
        fixture.seed(platform, fixture.keys(platform, "saved"))
        val song = fixture.song(platform, "new")

        fixture.browser.setLiked(song, true)
        fixture.browser.refresh(platform)
        fixture.browser.setLiked(fixture.song(platform, "another"), true)

        assertEquals(1, fixture.writes.size)
        assertEquals(1, fixture.fetches.size)
        assertEquals(song.key, fixture.browser.state.value.getValue(platform).pending)
        assertEquals("busy", fixture.browser.state.value.getValue(platform).error)

        fixture.writes.single().result(Result.success(Unit))
        assertEquals(2, fixture.fetches.size)
        fixture.fetches.last().result(Result.success(
            FavoritesSnapshot(fixture.keys(platform, "saved", "new"))
        ))
        assertNull(fixture.browser.state.value.getValue(platform).pending)
    }

    @Test
    fun oversizedAndMixedPlatformSnapshotsAreRejected() {
        val fixture = Fixture()
        val platform = Platform.NETEASE
        val oldKeys = fixture.keys(platform, "saved")
        fixture.seed(platform, oldKeys)

        val oversized = (0..FavoritesBrowser.MAX_KEYS)
            .mapTo(linkedSetOf()) { MediaKey(platform, "k$it") }
        fixture.refresh(platform).result(Result.success(FavoritesSnapshot(oversized)))
        assertEquals("limit", fixture.browser.state.value.getValue(platform).error)
        assertEquals(oldKeys, fixture.browser.state.value.getValue(platform).keys)

        val mixed = fixture.keys(platform, "new") + MediaKey(Platform.QQ, "wrong")
        fixture.refresh(platform).result(Result.success(FavoritesSnapshot(mixed)))
        assertEquals("protocol", fixture.browser.state.value.getValue(platform).error)
        assertEquals(oldKeys, fixture.browser.state.value.getValue(platform).keys)
        assertTrue(fixture.browser.state.value.getValue(platform).synced)
    }

    @Test
    fun successfulSnapshotCopiesAndFreezesKeys() {
        val fixture = Fixture()
        val platform = Platform.NETEASE
        val source = fixture.keys(platform, "one")

        fixture.refresh(platform).result(Result.success(FavoritesSnapshot(source)))
        source.clear()

        val state = fixture.browser.state.value.getValue(platform)
        assertEquals(1, state.keys.size)
        var mutationRejected = false
        try {
            @Suppress("UNCHECKED_CAST")
            (state.keys as MutableSet<MediaKey>).clear()
        } catch (_: UnsupportedOperationException) {
            mutationRejected = true
        }
        assertTrue(mutationRejected)
        assertEquals(1, fixture.browser.state.value.getValue(platform).keys.size)
    }

    @Test
    fun synchronousFetchAndWriteExceptionsAlwaysSettleTheState() {
        val fixture = Fixture()
        val platform = Platform.NETEASE
        fixture.fetchThrows = true

        fixture.browser.refresh(platform)

        var state = fixture.browser.state.value.getValue(platform)
        assertEquals("unclassified", state.error)
        assertFalse(state.loading)

        fixture.fetchThrows = false
        fixture.seed(platform, fixture.keys(platform, "saved"))
        fixture.writeThrows = true
        fixture.browser.setLiked(fixture.song(platform, "new"), true)

        state = fixture.browser.state.value.getValue(platform)
        assertEquals("unclassified", state.error)
        assertFalse(state.loading)
        assertFalse(state.synced)
        assertNull(state.pending)
    }

    @Test
    fun successfulWriteNotifiesOnceOnlyAfterVerifiedSnapshot() {
        val fixture = Fixture()
        val platform = Platform.NETEASE
        val identity = fixture.identities.getValue(platform)
        fixture.seed(platform, fixture.keys(platform, "saved"))
        val changed = fixture.keys(platform, "saved", "new")

        fixture.browser.setLiked(fixture.song(platform, "new"), true)
        assertTrue(fixture.verifiedCallbacks.isEmpty())
        fixture.writes.single().result(Result.success(Unit))
        fixture.writes.single().result(Result.success(Unit))
        fixture.fetches.last().result(Result.success(FavoritesSnapshot(changed)))

        assertEquals(listOf(platform to identity), fixture.verifiedCallbacks)
        assertTrue(fixture.browser.state.value.getValue(platform).synced)
        fixture.fetches.last().result(Result.success(FavoritesSnapshot(changed)))
        assertEquals(1, fixture.verifiedCallbacks.size)
    }

    @Test
    fun verifiedCallbackExceptionsCannotUndoPublishedFavorites() {
        val fixture = Fixture()
        val platform = Platform.NETEASE
        val changed = fixture.keys(platform, "saved", "new")
        fixture.seed(platform, fixture.keys(platform, "saved"))
        fixture.rejectVerifiedCallbacks = true

        fixture.browser.setLiked(fixture.song(platform, "new"), true)
        fixture.writes.single().result(Result.success(Unit))
        fixture.fetches.last().result(Result.success(FavoritesSnapshot(changed)))

        val state = fixture.browser.state.value.getValue(platform)
        assertEquals(changed, state.keys)
        assertTrue(state.synced)
        assertNull(state.error)
    }

    @Test
    fun unverifiedFailedAndStaleWritesNeverNotify() {
        val fixture = Fixture()
        val platform = Platform.NETEASE
        val old = fixture.keys(platform, "saved")
        val song = fixture.song(platform, "new")

        fixture.identities[platform] = fixture.identities.getValue(platform).copy(verified = false)
        fixture.browser.setLiked(song, true)
        assertTrue(fixture.writes.isEmpty())

        fixture.identities[platform] = fixture.identities.getValue(platform).copy(verified = true)
        fixture.seed(platform, old)

        fixture.browser.setLiked(song, true)
        fixture.fetches.last().result(Result.success(FavoritesSnapshot(old)))
        assertTrue(fixture.verifiedCallbacks.isEmpty())

        fixture.seed(platform, old)
        fixture.browser.setLiked(song, true)
        val staleWrite = fixture.writes.last()
        fixture.identities[platform] = fixture.identities.getValue(platform).copy(generation = 2)
        fixture.browser.accountChanged()
        staleWrite.result(Result.success(Unit))
        assertTrue(fixture.verifiedCallbacks.isEmpty())
    }

    @Test
    fun idempotentFavoriteWriteDoesNotNotify() {
        val fixture = Fixture()
        val platform = Platform.NETEASE
        fixture.seed(platform, fixture.keys(platform, "saved"))

        fixture.browser.setLiked(fixture.song(platform, "saved"), true)

        assertTrue(fixture.writes.isEmpty())
        assertTrue(fixture.verifiedCallbacks.isEmpty())
    }
}
