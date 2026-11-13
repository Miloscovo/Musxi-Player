package tech.musxi.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tech.musxi.core.account.Platform

/**
 * PlaylistDeletionCoordinator against a fake port: one fresh before-read, at most one delete
 * request, and one fresh after-read even when the write failed. A missing row is never deletion
 * evidence; a protected, stale-generation or duplicated request never reaches a write.
 */
class PlaylistDeletionTest {
    private fun target(id: String = "21", kind: PlaylistKind = PlaylistKind.CREATED,
        favorite: Boolean = false, accountGeneration: Long = GENERATION) = EditablePlaylist(
        PlaylistSummary(MediaKey(Platform.KUGOU, id), id, null, 3, kind), accountGeneration, favorite)

    private fun qqTarget(id: String = "q1") = EditablePlaylist(
        PlaylistSummary(MediaKey(Platform.QQ, id), id, null, 0, PlaylistKind.CREATED), GENERATION, false)

    /**
     * Answers each port call from a scripted queue, so a test states the exact remote sequence:
     * read 1 (before), read 2 (after), ... A read or write may be held to observe in-flight state.
     */
    private class FakePort : PlaylistDeletionPort {
        val log = mutableListOf<String>()
        val deletes = mutableListOf<EditablePlaylist>()
        private val reads = ArrayDeque<Result<Boolean>>()
        var writeResult: Result<Unit> = Result.success(Unit)
        var generationValue: Long? = GENERATION
        /** 1-based read ordinal to hold, or 0 for none. */
        var holdReadAt = 0
        var holdWrite = false
        private var readOrdinal = 0
        private val heldReads = mutableListOf<() -> Unit>()
        private val heldWrites = mutableListOf<() -> Unit>()
        val hasHeldCall get() = heldReads.isNotEmpty() || heldWrites.isNotEmpty()

        fun reads(vararg values: Boolean) = apply { values.forEach { reads += Result.success(it) } }
        fun reads(value: Result<Boolean>) = apply { reads += value }

        override fun generation(platform: Platform): Long? = generationValue

        override fun exists(target: EditablePlaylist, result: (Result<Boolean>) -> Unit) {
            log += "exists"
            readOrdinal++
            val answer = if (reads.isEmpty()) Result.success(false) else reads.removeFirst()
            if (readOrdinal == holdReadAt) { heldReads += { result(answer) }; return }
            result(answer)
        }

        override fun delete(target: EditablePlaylist, result: (Result<Unit>) -> Unit) {
            log += "delete"
            deletes += target
            if (holdWrite) { holdWrite = false; heldWrites += { result(writeResult) }; return }
            result(writeResult)
        }

        fun releaseRead() {
            val callbacks = heldReads.toList()
            heldReads.clear()
            callbacks.forEach { it() }
        }

        fun releaseWrite() {
            val callbacks = heldWrites.toList()
            heldWrites.clear()
            callbacks.forEach { it() }
        }
    }

    private class Call {
        var value: Result<PlaylistDeletionResult>? = null
        val done get() = value != null
        fun deliver(): Result<PlaylistDeletionResult> =
            requireNotNull(value) { "the coordinator did not deliver a result" }
    }

    private fun submit(coordinator: PlaylistDeletionCoordinator,
        target: EditablePlaylist): Call {
        val call = Call()
        coordinator.delete(target) { call.value = it }
        return call
    }

    private fun run(port: FakePort, target: EditablePlaylist = target()): Result<PlaylistDeletionResult> =
        submit(PlaylistDeletionCoordinator(port), target).deliver()

    private fun category(result: Result<PlaylistDeletionResult>): String {
        assertTrue("expected a failure result", result.isFailure)
        return (result.exceptionOrNull() as LibraryPageFailure).category
    }

    @Test fun oneFreshBeforeReadThenAtMostOneDeleteThenOneFreshAfterRead() {
        val port = FakePort().reads(true, false)
        val result = run(port).getOrThrow()

        assertEquals(listOf("exists", "delete", "exists"), port.log)
        assertEquals("a second write is never attempted", 1, port.deletes.size)
        assertEquals(target(), port.deletes.single())
        assertEquals(PlaylistChangeStatus.APPLIED, result.status)
        assertEquals(true, result.confirmedAbsent)
        assertEquals(target().playlist.key, result.playlist)
        assertNull(result.error)
    }

    @Test fun alreadyMissingTargetIsUnchangedAndNeverWritten() {
        val port = FakePort().reads(false)
        val result = run(port).getOrThrow()

        assertEquals(PlaylistChangeStatus.UNCHANGED, result.status)
        assertEquals(true, result.confirmedAbsent)
        assertNull(result.error)
        assertEquals(listOf("exists"), port.log)
        assertTrue(port.deletes.isEmpty())
    }

    @Test fun protectedTargetsAreRejectedBeforeAnyPortCall() {
        val cases = listOf(
            target(favorite = true) to "favorite",
            target(kind = PlaylistKind.LIKED) to "liked",
            target(kind = PlaylistKind.COLLECTED) to "collected",
            target(kind = PlaylistKind.OTHER) to "other",
        )
        for ((case, label) in cases) {
            val port = FakePort().reads(true, false)
            assertEquals("permission", category(run(port, case)))
            assertTrue("$label must not reach the port", port.log.isEmpty())
        }
    }

    @Test fun staleOrDisconnectedGenerationIsRejectedBeforeListing() {
        val stale = FakePort().reads(true, false).apply { generationValue = GENERATION + 1 }
        assertEquals("account_changed", category(run(stale)))
        assertTrue(stale.log.isEmpty())

        val signedOut = FakePort().reads(true, false).apply { generationValue = null }
        assertEquals("account_changed", category(run(signedOut)))
        assertTrue(signedOut.log.isEmpty())
    }

    @Test fun failedBeforeReadIsNotAnAbsentRowAndNeverWrites() {
        val port = FakePort().reads(Result.failure(LibraryPageFailure("platform")))
        val result = run(port).getOrThrow()

        // A failed read is a reported FAILED outcome, never a thrown "absent" verdict.
        assertEquals(PlaylistChangeStatus.FAILED, result.status)
        assertEquals("platform", result.error)
        assertNull("a failed read proves nothing about absence", result.confirmedAbsent)
        assertEquals(listOf("exists"), port.log)
        assertTrue(port.deletes.isEmpty())
    }

    @Test fun lostWriteResponseWithAbsentAfterReadIsApplied() {
        val port = FakePort().reads(true, false)
        port.writeResult = Result.failure(java.io.IOException("fixture socket closed"))
        val result = run(port).getOrThrow()

        assertEquals("the after-read decides, not the lost answer", PlaylistChangeStatus.APPLIED, result.status)
        assertEquals(true, result.confirmedAbsent)
        assertNull(result.error)
        assertEquals(1, port.deletes.size)
        assertEquals("the write failure still reads back", 2, port.log.count { it == "exists" })
    }

    @Test fun stillPresentAfterACompletedWriteIsFailedNotConfirmed() {
        val port = FakePort().reads(true, true)
        val result = run(port).getOrThrow()

        assertEquals(PlaylistChangeStatus.FAILED, result.status)
        assertEquals(false, result.confirmedAbsent)
        assertEquals("not_confirmed", result.error)
        assertEquals(1, port.deletes.size)
    }

    @Test fun stillPresentAfterAFailedWriteKeepsTheWriteCategory() {
        val port = FakePort().reads(true, true)
        port.writeResult = Result.failure(LibraryPageFailure("timeout"))
        val result = run(port).getOrThrow()

        assertEquals(PlaylistChangeStatus.FAILED, result.status)
        assertEquals(false, result.confirmedAbsent)
        assertEquals("timeout", result.error)
        assertEquals(1, port.deletes.size)
    }

    @Test fun unreadableAfterReadIsUnknownAndClaimsNoAbsence() {
        val port = FakePort().reads(true).reads(Result.failure(LibraryPageFailure("incomplete")))
        val result = run(port).getOrThrow()

        assertEquals(PlaylistChangeStatus.UNKNOWN, result.status)
        assertNull(result.confirmedAbsent)
        assertEquals("incomplete", result.error)
        assertEquals("the confirmation read is never repeated automatically", 1, port.deletes.size)
        assertEquals(2, port.log.count { it == "exists" })
    }

    @Test fun accountSwitchWhileTheBeforeReadIsInFlightSkipsTheWrite() {
        val port = FakePort().reads(true).apply { holdReadAt = 1 }
        val coordinator = PlaylistDeletionCoordinator(port)
        val call = submit(coordinator, target())
        assertTrue(port.hasHeldCall)

        port.generationValue = GENERATION + 1
        port.releaseRead()

        val result = call.deliver().getOrThrow()
        // Nothing was written yet, so an early switch is a definite FAILED, not an unknown outcome.
        assertEquals(PlaylistChangeStatus.FAILED, result.status)
        assertEquals("account_changed", result.error)
        assertNull(result.confirmedAbsent)
        assertTrue(port.deletes.isEmpty())
    }

    @Test fun accountSwitchWhileTheWriteIsInFlightNeverConfirms() {
        val port = FakePort().reads(true, false).apply { holdWrite = true }
        val coordinator = PlaylistDeletionCoordinator(port)
        val call = submit(coordinator, target())
        assertTrue(port.hasHeldCall)
        assertEquals(1, port.deletes.size)

        port.generationValue = GENERATION + 1
        port.releaseWrite()

        val result = call.deliver().getOrThrow()
        assertEquals(PlaylistChangeStatus.UNKNOWN, result.status)
        assertEquals("account_changed", result.error)
        assertNull(result.confirmedAbsent)
    }

    @Test fun accountSwitchWhileTheAfterReadIsInFlightNeverConfirms() {
        val port = FakePort().reads(true, false).apply { holdReadAt = 2 }
        val coordinator = PlaylistDeletionCoordinator(port)
        val call = submit(coordinator, target())
        assertTrue(port.hasHeldCall)
        assertEquals(1, port.deletes.size)

        port.generationValue = GENERATION + 1
        port.releaseRead()

        val result = call.deliver().getOrThrow()
        assertEquals(PlaylistChangeStatus.UNKNOWN, result.status)
        assertEquals("account_changed", result.error)
        assertNull(result.confirmedAbsent)
        assertEquals("no additional write after the switch", 1, port.deletes.size)
    }

    @Test fun duplicateRequestForTheSamePlatformIsBusyWhileTheFirstIsInFlight() {
        val port = FakePort().reads(true, false).apply { holdReadAt = 1 }
        val coordinator = PlaylistDeletionCoordinator(port)
        val first = submit(coordinator, target())
        assertEquals("busy", category(submit(coordinator, target()).deliver()))
        assertEquals("the busy request must not reach the port", 1, port.log.size)

        port.releaseRead()
        assertEquals(PlaylistChangeStatus.APPLIED, first.deliver().getOrThrow().status)
        assertEquals(1, port.deletes.size)
    }

    @Test fun anotherPlatformIsNotBlockedByAnInFlightDelete() {
        val port = FakePort().reads(true, true, false, false).apply { holdReadAt = 1 }
        val coordinator = PlaylistDeletionCoordinator(port)
        val kugou = submit(coordinator, target())

        assertEquals(PlaylistChangeStatus.APPLIED, submit(coordinator, qqTarget()).deliver().getOrThrow().status)

        port.releaseRead()
        assertEquals(PlaylistChangeStatus.APPLIED, kugou.deliver().getOrThrow().status)
        assertEquals("the second platform really completes while the first is still held",
            listOf(qqTarget().playlist.key, target().playlist.key),
            port.deletes.map { it.playlist.key })
    }

    @Test fun aLateCallbackCompletesTheRequestAndReleasesTheLock() {
        val port = FakePort().reads(true, false).reads(true, false).apply { holdWrite = true }
        val coordinator = PlaylistDeletionCoordinator(port)
        val late = submit(coordinator, target())
        assertFalse("the late path stays unfinished", late.done)
        assertEquals("lock is still held", "busy", category(submit(coordinator, target()).deliver()))

        port.releaseWrite()
        assertEquals(PlaylistChangeStatus.APPLIED, late.deliver().getOrThrow().status)

        assertEquals("the released lock allows one new attempt",
            PlaylistChangeStatus.APPLIED, submit(coordinator, target()).deliver().getOrThrow().status)
        assertEquals(2, port.deletes.size)
    }

    companion object {
        private const val GENERATION = 7L
    }
}
