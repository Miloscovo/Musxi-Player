package tech.musxi.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform

class LibraryPageRequestGateTest {
    private class Task(val due: Long, val seq: Long, val action: () -> Unit)

    /** Virtual main-thread clock: nothing runs until the test pumps it. */
    private class Clock {
        var now = 0L
        private var seq = 0L
        private val tasks = mutableListOf<Task>()

        fun schedule(delay: Long, action: () -> Unit) {
            assertTrue("gate must never schedule in the past: $delay", delay >= 0)
            tasks += Task(now + delay, seq++, action)
        }

        fun nextDue(): Long = tasks.minByOrNull { it.due }?.due ?: -1L

        fun advanceTo(target: Long) {
            require(target >= now)
            now = target
        }

        fun runNext(limit: Long = Long.MAX_VALUE): Boolean {
            val task = tasks.filter { it.due <= limit }
                .minWithOrNull(compareBy<Task>({ it.due }, { it.seq })) ?: return false
            tasks.remove(task)
            now = maxOf(now, task.due)
            task.action()
            return true
        }

        fun runAll() {
            repeat(100) { if (!runNext()) return }
            throw AssertionError("scheduler did not settle")
        }
    }

    private class FakeFetch : LibraryPageFetch {
        val playlistOffsets = mutableListOf<Int>()
        val trackOffsets = mutableListOf<Int>()
        private val playlistCallbacks = mutableMapOf<Int, (Result<Page<PlaylistSummary>>) -> Unit>()
        private val trackCallbacks = mutableMapOf<Int, (Result<Page<SongSummary>>) -> Unit>()
        var throwOnPlaylists = false

        override fun playlists(offset: Int, result: (Result<Page<PlaylistSummary>>) -> Unit) {
            playlistOffsets += offset
            if (throwOnPlaylists) throw IllegalStateException("boom")
            playlistCallbacks[offset] = result
        }

        override fun tracks(playlist: MediaKey, offset: Int, result: (Result<Page<SongSummary>>) -> Unit) {
            trackOffsets += offset
            trackCallbacks[offset] = result
        }

        fun completePlaylists(offset: Int, response: Result<Page<PlaylistSummary>>) {
            playlistCallbacks.getValue(offset)(response)
        }

        fun completeTracks(offset: Int, response: Result<Page<SongSummary>>) {
            trackCallbacks.getValue(offset)(response)
        }
    }

    private fun playlistsOk() = Result.success(Page(emptyList<PlaylistSummary>(), null, 0))
    private fun tracksOk() = Result.success(Page(emptyList<SongSummary>(), null, 0))
    private fun category(result: Result<*>): String? =
        (result.exceptionOrNull() as? LibraryPageFailure)?.category

    @Test fun intervalFollowsPlatformFromCompletion() {
        listOf(
            Platform.NETEASE to 500L,
            Platform.QQ to 750L,
            Platform.KUGOU to 1_000L,
        ).forEach { (platform, interval) ->
            val clock = Clock()
            val fetch = FakeFetch()
            val gate = LibraryPageRequestGate(fetch, platform, clock::schedule, { clock.now })

            gate.playlists(0) { }
            clock.runAll()
            assertEquals("$platform starts immediately", 1, fetch.playlistOffsets.size)

            clock.advanceTo(100)
            fetch.completePlaylists(0, playlistsOk())
            gate.playlists(20) { }
            assertEquals("$platform next due", 100L + interval, clock.nextDue())

            clock.runNext(100L + interval - 1)
            assertEquals("$platform still idling", 1, fetch.playlistOffsets.size)
            clock.runAll()
            assertEquals("$platform second request runs", listOf(0, 20), fetch.playlistOffsets)
        }
    }

    @Test fun onlyOneRequestInFlightAtATime() {
        val clock = Clock()
        val fetch = FakeFetch()
        val gate = LibraryPageRequestGate(fetch, Platform.NETEASE, clock::schedule, { clock.now })
        val results = mutableListOf<Int>()

        gate.playlists(0) { results += 0 }
        gate.playlists(10) { results += 10 }
        gate.playlists(20) { results += 20 }
        clock.runAll()
        assertEquals("one fetch only", listOf(0), fetch.playlistOffsets)
        assertEquals("no result before completion", emptyList<Int>(), results)

        clock.advanceTo(1_000)
        fetch.completePlaylists(0, playlistsOk())
        assertEquals(listOf(0), results)
        clock.runAll()
        assertEquals("queued requests stay serial", listOf(0, 10), fetch.playlistOffsets)
    }

    @Test fun manualPlaylistsAndFillingTracksShareOneLimit() {
        val clock = Clock()
        val fetch = FakeFetch()
        val gate = LibraryPageRequestGate(fetch, Platform.QQ, clock::schedule, { clock.now })
        val playlist = MediaKey(Platform.QQ, "pl")

        gate.playlists(0) { }
        clock.runAll()
        clock.advanceTo(200)
        fetch.completePlaylists(0, playlistsOk())

        gate.tracks(playlist, 0) { }
        assertEquals("tracks wait for the playlist fetch", 950L, clock.nextDue())
        clock.runNext(949L)
        assertEquals(emptyList<Int>(), fetch.trackOffsets)
        clock.runAll()
        assertEquals(listOf(0), fetch.trackOffsets)

        clock.advanceTo(1_200)
        fetch.completeTracks(0, tracksOk())
        gate.playlists(30) { }
        assertEquals("playlists wait for the filling fetch", 1_200L + 750L, clock.nextDue())
    }

    @Test fun rateAndRiskBackOffNextRequestButFailCurrentImmediately() {
        listOf("rate" to 30_000L, "risk" to 60_000L).forEach { (code, backoff) ->
            val clock = Clock()
            val fetch = FakeFetch()
            val gate = LibraryPageRequestGate(fetch, Platform.NETEASE, clock::schedule, { clock.now })
            val results = mutableListOf<Result<Page<PlaylistSummary>>>()

            gate.playlists(0) { results += it }
            clock.runAll()
            clock.advanceTo(400)
            fetch.completePlaylists(0, Result.failure(LibraryPageFailure(code)))
            assertEquals("$code error returned at once", 1, results.size)
            assertEquals(code, category(results[0]))
            assertEquals("$code never retries by itself", 1, fetch.playlistOffsets.size)

            gate.playlists(50) { results += it }
            assertEquals("$code backoff", 400L + backoff, clock.nextDue())
            clock.runNext(400L + backoff - 1)
            assertEquals("$code still backing off", 1, fetch.playlistOffsets.size)
            clock.runAll()
            assertEquals("$code queue resumes", listOf(0, 50), fetch.playlistOffsets)
        }
    }

    @Test fun cancelledWaiterNeverFetchesAndDoesNotBlockQueue() {
        val clock = Clock()
        val fetch = FakeFetch()
        val gate = LibraryPageRequestGate(fetch, Platform.KUGOU, clock::schedule, { clock.now })
        val cancelled = mutableListOf<Result<Page<SongSummary>>>()

        gate.playlists(0) { }
        clock.runAll()
        gate.tracks(MediaKey(Platform.KUGOU, "stale"), 0, { false }) { cancelled += it }
        gate.playlists(40) { }

        clock.advanceTo(0)
        fetch.completePlaylists(0, playlistsOk())
        clock.runAll()

        assertEquals("stale fetch skipped", emptyList<Int>(), fetch.trackOffsets)
        assertEquals("cancelled reported once", 1, cancelled.size)
        assertEquals("cancelled", category(cancelled[0]))
        assertEquals("queue continues", listOf(0, 40), fetch.playlistOffsets)
    }

    @Test fun synchronousThrowIsReportedOnceAndGateKeepsWorking() {
        val clock = Clock()
        val fetch = FakeFetch()
        val gate = LibraryPageRequestGate(fetch, Platform.NETEASE, clock::schedule, { clock.now })
        val results = mutableListOf<Result<Page<PlaylistSummary>>>()
        fetch.throwOnPlaylists = true

        gate.playlists(0) { results += it }
        clock.runAll()
        assertEquals("failure delivered once", 1, results.size)
        assertEquals("unclassified", category(results[0]))

        fetch.throwOnPlaylists = false
        gate.playlists(60) { results += it }
        assertEquals("normal interval after the failure", 500L, clock.nextDue())
        clock.runAll()
        assertEquals("gate is not stuck busy", listOf(0, 60), fetch.playlistOffsets)
        fetch.completePlaylists(60, playlistsOk())
        assertEquals(2, results.size)
    }

    @Test fun duplicateCallbackDeliversOnceAndDoesNotWedgeGate() {
        val clock = Clock()
        val fetch = FakeFetch()
        val gate = LibraryPageRequestGate(fetch, Platform.QQ, clock::schedule, { clock.now })
        val first = mutableListOf<Result<Page<PlaylistSummary>>>()

        gate.playlists(0) { first += it }
        clock.runAll()
        clock.advanceTo(300)
        fetch.completePlaylists(0, playlistsOk())
        fetch.completePlaylists(0, Result.failure(LibraryPageFailure("rate")))
        assertEquals("duplicate swallowed", 1, first.size)
        assertTrue(first[0].isSuccess)

        gate.playlists(80) { }
        assertEquals("normal interval kept", 300L + 750L, clock.nextDue())
        clock.runAll()
        assertEquals(listOf(0, 80), fetch.playlistOffsets)
    }
}
