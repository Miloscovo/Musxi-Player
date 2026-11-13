package tech.musxi.core.library

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform

class PlaylistMutationGateTest {
    @Test fun conflictingWriteIsRejectedWhileOtherPlatformsRemainIndependent() {
        val gate = PlaylistMutationGate()
        lateinit var finish: (Result<Unit>) -> Unit
        gate.run<Unit>(Platform.QQ, { assertTrue(it.isSuccess) }) { finish = it }
        assertEquals(setOf(Platform.QQ), gate.busyPlatforms.value)
        var called = false
        gate.run<Unit>(Platform.QQ, {
            assertEquals("busy", (it.exceptionOrNull() as LibraryPageFailure).category)
        }) { called = true }
        assertFalse(called)
        gate.run<Unit>(Platform.NETEASE, { assertTrue(it.isSuccess) }) { it(Result.success(Unit)) }
        assertEquals(setOf(Platform.QQ), gate.busyPlatforms.value)
        finish(Result.success(Unit))
        assertTrue(gate.busyPlatforms.value.isEmpty())
    }

    @Test fun synchronousFailureReleasesThePlatformAndLateCompletionCannotReleaseANewerWrite() {
        val gate = PlaylistMutationGate()
        gate.run<Unit>(Platform.QQ, { assertTrue(it.isFailure) }) { throw IllegalStateException("fixture") }
        assertTrue(gate.busyPlatforms.value.isEmpty())
        lateinit var old: (Result<Unit>) -> Unit
        var deliveries = 0
        gate.run<Unit>(Platform.QQ, { deliveries++ }) { old = it }
        old(Result.success(Unit))
        lateinit var newer: (Result<Unit>) -> Unit
        gate.run<Unit>(Platform.QQ, { deliveries++ }) { newer = it }
        old(Result.success(Unit))
        assertEquals(1, deliveries)
        assertEquals(setOf(Platform.QQ), gate.busyPlatforms.value)
        newer(Result.success(Unit))
        assertEquals(2, deliveries)
        assertTrue(gate.busyPlatforms.value.isEmpty())
    }

    @Test fun resultCanStartANewOperationAndItsExceptionIsNotRedelivered() {
        val gate = PlaylistMutationGate()
        var deliveries = 0
        val failure = IllegalStateException("caller")
        try {
            gate.run<Unit>(Platform.QQ, {
                deliveries++
                gate.run<Unit>(Platform.QQ, { assertTrue(it.isSuccess) }) { it(Result.success(Unit)) }
                throw failure
            }) { it(Result.success(Unit)) }
            fail("caller exception must propagate")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }
        assertEquals(1, deliveries)
        assertTrue(gate.busyPlatforms.value.isEmpty())
    }
}
