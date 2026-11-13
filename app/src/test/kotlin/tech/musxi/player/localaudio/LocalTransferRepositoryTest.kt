package tech.musxi.player.localaudio

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.musxi.core.localaudio.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalTransferRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val docs = Documents()
    private val transfers = Transfers()
    private lateinit var store: SqliteLocalAudioStore
    private lateinit var repository: AppLocalAudioRepository
    private lateinit var files: LocalAudioFiles
    private lateinit var folder: LocalAudioFolder
    private lateinit var audioId: String
    private val dbName = "xfer-${LocalAudioLimits.newId().take(8)}.db"
    private val target = Uri.parse("content://transfer.test/document/new")
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val flags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    @Before fun setup() = runBlocking {
        store = SqliteLocalAudioStore(context, dbName)
        files = LocalAudioFiles(File(temporary.root, "audio"))
        repository = AppLocalAudioRepository(store, files, docs, work, transfers)
        var response: Result<LocalAudioFolder>? = null
        repository.createFolder("音声") { response = it }.join()
        folder = response!!.getOrThrow()
        repository.importFiles(folder.id, listOf(docs.uri)).join()
        audioId = repository.state.value.items.single().id
    }
    @After fun close() { transfers.release.countDown(); work.cancel(); store.close(); context.deleteDatabase(dbName) }

    @Test fun exportsOriginalBytesAfterSourceDeletionAndKeepsLibraryUntouched() = runBlocking {
        docs.bytes = null
        val before = store.read()
        var response: Result<LocalAudioExportResult>? = null
        repository.exportAudio(audioId, target, flags) { response = it }.join()
        assertEquals(3L, response!!.getOrThrow().bytes)
        assertArrayEquals(byteArrayOf(1, 2, 3), transfers.output.toByteArray())
        assertEquals(before, store.read())
        assertTrue(repository.transfers.value.completed)
        assertFalse(repository.transfers.value.toString().contains("content://"))
    }

    @Test fun damagedCopyFailsHashAndCollectsPartialDestination() = runBlocking {
        val stored = store.source(audioId)!!
        files.resolve(stored.source.location).writeBytes(byteArrayOf(4, 5, 6))
        var response: Result<LocalAudioExportResult>? = null
        repository.exportAudio(audioId, target, flags) { response = it }.join()
        assertEquals(LocalAudioError.SOURCE_CHANGED, (response!!.exceptionOrNull() as LocalAudioFailure).error)
        assertEquals(1, transfers.discards)
        assertFalse(repository.transfers.value.completed)
        assertEquals(1, repository.state.value.items.size)
    }

    @Test fun invalidDestinationIsRejectedBeforeOpeningOrDeletingIt() = runBlocking {
        transfers.reject = true
        var response: Result<LocalAudioExportResult>? = null
        repository.exportAudio(audioId, target, flags) { response = it }.join()
        assertEquals(LocalAudioError.INVALID_TARGET, (response!!.exceptionOrNull() as LocalAudioFailure).error)
        assertEquals(0, transfers.opens)
        assertEquals(0, transfers.discards)
    }

    @Test fun referencedAudioExportsWithoutCreatingAnotherLibraryCopy() = runBlocking {
        repository.importFiles(folder.id, listOf(docs.uri), LocalImportMode.REFERENCE, 65).join()
        val reference = repository.state.value.items.single { it.mode == LocalImportMode.REFERENCE }
        val filesBefore = File(temporary.root, "audio").listFiles()!!.size
        var response: Result<LocalAudioExportResult>? = null
        repository.exportAudio(reference.id, target, flags) { response = it }.join()
        assertEquals(3L, response!!.getOrThrow().bytes)
        assertTrue(docs.uri.toString() in transfers.protected)
        assertEquals(filesBefore, File(temporary.root, "audio").listFiles()!!.size)
        assertEquals(2, repository.state.value.items.size)
    }

    @Test fun changedReferenceIsRejectedBeforeOpeningDestination() = runBlocking {
        repository.importFiles(folder.id, listOf(docs.uri), LocalImportMode.REFERENCE, 65).join()
        val reference = repository.state.value.items.single { it.mode == LocalImportMode.REFERENCE }
        docs.bytes = byteArrayOf(9)
        var response: Result<LocalAudioExportResult>? = null
        repository.exportAudio(reference.id, target, flags) { response = it }.join()
        assertEquals(LocalAudioError.SOURCE_CHANGED, (response!!.exceptionOrNull() as LocalAudioFailure).error)
        assertEquals(0, transfers.opens)
        assertEquals(0, transfers.discards)
    }

    @Test fun targetWriteFailureReportsCleanupFailureSeparately() = runBlocking {
        transfers.failWrite = true; transfers.cleanupWorks = false
        var response: Result<LocalAudioExportResult>? = null
        repository.exportAudio(audioId, target, flags) { response = it }.join()
        assertEquals(LocalAudioError.STORAGE_FAILED, (response!!.exceptionOrNull() as LocalAudioFailure).error)
        assertTrue(repository.transfers.value.cleanupFailed)
        assertEquals(1, transfers.discards)
    }

    @Test fun backupPreviewUsesValidatedBytesAndStaleRestoreDoesNotOverwriteEdits() = runBlocking {
        repository.renameAudio(audioId, "备份名称").join()
        repository.saveProgress(folder.id, LocalAudioProgress(audioId, 42_000, false, 1)).join()
        var saved: Result<LocalAudioBackupResult>? = null
        repository.backupOrganization(target, flags) { saved = it }.join()
        saved!!.getOrThrow()
        transfers.input = transfers.output.toByteArray()
        assertFalse(String(transfers.input, Charsets.ISO_8859_1).contains("content://"))
        var preview: Result<LocalAudioRestorePreview>? = null
        repository.previewOrganization(target) { preview = it }.join()
        val staged = preview!!.getOrThrow()
        assertEquals(1, staged.matchedItems)
        transfers.input = byteArrayOf(0) // Confirmation uses staged, validated bytes, not this document.
        repository.renameAudio(audioId, "新名称").join()
        var response: Result<LocalAudioRestoreResult>? = null
        repository.restoreOrganization(staged.backupId, staged.expectedRevision) { response = it }.join()
        assertEquals(LocalAudioError.STALE_STATE, (response!!.exceptionOrNull() as LocalAudioFailure).error)
        repository.restoreOrganization(staged.backupId, repository.state.value.revision) { response = it }.join()
        assertEquals(LocalAudioError.STALE_STATE, (response!!.exceptionOrNull() as LocalAudioFailure).error)
        transfers.input = transfers.output.toByteArray()
        repository.previewOrganization(target) { preview = it }.join()
        val currentPreview = preview!!.getOrThrow()
        transfers.input = byteArrayOf(0)
        repository.restoreOrganization(currentPreview.backupId, currentPreview.expectedRevision) { response = it }.join()
        assertEquals(1, response!!.getOrThrow().matchedItems)
        assertEquals("新名称", repository.state.value.items.single().title)
        assertEquals(42_000L, repository.state.value.progress.single().positionMs)
    }

    @Test fun corruptPreviewClearsPreviouslyStagedBackup() = runBlocking {
        repository.backupOrganization(target, flags).join()
        transfers.input = transfers.output.toByteArray()
        var preview: Result<LocalAudioRestorePreview>? = null
        repository.previewOrganization(target) { preview = it }.join()
        val first = preview!!.getOrThrow()
        transfers.input = byteArrayOf(0, 1)
        repository.previewOrganization(target) { preview = it }.join()
        assertEquals(LocalAudioError.INVALID_BACKUP, (preview!!.exceptionOrNull() as LocalAudioFailure).error)
        var response: Result<LocalAudioRestoreResult>? = null
        repository.restoreOrganization(first.backupId, repository.state.value.revision) { response = it }.join()
        assertEquals(LocalAudioError.INVALID_BACKUP, (response!!.exceptionOrNull() as LocalAudioFailure).error)
    }

    @Test fun cancellationDiscardsPartialTargetAndRejectsConcurrentImport() = runBlocking {
        transfers.block = true
        val job = repository.exportAudio(audioId, target, flags)
        try {
            assertTrue(transfers.entered.await(10, TimeUnit.SECONDS))
            var response: Result<List<LocalAudioImportResult>>? = null
            repository.importFiles(folder.id, listOf(docs.uri)) { response = it }.join()
            assertEquals(LocalAudioError.LIMIT_REACHED, (response!!.exceptionOrNull() as LocalAudioFailure).error)
            repository.cancelTransfer()
            var rejected: Result<LocalAudioExportResult>? = null
            repository.exportAudio(audioId, target, flags) { rejected = it }.join()
            assertEquals(LocalAudioError.LIMIT_REACHED, (rejected!!.exceptionOrNull() as LocalAudioFailure).error)
        } finally { transfers.release.countDown() }
        withTimeout(5000) { job.join() }
        assertTrue(repository.transfers.value.cancelled)
        assertFalse(repository.transfers.value.running)
        assertEquals(1, transfers.discards)
        assertEquals(1, repository.state.value.items.size)
    }

    @Test fun deleteWhileExportingPinsOwnedCopyThenCollectsIt() = runBlocking {
        transfers.block = true
        val copy = files.resolve(store.source(audioId)!!.source.location)
        val job = repository.exportAudio(audioId, target, flags)
        try {
            assertTrue(transfers.entered.await(10, TimeUnit.SECONDS))
            repository.deleteAudio(audioId).join()
            assertTrue(copy.exists())
        } finally { transfers.release.countDown() }
        job.join()
        assertFalse(copy.exists())
        assertEquals(1, transfers.discards)
    }

    private class Documents : LocalAudioDocuments {
        val uri = Uri.parse("content://source.test/document/one")
        var bytes: ByteArray? = byteArrayOf(1, 2, 3)
        override fun describe(uri: Uri) = LocalAudioDocument(uri, "1.opus", "audio/opus", bytes?.size?.toLong())
        override fun metadata(document: LocalAudioDocument) = LocalAudioMetadata(document.name, sizeBytes = document.size)
        override fun open(uri: Uri) = ByteArrayInputStream(bytes ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND))
        override fun acquireRead(grant: Uri, flags: Int) {}
        override fun releaseUnused(retained: Set<String>) {}
        override fun children(tree: Uri, recursive: Boolean, checkCancelled: () -> Unit) = emptyList<LocalAudioDocument>()
    }

    private class Transfers : LocalAudioTransferDocuments {
        var input = byteArrayOf()
        val output = ByteArrayOutputStream()
        var reject = false
        var failWrite = false
        var cleanupWorks = true
        var discards = 0
        var opens = 0
        var block = false
        var protected = emptySet<String>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun validateDestination(uri: Uri, flags: Int, protected: Set<String>) {
            this.protected = protected
            if (reject) throw LocalAudioFailure(LocalAudioError.INVALID_TARGET)
        }
        override fun openInput(uri: Uri) = ByteArrayInputStream(input)
        override fun openOutput(uri: Uri): OutputStream {
            opens++
            return object : OutputStream() {
                override fun write(value: Int) {
                    if (block) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                    if (failWrite) throw IOException("test output failed")
                    output.write(value)
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (block) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                    if (failWrite) throw IOException("test output failed")
                    output.write(bytes, offset, length)
                }
            }
        }
        override fun discardIncomplete(uri: Uri): Boolean { discards++; return cleanupWorks }
    }
}
