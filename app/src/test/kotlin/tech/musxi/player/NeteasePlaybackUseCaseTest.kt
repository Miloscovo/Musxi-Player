package tech.musxi.player

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.accounts.NeteaseLibraryRepository
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*
import tech.musxi.playback.QueueInsertAt

class NeteasePlaybackUseCaseTest {
    @Test fun recommendationSnapshotCancelsPendingPlaylistAndRetainsOrderAndStart() {
        val songs = listOf("1", "2", "3").map { SongSummary(MediaKey(Platform.QQ, it), "Song $it", emptyList(), "", 1000) }
        val submitted = mutableListOf<PlaybackQueue>()
        var stale: ((Result<PlaybackQueue>) -> Unit)? = null
        var intents = 0
        val useCase = NeteasePlaybackUseCase({ _, _, result -> stale = result },
            { queue, result -> submitted += queue; result(Result.success(Unit)) }, {}, { 42 }, onNewIntent = { intents++ })
        useCase.playPlaylist(MediaKey(Platform.QQ, "12_0"), songs.first().key)
        var accepted = false
        useCase.playSnapshot(PlaybackQueue(songs, 2, 42)) { accepted = it.isSuccess }
        stale!!(Result.success(PlaybackQueue(songs, 0, 42)))
        assertTrue(accepted)
        assertEquals(2, intents)
        assertEquals(1, submitted.size)
        assertEquals(songs, submitted.single().songs)
        assertEquals(2, submitted.single().startIndex)
    }

    @Test fun recommendationSnapshotRejectsAnotherAccountGenerationAndMixedPlatforms() {
        val first = SongSummary(MediaKey(Platform.NETEASE, "1"), "Song", emptyList(), "", 1000)
        var submits = 0
        val useCase = NeteasePlaybackUseCase({ _, _, _ -> }, { _, _ -> submits++ }, {}, { 2 })
        var failed = 0
        useCase.playSnapshot(PlaybackQueue(listOf(first), 0, 1)) { if (it.isFailure) failed++ }
        useCase.playSnapshot(PlaybackQueue(listOf(first, first.copy(key = MediaKey(Platform.QQ, "2"))), 0, 2)) {
            if (it.isFailure) failed++
        }
        assertEquals(2, failed)
        assertEquals(0, submits)
    }

    @Test fun savedQueueUsesFreshGenerationAndRestoreSubmitWithoutOrdinaryAutoplay() {
        val song = SongSummary(MediaKey(Platform.NETEASE, "1"), "Fixture", emptyList(), "", 1000)
        val saved = tech.musxi.core.playback.SavedPlaybackQueue(listOf(song), 0, 500, "ALL", true, 10)
        var restored: PlaybackQueue? = null
        var received: tech.musxi.core.playback.SavedPlaybackQueue? = null
        var ack: ((Result<Unit>) -> Unit)? = null
        var ordinary = 0
        var completed = 0
        val useCase = NeteasePlaybackUseCase({ _, _, _ -> }, { _, _ -> ordinary++ }, {}, { 42 },
            { queue, state, callback -> restored = queue; received = state; ack = callback })
        useCase.restore(saved) { if (it.isSuccess) completed++ }
        assertEquals(42L, restored!!.accountGeneration)
        assertEquals(saved, received)
        assertEquals(0, ordinary)
        assertTrue(useCase.state.value.loading)
        ack!!(Result.success(Unit))
        assertFalse(useCase.state.value.loading)
        assertEquals(1, completed)
        useCase.restore(saved) { completed++ }
        useCase.cancelPending()
        ack!!(Result.success(Unit))
        assertEquals(1, completed)
    }

    private val playlist = MediaKey(Platform.NETEASE, "9")
    private val song = MediaKey(Platform.NETEASE, "1")
    private fun queue(generation: Long = 1) = PlaybackQueue(listOf(SongSummary(song, "Title", emptyList(), "", 1000)), 0, generation)
    private val pending = mutableListOf<(Result<PlaybackQueue>) -> Unit>()
    private val submitted = mutableListOf<PlaybackQueue>()
    private var stops = 0
    private fun useCase() = NeteasePlaybackUseCase({ _, _, callback -> pending.add(callback) },
        { queue, accepted -> submitted.add(queue); accepted(Result.success(Unit)) }, { stops++ })

    @Test fun searchSongReplacesPendingPlaylistWithOneAccountBoundItem() {
        val useCase = NeteasePlaybackUseCase({ _, _, callback -> pending.add(callback) },
            { queue, accepted -> submitted.add(queue); accepted(Result.success(Unit)) }, { stops++ }, { 7 })
        useCase.playPlaylist(playlist, song)
        useCase.playSong(SongSummary(song, "Search song", listOf("Artist"), "Album", 1234,
            "http://p1.music.126.net/image.jpg"))
        pending.single()(Result.success(queue()))
        assertEquals(1, submitted.size)
        assertEquals(7L, submitted.single().accountGeneration)
        assertEquals(0, submitted.single().startIndex)
        assertEquals("Search song", submitted.single().songs.single().title)
        assertEquals("https://p1.music.126.net/image.jpg", submitted.single().songs.single().coverUrl)
        assertEquals(QueueLoadState(), useCase.state.value)
    }

    @Test fun unverifiedSearchSongCannotSubmitAndCancelsOlderPendingWork() {
        val useCase = useCase()
        useCase.playPlaylist(playlist, song)
        useCase.playSong(queue().songs.single())
        pending.single()(Result.success(queue()))
        assertTrue(submitted.isEmpty())
        assertEquals(QueueLoadState(error = "account_unavailable"), useCase.state.value)
    }

    @Test fun stopRejectsAnAlreadyRunningPlaylistRead() {
        val useCase = useCase()
        useCase.playPlaylist(playlist, song)
        assertTrue(useCase.state.value.loading)
        assertEquals(0, stops)
        useCase.stop()
        pending.single()(Result.success(queue()))
        assertTrue(submitted.isEmpty())
        assertFalse(useCase.state.value.loading)
        assertEquals(1, stops)
    }

    @Test fun switchingPlatformsRejectsAnOlderPlaylistResponse() {
        val useCase = useCase()
        useCase.playPlaylist(playlist, song)
        val kugouSong = MediaKey(Platform.KUGOU, "a".repeat(32) + "_0_1")
        val kugouPlaylist = MediaKey(Platform.KUGOU, "10")
        useCase.playPlaylist(kugouPlaylist, kugouSong)
        pending[0](Result.success(queue()))
        assertTrue(submitted.isEmpty())
        val kugouQueue = PlaybackQueue(listOf(SongSummary(kugouSong, "Fixture", emptyList(), "", 1000)), 0, 1)
        pending[1](Result.success(kugouQueue))
        assertEquals(listOf(kugouQueue), submitted)
        assertEquals(QueueLoadState(), useCase.state.value)
    }

    @Test fun replacementRejectsOlderSuccessAndFailureWithoutClobberingNewState() {
        val useCase = useCase()
        useCase.playPlaylist(playlist, song)
        useCase.playPlaylist(playlist, song)
        pending[0](Result.success(queue(1)))
        assertTrue(useCase.state.value.loading)
        assertTrue(submitted.isEmpty())
        val latest = queue(2)
        pending[1](Result.success(latest))
        pending[0](Result.failure(Exception("private upstream text")))
        assertEquals(listOf(latest), submitted)
        assertEquals(QueueLoadState(), useCase.state.value)
    }

    @Test fun systemStopCancelsPendingReadWithoutRecursivelySendingAnotherStop() {
        val useCase = useCase()
        useCase.playPlaylist(playlist, song)
        useCase.cancelPending()
        pending.single()(Result.success(queue()))
        assertTrue(submitted.isEmpty())
        assertEquals(0, stops) // The system STOP owns the actual player clear.
        assertEquals(QueueLoadState(), useCase.state.value)
        useCase.playPlaylist(playlist, song)
        pending.last()(Result.success(queue(2)))
        assertEquals(1, submitted.size)
    }

    @Test fun failureStaysFixedAndDoesNotSubmitOrAutomaticallyRetry() {
        val useCase = useCase()
        useCase.playPlaylist(playlist, song)
        pending.single()(Result.failure(NeteaseLibraryRepository.LibraryFailure("incomplete")))
        assertEquals(QueueLoadState(error = "incomplete"), useCase.state.value)
        assertEquals(1, pending.size)
        assertTrue(submitted.isEmpty())
    }

    @Test fun playerRejectionDoesNotReportSuccessfulQueueLoad() {
        val useCase = NeteasePlaybackUseCase({ _, _, callback -> pending.add(callback) }, { _, _ -> throw IllegalStateException("private detail") }, {})
        useCase.playPlaylist(playlist, song)
        pending.single()(Result.success(queue()))
        assertEquals(QueueLoadState(error = "player"), useCase.state.value)
    }

    @Test fun waitsForServiceAcknowledgementAndReportsAsyncRejection() {
        val acks = mutableListOf<(Result<Unit>) -> Unit>()
        val useCase = NeteasePlaybackUseCase({ _, _, callback -> pending.add(callback) }, { _, ack -> acks.add(ack) }, {})
        useCase.playPlaylist(playlist, song)
        pending.single()(Result.success(queue()))
        assertTrue(useCase.state.value.loading)
        acks.single()(Result.failure(IllegalStateException("private service detail")))
        assertEquals(QueueLoadState(error = "player"), useCase.state.value)
    }

    @Test fun lateServiceAcknowledgementsCannotOverwriteNewRequestOrStop() {
        val acks = mutableListOf<(Result<Unit>) -> Unit>()
        val useCase = NeteasePlaybackUseCase({ _, _, callback -> pending.add(callback) }, { _, ack -> acks.add(ack) }, {})
        useCase.playPlaylist(playlist, song)
        pending[0](Result.success(queue()))
        useCase.playPlaylist(playlist, song)
        pending[1](Result.success(queue(2)))
        acks[0](Result.failure(IllegalStateException()))
        assertTrue(useCase.state.value.loading)
        acks[1](Result.success(Unit))
        assertEquals(QueueLoadState(), useCase.state.value)
        useCase.stop()
        acks[0](Result.failure(IllegalStateException()))
        assertEquals(QueueLoadState(), useCase.state.value)
    }

    @Test fun playlistStartsWithSelectedPageThenSeriallyInsertsBothSidesAndLaterPages() {
        val known = listOf(
            SongSummary(MediaKey(Platform.NETEASE, "0"), "before", emptyList(), "", 0),
            SongSummary(song, "selected", emptyList(), "", 0),
            SongSummary(MediaKey(Platform.NETEASE, "2"), "after-known", emptyList(), "", 0),
        )
        val headQueue = PlaybackQueue(listOf(known[1]), 0, 1)
        val pageAcks = mutableListOf<(Result<List<SongSummary>?>) -> Unit>()
        val batchCalls = mutableListOf<BatchCall>()
        val remainder = QueueRemainder(known.take(1), known.drop(2), { pageAcks += it }, {})
        val useCase = NeteasePlaybackUseCase(
            loadQueue = { _, _, _ -> fail("Full queue path used") },
            submitQueue = { _, ack -> ack(Result.success(Unit)) },
            stopPlayer = { stops++ },
            loadQueueHead = { _, _, received, result ->
                assertEquals(known, received)
                result(Result.success(PlaylistQueueHead(headQueue, remainder)))
            },
            enqueueBatch = { songs, at, id, epoch, ack -> batchCalls += BatchCall(songs, at, id, epoch, ack) },
            currentQueueEpoch = { "epoch-1" },
            scheduleBatchRetry = { _, action -> action() },
        )
        useCase.playPlaylist(playlist, song, known)
        assertEquals(0, stops)
        assertEquals(QueueLoadState(loading = true), useCase.state.value)
        assertEquals(QueueInsertAt.HEAD, batchCalls.single().at)
        val first = batchCalls.single()
        first.ack(Result.failure(IllegalStateException()))
        assertEquals(2, batchCalls.size)
        assertEquals(first.batchId, batchCalls[1].batchId)
        assertEquals(first.queueEpoch, batchCalls[1].queueEpoch)
        batchCalls[1].ack(Result.success(Unit))
        assertEquals(QueueInsertAt.TAIL, batchCalls[2].at)
        assertEquals("2", batchCalls[2].songs.single().key.id)
        batchCalls[2].ack(Result.success(Unit))
        assertEquals(1, pageAcks.size)
        val later = SongSummary(MediaKey(Platform.NETEASE, "3"), "later", emptyList(), "", 0)
        pageAcks.single()(Result.success(listOf(later)))
        assertEquals(QueueInsertAt.TAIL, batchCalls[3].at)
        assertEquals("3", batchCalls[3].songs.single().key.id)
        batchCalls[3].ack(Result.success(Unit))
        pageAcks.last()(Result.success(null))
        assertEquals(QueueLoadState(), useCase.state.value)
    }

    private data class BatchCall(
        val songs: List<SongSummary>,
        val at: QueueInsertAt,
        val batchId: String,
        val queueEpoch: String,
        val ack: (Result<Unit>) -> Unit,
    )
}
