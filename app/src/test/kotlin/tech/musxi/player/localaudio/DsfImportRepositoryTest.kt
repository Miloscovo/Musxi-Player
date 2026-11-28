package tech.musxi.player.localaudio

import android.app.Application
import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.musxi.core.localaudio.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DsfImportRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val docs = Documents()
    private lateinit var store: SqliteLocalAudioStore
    private lateinit var files: LocalAudioFiles
    private lateinit var repository: AppLocalAudioRepository
    private lateinit var folder: LocalAudioFolder

    @Before fun setup() = runBlocking {
        store = SqliteLocalAudioStore(RuntimeEnvironment.getApplication() as Context, "dsf-${LocalAudioLimits.newId()}.db")
        store.read()
        files = LocalAudioFiles(File(temporary.root, "audio"))
        repository = AppLocalAudioRepository(store, files, docs, scope)
        var created: Result<LocalAudioFolder>? = null
        repository.createFolder("DSF") { created = it }.join()
        folder = created!!.getOrThrow()
    }
    @After fun close() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin(); store.close() }

    private suspend fun import(mode: LocalImportMode): LocalAudioImportResult {
        repository.importFiles(folder.id, listOf(docs.uri), mode, 65).join()
        return repository.imports.value.results.single()
    }

    @Test fun referenceImportsBoundedTagsAndProbesRandomAccessWithoutTraversingPayload() = runBlocking {
        val row = import(LocalImportMode.REFERENCE)
        assertNull(row.error)
        val item = repository.state.value.items.single()
        assertEquals("Synthetic title", item.metadata.title)
        assertEquals("Test artist", item.metadata.creator)
        assertEquals(1_000L, item.metadata.durationMs)
        assertTrue(docs.ranges.any { it.first == docs.dataEnd - 1 && it.second == 1 })
        assertTrue(docs.ranges.sumOf { it.second } < 2_000)
        assertTrue(docs.grants.isNotEmpty())
        assertEquals(docs.uri, repository.resolveForPlayback(item.id).readUri())
    }

    @Test fun pipeReferenceReturnsCopyRecoveryAndLeavesNoLibraryOrGrant() = runBlocking {
        docs.seekable = false
        val row = import(LocalImportMode.REFERENCE)
        assertEquals(LocalAudioError.READ_FAILED, row.error)
        assertEquals(LocalAudioRecoveryAction.COPY_REQUIRED, row.recoveryAction)
        assertTrue(repository.state.value.items.isEmpty())
        assertTrue(docs.grants.isEmpty())
    }

    @Test fun pipeCanBeCopiedAndPrivateCopySurvivesOriginalRemoval() = runBlocking {
        docs.seekable = false
        val row = import(LocalImportMode.COPY)
        assertNull(row.error)
        val id = row.audioId!!
        assertEquals("Synthetic title", repository.state.value.items.single().metadata.title)
        assertEquals("Test artist", repository.state.value.items.single().metadata.creator)
        docs.missing = true
        val uri = repository.resolveForPlayback(id).readUri()
        assertEquals("file", uri.scheme)
        assertArrayEquals(docs.bytes, File(uri.path!!).readBytes())
        assertTrue(docs.grants.isEmpty())
    }

    @Test fun sameLengthChangedHeaderDuringCopyRejectsAndCollectsNewCopy() = runBlocking {
        docs.copyBytes = docs.bytes.clone().apply { this[57] = (this[57].toInt() xor 1).toByte() }
        val row = import(LocalImportMode.COPY)
        assertEquals(LocalAudioError.SOURCE_CHANGED, row.error)
        assertTrue(repository.state.value.items.isEmpty())
        assertTrue(File(temporary.root, "audio").listFiles().orEmpty().none { it.extension == "audio" || it.extension == "tmp" })
    }

    @Test fun closedGateRejectsBothModesAndUnsupportedRateNeverClaimsPlayback() = runBlocking {
        docs.enabled = false
        assertEquals(LocalAudioError.UNSUPPORTED_AUDIO, import(LocalImportMode.REFERENCE).error)
        assertEquals(LocalAudioError.UNSUPPORTED_AUDIO, import(LocalImportMode.COPY).error)
        docs.enabled = true
        docs.bytes = source(22_579_200)
        assertEquals(LocalAudioError.UNSUPPORTED_AUDIO, import(LocalImportMode.COPY).error)
        assertTrue(repository.state.value.items.isEmpty())
    }

    @Test fun lostRandomAccessIsDetectedAtPlaybackAndSourceCheckWithRecovery() = runBlocking {
        val id = import(LocalImportMode.REFERENCE).audioId!!
        docs.seekable = false
        try { repository.resolveForPlayback(id); fail("Expected inaccessible reference") }
        catch (e: LocalAudioFailure) {
            assertEquals(LocalAudioError.READ_FAILED, e.error)
            assertEquals(LocalAudioRecoveryAction.COPY_REQUIRED, e.recoveryAction)
        }
        var rows: Result<List<LocalAudioSourceCheckResult>>? = null
        repository.checkSources(listOf(id), repository.state.value.revision) { rows = it }.join()
        assertEquals(LocalAudioRecoveryAction.COPY_REQUIRED, rows!!.getOrThrow().single().recoveryAction)
        assertEquals(LocalAudioAvailability.UNREADABLE, repository.state.value.items.single().availability)
    }

    @Test fun optionalBrokenTagsDoNotBlockImportButPermissionAndVersionChangesDo() = runBlocking {
        docs.bytes[docs.dataEnd.toInt()] = 0
        assertNull(import(LocalImportMode.COPY).error)
        assertNull(repository.state.value.items.single().metadata.title)
        docs.denied = true
        assertEquals(LocalAudioError.PERMISSION_REQUIRED, import(LocalImportMode.REFERENCE).error)
    }

    @Test fun cancellationDuringBoundedInspectionIsNotConvertedToTagFailure() {
        var checks = 0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            dsfImportMetadata(docs, docs.describe(docs.uri), LocalImportMode.COPY) {
                if (++checks >= 3) throw java.util.concurrent.CancellationException()
            }
        }
    }

    @Test fun sourceChangeDuringTagReadsPreventsImport() = runBlocking {
        docs.changeOnRange = true
        assertEquals(LocalAudioError.SOURCE_CHANGED, import(LocalImportMode.COPY).error)
        assertTrue(repository.state.value.items.isEmpty())
    }

    @Test fun relinkRejectsPipeWithoutReplacingExistingReference() = runBlocking {
        val id = import(LocalImportMode.REFERENCE).audioId!!
        val original = store.source(id)!!
        val originalVersion = AppLocalAudioRepository.sourceVersion(original)
        docs.seekable = false
        var result: Result<Unit>? = null
        repository.relink(id, docs.uri, 65) { result = it }.join()
        assertEquals(LocalAudioRecoveryAction.COPY_REQUIRED, (result!!.exceptionOrNull() as LocalAudioFailure).recoveryAction)
        val retained = store.source(id)!!
        assertEquals(original.source.mode, retained.source.mode)
        assertEquals(original.source.location, retained.source.location)
        assertEquals(original.source.identity, retained.source.identity)
        assertEquals(original.source.grantUri, retained.source.grantUri)
        assertEquals(originalVersion, AppLocalAudioRepository.sourceVersion(retained))
    }

    @Test fun unknownProviderLengthStillChecksPrivateCopyAgainstDeclaredDsfSize() = runBlocking {
        docs.unknownSize = true
        docs.copyBytes = docs.bytes.copyOf(docs.bytes.size - 1)
        val row = import(LocalImportMode.COPY)
        assertEquals(LocalAudioError.SOURCE_CHANGED, row.error)
        assertTrue(repository.state.value.items.isEmpty())
    }

    @Test fun copyReimportRetainsUserNameProgressAndBookmarkAndUsesOneOwnedFile() = runBlocking {
        val id = import(LocalImportMode.COPY).audioId!!
        repository.renameAudio(id, "My title").join()
        repository.saveProgress(folder.id, LocalAudioProgress(id, 500, false, 1)).join()
        repository.addBookmark(id, 250, "Marker").join()
        assertNull(import(LocalImportMode.COPY).error)
        assertEquals(id, repository.state.value.items.single().id)
        assertEquals("My title", repository.state.value.items.single().title)
        assertEquals(500L, repository.state.value.progress.single().positionMs)
        assertEquals(1, repository.state.value.bookmarks.size)
        assertEquals(1, File(temporary.root, "audio").listFiles().orEmpty().count { it.extension == "audio" })
    }

    private class Documents : LocalAudioDocuments {
        val uri = Uri.parse("content://dsf-tests/private")
        var bytes = source()
        var copyBytes: ByteArray? = null
        var enabled = true
        var seekable = true
        var missing = false
        var denied = false
        var changeOnRange = false
        var unknownSize = false
        var modified = 1L
        val dataEnd get() = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(20)
        val grants = mutableSetOf<String>()
        val ranges = mutableListOf<Pair<Long, Int>>()
        override val dsfPlaybackEnabled get() = enabled
        override fun describe(uri: Uri): LocalAudioDocument {
            if (denied) throw LocalAudioFailure(LocalAudioError.PERMISSION_REQUIRED)
            if (missing) throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
            return LocalAudioDocument(uri, "sample.DSF", "application/octet-stream", if (unknownSize) null else bytes.size.toLong(), modifiedAtMs = modified)
        }
        override fun metadata(document: LocalAudioDocument) = dsfImportMetadata(this, document, LocalImportMode.REFERENCE) {}
        override fun metadataForImport(document: LocalAudioDocument, mode: LocalImportMode, checkCancelled: () -> Unit) =
            dsfImportMetadata(this, document, mode, checkCancelled)
        override fun open(uri: Uri): ByteArrayInputStream {
            if (missing) throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
            if (denied) throw LocalAudioFailure(LocalAudioError.PERMISSION_REQUIRED)
            return ByteArrayInputStream(copyBytes ?: bytes)
        }
        override fun readRange(uri: Uri, offset: Long, count: Int, checkCancelled: () -> Unit): ByteArray? {
            checkCancelled(); ranges += offset to count
            if (denied) throw LocalAudioFailure(LocalAudioError.PERMISSION_REQUIRED)
            if (changeOnRange) modified++
            if (offset != 0L && !seekable) return null
            return bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + count))
        }
        override fun acquireRead(grant: Uri, flags: Int) { grants.add(grant.toString()) }
        override fun releaseUnused(retained: Set<String>) { grants.retainAll(retained) }
        override fun children(tree: Uri, recursive: Boolean, checkCancelled: () -> Unit) = listOf(describe(uri))
    }

    companion object {
        private fun source(rate: Int = 2_822_400): ByteArray {
            fun frame(id: String, value: String): ByteArray {
                val payload = byteArrayOf(0) + value.toByteArray(Charsets.ISO_8859_1)
                return id.toByteArray() + ByteBuffer.allocate(4).putInt(payload.size).array() + byteArrayOf(0, 0) + payload
            }
            val payload = frame("TIT2", "Synthetic title") + frame("TPE1", "Test artist")
            val tag = byteArrayOf(73, 68, 51, 3, 0, 0, 0, 0, (payload.size shr 7).toByte(), (payload.size and 127).toByte()) + payload
            val groups = (rate.toLong() + 32767) / 32768
            val end = 92 + groups * 8192
            return ByteBuffer.allocate((end + tag.size).toInt()).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("DSD ".toByteArray()); putLong(28); putLong(end + tag.size); putLong(end)
                put("fmt ".toByteArray()); putLong(52); putInt(1); putInt(0); putInt(2); putInt(2)
                putInt(rate); putInt(1); putLong(rate.toLong()); putInt(4096); putInt(0)
                put("data".toByteArray()); putLong(12 + groups * 8192)
                position(end.toInt()); put(tag)
            }.array()
        }
    }
}
