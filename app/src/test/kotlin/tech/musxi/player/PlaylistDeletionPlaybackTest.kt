package tech.musxi.player

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*

class PlaylistDeletionPlaybackTest {
    private val key = MediaKey(Platform.NETEASE, "9")
    private val song = SongSummary(MediaKey(Platform.NETEASE, "1"), "Song", emptyList(), "", 1000)
    private val queue = PlaybackQueue(listOf(song), 0, 1)

    @Test fun deletionFencesAnUnfinishedLoadWithoutStoppingAcceptedPlayback() {
        lateinit var pending: (Result<PlaybackQueue>) -> Unit
        var submits = 0
        var stops = 0
        lateinit var useCase: NeteasePlaybackUseCase
        useCase = NeteasePlaybackUseCase({ _, _, done -> pending = done },
            { _, done -> submits++; done(Result.success(Unit)) }, { stops++ }, { 1 },
            onNewIntent = { useCase.cancelPending() })
        useCase.playPlaylist(key, song.key)
        assertTrue(useCase.state.value.loading)
        useCase.playlistDeleted(key)
        pending(Result.success(queue))
        assertFalse(useCase.state.value.loading)
        assertEquals(0, submits)
        assertEquals(0, stops)
    }

    @Test fun deletingAnotherPlaylistDoesNotCancelTheSelectedOne() {
        lateinit var pending: (Result<PlaybackQueue>) -> Unit
        var submits = 0
        val useCase = NeteasePlaybackUseCase({ _, _, done -> pending = done },
            { _, done -> submits++; done(Result.success(Unit)) }, {})
        useCase.playPlaylist(key, song.key)
        useCase.playlistDeleted(key.copy(id = "10"))
        pending(Result.success(queue))
        assertEquals(1, submits)
    }

    @Test fun deletingSourceAfterPlaybackAcceptanceKeepsTheAcceptedQueue() {
        var stops = 0
        var submits = 0
        val useCase = NeteasePlaybackUseCase({ _, _, done -> done(Result.success(queue)) },
            { _, done -> submits++; done(Result.success(Unit)) }, { stops++ })
        useCase.playPlaylist(key, song.key)
        useCase.playlistDeleted(key)
        assertEquals(1, submits)
        assertEquals(0, stops)
    }

    @Test fun deletionCancelsTheActualRemainderAndDiscardsItsLatePage() {
        lateinit var pending: (Result<List<SongSummary>?>) -> Unit
        var cancelled = 0
        var inserted = 0
        var stopped = 0
        val remainder = QueueRemainder(emptyList(), emptyList(), { pending = it }, { cancelled++ })
        val useCase = NeteasePlaybackUseCase({ _, _, _ -> fail("head expected") },
            { _, done -> done(Result.success(Unit)) }, { stopped++ },
            loadQueueHead = { _, _, _, done -> done(Result.success(PlaylistQueueHead(queue, remainder))) },
            enqueueBatch = { _, _, _, _, done -> inserted++; done(Result.success(Unit)) },
            currentQueueEpoch = { "accepted-epoch" })
        useCase.playPlaylist(key, song.key, listOf(song))
        assertTrue(useCase.state.value.loading)
        useCase.playlistDeleted(key)
        pending(Result.success(listOf(song.copy(key = song.key.copy(id = "2")))))
        assertEquals(1, cancelled)
        assertEquals(0, inserted)
        assertEquals(0, stopped)
        assertFalse(useCase.state.value.loading)
    }
}
