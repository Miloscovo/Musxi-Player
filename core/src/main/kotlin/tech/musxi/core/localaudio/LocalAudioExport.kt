package tech.musxi.core.localaudio

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Streams local audio bytes between caller-supplied streams, for exporting a managed `COPY` or a
 * `REFERENCE` source to a target the user picked.
 *
 * Authorization, opening and cleanup of both streams stay with the caller: this object never opens,
 * closes, persists or logs a stream, and it publishes no path or URI. `progress` is the raw byte
 * counter; throttling for a UI belongs to the caller.
 */
object LocalAudioExport {
    /**
     * Copies [input] to [output] and returns the number of bytes copied.
     *
     * [expectedBytes] and [expectedSha256] are the values recorded for the source. When given they
     * are verified, so a source that changed since it was listed fails as
     * [LocalAudioError.SOURCE_CHANGED] instead of exporting different audio. The digest is computed
     * from the same buffer that is written, so the source is read exactly once.
     *
     * [checkCancelled] runs before every read and write and once after the final flush; it may throw
     * [java.util.concurrent.CancellationException]. Bytes already written stay in [output], and the
     * caller discards that partial target.
     *
     * [expectedBytes] of `0` or less is [LocalAudioError.READ_FAILED] because a local audio item is
     * never empty; a value above [LocalAudioLimits.MAX_FILE_BYTES] is [LocalAudioError.TOO_LARGE].
     * A malformed [expectedSha256] is a caller bug and throws [IllegalArgumentException].
     */
    fun copy(
        input: InputStream,
        output: OutputStream,
        expectedBytes: Long? = null,
        expectedSha256: String? = null,
        checkCancelled: () -> Unit = {},
        progress: (Long) -> Unit = {},
    ): Long {
        if (expectedSha256 != null && !SHA256.matches(expectedSha256)) {
            throw IllegalArgumentException("Expected SHA-256 must be 64 lowercase hex characters")
        }
        validateExpectedBytes(expectedBytes)
        checkCancelled()
        val digest = expectedSha256?.let { MessageDigest.getInstance("SHA-256") }
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            checkCancelled()
            val count = readChunk(input, buffer)
            if (count < 0) break
            val next = total + count
            // Limits are checked before anything is written or digested, so an oversized source
            // never reaches the target beyond the recorded size.
            if (next > LocalAudioLimits.MAX_FILE_BYTES) {
                throw LocalAudioFailure(LocalAudioError.TOO_LARGE)
            }
            if (expectedBytes != null && next > expectedBytes) {
                throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
            }
            digest?.update(buffer, 0, count)
            checkCancelled()
            writeFully(output, buffer, count)
            total = next
            progress(total)
        }
        checkCancelled()
        flush(output)
        checkCancelled()
        // LocalAudioMetadata only accepts 1..MAX_FILE_BYTES, so an empty source can never become a
        // usable export and is reported as unreadable rather than as a size change.
        if (total == 0L) throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        if (expectedBytes != null && total != expectedBytes) {
            throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
        }
        if (digest != null && hex(digest.digest()) != expectedSha256) {
            throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
        }
        return total
    }

    private fun readChunk(input: InputStream, buffer: ByteArray): Int = try {
        var read = input.read(buffer)
        // Some streams report 0 without being at the end; read a single byte so the copy loop can
        // neither spin forever nor drop data.
        if (read == 0) {
            val single = input.read()
            if (single >= 0) {
                buffer[0] = single.toByte()
                read = 1
            } else read = -1
        }
        if (read > buffer.size || read < -1) throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        read
    } catch (failure: LocalAudioFailure) {
        throw failure
    } catch (_: IOException) {
        throw LocalAudioFailure(LocalAudioError.READ_FAILED)
    } catch (_: SecurityException) {
        throw LocalAudioFailure(LocalAudioError.READ_FAILED)
    }

    private fun writeFully(output: OutputStream, buffer: ByteArray, count: Int) {
        try {
            output.write(buffer, 0, count)
        } catch (failure: LocalAudioFailure) {
            throw failure
        } catch (_: IOException) {
            throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        } catch (_: SecurityException) {
            throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        }
    }

    private fun flush(output: OutputStream) {
        try {
            output.flush()
        } catch (failure: LocalAudioFailure) {
            throw failure
        } catch (_: IOException) {
            throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        } catch (_: SecurityException) {
            throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        }
    }

    private fun validateExpectedBytes(expectedBytes: Long?) {
        if (expectedBytes == null) return
        if (expectedBytes > LocalAudioLimits.MAX_FILE_BYTES) {
            throw LocalAudioFailure(LocalAudioError.TOO_LARGE)
        }
        if (expectedBytes < 1L) throw LocalAudioFailure(LocalAudioError.READ_FAILED)
    }

    private fun hex(value: ByteArray): String {
        val chars = CharArray(value.size * 2)
        value.forEachIndexed { index, byte ->
            val unsigned = byte.toInt() and 0xff
            chars[index * 2] = HEX[unsigned ushr 4]
            chars[index * 2 + 1] = HEX[unsigned and 0xf]
        }
        return String(chars)
    }

    private const val BUFFER_BYTES = 64 * 1024
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val HEX = "0123456789abcdef".toCharArray()
}
