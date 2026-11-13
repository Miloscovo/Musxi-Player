package tech.musxi.core.localaudio

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Versioned, self-verifying binary envelope for an organization-only [LocalAudioBackup].
 *
 * The envelope is the ASCII magic, a version int and the payload, followed by a SHA-256 of every
 * byte before it. Only the fields a backup already exposes are written: no path, document URI,
 * grant, source identity or audio byte ever reaches the stream, and a reader rebuilds exactly what
 * was written or fails.
 *
 * Writer and reader both stream through fixed buffers, keep a running total against [MAX_BYTES],
 * never allocate from a length that was not capped first, check `checkCancelled` before every
 * record, and never close the caller's stream. A malformed, truncated, over-long or trailing-byte
 * envelope is [LocalAudioError.INVALID_BACKUP], an unknown version is
 * [LocalAudioError.UNSUPPORTED_BACKUP], an exceeded byte/list/field limit is
 * [LocalAudioError.LIMIT_REACHED], and stream failures map to [LocalAudioError.READ_FAILED] or
 * [LocalAudioError.STORAGE_FAILED]. Cancellation and [Error]s are never translated.
 */
object LocalAudioBackupCodec {
    /** Hard ceiling for the whole envelope, magic, payload and footer included. */
    const val MAX_BYTES: Long = 32L * 1024 * 1024

    private const val VERSION = 1
    private const val FOOTER_BYTES = 32
    private const val BUFFER_BYTES = 8 * 1024
    private const val ID_BYTES = 36
    private const val SOURCE_KEY_BYTES = 64
    private const val ENUM_BYTES = 48
    private const val NAME_BYTES = 4096
    private const val CAPACITY_HINT = 64
    private val MAGIC = "MUSXI-LOCAL-ORG".toByteArray(Charsets.US_ASCII)

    /**
     * Writes [value] to [output] and flushes it. Nothing is closed, so the caller owns the stream
     * and discards a partial envelope if this throws.
     */
    fun write(value: LocalAudioBackup, output: OutputStream, checkCancelled: () -> Unit = {}) {
        // The model validates on construction; re-check so a mutated list cannot slip past limits.
        value.validate()
        val limited = LimitedOutputStream(output, checkCancelled)
        val digest = MessageDigest.getInstance("SHA-256")
        val body = DataOutputStream(
            BufferedOutputStream(DigestOutputStream(limited, digest), BUFFER_BYTES),
        )
        try {
            checkCancelled()
            body.write(MAGIC)
            body.writeInt(VERSION)
            body.writeId(value.id, checkCancelled)
            body.writeLong(value.createdAtMs)

            body.writeCount(value.items.size, LocalAudioLimits.MAX_ITEMS)
            for (entry in value.items) {
                checkCancelled()
                body.writeId(entry.item.id, checkCancelled)
                body.writeName(entry.item.metadata.fileName, checkCancelled)
                body.writeNullableName(entry.item.metadata.title, checkCancelled)
                body.writeNullableName(entry.item.metadata.creator, checkCancelled)
                body.writeNullableLong(entry.item.metadata.durationMs)
                body.writeNullableLong(entry.item.metadata.sizeBytes)
                body.writeNullableLong(entry.item.metadata.modifiedAtMs)
                body.writeEnum(entry.item.mode, checkCancelled)
                body.writeNullableName(entry.item.displayName, checkCancelled)
                body.writeEnum(entry.item.availability, checkCancelled)
                body.writeLong(entry.item.addedAtMs)
                body.writeText(entry.sourceKey, SOURCE_KEY_BYTES, SOURCE_KEY_BYTES, checkCancelled)
            }

            body.writeCount(value.folders.size, LocalAudioLimits.MAX_FOLDERS)
            for (folder in value.folders) {
                checkCancelled()
                body.writeId(folder.id, checkCancelled)
                body.writeName(folder.name, checkCancelled)
                body.writeBoolean(folder.continuePlayback)
            }

            body.writeCount(value.members.size, LocalAudioLimits.MAX_MEMBERS)
            for (member in value.members) {
                checkCancelled()
                body.writeId(member.folderId, checkCancelled)
                body.writeId(member.audioId, checkCancelled)
                body.writeInt(member.position)
            }

            // Progress is one entry per audio, so it can never exceed the item cap.
            body.writeCount(value.progress.size, LocalAudioLimits.MAX_ITEMS)
            for (entry in value.progress) {
                checkCancelled()
                body.writeId(entry.audioId, checkCancelled)
                body.writeLong(entry.positionMs)
                body.writeBoolean(entry.completed)
                body.writeLong(entry.updatedAtMs)
            }

            body.writeCount(value.cursors.size, LocalAudioLimits.MAX_FOLDERS)
            for (cursor in value.cursors) {
                checkCancelled()
                body.writeId(cursor.folderId, checkCancelled)
                body.writeId(cursor.audioId, checkCancelled)
            }

            body.writeCount(value.bookmarks.size, LocalAudioLimits.MAX_BOOKMARKS)
            for (bookmark in value.bookmarks) {
                checkCancelled()
                body.writeId(bookmark.id, checkCancelled)
                body.writeId(bookmark.audioId, checkCancelled)
                body.writeLong(bookmark.positionMs)
                body.writeName(bookmark.name, checkCancelled)
            }

            checkCancelled()
            body.flush()
            // The digest stops here, so the footer never signs itself.
            limited.write(digest.digest())
            checkCancelled()
            limited.flush()
        } catch (failure: LocalAudioFailure) {
            throw failure
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        }
    }

    /**
     * Reads and verifies an envelope from [input], returning it only when every field, reference
     * and duplicate rule of the model holds. Nothing is closed and no byte past the envelope is
     * consumed.
     */
    fun read(input: InputStream, checkCancelled: () -> Unit = {}): LocalAudioBackup {
        val limited = LimitedInputStream(input, checkCancelled)
        val digest = MessageDigest.getInstance("SHA-256")
        val body = DataInputStream(DigestInputStream(limited, digest))
        try {
            checkCancelled()
            val magic = ByteArray(MAGIC.size)
            body.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw invalid()
            if (body.readInt() != VERSION) {
                throw LocalAudioFailure(LocalAudioError.UNSUPPORTED_BACKUP)
            }
            val id = body.readId(checkCancelled)
            val createdAtMs = body.readLong()

            val itemCount = body.readCount(LocalAudioLimits.MAX_ITEMS, checkCancelled)
            val items = ArrayList<LocalAudioBackupItem>(capacity(itemCount))
            repeat(itemCount) {
                checkCancelled()
                val audioId = body.readId(checkCancelled)
                val metadata = LocalAudioMetadata(
                    fileName = body.readName(checkCancelled),
                    title = body.readNullableName(checkCancelled),
                    creator = body.readNullableName(checkCancelled),
                    durationMs = body.readNullableLong(checkCancelled),
                    sizeBytes = body.readNullableLong(checkCancelled),
                    modifiedAtMs = body.readNullableLong(checkCancelled),
                )
                val mode = body.readEnum<LocalImportMode>(checkCancelled)
                val displayName = body.readNullableName(checkCancelled)
                val availability = body.readEnum<LocalAudioAvailability>(checkCancelled)
                val addedAtMs = body.readLong()
                val sourceKey = body.readText(SOURCE_KEY_BYTES, SOURCE_KEY_BYTES, checkCancelled)
                items += LocalAudioBackupItem(
                    LocalAudioItem(audioId, metadata, mode, displayName, availability, addedAtMs),
                    sourceKey,
                )
            }

            val folderCount = body.readCount(LocalAudioLimits.MAX_FOLDERS, checkCancelled)
            val folders = ArrayList<LocalAudioFolder>(capacity(folderCount))
            repeat(folderCount) {
                checkCancelled()
                folders += LocalAudioFolder(
                    id = body.readId(checkCancelled),
                    name = body.readName(checkCancelled),
                    continuePlayback = body.readStrictBoolean(checkCancelled),
                )
            }

            val memberCount = body.readCount(LocalAudioLimits.MAX_MEMBERS, checkCancelled)
            val members = ArrayList<LocalAudioMember>(capacity(memberCount))
            repeat(memberCount) {
                checkCancelled()
                members += LocalAudioMember(
                    folderId = body.readId(checkCancelled),
                    audioId = body.readId(checkCancelled),
                    position = body.readInt(),
                )
            }

            val progressCount = body.readCount(LocalAudioLimits.MAX_ITEMS, checkCancelled)
            val progress = ArrayList<LocalAudioProgress>(capacity(progressCount))
            repeat(progressCount) {
                checkCancelled()
                progress += LocalAudioProgress(
                    audioId = body.readId(checkCancelled),
                    positionMs = body.readLong(),
                    completed = body.readStrictBoolean(checkCancelled),
                    updatedAtMs = body.readLong(),
                )
            }

            val cursorCount = body.readCount(LocalAudioLimits.MAX_FOLDERS, checkCancelled)
            val cursors = ArrayList<LocalAudioFolderCursor>(capacity(cursorCount))
            repeat(cursorCount) {
                checkCancelled()
                cursors += LocalAudioFolderCursor(
                    folderId = body.readId(checkCancelled),
                    audioId = body.readId(checkCancelled),
                )
            }

            val bookmarkCount = body.readCount(LocalAudioLimits.MAX_BOOKMARKS, checkCancelled)
            val bookmarks = ArrayList<LocalAudioBookmark>(capacity(bookmarkCount))
            repeat(bookmarkCount) {
                checkCancelled()
                bookmarks += LocalAudioBookmark(
                    id = body.readId(checkCancelled),
                    audioId = body.readId(checkCancelled),
                    positionMs = body.readLong(),
                    name = body.readName(checkCancelled),
                )
            }

            checkCancelled()
            // Taken before the footer is consumed, so the footer is verified but never signed.
            val expected = digest.digest()
            val footer = ByteArray(FOOTER_BYTES)
            body.readFully(footer)
            if (!MessageDigest.isEqual(expected, footer)) throw invalid()
            // Anything after the footer means the file was not written by this codec.
            if (body.read() != -1) throw invalid()
            checkCancelled()
            return LocalAudioBackup(
                id,
                createdAtMs,
                items,
                folders,
                members,
                progress,
                cursors,
                bookmarks,
            )
        } catch (failure: LocalAudioFailure) {
            throw failure
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: EOFException) {
            throw invalid()
        } catch (_: IllegalArgumentException) {
            // Model constructors and validate() reject out-of-range values, bad UUIDs and broken
            // references; any of those means the bytes are not a backup this codec produced.
            throw invalid()
        } catch (_: IOException) {
            throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        }
    }

    private fun invalid() = LocalAudioFailure(LocalAudioError.INVALID_BACKUP)

    private fun capacity(count: Int) = minOf(count, CAPACITY_HINT)

    private fun DataOutputStream.writeCount(count: Int, cap: Int) {
        if (count > cap) throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
        writeInt(count)
    }

    private fun DataInputStream.readCount(cap: Int, check: () -> Unit): Int {
        check()
        val count = readInt()
        if (count < 0) throw invalid()
        if (count > cap) throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
        return count
    }

    private fun DataOutputStream.writeText(
        value: String,
        maxBytes: Int,
        maxChars: Int,
        check: () -> Unit,
    ) {
        check()
        if (value.length > maxChars) throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
        // A lone surrogate has no UTF-8 form; REPORT refuses it instead of writing a '?' that
        // would silently change a user's name.
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
            ByteArray(encoded.remaining()).also { encoded.get(it) }
        } catch (_: CharacterCodingException) {
            throw invalid()
        }
        if (bytes.size > maxBytes) throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(
        maxBytes: Int,
        maxChars: Int,
        check: () -> Unit,
    ): String {
        check()
        val size = readInt()
        if (size < 0) throw invalid()
        // Capped before allocating, so a forged length can never become a large array.
        if (size > maxBytes) throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
        val bytes = ByteArray(size)
        readFully(bytes)
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw invalid()
        }
        if (text.length > maxChars) throw invalid()
        return text
    }

    private fun DataOutputStream.writeId(value: String, check: () -> Unit) =
        writeText(value, ID_BYTES, ID_BYTES, check)

    private fun DataInputStream.readId(check: () -> Unit): String {
        val value = readText(ID_BYTES, ID_BYTES, check)
        LocalAudioLimits.requireId(value)
        return value
    }

    private fun DataOutputStream.writeName(value: String, check: () -> Unit) =
        writeText(value, NAME_BYTES, LocalAudioLimits.MAX_NAME, check)

    private fun DataInputStream.readName(check: () -> Unit): String =
        readText(NAME_BYTES, LocalAudioLimits.MAX_NAME, check)

    private fun DataOutputStream.writeNullableName(value: String?, check: () -> Unit) {
        writeBoolean(value != null)
        if (value != null) writeName(value, check)
    }

    private fun DataInputStream.readNullableName(check: () -> Unit): String? =
        if (readStrictBoolean(check)) readName(check) else null

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataInputStream.readNullableLong(check: () -> Unit): Long? {
        check()
        return if (readStrictBoolean(check)) readLong() else null
    }

    /** [DataInputStream.readBoolean] accepts any non-zero byte; the format only allows 0 or 1. */
    private fun DataInputStream.readStrictBoolean(check: () -> Unit): Boolean {
        check()
        return when (val value = readUnsignedByte()) {
            0 -> false
            1 -> true
            else -> throw invalid()
        }
    }

    private fun DataOutputStream.writeEnum(value: Enum<*>, check: () -> Unit) =
        writeText(value.name, ENUM_BYTES, ENUM_BYTES, check)

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(noinline check: () -> Unit): T {
        val name = readText(ENUM_BYTES, ENUM_BYTES, check)
        return try {
            enumValueOf<T>(name)
        } catch (_: IllegalArgumentException) {
            throw invalid()
        }
    }

    /**
     * Counts every byte handed to the caller's stream, refuses to pass [MAX_BYTES] and checks
     * cancellation before each write. Slicing keeps one huge array from bypassing the counter.
     */
    private class LimitedOutputStream(
        private val target: OutputStream,
        private val check: () -> Unit,
    ) : OutputStream() {
        private var total = 0L

        override fun write(value: Int) {
            check()
            account(1)
            target.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            var done = 0
            while (done < length) {
                check()
                val slice = minOf(length - done, BUFFER_BYTES)
                account(slice)
                target.write(bytes, offset + done, slice)
                done += slice
            }
        }

        override fun flush() {
            check()
            target.flush()
        }

        private fun account(count: Int) {
            total += count
            if (total > MAX_BYTES) throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
        }
    }

    /**
     * Counts every byte pulled from the caller's stream, refuses to pass [MAX_BYTES] and checks
     * cancellation before each real read, so a long `readFully` cannot block unnoticed.
     */
    private class LimitedInputStream(
        private val source: InputStream,
        private val check: () -> Unit,
    ) : InputStream() {
        private var total = 0L

        override fun read(): Int {
            check()
            val value = source.read()
            if (value >= 0) account(1)
            return value
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            check()
            if (length == 0) return 0
            val count = source.read(bytes, offset, length)
            if (count > 0) {
                account(count)
                return count
            }
            if (count < 0) return -1
            // Some streams report 0 without being at the end; read one byte so a caller such as
            // DataInputStream.readFully can neither spin forever nor lose data.
            check()
            val single = source.read()
            if (single < 0) return -1
            account(1)
            bytes[offset] = single.toByte()
            return 1
        }

        override fun available() = source.available()

        private fun account(count: Int) {
            total += count
            if (total > MAX_BYTES) throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
        }
    }
}
