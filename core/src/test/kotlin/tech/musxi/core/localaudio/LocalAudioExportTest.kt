package tech.musxi.core.localaudio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalAudioExportTest {
    @Test fun zeroReadAtEndDoesNotLoopForever() {
        val source = object : InputStream() {
            override fun read(bytes: ByteArray, offset: Int, length: Int) = 0
            override fun read() = -1
        }
        assertFailure(LocalAudioError.READ_FAILED) { LocalAudioExport.copy(source, ByteArrayOutputStream()) }
    }
    private val payload = content(200_000)
    private val payloadSha256 = sha256(payload)

    @Test
    fun copiesMultiChunkSourceWithoutClosingCallerStreams() {
        val input = TrackingInputStream(payload)
        val output = TrackingOutputStream()
        val progress = ArrayList<Long>()

        val copied = LocalAudioExport.copy(
            input,
            output,
            expectedBytes = payload.size.toLong(),
            expectedSha256 = payloadSha256,
            progress = { progress += it },
        )

        assertEquals(payload.size.toLong(), copied)
        assertArrayEquals(payload, output.written())
        assertEquals(payload.size.toLong(), progress.last())
        assertEquals(progress.sorted(), progress)
        assertTrue(progress.size > 1)
        assertEquals(1, output.flushes)
        // Exporting hands the streams back untouched; the caller owns closing and cleanup.
        assertFalse(input.closed)
        assertFalse(output.closed)
    }

    @Test
    fun unknownSizeAndDigestSkipVerification() {
        val output = ByteArrayOutputStream()

        val copied = LocalAudioExport.copy(ByteArrayInputStream(payload), output)

        assertEquals(payload.size.toLong(), copied)
        assertArrayEquals(payload, output.toByteArray())
    }

    @Test
    fun zeroLengthReadsStillCopyEveryByte() {
        val output = ByteArrayOutputStream()

        val copied = LocalAudioExport.copy(
            ZeroReadsStream(payload),
            output,
            expectedBytes = payload.size.toLong(),
            expectedSha256 = payloadSha256,
        )

        assertEquals(payload.size.toLong(), copied)
        assertArrayEquals(payload, output.toByteArray())
    }

    @Test
    fun sizeMismatchInEitherDirectionIsSourceChanged() {
        assertFailure(LocalAudioError.SOURCE_CHANGED) {
            LocalAudioExport.copy(
                ByteArrayInputStream(payload),
                ByteArrayOutputStream(),
                expectedBytes = payload.size + 1L,
            )
        }
        assertFailure(LocalAudioError.SOURCE_CHANGED) {
            LocalAudioExport.copy(
                ByteArrayInputStream(payload),
                ByteArrayOutputStream(),
                expectedBytes = payload.size - 1L,
            )
        }
    }

    @Test
    fun digestMismatchIsSourceChanged() {
        assertFailure(LocalAudioError.SOURCE_CHANGED) {
            LocalAudioExport.copy(
                ByteArrayInputStream(payload),
                ByteArrayOutputStream(),
                expectedBytes = payload.size.toLong(),
                expectedSha256 = sha256(content(200_001)),
            )
        }
    }

    @Test
    fun emptySourceAndInvalidExpectedSizesFailBeforeExporting() {
        assertFailure(LocalAudioError.READ_FAILED) {
            LocalAudioExport.copy(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        }

        val input = TrackingInputStream(payload)
        val untouched = TrackingOutputStream()
        assertFailure(LocalAudioError.READ_FAILED) {
            LocalAudioExport.copy(input, untouched, expectedBytes = 0)
        }
        assertFailure(LocalAudioError.READ_FAILED) {
            LocalAudioExport.copy(input, untouched, expectedBytes = -1)
        }
        assertFailure(LocalAudioError.TOO_LARGE) {
            LocalAudioExport.copy(input, untouched, expectedBytes = LocalAudioLimits.MAX_FILE_BYTES + 1)
        }
        // A rejected expectation must not consume the source or touch the target.
        assertEquals(0, input.reads)
        assertEquals(0, untouched.written().size)
    }

    @Test
    fun malformedDigestIsACallerBug() {
        try {
            LocalAudioExport.copy(
                ByteArrayInputStream(payload),
                ByteArrayOutputStream(),
                expectedSha256 = payloadSha256.uppercase(),
            )
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun readAndWriteFailuresKeepTheirCategories() {
        assertFailure(LocalAudioError.READ_FAILED) {
            LocalAudioExport.copy(FailingInputStream(failureAfter = 1_000), ByteArrayOutputStream())
        }
        assertFailure(LocalAudioError.STORAGE_FAILED) {
            LocalAudioExport.copy(ByteArrayInputStream(payload), FailingOutputStream())
        }
        assertFailure(LocalAudioError.STORAGE_FAILED) {
            LocalAudioExport.copy(ByteArrayInputStream(payload), FailingFlushOutputStream())
        }
    }

    @Test
    fun unexpectedErrorsAreNotSwallowed() {
        val thrown = OutOfMemoryError("injected")
        try {
            LocalAudioExport.copy(ByteArrayInputStream(payload), ThrowingOutputStream(thrown))
            fail("Expected the original error")
        } catch (actual: OutOfMemoryError) {
            assertTrue(actual === thrown)
        }
    }

    @Test
    fun cancellationStopsAfterTheCurrentChunkAndIsPropagated() {
        val cancellation = CancellationException("user cancelled")
        val output = ByteArrayOutputStream()

        try {
            LocalAudioExport.copy(
                ByteArrayInputStream(payload),
                output,
                checkCancelled = { if (output.size() > 0) throw cancellation },
            )
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertTrue(actual === cancellation)
        }
        assertTrue(output.size() > 0)
        assertTrue(output.size() < payload.size)
    }

    private fun content(size: Int): ByteArray =
        ByteArray(size) { index -> ((index * 31 + 7) and 0xff).toByte() }

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

    private class TrackingInputStream(private val payload: ByteArray) : InputStream() {
        private var index = 0
        var closed = false
            private set
        var reads = 0
            private set

        override fun read(): Int {
            reads++
            return if (index < payload.size) payload[index++].toInt() and 0xff else -1
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            reads++
            if (index >= payload.size) return -1
            val count = minOf(length, payload.size - index)
            payload.copyInto(bytes, offset, index, index + count)
            index += count
            return count
        }

        override fun close() {
            closed = true
        }
    }

    private class TrackingOutputStream : OutputStream() {
        private val bytes = ByteArrayOutputStream()
        var closed = false
            private set
        var flushes = 0
            private set

        override fun write(value: Int) {
            bytes.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            bytes.write(buffer, offset, length)
        }

        override fun flush() {
            flushes++
        }

        override fun close() {
            closed = true
        }

        fun written(): ByteArray = bytes.toByteArray()
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

    /** Produces [failureAfter] bytes and then fails, to model a source read error. */
    private class FailingInputStream(private val failureAfter: Int) : InputStream() {
        private var delivered = 0

        override fun read(): Int {
            if (delivered >= failureAfter) throw IOException("source read failed")
            delivered++
            return delivered and 0xff
        }
    }

    private class FailingOutputStream : OutputStream() {
        override fun write(value: Int) {
            throw IOException("target write failed")
        }
    }

    private class FailingFlushOutputStream : OutputStream() {
        override fun write(value: Int) = Unit

        override fun flush() {
            throw IOException("target flush failed")
        }
    }

    private class ThrowingOutputStream(private val error: Error) : OutputStream() {
        override fun write(value: Int) {
            throw error
        }
    }
}
