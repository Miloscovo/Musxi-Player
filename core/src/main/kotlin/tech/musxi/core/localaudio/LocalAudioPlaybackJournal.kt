package tech.musxi.core.localaudio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.UTFDataFormatException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.zip.CRC32

private val SOURCE_VERSION = Regex("[0-9a-f]{64}")
private val REPEAT_MODES = setOf("OFF", "ONE", "ALL")

/** One saved queue member: a library audio ID, its source version and a progress mark. */
data class SavedLocalAudioEntry(
    val audioId: String,
    val sourceVersion: String,
    val positionMs: Long,
) {
    init {
        LocalAudioLimits.requireId(audioId)
        require(SOURCE_VERSION.matches(sourceVersion))
        require(positionMs in 0..LocalAudioLimits.MAX_POSITION_MS)
    }
}

/**
 * Metadata-only queue snapshot with no paths, URIs or credentials.
 * [useStoredProgress] is false for a queue that was only installed and never played, where the
 * snapshot positions apply; true means per-entry progress must be read from the library database.
 */
data class SavedLocalAudioQueue(
    val folderId: String?,
    val entries: List<SavedLocalAudioEntry>,
    val index: Int,
    val positionMs: Long,
    val repeatMode: String,
    val shuffleEnabled: Boolean,
    val shuffleOrder: List<Int>,
    val continuePlayback: Boolean,
    val savedAtEpochMs: Long,
    val useStoredProgress: Boolean = true,
) {
    init {
        folderId?.let(LocalAudioLimits::requireId)
        require(entries.size in 1..LocalAudioLimits.MAX_ITEMS)
        require(entries.mapTo(HashSet()) { it.audioId }.size == entries.size)
        require(index in entries.indices)
        require(positionMs in 0..LocalAudioLimits.MAX_POSITION_MS)
        require(repeatMode in REPEAT_MODES)
        require(isPermutation(shuffleOrder, entries.size))
        require(savedAtEpochMs >= 0)
    }

    private companion object {
        fun isPermutation(order: List<Int>, size: Int): Boolean {
            if (order.size != size) return false
            val seen = BooleanArray(size)
            order.forEach { value ->
                if (value < 0 || value >= size || seen[value]) return false
                seen[value] = true
            }
            return true
        }
    }
}

data class LocalAudioPlaybackJournal(
    val queue: SavedLocalAudioQueue? = null,
    val active: Boolean = false,
) {
    init { require(!active || queue != null) }
}

/** Blocking storage. The application facade dispatches calls to IO. */
interface LocalAudioPlaybackJournalStore {
    /** Missing or damaged content yields an empty journal. */
    fun read(): LocalAudioPlaybackJournal
    /** Replaces the journal atomically; an invalid value fails and keeps the previous file. */
    fun write(value: LocalAudioPlaybackJournal)
}

/**
 * Versioned single-file journal inside a caller-supplied private directory.
 * The payload is length-checked and CRC32 protected; symbolic links are never followed.
 */
class FileLocalAudioPlaybackJournalStore(
    private val directory: File,
) : LocalAudioPlaybackJournalStore {
    @Synchronized
    override fun read(): LocalAudioPlaybackJournal = try {
        readInternal()
    } catch (error: Throwable) {
        if (error is VirtualMachineError || error is ThreadDeath) throw error
        throw unavailable()
    }

    @Synchronized
    override fun write(value: LocalAudioPlaybackJournal) {
        try {
            atomicReplace(encode(snapshot(value)))
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            throw unavailable()
        }
    }

    override fun toString(): String = "FileLocalAudioPlaybackJournalStore(redacted)"

    private fun readInternal(): LocalAudioPlaybackJournal {
        if (Files.isSymbolicLink(directory.toPath())) return emptyJournal()
        if (!directory.isDirectory) return emptyJournal()
        val target = journalFile()
        if (Files.isSymbolicLink(target.toPath()) || !target.isFile) return emptyJournal()
        val length = target.length()
        if (length !in HEADER_BYTES.toLong()..MAX_FILE_BYTES.toLong()) return emptyJournal()
        val bytes = readBounded(target) ?: return emptyJournal()
        // The file changed while it was being read; never trust a partial or grown snapshot.
        if (bytes.size.toLong() != length) return emptyJournal()
        return decode(bytes) ?: emptyJournal()
    }

    /**
     * Never loads more than [MAX_FILE_BYTES]; a file that grows past the limit while being read is
     * rejected instead of being read into memory.
     */
    private fun readBounded(file: File): ByteArray? = FileInputStream(file).use { input ->
        val buffer = ByteArray(READ_CHUNK_BYTES)
        val output = ByteArrayOutputStream(READ_CHUNK_BYTES)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_FILE_BYTES) return null
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }

    private fun snapshot(value: LocalAudioPlaybackJournal): LocalAudioPlaybackJournal {
        val queue = value.queue
            ?: return LocalAudioPlaybackJournal(null, value.active)
        if (queue.entries.size > LocalAudioLimits.MAX_ITEMS) throw Invalid()
        val entries = immutableList(queue.entries.map {
            SavedLocalAudioEntry(it.audioId, it.sourceVersion, it.positionMs)
        })
        return LocalAudioPlaybackJournal(
            SavedLocalAudioQueue(
                queue.folderId,
                entries,
                queue.index,
                queue.positionMs,
                queue.repeatMode,
                queue.shuffleEnabled,
                immutableList(ArrayList(queue.shuffleOrder)),
                queue.continuePlayback,
                queue.savedAtEpochMs,
                queue.useStoredProgress,
            ),
            value.active,
        )
    }

    private fun encode(value: LocalAudioPlaybackJournal): ByteArray {
        val payload = ByteArrayOutputStream()
        val limited = object : OutputStream() {
            override fun write(value: Int) {
                if (payload.size() >= MAX_PAYLOAD_BYTES) throw TooLarge()
                payload.write(value)
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length > MAX_PAYLOAD_BYTES - payload.size()) throw TooLarge()
                payload.write(bytes, offset, length)
            }
        }
        DataOutputStream(limited).use { data ->
            data.writeBoolean(value.active)
            val queue = value.queue
            data.writeBoolean(queue != null)
            if (queue != null) {
                data.writeBoolean(queue.folderId != null)
                queue.folderId?.let(data::writeUTF)
                data.writeInt(queue.entries.size)
                queue.entries.forEach { entry ->
                    data.writeUTF(entry.audioId)
                    data.writeUTF(entry.sourceVersion)
                    data.writeLong(entry.positionMs)
                }
                data.writeInt(queue.index)
                data.writeLong(queue.positionMs)
                data.writeUTF(queue.repeatMode)
                data.writeBoolean(queue.shuffleEnabled)
                data.writeInt(queue.shuffleOrder.size)
                queue.shuffleOrder.forEach(data::writeInt)
                data.writeBoolean(queue.continuePlayback)
                data.writeLong(queue.savedAtEpochMs)
                data.writeBoolean(queue.useStoredProgress)
            }
        }
        val body = payload.toByteArray()
        if (body.size > MAX_PAYLOAD_BYTES) throw TooLarge()
        val envelope = ByteArrayOutputStream(HEADER_BYTES + body.size)
        DataOutputStream(envelope).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeInt(body.size)
            data.writeLong(CRC32().apply { update(body) }.value)
            data.write(body)
        }
        return envelope.toByteArray()
    }

    private fun decode(bytes: ByteArray): LocalAudioPlaybackJournal? = try {
        if (bytes.size !in HEADER_BYTES..MAX_FILE_BYTES) throw Invalid()
        val envelope = DataInputStream(ByteArrayInputStream(bytes))
        if (envelope.readInt() != MAGIC) throw Invalid()
        if (envelope.readInt() != VERSION) throw Invalid()
        val payloadSize = envelope.readInt()
        if (payloadSize < 0 || payloadSize != bytes.size - HEADER_BYTES) throw Invalid()
        val expectedCrc = envelope.readLong()
        val payload = ByteArray(payloadSize)
        envelope.readFully(payload)
        if (envelope.available() != 0) throw Invalid()
        if (CRC32().apply { update(payload) }.value != expectedCrc) throw Invalid()

        val data = DataInputStream(ByteArrayInputStream(payload))
        val active = data.readBoolean()
        val queue = if (data.readBoolean()) {
            val folderId = if (data.readBoolean()) data.readUTF() else null
            val count = data.readInt()
            if (count !in 1..LocalAudioLimits.MAX_ITEMS) throw Invalid()
            // Every entry needs at least two UTF strings and a long; reject counts the payload cannot hold.
            if (count.toLong() * MIN_ENTRY_BYTES > data.available()) throw Invalid()
            val entries = List(count) {
                SavedLocalAudioEntry(data.readUTF(), data.readUTF(), data.readLong())
            }
            val index = data.readInt()
            val positionMs = data.readLong()
            val repeatMode = data.readUTF()
            val shuffleEnabled = data.readBoolean()
            val orderCount = data.readInt()
            if (orderCount !in 0..LocalAudioLimits.MAX_ITEMS) throw Invalid()
            if (orderCount.toLong() * Integer.BYTES > data.available()) throw Invalid()
            val shuffleOrder = List(orderCount) { data.readInt() }
            val continuePlayback = data.readBoolean()
            val savedAtEpochMs = data.readLong()
            val useStoredProgress = data.readBoolean()
            SavedLocalAudioQueue(
                folderId,
                entries,
                index,
                positionMs,
                repeatMode,
                shuffleEnabled,
                shuffleOrder,
                continuePlayback,
                savedAtEpochMs,
                useStoredProgress,
            )
        } else {
            null
        }
        if (data.available() != 0) throw Invalid()
        snapshot(LocalAudioPlaybackJournal(queue, active))
    } catch (_: Invalid) {
        null
    } catch (_: EOFException) {
        null
    } catch (_: UTFDataFormatException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    } catch (_: IOException) {
        null
    }

    private fun atomicReplace(bytes: ByteArray) {
        if (bytes.size > MAX_FILE_BYTES) throw TooLarge()
        ensureDirectory(create = true)
        val target = journalFile()
        if (Files.isSymbolicLink(target.toPath()) || (target.exists() && !target.isFile)) throw Invalid()
        val temporary = File(directory, TEMP_NAME)
        if (Files.isSymbolicLink(temporary.toPath()) ||
            (temporary.exists() && !temporary.isFile)
        ) throw Invalid()
        if (temporary.exists()) Files.delete(temporary.toPath())
        try {
            FileOutputStream(temporary).use {
                it.write(bytes)
                it.flush()
                it.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            runCatching { Files.deleteIfExists(temporary.toPath()) }
            throw error
        }
        syncDirectory()
    }

    /** Best effort: some platforms cannot fsync a directory, which must not fail the write. */
    private fun syncDirectory() {
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private fun ensureDirectory(create: Boolean) {
        if (Files.isSymbolicLink(directory.toPath())) throw Invalid()
        if (directory.exists()) {
            if (!directory.isDirectory) throw Invalid()
            return
        }
        if (create && !directory.mkdirs() && !directory.isDirectory) throw IOException()
    }

    private fun journalFile() = File(directory, FILE_NAME)

    private fun emptyJournal() = LocalAudioPlaybackJournal()

    private fun unavailable() = LocalAudioFailure(LocalAudioError.STORAGE_FAILED)

    private class Invalid : Exception()
    private class TooLarge : Exception()

    companion object {
        private const val MAGIC = 0x4d584a51
        private const val VERSION = 1
        private const val HEADER_BYTES = 20
        private const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024
        private const val MAX_FILE_BYTES = HEADER_BYTES + MAX_PAYLOAD_BYTES
        private const val READ_CHUNK_BYTES = 64 * 1024
        /** Minimum encoded entry size: two UTF strings (36 and 64 chars) plus a long. */
        private const val MIN_ENTRY_BYTES = 38L + 66L + 8L
        private const val FILE_NAME = "local-audio-playback-journal-v1.bin"
        private const val TEMP_NAME = "$FILE_NAME.tmp"

        private fun <T> immutableList(values: List<T>): List<T> =
            Collections.unmodifiableList(ArrayList(values))
    }
}
