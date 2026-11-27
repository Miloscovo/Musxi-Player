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
import org.junit.Assert.assertThrows
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
    fun versionOneStillDecodesAndLeavesSegmentsNull() {
        val legacy = read(envelope(payloadWithItem(fileName = "legacy.flac")))

        assertNull(legacy.items.single().item.segment)
        assertEquals("legacy.flac", legacy.items.single().item.metadata.fileName)
    }

    @Test
    fun ordinaryKeysKeepTheHistoricalCopyAndReferenceValues() {
        val location = "b".repeat(64)
        val copy = StoredLocalAudio(
            LocalAudioItem(uuid(1), LocalAudioMetadata("ordinary.flac"),
                LocalImportMode.COPY, addedAtMs = 1),
            LocalAudioSource(LocalImportMode.COPY, location, "copy-identity"),
        )
        val reference = StoredLocalAudio(
            LocalAudioItem(uuid(2), LocalAudioMetadata("ordinary.flac", sizeBytes = 1_024,
                modifiedAtMs = 5_000), LocalImportMode.REFERENCE, addedAtMs = 1),
            LocalAudioSource(LocalImportMode.REFERENCE,
                "content://com.example.documents/document/ordinary", "doc-identity",
                "content://com.example.documents/tree/root"),
        )

        assertEquals(location, LocalAudioBackupKeys.source(copy))
        assertEquals(legacyReferenceKey("doc-identity", 1_024, 5_000),
            LocalAudioBackupKeys.source(reference))
    }

    @Test
    fun cueSegmentsFromOneCopySourceHaveDistinctStableKeys() {
        val location = "c".repeat(64)
        fun item(id: Int, startUs: Long, endUs: Long, track: Int): StoredLocalAudio {
            val segment = LocalAudioSegment(startUs, endUs, track)
            return StoredLocalAudio(
                LocalAudioItem(uuid(id), LocalAudioMetadata("album.flac",
                    durationMs = segment.durationMs), LocalImportMode.COPY,
                    addedAtMs = 1, segment = segment),
                LocalAudioSource(LocalImportMode.COPY, location,
                    "cue-v1:identity-$track"),
            )
        }
        val first = item(1, 0, 60_000_000, 1)
        val second = item(2, 60_000_000, 120_000_000, 2)
        val sameContentAgain = item(3, 0, 60_000_000, 1)

        val firstKey = LocalAudioBackupKeys.source(first)
        assertTrue(firstKey.matches(Regex("[0-9a-f]{64}")))
        assertFalse(firstKey == location)
        assertFalse(firstKey == LocalAudioBackupKeys.source(second))
        assertEquals(firstKey, LocalAudioBackupKeys.source(sameContentAgain))
        assertEquals(firstKey, LocalAudioBackupKeys.source(first.copy(
            item = first.item.copy(id = uuid(99)),
        )))
    }

    @Test
    fun segmentRoundTripIsExactAndContainsNoSourceCapability() {
        val firstSegment = LocalAudioSegment(0, 61_234_000, 1)
        val secondSegment = LocalAudioSegment(61_234_000, 123_456_000, 2)
        val location = "d".repeat(64)
        val firstSource = LocalAudioSource(LocalImportMode.COPY, location, "cue-v1:album-a")
        val secondSource = LocalAudioSource(LocalImportMode.COPY, location, "cue-v1:album-b")
        val first = LocalAudioItem(uuid(10), LocalAudioMetadata("album.flac",
            title = "Track 1", durationMs = firstSegment.durationMs), LocalImportMode.COPY,
            addedAtMs = 1, segment = firstSegment)
        val second = LocalAudioItem(uuid(11), LocalAudioMetadata("album.flac",
            title = "Track 2", durationMs = secondSegment.durationMs), LocalImportMode.COPY,
            addedAtMs = 2, segment = secondSegment)
        val backup = LocalAudioBackup(uuid(1), 1,
            listOf(
                LocalAudioBackupItem(first, LocalAudioBackupKeys.source(
                    StoredLocalAudio(first, firstSource))),
                LocalAudioBackupItem(second, LocalAudioBackupKeys.source(
                    StoredLocalAudio(second, secondSource))),
            ),
            emptyList(), emptyList(),
            listOf(
                LocalAudioProgress(first.id, 10_000, false, 1),
                LocalAudioProgress(second.id, 20_000, false, 1),
            ),
            emptyList(),
            listOf(LocalAudioBookmark(uuid(30), second.id, 30_000, "within range")),
        )

        val bytes = serialized(backup)
        val restored = read(bytes)

        assertEquals(listOf(firstSegment, secondSegment),
            restored.items.map { it.item.segment })
        assertEquals(backup.items.map { it.sourceKey },
            restored.items.map { it.sourceKey })
        val raw = String(bytes, Charsets.ISO_8859_1)
        assertFalse(raw.contains("content://"))
        assertFalse(raw.contains(location))
        assertFalse(raw.contains(firstSource.identity))
        assertFalse(raw.contains(secondSource.identity))
    }

    @Test
    fun segmentRangesAndDurationsAreValidated() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalAudioSegment(-1, 1_000, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalAudioSegment(1_000, 1_000, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalAudioSegment(0, 1_000, 0)
        }

        val segment = LocalAudioSegment(0, 2_000_000, 1)
        val audioId = uuid(10)
        val item = LocalAudioItem(audioId, LocalAudioMetadata("album.flac", durationMs = 1_000),
            LocalImportMode.COPY, addedAtMs = 1, segment = segment)
        assertThrows(IllegalArgumentException::class.java) {
            LocalAudioBackup(uuid(1), 1,
                listOf(LocalAudioBackupItem(item, sourceKey(1))),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalAudioBackup(uuid(1), 1,
                listOf(LocalAudioBackupItem(item.copy(metadata = LocalAudioMetadata(
                    "album.flac", durationMs = segment.durationMs)), sourceKey(1))),
                emptyList(), emptyList(),
                listOf(LocalAudioProgress(audioId, segment.durationMs + 1, false, 1)),
                emptyList(), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalAudioBackup(uuid(1), 1,
                listOf(LocalAudioBackupItem(item.copy(metadata = LocalAudioMetadata(
                    "album.flac", durationMs = segment.durationMs)), sourceKey(1))),
                emptyList(), emptyList(), emptyList(), emptyList(),
                listOf(LocalAudioBookmark(uuid(30), audioId, segment.durationMs + 1, "past end")))
        }
    }

    @Test
    fun malformedSegmentPayloadsAreInvalid() {
        fun payloadWithSegment(startUs: Long = 0, endUs: Long = 1_001_000,
            trackNumber: Int = 1, durationMs: Long? = 1_000,
            progressMs: Long? = null, bookmarkMs: Long? = null,
            onSegmentFlag: (Int) -> Unit = {}): ByteArray {
            val audioId = uuid(10)
            val folderId = uuid(20)
            val builder = PayloadBuilder()
            builder.uuid(uuid(1))
            builder.long(1)
            builder.int(1)
            builder.uuid(audioId)
            builder.text("album.flac")
            builder.boolean(false)
            builder.boolean(false)
            builder.boolean(durationMs != null)
            if (durationMs != null) builder.long(durationMs)
            builder.boolean(false)
            builder.boolean(false)
            builder.text("COPY")
            builder.boolean(false)
            builder.text("AVAILABLE")
            builder.long(1)
            builder.text(sourceKey(1))
            onSegmentFlag(builder.size())
            builder.boolean(true)
            builder.long(startUs)
            builder.long(endUs)
            builder.int(trackNumber)
            builder.int(1)
            builder.uuid(folderId)
            builder.text("Folder")
            builder.boolean(true)
            builder.int(1)
            builder.uuid(folderId)
            builder.uuid(audioId)
            builder.int(0)
            builder.int(if (progressMs != null) 1 else 0)
            if (progressMs != null) {
                builder.uuid(audioId)
                builder.long(progressMs)
                builder.boolean(false)
                builder.long(0)
            }
            builder.int(0)
            builder.int(if (bookmarkMs != null) 1 else 0)
            if (bookmarkMs != null) {
                builder.uuid(uuid(30))
                builder.uuid(audioId)
                builder.long(bookmarkMs)
                builder.text("Bookmark")
            }
            return builder.bytes()
        }

        assertFailure(LocalAudioError.INVALID_BACKUP) {
            read(envelope(payloadWithSegment(startUs = -1), version = 2))
        }
        assertFailure(LocalAudioError.INVALID_BACKUP) {
            read(envelope(payloadWithSegment(startUs = 2_000_000, endUs = 1_000_000), version = 2))
        }
        assertFailure(LocalAudioError.INVALID_BACKUP) {
            read(envelope(payloadWithSegment(trackNumber = 100), version = 2))
        }
        assertFailure(LocalAudioError.INVALID_BACKUP) {
            read(envelope(payloadWithSegment(durationMs = 999), version = 2))
        }
        assertFailure(LocalAudioError.INVALID_BACKUP) {
            read(envelope(payloadWithSegment(endUs = 4_001_000, durationMs = 3_000,
                progressMs = 4_000), version = 2))
        }
        assertFailure(LocalAudioError.INVALID_BACKUP) {
            read(envelope(payloadWithSegment(bookmarkMs = 1_001), version = 2))
        }
        var segmentFlagOffset = -1
        val complete = payloadWithSegment { segmentFlagOffset = it }
        assertTrue(segmentFlagOffset > 0)
        assertFailure(LocalAudioError.INVALID_BACKUP) {
            read(envelope(complete.copyOf(segmentFlagOffset + 1 + 9), version = 2))
        }
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
    fun onlyKnownVersionsAreAccepted() {
        val versionThree = serialized(sampleBackup()).also { it[MAGIC.size + 3] = 3 }
        assertFailure(LocalAudioError.UNSUPPORTED_BACKUP) { read(versionThree) }

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
    private fun envelope(body: ByteArray, version: Int = 1): ByteArray {
        val head = MAGIC + int(version) + body
        return head + sha256(head)
    }

    private fun legacyReferenceKey(identity: String, sizeBytes: Long?, modifiedAtMs: Long?): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUTF("musxi-local-reference-v1")
            val encoded = identity.toByteArray(Charsets.UTF_8)
            out.writeInt(encoded.size)
            out.write(encoded)
            out.writeLong(sizeBytes ?: -1)
            out.writeLong(modifiedAtMs ?: -1)
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
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

        fun size(): Int = buffer.size()

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

    private fun longAt(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 56) or
            ((bytes[offset + 1].toLong() and 0xff) shl 48) or
            ((bytes[offset + 2].toLong() and 0xff) shl 40) or
            ((bytes[offset + 3].toLong() and 0xff) shl 32) or
            ((bytes[offset + 4].toLong() and 0xff) shl 24) or
            ((bytes[offset + 5].toLong() and 0xff) shl 16) or
            ((bytes[offset + 6].toLong() and 0xff) shl 8) or
            (bytes[offset + 7].toLong() and 0xff)

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
        const val SOURCE_KEY_BYTES = 64
        const val NAME_BYTES = 4096
        val MAGIC: ByteArray = "MUSXI-LOCAL-ORG".toByteArray(Charsets.US_ASCII)
    }
}
