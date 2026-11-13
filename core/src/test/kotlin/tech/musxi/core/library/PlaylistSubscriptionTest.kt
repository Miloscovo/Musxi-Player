package tech.musxi.core.library

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform

class PlaylistSubscriptionTest {
    private val playlist = PlaylistSummary(MediaKey(Platform.QQ, "collected"), "Collected", null, 20,
        PlaylistKind.COLLECTED)
    private fun snap(followed: Boolean = false, generation: Long = 7) =
        PlaylistSubscription(playlist, generation, followed)

    private class Port : PlaylistSubscriptionPort {
        var activeGeneration: Long? = 7
        var current = PlaylistSubscription(
            PlaylistSummary(MediaKey(Platform.QQ, "collected"), "Collected", null, 20, PlaylistKind.COLLECTED), 7, false)
        val writes = mutableListOf<Pair<MediaKey, Boolean>>()
        var write: ((PlaylistSubscription, Boolean, (Result<Unit>) -> Unit) -> Unit)? = null
        var read: ((MediaKey, (Result<PlaylistSubscription>) -> Unit) -> Unit)? = null
        override fun generation(platform: Platform) = activeGeneration
        override fun read(playlist: MediaKey, result: (Result<PlaylistSubscription>) -> Unit) {
            read?.invoke(playlist, result) ?: result(Result.success(current))
        }
        override fun setFollowed(snapshot: PlaylistSubscription, followed: Boolean,
            result: (Result<Unit>) -> Unit) {
            writes += snapshot.playlist.key to followed
            val action = write
            if (action != null) action(snapshot, followed, result)
            else { current = current.copy(followed = followed); result(Result.success(Unit)) }
        }
    }

    private fun update(port: Port, snapshot: PlaylistSubscription, followed: Boolean):
        Result<PlaylistFollowResult> {
        var result: Result<PlaylistFollowResult>? = null
        PlaylistSubscriptionCoordinator(port).update(snapshot, followed) { result = it }
        return requireNotNull(result)
    }

    private fun read(port: Port, key: MediaKey = playlist.key): Result<PlaylistSubscription> {
        var result: Result<PlaylistSubscription>? = null
        PlaylistSubscriptionCoordinator(port).read(key) { result = it }
        return requireNotNull(result)
    }

    private fun category(result: Result<*>): String =
        (result.exceptionOrNull() as LibraryPageFailure).category

    @Test fun readReturnsCollectedSnapshot() {
        val port = Port()
        port.current = snap(followed = true)
        assertEquals(snap(followed = true), read(port).getOrThrow())
    }

    @Test fun readRejectsWrongKeyGenerationOrNonCollectedPlaylist() {
        val port = Port()
        assertEquals("protocol", category(read(port, MediaKey(Platform.QQ, "other"))))
        port.current = snap().copy(accountGeneration = 6)
        assertEquals("protocol", category(read(port)))
        port.current = snap(generation = 7).copy(playlist = playlist.copy(kind = PlaylistKind.CREATED))
        assertEquals("not_collected", category(read(port)))
    }

    @Test fun readFailsWhenAccountIsUnverifiedOrChangesMidRead() {
        val port = Port()
        port.activeGeneration = null
        assertEquals("account_unverified", category(read(port)))
        port.activeGeneration = 7
        port.read = { _, done -> port.activeGeneration = 8; done(Result.success(snap())) }
        assertEquals("account_changed", category(read(port)))
    }

    @Test fun followWritesOnceAndConfirmsApplied() {
        val port = Port()
        val result = update(port, snap(), true).getOrThrow()
        assertEquals(PlaylistChangeStatus.APPLIED, result.status)
        assertEquals(true, result.confirmedFollowed)
        assertTrue(result.requestedFollowed)
        assertEquals(listOf(playlist.key to true), port.writes)
    }

    @Test fun unfollowAppliesWithoutRetry() {
        val port = Port()
        port.current = snap(followed = true)
        val result = update(port, snap(followed = true), false).getOrThrow()
        assertEquals(PlaylistChangeStatus.APPLIED, result.status)
        assertEquals(false, result.confirmedFollowed)
        assertEquals(1, port.writes.size)
    }

    @Test fun lostWriteResponseIsRecoveredByConfirmingRead() {
        val port = Port()
        port.write = { snapshot, followed, done ->
            port.current = snapshot.copy(followed = followed)
            done(Result.failure(LibraryPageFailure("timeout")))
        }
        val result = update(port, snap(), true).getOrThrow()
        assertEquals(PlaylistChangeStatus.APPLIED, result.status)
        assertEquals(true, result.confirmedFollowed)
    }

    @Test fun unreadableConfirmationIsUnknownAndNeverApplied() {
        val port = Port()
        port.write = { snapshot, followed, done ->
            port.current = snapshot.copy(followed = followed)
            port.read = { _, response -> response(Result.failure(LibraryPageFailure("network"))) }
            done(Result.success(Unit))
        }
        val result = update(port, snap(), true).getOrThrow()
        assertEquals(PlaylistChangeStatus.UNKNOWN, result.status)
        assertEquals("network", result.error)
        assertNull(result.confirmedFollowed)
    }

    @Test fun writeWhoseStateNeverChangedIsFailedWithoutRetry() {
        val port = Port()
        port.write = { _, _, done -> done(Result.success(Unit)) }
        val result = update(port, snap(), true).getOrThrow()
        assertEquals(PlaylistChangeStatus.FAILED, result.status)
        assertEquals("not_confirmed", result.error)
        assertEquals(false, result.confirmedFollowed)
        assertEquals(1, port.writes.size)
    }

    @Test fun remoteChangeKeepsIntentAsConflictWithoutWriting() {
        val port = Port()
        port.current = snap(followed = true)
        val result = update(port, snap(followed = false), false).getOrThrow()
        assertEquals(PlaylistChangeStatus.CONFLICT, result.status)
        assertEquals(true, result.confirmedFollowed)
        assertEquals("conflict", result.error)
        assertTrue(port.writes.isEmpty())
    }

    @Test fun alreadyWantedStateIsUnchangedWithoutWriting() {
        val port = Port()
        port.current = snap(followed = true)
        val result = update(port, snap(followed = false), true).getOrThrow()
        assertEquals(PlaylistChangeStatus.UNCHANGED, result.status)
        assertEquals(true, result.confirmedFollowed)
        assertTrue(port.writes.isEmpty())
    }

    @Test fun accountChangeAfterWriteLeavesOutcomeUnknownAndBlocksOldTarget() {
        val port = Port()
        port.write = { _, _, done -> port.activeGeneration = 8; done(Result.success(Unit)) }
        val result = update(port, snap(), true).getOrThrow()
        assertEquals(PlaylistChangeStatus.UNKNOWN, result.status)
        assertEquals("account_changed", result.error)
        assertEquals(1, port.writes.size)
    }

    @Test fun staleOrMissingGenerationNeverWrites() {
        val port = Port()
        port.activeGeneration = 8
        assertEquals("account_changed", category(update(port, snap(), true)))
        port.activeGeneration = null
        assertEquals("account_changed", category(update(port, snap(), true)))
        assertTrue(port.writes.isEmpty())
    }

    @Test fun nonCollectedSnapshotIsRejectedBeforeAnyRead() {
        val port = Port()
        val snapshot = snap().copy(playlist = playlist.copy(kind = PlaylistKind.CREATED))
        assertEquals("not_collected", category(update(port, snapshot, true)))
        assertTrue(port.writes.isEmpty())
    }

    @Test fun failedFreshReadDoesNotWrite() {
        val port = Port()
        port.read = { _, done -> done(Result.failure(LibraryPageFailure("permission"))) }
        val result = update(port, snap(), true).getOrThrow()
        assertEquals(PlaylistChangeStatus.FAILED, result.status)
        assertEquals("permission", result.error)
        assertNull(result.confirmedFollowed)
        assertTrue(port.writes.isEmpty())
    }

    @Test fun freshReadForAnotherPlaylistOrGenerationNeverWrites() {
        val port = Port()
        port.read = { _, done ->
            done(Result.success(snap().copy(playlist = playlist.copy(key = MediaKey(Platform.QQ, "other")))))
        }
        assertEquals(PlaylistChangeStatus.FAILED, update(port, snap(), true).getOrThrow().status)
        port.read = { _, done -> done(Result.success(snap().copy(accountGeneration = 8))) }
        assertEquals(PlaylistChangeStatus.FAILED, update(port, snap(), true).getOrThrow().status)
        assertTrue(port.writes.isEmpty())
    }

    @Test fun overlappingUpdatesOnSamePlatformAreBusyAndLockIsReleased() {
        val port = Port()
        val coordinator = PlaylistSubscriptionCoordinator(port)
        var resume: ((Result<Unit>) -> Unit)? = null
        port.write = { _, _, done -> resume = done }
        coordinator.update(snap(), true) {}
        var second: Result<PlaylistFollowResult>? = null
        coordinator.update(snap(), true) { second = it }
        assertEquals("busy", category(requireNotNull(second)))
        port.current = snap(followed = true)
        requireNotNull(resume)(Result.success(Unit))
        port.write = null
        var third: Result<PlaylistFollowResult>? = null
        coordinator.update(snap(followed = true), true) { third = it }
        assertEquals(PlaylistChangeStatus.UNCHANGED, requireNotNull(third).getOrThrow().status)
    }

    @Test fun lockIsReleasedAfterFailedPreReadAndUnknownWrite() {
        val port = Port()
        val coordinator = PlaylistSubscriptionCoordinator(port)
        port.read = { _, done -> done(Result.failure(LibraryPageFailure("network"))) }
        var first: Result<PlaylistFollowResult>? = null
        coordinator.update(snap(), true) { first = it }
        assertEquals(PlaylistChangeStatus.FAILED, requireNotNull(first).getOrThrow().status)
        port.read = null
        port.write = { _, _, done ->
            port.read = { _, response -> response(Result.failure(LibraryPageFailure("network"))) }
            done(Result.success(Unit))
        }
        var second: Result<PlaylistFollowResult>? = null
        coordinator.update(snap(), true) { second = it }
        assertEquals(PlaylistChangeStatus.UNKNOWN, requireNotNull(second).getOrThrow().status)
        port.read = null
        port.write = null
        var third: Result<PlaylistFollowResult>? = null
        coordinator.update(snap(), true) { third = it }
        assertEquals(PlaylistChangeStatus.APPLIED, requireNotNull(third).getOrThrow().status)
    }

    @Test fun otherPlatformIsNotBlockedByOngoingUpdate() {
        val port = Port()
        val coordinator = PlaylistSubscriptionCoordinator(port)
        var resume: ((Result<Unit>) -> Unit)? = null
        port.write = { snapshot, followed, done ->
            if (snapshot.playlist.key.platform == Platform.QQ) resume = done
            else { port.current = snapshot.copy(followed = followed); done(Result.success(Unit)) }
        }
        coordinator.update(snap(), true) {}
        val other = PlaylistSummary(MediaKey(Platform.NETEASE, "collect"), "Other", null, 3, PlaylistKind.COLLECTED)
        port.current = PlaylistSubscription(other, 7, false)
        var second: Result<PlaylistFollowResult>? = null
        coordinator.update(PlaylistSubscription(other, 7, false), true) { second = it }
        assertEquals(PlaylistChangeStatus.APPLIED, requireNotNull(second).getOrThrow().status)
        port.current = snap(followed = true)
        requireNotNull(resume)(Result.success(Unit))
    }
}
