package tech.musxi.core.localaudio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.Assert.assertSame

class LocalAudioBackupCodecTest {
    @Test
    fun roundTripPreservesEveryFieldIncludingNullsAndUnicode() {
        val backup = sampleBackup()
        val output = TrackingOutputStream()

        LocalAudioBackupCodec.write(backup, output)
        val restored = LocalAudioBackupCodec.read(ByteArrayInputStream(output.written()))

        assertEquals(backup.id, restored.id)
        assertEquals(backup.createdAtMs, restored.createdAtMs)
        assertEquals(backup.items.size, restored.items.size)
        backup.items.zip(restored.items).forEach { (expected, actual) ->
            assertEquals(expected.item.id, actual.item.id)
            assertEquals(expected.item.metadata, actual.item.metadata)
            assertEquals(expected.item.mode, actual.item.mode)
            assertEquals(expected.item.displayName, actual.item.displayName)
            assertEquals(expected.item.availability, actual.item.availability)
            assertEquals(expected.item.addedAtMs, actual.item.addedAtMs)
            assertEquals(expected.sourceKey, actual.sourceKey)
        }
        assertEquals(backup.folders, restored.folders)
        assertEquals(backup.members, restored.members)
        assertEquals(backup.progress, restored.progress)
        assertEquals(backup.cursors, restored.cursors)
        assertEquals(backup.bookmarks, restored.bookmarks)
        // The reference item carries every optional field as null.
        assertNull(restored.items[1].item.metadata.title)
        assertNull(restored.items[1].item.metadata.sizeBytes)
        assertNull(restored.items[1].item.displayName)
        // Encoding is deterministic, so the same backup always yields the same checked bytes.
        assertEquals(output.written().toList(), serialized(backup).toList())
        assertTrue(output.written().size.toLong() < LocalAudioBackupCodec.MAX_BYTES)
    }

    @Test
    fun roundTripCoversEmptyBackupAndLongestAllowedNames() {
        val empty = LocalAudioBackup(uuid(1), 0, emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList())
        val restoredEmpty = LocalAudioBackupCodec.read(ByteArrayInputStream(serialized(empty)))
        assertEquals(empty.id, restoredEmpty.id)
        assertTrue(restoredEmpty.items.isEmpty())

        // 512 emoji are 1024 UTF-16 units and 2048 UTF-8 bytes: at the character ceiling without
        // crossing it, so the written length prefix and the decoded text must match exactly.
        val widest = "🎵".repeat(LocalAudioLimits.MAX_NAME / 2)
        val item = LocalAudioItem(uuid(2), LocalAudioMetadata(widest, widest, widest,
            LocalAudioLimits.MAX_POSITION_MS, 1, 0), LocalImportMode.COPY, widest,
            LocalAudioAvailability.UNREADABLE, 9)
        val backup = LocalAudioBackup(uuid(1), 1, listOf(LocalAudioBackupItem(item, sourceKey(1))),
            listOf(LocalAudioFolder(uuid(3), widest, false)), emptyList(), emptyList(),
            emptyList(), emptyList())

        val restored = LocalAudioBackupCodec.read(ByteArrayInputStream(serialized(backup)))

        assertEquals(widest, restored.items.single().item.metadata.fileName)
        assertEquals(widest, restored.items.single().item.displayName)
        assertEquals(widest, restored.folders.single().name)
        assertEquals(LocalAudioAvailability.UNREADABLE,
            restored.items.single().item.availability)
    }

    @Test
    fun checksumCorruptionIsInvalidAndRehashingDoesNotHideIt() {
        val valid = envelope(payload())
        // Sanity check: the fixture is a readable envelope before it is damaged.
        assertEquals(1L, LocalAudioBackupCodec.read(ByteArrayInputStream(valid)).createdAtMs)

        // The payload's second field is createdAtMs; flipping it must not survive the digest.
        val createdAt = MAGIC.size + 4 + 4 + ID_BYTES
        val corrupted = valid.copyOf()
        corrupted[createdAt + 7] = 2
        assertFailure(LocalAudioError.INVALID_BACKUP) { read(corrupted) }

        // The same edit with a fresh footer is accepted, proving the failure above was the digest.
        assertEquals(2L, read(rehash(corrupted)).createdAtMs)

        // A damaged payload byte inside a name is invalid as well, even though it still decodes.
        val sample = serialized(sampleBackup())
        val nameOffset = indexOf(sample, "plain.mp3".toByteArray(Charsets.UTF_8))
        assertTrue(nameOffset > 0)
        val damagedName = sample.copyOf()
        damagedName[nameOffset] = 'q'.code.toByte()
        assertFailure(LocalAudioError.INVALID_BACKUP) { read(damagedName) }
    }

    @Test
    fun truncationAndTrailingBytesAreInvalid() {
        val bytes = serialized(sampleBackup())
        listOf(0, 1, MAGIC.size, MAGIC.size + 4, MAGIC.size + 20, bytes.size / 2,
            bytes.size - 1).forEach { size ->
            assertFailure(LocalAudioError.INVALID_BACKUP) { read(bytes.copyOf(size)) }
        }
        assertFailure(LocalAudioError.INVALID_BACKUP) { read(bytes + byteArrayOf(0)) }
        assertFailure(LocalAudioError.INVALID_BACKUP) {
            read(bytes + ByteArray(FOOTER_BYTES))
        }
    }

    @Test
    fun onlyVersionOneIsAccepted() {
        val versionTwo = serialized(sampleBackup()).also { it[MAGIC.size + 3] = 2 }
        assertFailure(LocalAudioError.UNSUPPORTED_BACKUP) { read(versionTwo) }

        val versionZero = serialized(sampleBackup()).also { it.fill(0, MAGIC.size, MAGIC.size + 4) }
        assertFailure(LocalAudioError.UNSUPPORTED_BACKUP) { read(versionZero) }

        // An unknown magic is not a backup at all, and is reported that way.
        val wrongMagic = serialized(sampleBackup()).also { it[0] = 'X'.code.toByte() }
        assertFailure(LocalAudioError.INVALID_BACKUP) { read(wrongMagic) }
    }

    @Test
    fun unknownEnumsAndModelViolationsAreInvalid() {
        // Unknown import mode: the only reference item is renamed to a non-constant.
        assertFailure(LocalAudioError.INVALID_BACKUP) {
            read(envelope(payloadWithItem(mode = "REFERENCX")))
        }
        assertFailure(LocalAudioError.INVALID_BACKUP) {
            read(envelope(payloadWithItem(availability = "GONE")))
        }
        // Duplicate bookmark IDs.
        assertFailure(LocalAudioError.INVALID_BACKUP) {
            read(envelope(payload(folders = 1, bookmarks = 2, duplicateBookmarkId = true)))
        }
        // Duplicate folder IDs.
        assertFailure(LocalAudioError.INVALID_BACKUP) {
            read(envelope(payload(folders = 2, duplicateFolderId = true)))
        }
        // A member whose audio ID exists in no item.
        assertFailure(LocalAudioError.INVALID_BACKUP) {
            read(envelope(payloadWithItem(memberAudioId = uuid(99))))
        }
        // A cursor pointing at a folder/audio pair that is not a membership.
        assertFailure(LocalAudioError.INVALID_BACKUP) {
            read(envelope(payloadWithItem(cursor = true)))
        }
        // Progress beyond the item count.
        assertFailure(LocalAudioError.INVALID_BACKUP) {
            read(envelope(payload(progressOnly = true)))
        }
        // A negative createdAt in an otherwise well-formed envelope.
        assertFailure(LocalAudioError.INVALID_BACKUP) {
            read(envelope(withCreatedAt(payload(), -1)))
        }
    }

    @Test
    fun malformedLengthsAreRejectedBeforeAllocating() {
        // Negative counts and lengths are impossible in a written envelope.
        assertFailure(LocalAudioError.INVALID_BACKUP) {
            read(envelope(uuidText(uuid(1)) + long(0) + int(-1)))
        }
        assertFailure(LocalAudioError.INVALID_BACKUP) {
            read(envelope(uuidText(uuid(1)) + long(0) + int(0) + int(0) + int(0) + int(0) +
                int(0) + int(-3)))
        }
        // A declared name length above the field cap fails without a large allocation, and the
        // stream ends right after the length: a LIMIT rather than a truncation error.
        assertFailure(LocalAudioError.LIMIT_REACHED) {
            read(envelope(uuidText(uuid(1)) + long(0) + int(1) + uuidText(uuid(2)) +
                int(Int.MAX_VALUE)))
        }
        assertFailure(LocalAudioError.LIMIT_REACHED) {
            read(envelope(uuidText(uuid(1)) + long(0) + int(1) + uuidText(uuid(2)) +
                int(NAME_BYTES + 1)))
        }
        // Invalid UTF-8 is refused instead of being replaced.
        assertFailure(LocalAudioError.INVALID_BACKUP) {
            read(envelope(int(ID_BYTES) + ByteArray(ID_BYTES) { 0xC3.toByte() } + long(0)))
        }
        // A 36-character value that is not a UUID, and a name carrying a control character.
        assertFailure(LocalAudioError.INVALID_BACKUP) {
            read(envelope(text("z".repeat(ID_BYTES)) + long(0)))
        }
        assertFailure(LocalAudioError.INVALID_BACKUP) {
            read(envelope(payloadWithItem(fileName = "bad\u0001name")))
        }
    }

    @Test
    fun everyListCapIsEnforcedOnRead() {
        val head = uuidText(uuid(1)) + long(0)
        assertFailure(LocalAudioError.LIMIT_REACHED) {
            read(envelope(head + int(LocalAudioLimits.MAX_ITEMS + 1)))
        }
        assertFailure(LocalAudioError.LIMIT_REACHED) {
            read(envelope(head + int(0) + int(LocalAudioLimits.MAX_FOLDERS + 1)))
        }
        assertFailure(LocalAudioError.LIMIT_REACHED) {
            read(envelope(head + int(0) + int(0) + int(LocalAudioLimits.MAX_MEMBERS + 1)))
        }
        assertFailure(LocalAudioError.LIMIT_REACHED) {
            read(envelope(head + int(0) + int(0) + int(0) + int(0) + int(0) +
                int(LocalAudioLimits.MAX_BOOKMARKS + 1)))
        }
        // Progress is bounded by the item count, cursors by the folder count.
        assertFailure(LocalAudioError.LIMIT_REACHED) {
            read(envelope(head + int(0) + int(0) + int(0) + int(LocalAudioLimits.MAX_ITEMS + 1)))
        }
        assertFailure(LocalAudioError.LIMIT_REACHED) {
            read(envelope(head + int(0) + int(0) + int(0) + int(0) +
                int(LocalAudioLimits.MAX_FOLDERS + 1)))
        }
    }

    @Test
    fun envelopeBeyondTheByteBudgetIsRefusedWhileStreaming() {
        // 50k bookmarks with 1 KiB names exceed 32 MiB long before the list ends; the stream is
        // generated, so this test proves the cap without buffering an oversized envelope.
        val prefix = uuidText(uuid(1)) + long(0) + int(0) + int(0) + int(0) + int(0) + int(0) +
            int(LocalAudioLimits.MAX_BOOKMARKS)
        val entry = uuidText(uuid(2)) + uuidText(uuid(3)) + long(0) +
            text("b".repeat(LocalAudioLimits.MAX_NAME))
        val body = MAGIC + int(1) + prefix + entry
        assertTrue(body.size < 4_000)

        assertFailure(LocalAudioError.LIMIT_REACHED) {
            LocalAudioBackupCodec.read(
                RepeatingStream(prefix = MAGIC + int(1) + prefix, block = entry,
                    repeat = LocalAudioLimits.MAX_BOOKMARKS),
            )
        }
    }

    @Test
    fun cancellationIsPropagatedInBothDirectionsAndStreamsStayOpen() {
        val cancellation = CancellationException("user cancelled")
        val output = TrackingOutputStream()
        var calls = 0

        try {
            LocalAudioBackupCodec.write(sampleBackup(), output) {
                calls++
                if (calls > 3) throw cancellation
            }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertFalse(output.closed)

        val input = TrackingInputStream(serialized(sampleBackup()))
        calls = 0
        try {
            LocalAudioBackupCodec.read(input) {
                calls++
                if (calls > 2) throw cancellation
            }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertTrue(input.reads > 0)
        assertFalse(input.closed)
    }

    @Test
    fun callerStreamsAreFlushedButNeverClosed() {
        val output = TrackingOutputStream()
        LocalAudioBackupCodec.write(sampleBackup(), output)
        assertTrue(output.flushes >= 1)
        assertFalse(output.closed)

        val input = TrackingInputStream(serialized(sampleBackup()))
        LocalAudioBackupCodec.read(input)
        assertFalse(input.closed)
    }

    @Test
    fun streamFailuresKeepTheirCategoriesAndErrorsAreNotTranslated() {
        assertFailure(LocalAudioError.STORAGE_FAILED) {
            LocalAudioBackupCodec.write(sampleBackup(), FailingOutputStream())
        }
        assertFailure(LocalAudioError.STORAGE_FAILED) {
            LocalAudioBackupCodec.write(sampleBackup(), FailingFlushOutputStream())
        }
        assertFailure(LocalAudioError.READ_FAILED) {
            LocalAudioBackupCodec.read(FailingInputStream(serialized(sampleBackup())))
        }

        val thrown = OutOfMemoryError("injected")
        try {
            LocalAudioBackupCodec.write(sampleBackup(), ThrowingOutputStream(thrown))
            fail("Expected the original error")
        } catch (actual: OutOfMemoryError) {
            assertSame(thrown, actual)
        }
    }

    private fun sampleBackup(): LocalAudioBackup {
        val copy = LocalAudioItem(
            id = uuid(10),
            metadata = LocalAudioMetadata(
                fileName = "Ōkami 歌曲 🎵.flac",
                title = "遠い約束 — Pražský výběr",
                creator = "Фёдор",
                durationMs = LocalAudioLimits.MAX_POSITION_MS,
                sizeBytes = 64L * 1024 * 1024 * 1024,
                modifiedAtMs = 1_700_000_000_000,
            ),
            mode = LocalImportMode.COPY,
            displayName = "Мой 📻",
            availability = LocalAudioAvailability.AVAILABLE,
            addedAtMs = 1,
        )
        val reference = LocalAudioItem(
            id = uuid(11),
            metadata = LocalAudioMetadata(fileName = "plain.mp3"),
            mode = LocalImportMode.REFERENCE,
            availability = LocalAudioAvailability.MISSING,
            addedAtMs = 2,
        )
        val folders = listOf(
            LocalAudioFolder(uuid(20), "晚间 🌙", false),
            LocalAudioFolder(uuid(21), "Fahrten"),
        )
        return LocalAudioBackup(
            id = uuid(1),
            createdAtMs = 1_700_000_000_123,
            items = listOf(LocalAudioBackupItem(copy, sourceKey(5)),
                LocalAudioBackupItem(reference, sourceKey(6))),
            folders = folders,
            members = listOf(
                LocalAudioMember(folders[0].id, copy.id, 0),
                LocalAudioMember(folders[1].id, reference.id, 5),
            ),
            progress = listOf(
                LocalAudioProgress(copy.id, LocalAudioLimits.MAX_POSITION_MS, true,
                    1_700_000_000_500),
                LocalAudioProgress(reference.id, 0, false, 0),
            ),
            cursors = listOf(LocalAudioFolderCursor(folders[0].id, copy.id)),
            bookmarks = listOf(
                LocalAudioBookmark(uuid(30), copy.id, 12_345, "标记 🔖"),
                LocalAudioBookmark(uuid(31), reference.id, 0, "start"),
            ),
        )
    }

    private fun serialized(value: LocalAudioBackup): ByteArray {
        val output = ByteArrayOutputStream()
        LocalAudioBackupCodec.write(value, output)
        return output.toByteArray()
    }

    private fun read(bytes: ByteArray): LocalAudioBackup =
        LocalAudioBackupCodec.read(ByteArrayInputStream(bytes))

    /** Wraps a payload in the real header and a correct footer. */
    private fun envelope(body: ByteArray): ByteArray {
        val head = MAGIC + int(1) + body
        return head + sha256(head)
    }

    /** Replaces a stale footer after a deliberate payload edit. */
    private fun rehash(bytes: ByteArray): ByteArray {
        val head = bytes.copyOf(bytes.size - FOOTER_BYTES)
        return head + sha256(head)
    }

    private fun withCreatedAt(bytes: ByteArray, value: Long): ByteArray {
        val result = bytes.copyOf()
        long(value).copyInto(result, MAGIC.size + 4 + 4 + ID_BYTES)
        return result
    }

    /** Minimal but structurally complete payload; only what a test needs varies. */
    @Suppress("LongParameterList")
    private fun payload(
        folders: Int = 0,
        bookmarks: Int = 0,
        duplicateFolderId: Boolean = false,
        duplicateBookmarkId: Boolean = false,
        progressOnly: Boolean = false,
    ): ByteArray {
        val audioId = uuid(10)
        val folderId = uuid(20)
        val builder = PayloadBuilder()
        builder.uuid(uuid(1))
        builder.long(1)
        builder.int(0)
        builder.int(folders)
        repeat(folders) { index ->
            builder.uuid(if (duplicateFolderId && index > 0) folderId else uuid(20 + index))
            builder.text("Folder $index")
            builder.boolean(true)
        }
        builder.int(0)
        builder.int(if (progressOnly) 1 else 0)
        if (progressOnly) {
            builder.uuid(audioId)
            builder.long(0)
            builder.boolean(false)
            builder.long(0)
        }
        builder.int(0)
        builder.int(bookmarks)
        repeat(bookmarks) { index ->
            builder.uuid(if (duplicateBookmarkId && index > 0) uuid(30) else uuid(30 + index))
            builder.uuid(audioId)
            builder.long(0)
            builder.text("Bookmark $index")
        }
        return builder.bytes()
    }

    /** One item plus, optionally, one folder, one membership, one cursor and one progress. */
    @Suppress("LongParameterList")
    private fun payloadWithItem(
        fileName: String = "track.flac",
        mode: String = "COPY",
        availability: String = "AVAILABLE",
        memberAudioId: String? = null,
        cursor: Boolean = false,
    ): ByteArray {
        val audioId = uuid(10)
        val folderId = uuid(20)
        val builder = PayloadBuilder()
        builder.uuid(uuid(1))
        builder.long(1)
        builder.int(1)
        builder.uuid(audioId)
        builder.text(fileName)
        builder.boolean(false)
        builder.boolean(false)
        builder.boolean(true)
        builder.long(1_000)
        builder.boolean(false)
        builder.boolean(false)
        builder.text(mode)
        builder.boolean(false)
        builder.text(availability)
        builder.long(3)
        builder.text(sourceKey(7))
        builder.int(1)
        builder.uuid(folderId)
        builder.text("Folder")
        builder.boolean(true)
        builder.int(1)
        builder.uuid(folderId)
        builder.uuid(memberAudioId ?: audioId)
        builder.int(0)
        builder.int(0)
        builder.int(if (cursor) 1 else 0)
        if (cursor) {
            builder.uuid(folderId)
            builder.uuid(uuid(99))
        }
        builder.int(0)
        return builder.bytes()
    }

    private class PayloadBuilder {
        private val buffer = ByteArrayOutputStream()
        private val out = DataOutputStream(buffer)

        fun int(value: Int) = out.writeInt(value)
        fun long(value: Long) = out.writeLong(value)
        fun boolean(value: Boolean) = out.writeBoolean(value)

        fun text(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
        }

        fun uuid(value: String) = text(value)

        fun bytes(): ByteArray {
            out.flush()
            return buffer.toByteArray()
        }
    }

    private fun uuidText(value: String) = text(value)

    private fun text(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return int(bytes.size) + bytes
    }

    private fun int(value: Int): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(),
            (value ushr 8).toByte(), value.toByte())

    private fun long(value: Long): ByteArray = int((value ushr 32).toInt()) + int(value.toInt())

    private fun uuid(value: Int): String =
        "00000000-0000-4000-8000-" + value.toString().padStart(12, '0')

    private fun sourceKey(value: Int): String =
        value.toString().padStart(64, '0').map { if (it == '0') 'a' else it }.joinToString("")

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun indexOf(bytes: ByteArray, pattern: ByteArray): Int {
        outer@ for (start in 0..bytes.size - pattern.size) {
            for (offset in pattern.indices) {
                if (bytes[start + offset] != pattern[offset]) continue@outer
            }
            return start
        }
        return -1
    }

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
        var reads = 0
            private set
        var closed = false
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

        override fun write(value: Int) = bytes.write(value)

        override fun write(buffer: ByteArray, offset: Int, length: Int) =
            bytes.write(buffer, offset, length)

        override fun flush() {
            flushes++
        }

        override fun close() {
            closed = true
        }

        fun written(): ByteArray = bytes.toByteArray()
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

    /** Delivers half of the envelope, then fails like a damaged document read. */
    private class FailingInputStream(private val payload: ByteArray) : InputStream() {
        private var index = 0

        override fun read(): Int {
            if (index >= payload.size / 2) throw IOException("source read failed")
            return payload[index++].toInt() and 0xff
        }
    }

    /** Serves [prefix] then [block] repeated [repeat] times without holding the whole body. */
    private class RepeatingStream(
        private val prefix: ByteArray,
        private val block: ByteArray,
        private val repeat: Int,
    ) : InputStream() {
        private var prefixIndex = 0
        private var blocksLeft = repeat
        private var blockIndex = 0

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            var written = 0
            while (written < length) {
                if (prefixIndex < prefix.size) {
                    bytes[offset + written] = prefix[prefixIndex++]
                } else if (blocksLeft > 0) {
                    bytes[offset + written] = block[blockIndex]
                    blockIndex++
                    if (blockIndex == block.size) {
                        blockIndex = 0
                        blocksLeft--
                    }
                } else {
                    break
                }
                written++
            }
            return if (written == 0) -1 else written
        }
    }

    private companion object {
        const val FOOTER_BYTES = 32
        const val ID_BYTES = 36
        const val NAME_BYTES = 4096
        val MAGIC: ByteArray = "MUSXI-LOCAL-ORG".toByteArray(Charsets.US_ASCII)
    }
}
