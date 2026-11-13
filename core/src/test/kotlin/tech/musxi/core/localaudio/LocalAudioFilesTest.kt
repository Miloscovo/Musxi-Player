package tech.musxi.core.localaudio

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAudioFilesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    /** The managed directory is the configured directory itself, not a nested one. */
    private fun managedDirectory(): File = File(temporary.newFolder("files"), "local-audio-v1")

    @Test
    fun streamsContentIntoHashedCopyInTheConfiguredDirectory() {
        val directory = managedDirectory()
        val store = LocalAudioFiles(directory)
        val payload = content(200_000)
        val progress = ArrayList<Long>()

        val copied = store.copy(
            { ByteArrayInputStream(payload) },
            payload.size.toLong(),
            progress = { progress += it },
        )

        assertEquals(sha256(payload), copied.name)
        assertEquals(64, copied.name.length)
        assertFalse(copied.name.contains('.'))
        assertEquals(payload.size.toLong(), copied.bytes)
        assertTrue(copied.created)
        val published = store.resolve(copied.name)
        assertEquals(directory.canonicalFile, published.parentFile.canonicalFile)
        assertEquals(copied.name + ".audio", published.name)
        assertArrayEquals(payload, published.readBytes())
        assertTrue(progress.isNotEmpty())
        assertEquals(payload.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (first, second) -> first < second })
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".tmp") })
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test
    fun repeatedCopyReusesPublishedFileWithoutOverwriting() {
        val directory = managedDirectory()
        val store = LocalAudioFiles(directory)
        val payload = content(70_000)
        val first = store.copy({ ByteArrayInputStream(payload) })
        val published = store.resolve(first.name)
        assertTrue(published.setLastModified(1_000_000L))

        val second = store.copy({ ByteArrayInputStream(payload) }, payload.size.toLong())

        assertEquals(first.name, second.name)
        assertFalse(second.created)
        assertEquals(1_000_000L, published.lastModified())
        assertArrayEquals(payload, published.readBytes())
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test
    fun resolveDoesNotRehashStoredContent() {
        val directory = managedDirectory()
        val store = LocalAudioFiles(directory)
        // Content that does not match its name must still resolve: lookup never re-reads bytes.
        directory.mkdirs()
        val name = sha256(content(16))
        File(directory, "$name.audio").writeBytes(content(64))

        assertEquals(name + ".audio", store.resolve(name).name)
    }

    @Test
    fun cancellationLeavesNoPartialFileAndPropagatesUnchanged() {
        val directory = managedDirectory()
        val store = LocalAudioFiles(directory)
        var polls = 0
        val cancelled = IllegalStateException("cancelled")

        try {
            store.copy(
                { ByteArrayInputStream(content(300_000)) },
                checkCancelled = {
                    polls++
                    if (polls > 2) throw cancelled
                },
            )
            fail("Expected cancellation")
        } catch (actual: IllegalStateException) {
            assertTrue(actual === cancelled)
        }

        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun inputFailureLeavesNoPartialFileAndClosesSource() {
        val directory = managedDirectory()
        val store = LocalAudioFiles(directory)
        val source = FailingStream(120_000)

        assertFailure(LocalAudioError.READ_FAILED) {
            store.copy({ source }, expectedBytes = 400_000)
        }

        assertTrue(source.closed)
        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun failureFromOpenInputIsPropagatedUnchanged() {
        val directory = managedDirectory()
        val store = LocalAudioFiles(directory)
        val failure = LocalAudioFailure(LocalAudioError.PERMISSION_REQUIRED)

        try {
            store.copy({ throw failure })
            fail("Expected failure")
        } catch (actual: LocalAudioFailure) {
            assertTrue(actual === failure)
        }

        assertFailure(LocalAudioError.READ_FAILED) {
            store.copy({ throw IOException("grant revoked") })
        }
        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun expectedByteMismatchIsReportedAsSourceChanged() {
        val directory = managedDirectory()
        val store = LocalAudioFiles(directory)

        assertFailure(LocalAudioError.SOURCE_CHANGED) {
            store.copy({ ByteArrayInputStream(content(4_000)) }, expectedBytes = 4_099)
        }
        assertFailure(LocalAudioError.SOURCE_CHANGED) {
            store.copy({ ByteArrayInputStream(content(4_000)) }, expectedBytes = 3_999)
        }

        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun invalidExpectedBytesMapToLocalAudioFailuresBeforeReading() {
        val store = LocalAudioFiles(managedDirectory())
        var opened = false

        assertFailure(LocalAudioError.READ_FAILED) {
            store.copy({ opened = true; ByteArrayInputStream(content(4)) }, expectedBytes = 0)
        }
        assertFailure(LocalAudioError.TOO_LARGE) {
            store.copy(
                { opened = true; ByteArrayInputStream(content(4)) },
                expectedBytes = LocalAudioLimits.MAX_FILE_BYTES + 1,
            )
        }
        assertFalse(opened)
    }

    @Test
    fun emptyInputIsRejectedBeforePublishing() {
        val directory = managedDirectory()
        val store = LocalAudioFiles(directory)

        assertFailure(LocalAudioError.READ_FAILED) {
            store.copy({ ByteArrayInputStream(ByteArray(0)) })
        }
        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun zeroLengthReadsDoNotSpinAndStillCopyEveryByte() {
        val store = LocalAudioFiles(managedDirectory())
        val payload = content(1_000)

        val copied = store.copy({ ZeroReadsStream(payload) })

        assertArrayEquals(payload, store.resolve(copied.name).readBytes())
        assertEquals(payload.size.toLong(), copied.bytes)
    }

    @Test
    fun oversizedStreamStopsAtTheLimitBeforeWritingPastIt() {
        val directory = managedDirectory()
        val store = LocalAudioFiles(directory, maxBytes = 1_024L)
        val endless = CountingStream()

        assertFailure(LocalAudioError.TOO_LARGE) { store.copy({ endless }) }

        assertTrue(endless.produced <= 1_024L + 64 * 1_024)
        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun invalidNamesAndTraversalAreRejected() {
        val directory = managedDirectory()
        val store = LocalAudioFiles(directory)
        directory.mkdirs()
        val valid = sha256(content(8))
        File(directory, "$valid.audio").writeBytes(ByteArray(8))

        listOf("../$valid", "$valid/..", "$valid.audio", valid.uppercase(), valid.dropLast(1), "").forEach {
            assertFailure(LocalAudioError.INVALID_NAME) { store.resolve(it) }
            assertFailure(LocalAudioError.INVALID_NAME) { store.discard(it) }
        }
        assertFailure(LocalAudioError.INVALID_NAME) { store.prune(setOf("../$valid")) }
        assertFailure(LocalAudioError.INVALID_NAME) { store.prune(setOf("$valid.audio")) }

        assertEquals(1, directory.listFiles()!!.size)
        assertFalse(File(directory.parentFile, "$valid.audio").exists())
    }

    @Test
    fun symbolicLinkFileIsNeverFollowed() {
        val directory = managedDirectory()
        val outside = temporary.newFolder("outside")
        val outsideFile = File(outside, "audio.bin").apply { writeBytes(ByteArray(32) { 7 }) }
        val store = LocalAudioFiles(directory)
        val name = sha256(content(32))
        directory.mkdirs()
        val link = File(directory, "$name.audio")
        try {
            Files.createSymbolicLink(link.toPath(), outsideFile.toPath())
        } catch (error: Exception) {
            assumeNoException("Symbolic links unavailable on this host", error)
        }

        assertFailure(LocalAudioError.NOT_FOUND) { store.resolve(name) }
        store.discard(name)
        store.cleanupAbandoned()
        store.prune(emptySet())

        assertTrue(outsideFile.exists())
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test
    fun redirectedDirectoryIsRejectedByEveryOperation() {
        val parent = temporary.newFolder("files")
        val directory = File(parent, "local-audio-v1")
        val outside = temporary.newFolder("outside")
        val store = LocalAudioFiles(directory)
        val name = sha256(content(16))
        File(outside, "$name.audio").writeBytes(content(16))
        try {
            Files.createSymbolicLink(directory.toPath(), outside.toPath())
        } catch (error: Exception) {
            assumeNoException("Symbolic links unavailable on this host", error)
        }

        assertFailure(LocalAudioError.STORAGE_FAILED) { store.resolve(name) }
        assertFailure(LocalAudioError.STORAGE_FAILED) { store.discard(name) }
        assertFailure(LocalAudioError.STORAGE_FAILED) { store.cleanupAbandoned() }
        assertFailure(LocalAudioError.STORAGE_FAILED) { store.prune(emptySet()) }
        assertFailure(LocalAudioError.STORAGE_FAILED) { store.copy({ ByteArrayInputStream(content(16)) }) }
        assertTrue(File(outside, "$name.audio").exists())
    }

    @Test
    fun cleanupAbandonedRemovesOnlyOwnTemporaryFiles() {
        val directory = managedDirectory()
        val store = LocalAudioFiles(directory)
        val published = store.copy({ ByteArrayInputStream(content(500)) })
        val ownTemporary = File(directory, "part-${UUID.randomUUID()}.tmp").apply { writeBytes(ByteArray(16)) }
        val foreignTemporary = File(directory, "other.tmp").apply { writeBytes(ByteArray(16)) }
        val unknown = File(directory, "notes.txt").apply { writeBytes(ByteArray(16)) }

        store.cleanupAbandoned()

        assertFalse(ownTemporary.exists())
        assertTrue(foreignTemporary.exists())
        assertTrue(unknown.exists())
        assertArrayEquals(content(500), store.resolve(published.name).readBytes())
    }

    @Test
    fun redirectedParentCannotCreateOrCollectFilesOutsideItsOriginalDirectory() {
        val parent = temporary.newFolder("parent-before")
        val moved = File(temporary.root, "parent-original")
        val directory = File(parent, "audio")
        val outside = temporary.newFolder("parent-outside")
        val store = LocalAudioFiles(directory)
        val content = content(20)
        val name = sha256(content)
        val outsideAudio = File(outside, "audio").apply { mkdirs() }
        val protected = File(outsideAudio, "$name.audio").apply { writeBytes(content) }
        Files.move(parent.toPath(), moved.toPath())
        try {
            Files.createSymbolicLink(parent.toPath(), outside.toPath())
        } catch (error: Exception) {
            Files.move(moved.toPath(), parent.toPath())
            assumeNoException("Symbolic links unavailable on this host", error)
        }
        try {
            assertFailure(LocalAudioError.STORAGE_FAILED) { store.copy({ ByteArrayInputStream(content) }) }
            assertFailure(LocalAudioError.STORAGE_FAILED) { store.prune(emptySet()) }
            assertFailure(LocalAudioError.STORAGE_FAILED) { store.discard(name) }
            assertTrue(protected.exists())
        } finally {
            Files.delete(parent.toPath())
            Files.move(moved.toPath(), parent.toPath())
        }
    }

    @Test
    fun pruneDropsOnlyUnreferencedPublishedCopies() {
        val directory = managedDirectory()
        val store = LocalAudioFiles(directory)
        val kept = store.copy({ ByteArrayInputStream(content(300)) })
        val orphan = store.copy({ ByteArrayInputStream(content(400)) })
        val foreignTemporary = File(directory, "part-not-mine.tmp").apply { writeBytes(ByteArray(4)) }
        val unknown = File(directory, "notes.txt").apply { writeBytes(ByteArray(4)) }
        val directoryLookalike = File(directory, "${sha256(content(1))}.audio").apply { mkdirs() }

        store.prune(setOf(kept.name))

        assertArrayEquals(content(300), store.resolve(kept.name).readBytes())
        assertFalse(File(directory, "${orphan.name}.audio").exists())
        assertTrue(foreignTemporary.exists())
        assertTrue(unknown.exists())
        assertTrue(directoryLookalike.isDirectory)
    }

    @Test
    fun discardRemovesOnlyTheNamedCopy() {
        val directory = managedDirectory()
        val store = LocalAudioFiles(directory)
        val first = store.copy({ ByteArrayInputStream(content(128)) })
        val second = store.copy({ ByteArrayInputStream(content(129)) })

        store.discard(first.name)

        assertFalse(File(directory, "${first.name}.audio").exists())
        assertArrayEquals(content(129), store.resolve(second.name).readBytes())
        // Discarding the same copy again is a no-op, not an error.
        store.discard(first.name)
        assertFailure(LocalAudioError.NOT_FOUND) { store.resolve(first.name) }
    }

    @Test
    fun damagedExistingCopyIsReportedInsteadOfReplaced() {
        val directory = managedDirectory()
        val store = LocalAudioFiles(directory)
        val payload = content(2_048)
        val name = sha256(payload)
        directory.mkdirs()
        val damaged = File(directory, "$name.audio")
        damaged.writeBytes(ByteArray(10))

        assertFailure(LocalAudioError.STORAGE_FAILED) { store.copy({ ByteArrayInputStream(payload) }) }

        assertEquals(10L, damaged.length())
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test
    fun missingDirectoryIsTreatedAsNoCopies() {
        val directory = managedDirectory()
        val store = LocalAudioFiles(directory)
        val name = sha256(content(4))

        assertFailure(LocalAudioError.NOT_FOUND) { store.resolve(name) }
        store.discard(name)
        store.cleanupAbandoned()
        store.prune(emptySet())

        assertFalse(directory.exists())
    }

    @Test
    fun directoryThatIsNotADirectoryIsReportedAsStorageFailure() {
        val parent = temporary.newFolder("files")
        val directory = File(parent, "local-audio-v1").apply { writeBytes(ByteArray(8)) }
        val store = LocalAudioFiles(directory)

        assertFailure(LocalAudioError.STORAGE_FAILED) {
            store.copy({ ByteArrayInputStream(content(16)) })
        }
        assertFailure(LocalAudioError.STORAGE_FAILED) { store.resolve(sha256(content(16))) }
    }

    private fun content(size: Int): ByteArray = ByteArray(size) { index -> ((index * 31 + 7) and 0xff).toByte() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private inline fun assertFailure(expected: LocalAudioError, block: () -> Unit) {
        try {
            block()
            fail("Expected $expected")
        } catch (failure: LocalAudioFailure) {
            assertEquals(expected, failure.error)
        }
    }

    /** Produces [failureAfter] bytes and then fails, to model a source read error. */
    private class FailingStream(private val failureAfter: Int) : InputStream() {
        private var delivered = 0
        var closed = false
            private set

        override fun read(): Int {
            if (delivered >= failureAfter) throw IOException("source read failed")
            delivered++
            return delivered and 0xff
        }

        override fun close() {
            closed = true
        }
    }

    /** Returns 0 from the first bulk read without being at the end of the data. */
    private class ZeroReadsStream(private val payload: ByteArray) : InputStream() {
        private var index = 0
        private var zeroReturned = false

        override fun read(): Int = if (index < payload.size) payload[index++].toInt() and 0xff else -1

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (!zeroReturned) {
                zeroReturned = true
                return 0
            }
            return super.read(bytes, offset, length)
        }
    }

    /** Endless source that counts delivered bytes so an early stop can be verified. */
    private class CountingStream : InputStream() {
        var produced = 0L
            private set

        override fun read(): Int {
            produced++
            return (produced and 0xff).toInt()
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            produced += length
            return length
        }
    }
}
