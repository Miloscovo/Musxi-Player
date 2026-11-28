package tech.musxi.player.localaudio

import android.app.Application
import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
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
class AppCueImportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val documents = Documents()
    private lateinit var store: SqliteLocalAudioStore
    private lateinit var files: LocalAudioFiles
    private lateinit var repository: AppLocalAudioRepository
    private lateinit var folder: LocalAudioFolder
    private val cue = Uri.parse("content://cue/sheet")
    private val audio = Uri.parse("content://cue/audio")

    @Before fun setup() = runBlocking {
        store = SqliteLocalAudioStore(RuntimeEnvironment.getApplication() as Context, "cue-${LocalAudioLimits.newId()}.db")
        files = LocalAudioFiles(File(temporary.root, "audio"))
        repository = AppLocalAudioRepository(store, files, documents, scope, transferDocuments = object : LocalAudioTransferDocuments {
            override fun validateDestination(uri: Uri, flags: Int, protected: Set<String>) = error("Export must stop before target access")
            override fun openInput(uri: Uri): InputStream = error("No input")
            override fun openOutput(uri: Uri): java.io.OutputStream = error("No output")
            override fun discardIncomplete(uri: Uri) = false
        })
        var created: LocalAudioFolder? = null
        repository.createFolder("分轨") { created = it.getOrThrow() }.join()
        folder = created!!
        documents.data[cue] = sheet()
        documents.data[audio] = flac()
    }

    @After fun close() = runBlocking {
        documents.release.countDown()
        scope.coroutineContext[Job]!!.cancelAndJoin()
        store.close()
    }

    @Test fun copyPublishesOnlySlicesAndSharesOneFileUntilLastDeletion() = runBlocking {
        preview()
        val imported = commit().getOrThrow()
        assertEquals(2, imported.size)
        assertEquals(listOf(60_000L, 60_000L), imported.map { it.metadata.durationMs })
        assertEquals(listOf(0L, 60_000_000L), imported.map { it.segment!!.startUs })
        assertEquals(1, store.retainedCopyNames().size)
        assertEquals(1, File(temporary.root, "audio").listFiles()!!.count { it.extension == "audio" })
        assertEquals(0, documents.acquireCalls)
        assertFalse(repository.state.value.toString().contains("content://"))
        val file = File(repository.resolveForPlayback(imported[0].id).readUri().path!!)
        documents.data.remove(audio)
        assertTrue(repository.resolveForPlayback(imported[1].id).readUri().scheme == "file")
        repository.deleteAudio(imported[0].id).join()
        assertTrue(file.exists())
        repository.deleteAudio(imported[1].id).join()
        assertFalse(file.exists())
    }

    @Test fun reimportKeepsIdsProgressBookmarksAndUserPreferences() = runBlocking {
        preview()
        val first = commit().getOrThrow()
        val id = first[0].id
        repository.renameAudio(id, "自己的名字").join()
        repository.setPlaybackSpeed(id, 1.5f).join()
        repository.saveProgress(folder.id, LocalAudioProgress(id, 12_000, false, 100)).join()
        repository.addBookmark(id, 13_000, "这里").join()
        val second = commit().getOrThrow()
        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals("自己的名字", second[0].title)
        assertEquals(1.5f, second[0].playbackSpeed)
        assertEquals(12_000L, repository.state.value.progress.single().positionMs)
        assertEquals(1, repository.state.value.bookmarks.size)
        assertEquals(1, store.retainedCopyNames().size)
    }

    @Test fun cueChangesAfterPreviewAreRejectedBeforeCopy() = runBlocking {
        preview()
        documents.data[cue] = sheet("Changed")
        assertEquals(LocalAudioError.SOURCE_CHANGED, error(commit()))
        assertTrue(store.read().items.isEmpty())
        assertTrue(store.retainedCopyNames().isEmpty())
    }

    @Test fun changedLayoutNewPreviewCreatesDistinctIdsWithoutReusingProgress() = runBlocking {
        preview()
        val old = commit().getOrThrow()
        repository.saveProgress(folder.id, LocalAudioProgress(old[0].id, 1_000, false, 1)).join()
        documents.data[cue] = sheet("Changed")
        preview()
        val fresh = commit().getOrThrow()
        assertTrue(fresh.none { next -> old.any { it.id == next.id } })
        assertTrue(store.read().progress.none { p -> fresh.any { it.id == p.audioId } })
        assertEquals(1, store.retainedCopyNames().size)
    }

    @Test fun staleLibraryOrClearedPreviewCannotCommit() = runBlocking {
        preview()
        val revision = repository.state.value.revision
        repository.renameFolder(folder.id, "Changed").join()
        assertEquals(LocalAudioError.STALE_STATE, error(commit(revision = revision)))
        repository.cuePreview.clear()
        assertEquals(LocalAudioError.STALE_STATE, error(commit()))
        assertTrue(store.read().items.isEmpty())
    }

    @Test fun referenceRetainsOnlyAudioGrantAndReportsRevocation() = runBlocking {
        preview()
        val items = commit(mode = LocalImportMode.REFERENCE).getOrThrow()
        assertEquals(setOf(audio.toString()), store.retainedGrantUris())
        assertEquals(listOf(audio), documents.acquired)
        assertTrue(store.retainedCopyNames().isEmpty())
        documents.denied = audio
        try { repository.resolveForPlayback(items[0].id); fail("Expected permission error") }
        catch (e: LocalAudioFailure) { assertEquals(LocalAudioError.PERMISSION_REQUIRED, e.error) }
        assertEquals(2, store.read().items.size)
    }

    @Test fun failedReferenceGrantDoesNotPublishAnySlice() = runBlocking {
        preview()
        documents.rejectGrant = true
        assertEquals(LocalAudioError.PERMISSION_REQUIRED, error(commit(mode = LocalImportMode.REFERENCE)))
        assertTrue(store.read().items.isEmpty())
    }

    @Test fun cancellationDuringCopyLeavesNoPartialLibraryOrOrphanCopy() = runBlocking {
        preview()
        documents.blockCopy = true
        var callback = false
        val job = repository.importCue(folder.id, cue, listOf(audio), repository.cuePreview.state.value.revision,
            repository.state.value.revision) { callback = true }
        assertTrue(documents.entered.await(10, TimeUnit.SECONDS))
        repository.cancelImport()
        documents.release.countDown()
        job.join()
        assertTrue(repository.imports.value.cancelled)
        assertFalse(callback)
        assertTrue(store.read().items.isEmpty())
        assertTrue(File(temporary.root, "audio").listFiles().orEmpty().none { it.extension == "audio" || it.extension == "tmp" })
    }

    @Test fun savedQueueAndBookmarksUseTrackRelativePositions() = runBlocking {
        preview()
        val items = commit().getOrThrow()
        repository.saveProgress(folder.id, LocalAudioProgress(items[1].id, 9_000, false, 1)).join()
        val queue = repository.preparePlayback(folder.id, items[1].id)
        assertEquals(9_000L, queue.positionMs)
        assertEquals(60_000_000L, queue.entries[1].audio.segment!!.startUs)
        val saved = SavedLocalAudioQueue(folder.id, queue.entries.map { SavedLocalAudioEntry(it.audio.id, it.sourceVersion, it.resumePositionMs) },
            1, 9_000, "OFF", false, listOf(0, 1), true, 100, true)
        val restored = repository.prepareSavedPlayback(saved)
        assertEquals(9_000L, restored.positionMs)
        assertEquals(items[1].segment, restored.entries[1].audio.segment)
        var bookmark: Result<LocalAudioBookmark>? = null
        repository.addBookmark(items[1].id, 60_001, "越界") { bookmark = it }.join()
        assertTrue(bookmark!!.isFailure)
    }

    @Test fun wholeFileRelinkAndExportAreBlockedForSlices() = runBlocking {
        preview()
        val id = commit(mode = LocalImportMode.REFERENCE).getOrThrow()[0].id
        var relink: Result<Unit>? = null
        repository.relink(id, audio, 65) { relink = it }.join()
        assertEquals(LocalAudioError.INVALID_TARGET, error(relink!!))
        var exported: Result<LocalAudioExportResult>? = null
        repository.exportAudio(id, Uri.parse("content://cue/output"), 3) { exported = it }.join()
        assertEquals(LocalAudioError.INVALID_TARGET, error(exported!!))
    }

    @Test fun localFormatQueryReadsSharedSourceWithoutMutatingSliceLibrary() = runBlocking {
        preview()
        val items = commit().getOrThrow()
        val before = store.read()
        var value: Result<LocalAudioFormatInfo>? = null
        repository.inspectFormat(items[1].id) { value = it }.join()
        val facts = value!!.getOrThrow()
        assertEquals(items[1].id, facts.audioId)
        assertEquals("flac", facts.codec)
        assertEquals(44100, facts.sampleRateHz)
        assertEquals(16, facts.bitsPerSample)
        assertEquals(2, facts.channelCount)
        assertEquals(before, store.read())
        assertFalse(facts.toString().contains("content://"))
    }
    private suspend fun preview() { repository.cuePreview.preview(cue, listOf(audio)).join(); assertTrue(repository.cuePreview.state.value.preview!!.canProceed) }
    private suspend fun commit(mode: LocalImportMode = LocalImportMode.COPY, revision: Long = repository.state.value.revision): Result<List<LocalAudioItem>> {
        var response: Result<List<LocalAudioItem>>? = null
        repository.importCue(folder.id, cue, listOf(audio), repository.cuePreview.state.value.revision, revision,
            mode = mode, grantedFlags = 65) { response = it }.join()
        return response!!
    }
    private fun error(result: Result<*>): LocalAudioError = (result.exceptionOrNull() as LocalAudioFailure).error
    private fun sheet(title: String = "Album") = "TITLE \"$title\"\nFILE \"album.flac\" WAVE\nTRACK 01 AUDIO\nTITLE \"One\"\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nTITLE \"Two\"\nINDEX 01 01:00:00".toByteArray()
    private fun flac() = ByteArray(100).apply {
        byteArrayOf(102, 76, 97, 67).copyInto(this); this[4] = 0x80.toByte(); this[7] = 34
        val packed = (44100L shl 44) or (1L shl 41) or (15L shl 36) or (120L * 44100)
        for (i in 0..7) this[18+i] = (packed ushr (56-8*i)).toByte()
    }
    private class Documents : LocalAudioDocuments {
        val data = java.util.concurrent.ConcurrentHashMap<Uri, ByteArray>()
        val acquired = mutableListOf<Uri>()
        var acquireCalls = 0
        @Volatile var denied: Uri? = null
        @Volatile var rejectGrant = false
        @Volatile var blockCopy = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun describe(uri: Uri): LocalAudioDocument {
            if (uri == denied) throw SecurityException("private")
            return LocalAudioDocument(uri, if (uri.path == "/audio") "album.flac" else "album.cue", null, data[uri]?.size?.toLong(), modifiedAtMs = 1)
        }
        override fun open(uri: Uri): InputStream {
            if (uri == denied) throw SecurityException("private")
            return object : ByteArrayInputStream(data[uri] ?: throw java.io.FileNotFoundException()) {
                override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                    if (blockCopy && uri.path == "/audio" && len > 42) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                    return super.read(bytes, off, len)
                }
            }
        }
        override fun metadata(document: LocalAudioDocument): LocalAudioMetadata = error("CUE uses bounded FLAC header")
        override fun acquireRead(grant: Uri, flags: Int) { acquireCalls++; if (rejectGrant) throw LocalAudioFailure(LocalAudioError.PERMISSION_REQUIRED); acquired += grant }
        override fun releaseUnused(retained: Set<String>) { acquired.removeAll { it.toString() !in retained } }
        override fun children(tree: Uri, recursive: Boolean, checkCancelled: () -> Unit): List<LocalAudioDocument> = error("No sibling discovery")
    }
}
