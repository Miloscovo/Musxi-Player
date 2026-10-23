package tech.musxi.playback

import org.junit.Assert.*
import org.junit.Test

class ControllerRecoveryTest {
    private class Resource(val id: Int)
    private data class Attempt(val token: Long, val result: (Result<Resource>) -> Unit, var cancelled: Boolean = false)
    private data class Timer(val at: Long, val run: () -> Unit, var cancelled: Boolean = false)
    private class Fixture {
        var time = 0L
        val attempts = mutableListOf<Attempt>()
        val timers = mutableListOf<Timer>()
        val released = mutableListOf<Resource>()
        val attached = mutableListOf<Resource>()
        val changes = mutableListOf<ControllerConnectionState>()
        val connection = ControllerRecovery<Resource>(
            connect = { token, result ->
                val attempt = Attempt(token, result).also(attempts::add)
                val cancel: () -> Unit = { attempt.cancelled = true }
                cancel
            },
            release = { released.add(it) },
            schedule = { delay, run ->
                val timer = Timer(time + delay, run).also(timers::add)
                val cancel: () -> Unit = { timer.cancelled = true }
                cancel
            }, now = { time }, attached = { attached.add(it) }, changed = { changes.add(it) })
        fun advance(delta: Long) {
            val end = time + delta
            while (true) {
                val next = timers.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
                time = next.at; next.cancelled = true; next.run()
            }
            time = end
        }
        fun success(index: Int = attempts.lastIndex): Resource = Resource(index).also { attempts[index].result(Result.success(it)) }
        fun fail(index: Int = attempts.lastIndex) { attempts[index].result(Result.failure(IllegalStateException("private error"))) }
    }

    @Test fun initialConnectionAttachesOnceAndBusyRetryDoesNotCreateAnotherController() {
        val f = Fixture(); f.connection.start(); f.connection.start(); f.connection.retry()
        assertEquals(1, f.attempts.size)
        val resource = f.success()
        f.connection.retry(); f.advance(60_000)
        assertEquals(listOf(resource), f.attached)
        assertEquals(PlaybackConnectionPhase.CONNECTED, f.connection.state.phase)
        assertEquals(1, f.attempts.size)
        assertTrue(f.released.isEmpty())
    }

    @Test fun threeFailuresEndAutomaticRetriesAndExplicitRetryStartsFreshBudget() {
        val f = Fixture(); f.connection.start(); f.fail()
        assertEquals(PlaybackConnectionPhase.RETRY_WAIT, f.connection.state.phase)
        f.connection.retry(); f.advance(999); assertEquals(1, f.attempts.size)
        f.advance(1); f.fail(); f.advance(2_999); assertEquals(2, f.attempts.size)
        f.advance(1); f.fail(); f.advance(100_000)
        assertEquals(3, f.attempts.size)
        assertEquals(PlaybackConnectionPhase.FAILED, f.connection.state.phase)
        assertEquals(3, f.connection.state.attempt)
        f.connection.retry(); assertEquals(4, f.attempts.size)
        assertEquals(1, f.connection.state.attempt)
        f.success(); assertEquals(PlaybackConnectionPhase.CONNECTED, f.connection.state.phase)
    }

    @Test fun timeoutCancelsPendingConnectAndLateSuccessIsReleasedWithoutAttachment() {
        val f = Fixture(); f.connection.start(); f.advance(10_000)
        assertTrue(f.attempts[0].cancelled)
        val late = f.success(0)
        assertEquals(listOf(late), f.released)
        assertTrue(f.attached.isEmpty())
        f.advance(1_000); val current = f.success(1)
        assertEquals(listOf(current), f.attached)
    }

    @Test fun oldDisconnectAndFailureCannotReplaceNewConnection() {
        val f = Fixture(); f.connection.start(); val first = f.success()
        f.connection.disconnected(f.attempts[0].token)
        assertEquals(listOf(first), f.released)
        f.advance(1_000); val current = f.success()
        f.connection.disconnected(f.attempts[0].token); f.fail(0)
        assertEquals(PlaybackConnectionPhase.CONNECTED, f.connection.state.phase)
        assertEquals(listOf(first, current), f.attached)
        assertEquals(listOf(first), f.released)
    }

    @Test fun rapidConnectDisconnectLoopsExhaustTheSameBudget() {
        val f = Fixture(); f.connection.start(); f.success()
        f.connection.disconnected(f.attempts.last().token)
        f.advance(1_000); f.success(); f.connection.disconnected(f.attempts.last().token)
        f.advance(3_000); f.success(); f.connection.disconnected(f.attempts.last().token)
        f.advance(100_000)
        assertEquals(3, f.attempts.size)
        assertEquals(3, f.released.size)
        assertEquals(PlaybackConnectionPhase.FAILED, f.connection.state.phase)
    }

    @Test fun stableConnectionAllowsANewFiniteRecoveryBudgetAfterLaterDisconnect() {
        val f = Fixture(); f.connection.start(); f.fail(); f.advance(1_000); f.success()
        f.advance(30_000); f.connection.disconnected(f.attempts.last().token)
        f.advance(1_000)
        assertEquals(1, f.connection.state.attempt)
        assertEquals(3, f.attempts.size)
    }

    @Test fun duplicateSuccessDoesNotAttachAgainOrReleaseTheAcceptedResource() {
        val f = Fixture(); f.connection.start(); val resource = f.success()
        f.attempts.single().result(Result.success(resource))
        assertEquals(1, f.attached.size); assertTrue(f.released.isEmpty())
        val unexpected = Resource(99)
        f.attempts.single().result(Result.success(unexpected))
        assertEquals(listOf(unexpected), f.released)
    }

    @Test fun closeCancelsPendingTimersAndReleasesLateResults() {
        val f = Fixture(); f.connection.start(); f.connection.close(); f.connection.close()
        assertTrue(f.attempts.single().cancelled)
        val late = f.success(); f.advance(100_000); f.connection.retry(); f.connection.start()
        assertEquals(PlaybackConnectionPhase.CLOSED, f.connection.state.phase)
        assertEquals(listOf(late), f.released)
        assertTrue(f.attached.isEmpty()); assertEquals(1, f.attempts.size)
    }

    @Test fun closeWhileWaitingCancelsRetryAndCloseWhileConnectedReleasesController() {
        val waiting = Fixture(); waiting.connection.start(); waiting.fail(); waiting.connection.close(); waiting.advance(50_000)
        assertEquals(1, waiting.attempts.size)
        val connected = Fixture(); connected.connection.start(); val resource = connected.success(); connected.connection.close()
        assertEquals(listOf(resource), connected.released)
    }

    @Test fun synchronousConnectorAndFactoryExceptionsUseTheSameLifecycle() {
        val attached = mutableListOf<Resource>(); val released = mutableListOf<Resource>()
        val resource = Resource(1)
        val connection = ControllerRecovery<Resource>(
            connect = { _, result -> result(Result.success(resource)); { fail("accepted connection must not be cancelled") } },
            release = { released.add(it) }, schedule = { _, _ -> {} }, now = { 0 },
            attached = { attached.add(it) }, changed = {})
        connection.start(); assertEquals(listOf(resource), attached)
        connection.close(); assertEquals(listOf(resource), released)
        val failing = ControllerRecovery<Resource>(connect = { _, _ -> throw IllegalStateException() },
            release = {}, schedule = { _, _ -> {} }, now = { 0 }, attached = {}, changed = {})
        failing.start(); assertEquals(PlaybackConnectionPhase.RETRY_WAIT, failing.state.phase)
    }
}
