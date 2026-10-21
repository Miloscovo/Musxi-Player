package tech.musxi.player

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.accounts.NeteaseLibraryRepository
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*

class NeteasePlaybackUseCaseTest {
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
        useCase.stop()
        pending.single()(Result.success(queue()))
        assertTrue(submitted.isEmpty())
        assertFalse(useCase.state.value.loading)
        assertEquals(2, stops)
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
        assertEquals(1, stops) // Only the internal pre-clear; system STOP must not send another one.
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
}
