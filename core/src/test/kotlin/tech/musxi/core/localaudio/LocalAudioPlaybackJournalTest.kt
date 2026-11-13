package tech.musxi.core.localaudio

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAudioPlaybackJournalTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val idA = "11111111-1111-1111-1111-111111111111"
    private val idB = "22222222-2222-2222-2222-222222222222"
    private val idC = "33333333-3333-3333-3333-333333333333"
    private val versionA = "a".repeat(64)
    private val versionB = "b".repeat(64)
    private val storedFailure = "Local audio: STORAGE_FAILED"

    @Test
    fun roundTripKeepsLongPositionsAndStoredProgressFlag() {
        val directory = temporary.newFolder("round-trip")
        val twentyFiveHours = 25L * 60 * 60 * 1000
        val journal = LocalAudioPlaybackJournal(
            SavedLocalAudioQueue(
                folderId = idC,
                entries = listOf(
                    SavedLocalAudioEntry(idA, versionA, twentyFiveHours),
                    SavedLocalAudioEntry(idB, versionB, 0),
                ),
                index = 1,
                positionMs = twentyFiveHours + 7,
                repeatMode = "ALL",
                shuffleEnabled = true,
                shuffleOrder = listOf(1, 0),
                continuePlayback = false,
                savedAtEpochMs = 1_700_000_000_000,
                useStoredProgress = false,
            ),
            active = true,
        )

        FileLocalAudioPlaybackJournalStore(directory).write(journal)

        val restored = FileLocalAudioPlaybackJournalStore(directory).read()
        assertEquals(journal, restored)
        assertEquals(twentyFiveHours, restored.queue!!.entries.first().positionMs)
        assertFalse(restored.queue!!.useStoredProgress)
        assertTrue(restored.active)
    }

    @Test
    fun missingFileReturnsEmptyJournalAndInactiveQueueSurvives() {
        assertEquals(
            LocalAudioPlaybackJournal(),
            FileLocalAudioPlaybackJournalStore(temporary.newFolder("never-written")).read(),
        )

        val directory = temporary.newFolder("inactive")
        val store = FileLocalAudioPlaybackJournalStore(directory)
        val journal = LocalAudioPlaybackJournal(
            SavedLocalAudioQueue(
                folderId = null,
                entries = listOf(SavedLocalAudioEntry(idA, versionA, 10)),
                index = 0,
                positionMs = 10,
                repeatMode = "OFF",
                shuffleEnabled = false,
                shuffleOrder = listOf(0),
                continuePlayback = true,
                savedAtEpochMs = 5,
            ),
            active = false,
        )
        store.write(journal)

        val restored = FileLocalAudioPlaybackJournalStore(directory).read()
        assertEquals(journal, restored)
        assertTrue(restored.queue!!.useStoredProgress)
        assertNull(restored.queue!!.folderId)
        assertFalse(restored.active)
    }

    @Test
    fun storedBytesStayPathFreeAndCallerListsCannotRewriteTheSnapshot() {
        val directory = temporary.newFolder("snapshot")
        val entries = mutableListOf(
            SavedLocalAudioEntry(idA, versionA, 1),
            SavedLocalAudioEntry(idB, versionB, 2),
        )
        val order = mutableListOf(0, 1)
        val queue = SavedLocalAudioQueue(
            folderId = null,
            entries = entries,
            index = 0,
            positionMs = 1,
            repeatMode = "ONE",
            shuffleEnabled = false,
            shuffleOrder = order,
            continuePlayback = true,
            savedAtEpochMs = 3,
        )
        val store = FileLocalAudioPlaybackJournalStore(directory)
        store.write(LocalAudioPlaybackJournal(queue, active = true))

        entries.clear()
        order.clear()

        val restored = store.read()
        assertEquals(listOf(idA, idB), restored.queue!!.entries.map { it.audioId })
        assertEquals(listOf(0, 1), restored.queue!!.shuffleOrder)
        assertThrowsUnsupported { (restored.queue!!.entries as MutableList<SavedLocalAudioEntry>).clear() }
        assertThrowsUnsupported { (restored.queue!!.shuffleOrder as MutableList<Int>).clear() }

        val text = journalFile(directory).readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(text.contains(directory.absolutePath))
        assertFalse(text.contains("content://"))
        assertFalse(text.contains("file://"))
        assertFalse(text.contains("/storage/"))
    }

    @Test
    fun fullQueueFitsThePayloadLimitAndKeepsOrder() {
        val directory = temporary.newFolder("capacity")
        val entries = List(LocalAudioLimits.MAX_ITEMS) {
            SavedLocalAudioEntry("00000000-0000-0000-0000-%012d".format(it), versionA, it.toLong())
        }
        val store = FileLocalAudioPlaybackJournalStore(directory)
        val journal = LocalAudioPlaybackJournal(
            SavedLocalAudioQueue(
                folderId = null,
                entries = entries,
                index = entries.lastIndex,
                positionMs = 1,
                repeatMode = "OFF",
                shuffleEnabled = true,
                shuffleOrder = entries.indices.reversed().toList(),
                continuePlayback = true,
                savedAtEpochMs = 1,
            ),
            active = true,
        )

        store.write(journal)

        assertTrue(journalFile(directory).length() < 4L * 1024 * 1024)
        val restored = store.read()
        assertEquals(entries, restored.queue!!.entries)
        assertEquals(entries.lastIndex, restored.queue!!.index)
        assertEquals(entries.indices.reversed().toList(), restored.queue!!.shuffleOrder)
    }

    @Test
    fun damagedTruncatedOversizedAndUnknownVersionFilesReturnEmptyJournal() {
        val directory = temporary.newFolder("damage")
        val store = FileLocalAudioPlaybackJournalStore(directory)
        val valid = queueJournal()
        store.write(valid)
        val file = journalFile(directory)
        val original = file.readBytes()

        file.writeBytes(original.copyOf(original.size - 1))
        assertEquals(LocalAudioPlaybackJournal(), store.read())

        file.writeBytes(original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        assertEquals(LocalAudioPlaybackJournal(), store.read())

        craft(directory, payload = payloadOf(valid), version = 2)
        assertEquals(LocalAudioPlaybackJournal(), store.read())

        craft(directory, payload = payloadOf(valid), version = 0)
        assertEquals(LocalAudioPlaybackJournal(), store.read())

        file.writeBytes(original.copyOf(10))
        assertEquals(LocalAudioPlaybackJournal(), store.read())

        file.writeBytes(ByteArray(0))
        assertEquals(LocalAudioPlaybackJournal(), store.read())

        val oversized = 4L * 1024 * 1024 + 21
        RandomAccessFile(file, "rw").use { it.setLength(oversized) }
        assertEquals(oversized, file.length())
        assertEquals(LocalAudioPlaybackJournal(), store.read())

        craft(directory, payload = payloadOf(valid))
        assertEquals(valid, store.read())
    }

    @Test
    fun readRejectsIllegalContentWithoutTrustingLengths() {
        val directory = temporary.newFolder("invalid-content")
        val store = FileLocalAudioPlaybackJournalStore(directory)

        val cases = mapOf(
            "uuid" to queuePayload(entry("not-a-uuid", versionA, 0), entry(idB, versionB, 0)),
            "version-case" to queuePayload(entry(idA, versionA.uppercase(), 0), entry(idB, versionB, 0)),
            "version-length" to queuePayload(entry(idA, "abc", 0), entry(idB, versionB, 0)),
            "position" to queuePayload(entry(idA, versionA, -1), entry(idB, versionB, 0)),
            "duplicate" to queuePayload(entry(idA, versionA, 0), entry(idA, versionB, 0)),
            "index" to queuePayload(entry(idA, versionA, 0), entry(idB, versionB, 0), index = 2),
            "negative-index" to queuePayload(entry(idA, versionA, 0), entry(idB, versionB, 0), index = -1),
            "repeat" to queuePayload(entry(idA, versionA, 0), entry(idB, versionB, 0), repeatMode = "SHUFFLE"),
            "shuffle-duplicate" to queuePayload(
                entry(idA, versionA, 0), entry(idB, versionB, 0), order = listOf(0, 0),
            ),
            "shuffle-short" to queuePayload(
                entry(idA, versionA, 0), entry(idB, versionB, 0), order = listOf(0),
            ),
            "saved-at" to queuePayload(entry(idA, versionA, 0), entry(idB, versionB, 0), savedAt = -1),
            "active-without-queue" to activeWithoutQueue(),
            "count-overflow" to queueHeaderOnly(count = LocalAudioLimits.MAX_ITEMS + 1),
            "count-negative" to queueHeaderOnly(count = -1),
            "count-exceeds-payload" to queueHeaderOnly(count = LocalAudioLimits.MAX_ITEMS),
            "trailing-bytes" to payloadOf(queueJournal()) + byteArrayOf(0),
        )
        cases.forEach { (name, payload) ->
            craft(directory, payload = payload)
            assertEquals(name, LocalAudioPlaybackJournal(), store.read())
        }
    }

    @Test
    fun invalidWritesKeepThePreviousFile() {
        val directory = temporary.newFolder("invalid-write")
        val store = FileLocalAudioPlaybackJournalStore(directory)
        val valid = queueJournal()
        store.write(valid)
        val before = journalFile(directory).readBytes()

        val entries = mutableListOf(
            SavedLocalAudioEntry(idA, versionA, 0),
            SavedLocalAudioEntry(idB, versionB, 0),
        )
        val order = mutableListOf(0, 1)
        val queue = SavedLocalAudioQueue(null, entries, 0, 0, "OFF", false, order, true, 1)
        store.write(LocalAudioPlaybackJournal(queue, active = true))
        val withQueue = journalFile(directory).readBytes()

        entries.removeAt(1)
        assertEquals(storedFailure, failureOf {
            store.write(LocalAudioPlaybackJournal(queue, active = true))
        }.message)
        assertTrue(journalFile(directory).readBytes().contentEquals(withQueue))

        entries.add(SavedLocalAudioEntry(idB, versionB, 0))
        order[1] = 0
        assertEquals(storedFailure, failureOf {
            store.write(LocalAudioPlaybackJournal(queue, active = true))
        }.message)
        assertTrue(journalFile(directory).readBytes().contentEquals(withQueue))

        order[1] = 1
        entries[0] = SavedLocalAudioEntry(idB, versionA, 0)
        assertEquals(storedFailure, failureOf {
            store.write(LocalAudioPlaybackJournal(queue, active = false))
        }.message)
        assertTrue(journalFile(directory).readBytes().contentEquals(withQueue))

        store.write(valid)
        assertTrue(journalFile(directory).readBytes().contentEquals(before))
    }

    @Test
    fun symlinkedDirectoryAndTargetAreNotFollowedWhenAvailable() {
        val realDirectory = temporary.newFolder("link-real")
        val parent = temporary.newFolder("link-parent")
        val linkedDirectory = File(parent, "linked")
        if (createSymbolicLink(linkedDirectory, realDirectory)) {
            val store = FileLocalAudioPlaybackJournalStore(linkedDirectory)
            assertEquals(LocalAudioPlaybackJournal(), store.read())
            assertEquals(storedFailure, failureOf { store.write(queueJournal()) }.message)
            assertTrue(realDirectory.listFiles().isNullOrEmpty())
        }

        val directory = temporary.newFolder("link-target")
        val outside = File(temporary.newFolder("link-outside"), "outside.bin")
        outside.writeBytes(payloadOf(queueJournal()))
        val outsideBytes = outside.readBytes()
        if (createSymbolicLink(journalFile(directory), outside)) {
            val target = journalFile(directory)
            assertTrue(Files.isSymbolicLink(target.toPath()))
            val store = FileLocalAudioPlaybackJournalStore(directory)
            assertEquals(LocalAudioPlaybackJournal(), store.read())
            assertEquals(storedFailure, failureOf { store.write(queueJournal()) }.message)
            assertTrue(outside.readBytes().contentEquals(outsideBytes))
            assertTrue(Files.isSymbolicLink(target.toPath()))
        }
    }

    @Test
    fun modelsRejectInvalidCombinationsAtConstruction() {
        assertRejected { SavedLocalAudioEntry("bad", versionA, 0) }
        assertRejected { SavedLocalAudioEntry(idA, "d".repeat(63), 0) }
        assertRejected { SavedLocalAudioEntry(idA, versionA, LocalAudioLimits.MAX_POSITION_MS + 1) }
        assertRejected { LocalAudioPlaybackJournal(active = true) }
        assertRejected {
            SavedLocalAudioQueue("bad", listOf(entryOf(idA)), 0, 0, "OFF", false, listOf(0), true, 0)
        }
        assertRejected {
            SavedLocalAudioQueue(null, emptyList(), 0, 0, "OFF", false, emptyList(), true, 0)
        }
        assertRejected {
            SavedLocalAudioQueue(
                null,
                List(LocalAudioLimits.MAX_ITEMS + 1) { entryOf("00000000-0000-0000-0000-%012d".format(it)) },
                0,
                0,
                "OFF",
                false,
                (0..LocalAudioLimits.MAX_ITEMS).toList(),
                true,
                0,
            )
        }
        assertRejected {
            SavedLocalAudioQueue(
                null,
                listOf(entryOf(idA)),
                0,
                LocalAudioLimits.MAX_POSITION_MS + 1,
                "OFF",
                false,
                listOf(0),
                true,
                0,
            )
        }
        assertRejected {
            SavedLocalAudioQueue(null, listOf(entryOf(idA)), 0, 0, "ALL", true, listOf(1), true, 0)
        }
        assertRejected {
            SavedLocalAudioQueue(
                null,
                listOf(entryOf(idA), SavedLocalAudioEntry(idA, versionB, 0)),
                0, 0, "ALL", false, listOf(0, 1), true, 1,
            )
        }
    }

    private fun entryOf(audioId: String) = SavedLocalAudioEntry(audioId, versionA, 0)

    private fun assertRejected(block: () -> Unit) {
        val failed = try {
            block()
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue(failed)
    }

    private fun assertThrowsUnsupported(block: () -> Unit) {
        val failed = try {
            block()
            false
        } catch (_: UnsupportedOperationException) {
            true
        }
        assertTrue(failed)
    }

    private fun failureOf(block: () -> Unit): LocalAudioFailure = try {
        block()
        throw AssertionError("expected failure")
    } catch (failure: LocalAudioFailure) {
        failure
    }

    private fun queueJournal() = LocalAudioPlaybackJournal(
        SavedLocalAudioQueue(
            folderId = idC,
            entries = listOf(
                SavedLocalAudioEntry(idA, versionA, 5),
                SavedLocalAudioEntry(idB, versionB, 6),
            ),
            index = 1,
            positionMs = 6,
            repeatMode = "ALL",
            shuffleEnabled = true,
            shuffleOrder = listOf(1, 0),
            continuePlayback = true,
            savedAtEpochMs = 9,
        ),
        active = true,
    )

    private fun journalFile(directory: File) =
        File(directory, "local-audio-playback-journal-v1.bin")

    private fun payloadOf(journal: LocalAudioPlaybackJournal): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeBoolean(journal.active)
                val queue = journal.queue
                data.writeBoolean(queue != null)
                if (queue != null) {
                    data.writeBoolean(queue.folderId != null)
                    queue.folderId?.let(data::writeUTF)
                    data.writeInt(queue.entries.size)
                    queue.entries.forEach {
                        data.writeUTF(it.audioId)
                        data.writeUTF(it.sourceVersion)
                        data.writeLong(it.positionMs)
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
        }.toByteArray()

    private fun activeWithoutQueue(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { data ->
            data.writeBoolean(true)
            data.writeBoolean(false)
        }
    }.toByteArray()

    private fun queueHeaderOnly(count: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { data ->
            data.writeBoolean(false)
            data.writeBoolean(true)
            data.writeBoolean(false)
            data.writeInt(count)
        }
    }.toByteArray()

    private fun queuePayload(
        vararg entries: RawEntry,
        index: Int = 0,
        repeatMode: String = "ALL",
        order: List<Int> = entries.indices.toList(),
        savedAt: Long = 7,
    ): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { data ->
            data.writeBoolean(false)
            data.writeBoolean(true)
            data.writeBoolean(false)
            data.writeInt(entries.size)
            entries.forEach {
                data.writeUTF(it.audioId)
                data.writeUTF(it.sourceVersion)
                data.writeLong(it.positionMs)
            }
            data.writeInt(index)
            data.writeLong(0)
            data.writeUTF(repeatMode)
            data.writeBoolean(false)
            data.writeInt(order.size)
            order.forEach(data::writeInt)
            data.writeBoolean(true)
            data.writeLong(savedAt)
            data.writeBoolean(true)
        }
    }.toByteArray()

    private fun entry(audioId: String, sourceVersion: String, positionMs: Long) =
        RawEntry(audioId, sourceVersion, positionMs)

    private fun craft(directory: File, payload: ByteArray, version: Int = 1) {
        val envelope = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeInt(0x4d584a51)
                data.writeInt(version)
                data.writeInt(payload.size)
                data.writeLong(CRC32().apply { update(payload) }.value)
                data.write(payload)
            }
        }.toByteArray()
        journalFile(directory).writeBytes(envelope)
    }

    private fun createSymbolicLink(link: File, target: File): Boolean = try {
        Files.createSymbolicLink(link.toPath(), target.toPath())
        true
    } catch (_: Exception) {
        assumeTrue("symbolic links unavailable in this environment", false)
        false
    }

    /** Raw entry fields that bypass model validation so the store decoder can be exercised. */
    private data class RawEntry(val audioId: String, val sourceVersion: String, val positionMs: Long)
}
