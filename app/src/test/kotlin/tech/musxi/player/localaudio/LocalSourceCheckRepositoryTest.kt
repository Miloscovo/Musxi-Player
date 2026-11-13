package tech.musxi.player.localaudio

import android.app.Application
import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
class LocalSourceCheckRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val documents = Documents()
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val dbName = "probe-${LocalAudioLimits.newId().take(8)}.db"
    private lateinit var store: SqliteLocalAudioStore
    private lateinit var repository: AppLocalAudioRepository
    private lateinit var files: LocalAudioFiles
    private lateinit var folder: LocalAudioFolder

    @Before fun setup() = runBlocking {
        store = SqliteLocalAudioStore(context, dbName)
        files = LocalAudioFiles(File(temporary.root, "audio"))
        repository = AppLocalAudioRepository(store, files, documents, scope)
        var result: Result<LocalAudioFolder>? = null
        repository.createFolder("音声") { result = it }.join()
        folder = result!!.getOrThrow()
    }
    @After fun close() { documents.gate.get()?.release?.countDown(); scope.cancel(); store.close(); context.deleteDatabase(dbName) }

    @Test fun referenceCheckReadsOneByteClosesItAndDoesNotChangeMetadataOrRevision() = runBlocking {
        val (uri, id) = imported()
        repository.renameAudio(id, "睡前").join()
        repository.saveProgress(folder.id, LocalAudioProgress(id, 50_000, false, 1)).join()
        repository.addBookmark(id, 10_000, "片段").join()
        val before = repository.state.value
        val opens = documents.opens.get()
        val closes = documents.closes.get()
        val reads = documents.reads.get()
        val metadataReads = documents.metadataReads
        val grants = documents.acquisitions
        val result = check(listOf(id)).getOrThrow().single()
        assertEquals(LocalAudioSourceCheckResult(id, LocalAudioAvailability.AVAILABLE, applied = true), result)
        assertEquals(before, repository.state.value)
        assertEquals(opens + 1, documents.opens.get())
        assertEquals(closes + 1, documents.closes.get())
        assertEquals(reads + 1, documents.reads.get())
        assertEquals(metadataReads, documents.metadataReads)
        assertEquals(grants, documents.acquisitions)
        assertTrue(repository.sourceChecks.value.completed)
        assertFalse(repository.sourceChecks.value.toString().contains(uri.toString()))
    }

    @Test fun missingReferenceKeepsUserDataAndRetryRecoversAvailability() = runBlocking {
        val (uri, id) = imported()
        repository.renameAudio(id, "留着名称").join()
        repository.saveProgress(folder.id, LocalAudioProgress(id, 12_000, false, 1)).join()
        repository.addBookmark(id, 1000, "保留").join()
        val before = repository.state.value
        documents.describeErrors[uri] = LocalAudioError.NOT_FOUND
        val failed = check(listOf(id)).getOrThrow().single()
        assertEquals(LocalAudioAvailability.MISSING, failed.availability)
        assertEquals(LocalAudioError.NOT_FOUND, failed.error)
        assertTrue(failed.applied)
        val missing = repository.state.value
        assertEquals(before.members, missing.members)
        assertEquals(before.progress, missing.progress)
        assertEquals(before.bookmarks, missing.bookmarks)
        assertEquals("留着名称", missing.items.single().title)
        check(listOf(id)).getOrThrow()
        assertEquals(missing.revision, repository.state.value.revision)
        documents.describeErrors.remove(uri)
        assertEquals(LocalAudioAvailability.AVAILABLE, check(listOf(id)).getOrThrow().single().availability)
        assertEquals(missing.revision + 1, repository.state.value.revision)
    }

    @Test fun permissionReadAndEmptyStreamFailuresAreDistinctAndPerFile() = runBlocking {
        val (denied, first) = imported("1.mp3")
        val (broken, second) = imported("2.mp3")
        val (empty, third) = imported("3.mp3")
        documents.openErrors[denied] = LocalAudioError.PERMISSION_REQUIRED
        documents.openErrors[broken] = LocalAudioError.READ_FAILED
        documents.data[empty] = byteArrayOf()
        // Metadata can be unavailable on a provider, so the stream must still be probed.
        documents.unknownSize.add(empty)
        val results = check(listOf(first, second, third)).getOrThrow()
        assertEquals(listOf(LocalAudioError.PERMISSION_REQUIRED, LocalAudioError.READ_FAILED, LocalAudioError.READ_FAILED), results.map { it.error })
        assertEquals(listOf(LocalAudioAvailability.PERMISSION_REQUIRED, LocalAudioAvailability.UNREADABLE, LocalAudioAvailability.UNREADABLE), results.map { it.availability })
        assertTrue(results.all { it.applied })
        assertTrue(repository.sourceChecks.value.completed)
        assertNull(repository.sourceChecks.value.error)
    }

    @Test fun changedSizeOrModificationHintNeverReplacesTheRecording() = runBlocking {
        val (uri, id) = imported()
        val original = store.source(id)!!
        documents.modified[uri] = 200L
        val opens = documents.opens.get()
        assertEquals(LocalAudioError.SOURCE_CHANGED, check(listOf(id)).getOrThrow().single().error)
        assertEquals(opens, documents.opens.get())
        assertEquals(original.item.metadata, store.source(id)!!.item.metadata)
        documents.modified[uri] = 100L
        documents.data[uri] = byteArrayOf(9, 8, 7, 6)
        assertEquals(LocalAudioError.SOURCE_CHANGED, check(listOf(id)).getOrThrow().single().error)
        assertEquals(original.source.location, store.source(id)!!.source.location)
    }

    @Test fun copyCheckIgnoresDeletedSourceAndReportsChangedOrMissingManagedFile() = runBlocking {
        val (uri, id) = imported(mode = LocalImportMode.COPY)
        documents.data.remove(uri)
        val opens = documents.opens.get()
        assertEquals(LocalAudioAvailability.AVAILABLE, check(listOf(id)).getOrThrow().single().availability)
        assertEquals(opens, documents.opens.get())
        val file = files.resolve(store.source(id)!!.source.location)
        file.writeBytes(byteArrayOf(1))
        assertEquals(LocalAudioError.SOURCE_CHANGED, check(listOf(id)).getOrThrow().single().error)
        assertTrue(file.delete())
        assertEquals(LocalAudioAvailability.MISSING, check(listOf(id)).getOrThrow().single().availability)
        assertEquals(1, repository.state.value.items.size)
    }

    @Test fun invalidOrStaleSelectionOpensNothingAndChangesNothing() = runBlocking {
        val (_, id) = imported()
        val before = repository.state.value
        val opens = documents.opens.get()
        suspend fun rejected(ids: List<String>, revision: Long, reason: LocalAudioError) {
            assertEquals(reason, (check(ids, revision).exceptionOrNull() as LocalAudioFailure).error)
            assertEquals(before, repository.state.value)
            assertEquals(opens, documents.opens.get())
        }
        rejected(emptyList(), before.revision, LocalAudioError.LIMIT_REACHED)
        rejected(List(501) { LocalAudioLimits.newId() }, before.revision, LocalAudioError.LIMIT_REACHED)
        rejected(listOf(id, id), before.revision, LocalAudioError.INVALID_ORDER)
        rejected(listOf("bad"), before.revision, LocalAudioError.INVALID_ORDER)
        rejected(listOf(id, LocalAudioLimits.newId()), before.revision, LocalAudioError.NOT_FOUND)
        rejected(listOf(id), before.revision - 1, LocalAudioError.STALE_STATE)
    }

    @Test fun cancellingCheckKeepsCompletedResultsAndDoesNotCommitPendingFailure() = runBlocking {
        val (firstUri, first) = imported("1.mp3")
        val (secondUri, second) = imported("2.mp3")
        documents.openErrors[firstUri] = LocalAudioError.PERMISSION_REQUIRED
        val gate = Gate(LocalAudioError.READ_FAILED)
        documents.gate.set(gate)
        documents.gatedUri = secondUri
        var callback = false
        val job = repository.checkSources(listOf(first, second), repository.state.value.revision) { callback = true }
        try {
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            repository.cancelSourceCheck()
            gate.release.countDown()
            withTimeout(10_000) { job.join() }
            assertTrue(job.isCancelled)
            assertFalse(callback)
            assertTrue(repository.sourceChecks.value.cancelled)
            assertEquals(1, repository.sourceChecks.value.processed)
            assertEquals(LocalAudioAvailability.PERMISSION_REQUIRED, store.source(first)!!.item.availability)
            assertEquals(LocalAudioAvailability.AVAILABLE, store.source(second)!!.item.availability)
        } finally { gate.release.countDown() }
    }

    @Test fun blockedProbeAllowsEditsAndRejectsDuplicateRunWithoutChangingItsState() = runBlocking {
        val (uri, id) = imported()
        val gate = block(uri)
        val job = repository.checkSources(listOf(id), repository.state.value.revision)
        try {
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            assertEquals(LocalAudioError.LIMIT_REACHED, (check(listOf(id)).exceptionOrNull() as LocalAudioFailure).error)
            assertTrue(repository.sourceChecks.value.running)
            withTimeout(5000) {
                repository.renameAudio(id, "检查时改名").join()
                repository.saveProgress(folder.id, LocalAudioProgress(id, 30_000, false, 2)).join()
            }
            gate.release.countDown()
            withTimeout(10_000) { job.join() }
            assertTrue(repository.sourceChecks.value.results.single().applied)
            assertEquals("检查时改名", repository.state.value.items.single().title)
            assertEquals(30_000L, repository.state.value.progress.single().positionMs)
        } finally { gate.release.countDown() }
    }

    @Test fun deletedAudioCannotBeRecreatedByLateProbe() = runBlocking {
        val (uri, id) = imported()
        val gate = block(uri)
        val job = repository.checkSources(listOf(id), repository.state.value.revision)
        try {
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            repository.deleteAudio(id).join()
            gate.release.countDown()
            withTimeout(10_000) { job.join() }
            assertEquals(LocalAudioSourceCheckResult(id, error = LocalAudioError.NOT_FOUND), repository.sourceChecks.value.results.single())
            assertTrue(repository.state.value.items.isEmpty())
        } finally { gate.release.countDown() }
    }

    @Test fun relinkEvenToTheSameUriRejectsLateFailure() = runBlocking {
        val (uri, id) = imported()
        val gate = block(uri, LocalAudioError.PERMISSION_REQUIRED)
        val job = repository.checkSources(listOf(id), repository.state.value.revision)
        try {
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            repository.relink(id, uri, 65).join()
            gate.release.countDown()
            withTimeout(10_000) { job.join() }
            val result = repository.sourceChecks.value.results.single()
            assertFalse(result.applied)
            assertEquals(LocalAudioError.SOURCE_CHANGED, result.error)
            assertEquals(LocalAudioAvailability.AVAILABLE, store.source(id)!!.item.availability)
        } finally { gate.release.countDown() }
    }

    @Test fun newerPlaybackProbePreventsOldCheckFailureFromOverwritingAvailability() = runBlocking {
        val (uri, id) = imported()
        val gate = block(uri, LocalAudioError.READ_FAILED)
        val job = repository.checkSources(listOf(id), repository.state.value.revision)
        try {
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            repository.resolveForPlayback(id)
            gate.release.countDown()
            withTimeout(10_000) { job.join() }
            assertEquals(LocalAudioError.STALE_STATE, repository.sourceChecks.value.results.single().error)
            assertEquals(LocalAudioAvailability.AVAILABLE, store.source(id)!!.item.availability)
        } finally { gate.release.countDown() }
    }

    @Test fun manualCheckDoesNotRejectAValidPlaybackAlreadyReadingTheSameSource() = runBlocking {
        val (uri, id) = imported()
        val gate = block(uri)
        val playback = async(Dispatchers.Default) { repository.resolveForPlayback(id) }
        try {
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            assertTrue(check(listOf(id)).getOrThrow().single().applied)
            gate.release.countDown()
            assertEquals(uri, withTimeout(10_000) { playback.await() }.readUri())
        } finally { gate.release.countDown() }
    }

    @Test fun temporaryProviderFailureIsUnreadableAndNeverDeletesOrMarksMissing() = runBlocking {
        val (uri, id) = imported()
        documents.describeErrors[uri] = LocalAudioError.READ_FAILED
        assertEquals(LocalAudioAvailability.UNREADABLE, check(listOf(id)).getOrThrow().single().availability)
        assertEquals(listOf(id), repository.state.value.items.map { it.id })
        assertEquals(1, repository.state.value.members.size)
    }

    @Test fun directoryReauthorizationRejectsOldPermissionFailure() = runBlocking {
        val tree = Uri.parse("content://source.test/tree/root")
        var linked: Result<LocalAudioDirectoryLink>? = null
        repository.linkDirectory(folder.id, tree, "下载目录", LocalImportMode.REFERENCE, 65) { linked = it }.join()
        val link = linked!!.getOrThrow()
        val uri = documents.add("目录音频.mp3")
        val id = store.importAudio(folder.id, documents.metadata(documents.describe(uri)),
            LocalAudioSource(LocalImportMode.REFERENCE, uri.toString(), AndroidLocalAudioDocuments.identity(uri), tree.toString()),
            LocalAudioDirectoryImport(link.id, AndroidLocalAudioDocuments.identity(uri), 3, 100)).id
        repository.renameAudio(id, "保留").join()
        val gate = block(uri, LocalAudioError.PERMISSION_REQUIRED)
        val job = repository.checkSources(listOf(id), repository.state.value.revision)
        try {
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            repository.reauthorizeDirectory(link.id, tree, 65).join()
            gate.release.countDown()
            withTimeout(10_000) { job.join() }
            assertEquals(LocalAudioError.STALE_STATE, repository.sourceChecks.value.results.single().error)
            assertEquals(LocalAudioAvailability.AVAILABLE, store.source(id)!!.item.availability)
        } finally { gate.release.countDown() }
    }

    @Test fun cancelledPlaybackProbeDoesNotMarkTheSourceUnreadable() = runBlocking {
        val (uri, id) = imported()
        val before = repository.state.value
        val gate = block(uri, LocalAudioError.READ_FAILED)
        val playback = async(Dispatchers.Default) { repository.resolveForPlayback(id) }
        try {
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            playback.cancel()
            gate.release.countDown()
            withTimeout(10_000) { playback.join() }
            assertTrue(playback.isCancelled)
            assertEquals(before, repository.state.value)
        } finally { gate.release.countDown() }
    }

    @Test fun reimportingTheSameSourceDoesNotRejectValidInFlightPlayback() = runBlocking {
        val (uri, id) = imported()
        val gate = block(uri)
        val playback = async(Dispatchers.Default) { repository.resolveForPlayback(id) }
        try {
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            var imported: Result<List<LocalAudioImportResult>>? = null
            repository.importFiles(folder.id, listOf(uri), LocalImportMode.REFERENCE, 65) { imported = it }.join()
            assertEquals(id, imported!!.getOrThrow().single().audioId)
            gate.release.countDown()
            assertEquals(uri, withTimeout(10_000) { playback.await() }.readUri())
        } finally { gate.release.countDown() }
    }

    @Test fun unlinkAndLinkTheSameDirectoryRejectsOldPermissionFailure() = runBlocking {
        val tree = Uri.parse("content://source.test/tree/root")
        var linked: Result<LocalAudioDirectoryLink>? = null
        repository.linkDirectory(folder.id, tree, "来源", LocalImportMode.REFERENCE, 65) { linked = it }.join()
        val link = linked!!.getOrThrow()
        val uri = documents.add("目录音频.mp3")
        val id = store.importAudio(folder.id, documents.metadata(documents.describe(uri)),
            LocalAudioSource(LocalImportMode.REFERENCE, uri.toString(), AndroidLocalAudioDocuments.identity(uri), tree.toString()),
            LocalAudioDirectoryImport(link.id, AndroidLocalAudioDocuments.identity(uri), 3, 100)).id
        repository.renameAudio(id, "保留").join()
        val gate = block(uri, LocalAudioError.PERMISSION_REQUIRED)
        val job = repository.checkSources(listOf(id), repository.state.value.revision)
        try {
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            repository.unlinkDirectory(link.id).join()
            repository.linkDirectory(folder.id, tree, "重新授权", LocalImportMode.REFERENCE, 65).join()
            gate.release.countDown()
            withTimeout(10_000) { job.join() }
            assertEquals(LocalAudioError.STALE_STATE, repository.sourceChecks.value.results.single().error)
            assertEquals(LocalAudioAvailability.AVAILABLE, store.source(id)!!.item.availability)
        } finally { gate.release.countDown() }
    }

    private suspend fun imported(name: String = "雨声.mp3", mode: LocalImportMode = LocalImportMode.REFERENCE): Pair<Uri, String> {
        val uri = documents.add(name)
        var result: Result<List<LocalAudioImportResult>>? = null
        repository.importFiles(folder.id, listOf(uri), mode, 65) { result = it }.join()
        return uri to result!!.getOrThrow().single().audioId!!
    }
    private suspend fun check(ids: List<String>, revision: Long = repository.state.value.revision): Result<List<LocalAudioSourceCheckResult>> {
        var result: Result<List<LocalAudioSourceCheckResult>>? = null
        repository.checkSources(ids, revision) { result = it }.join()
        return result!!
    }
    private fun block(uri: Uri, error: LocalAudioError? = null) = Gate(error).also {
        documents.gatedUri = uri; documents.gate.set(it)
    }
    private class Gate(val error: LocalAudioError? = null) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }
    private class Documents : LocalAudioDocuments {
        val data = ConcurrentHashMap<Uri, ByteArray>()
        val names = ConcurrentHashMap<Uri, String>()
        val modified = ConcurrentHashMap<Uri, Long>()
        val describeErrors = ConcurrentHashMap<Uri, LocalAudioError>()
        val openErrors = ConcurrentHashMap<Uri, LocalAudioError>()
        val unknownSize = ConcurrentHashMap.newKeySet<Uri>()
        val gate = AtomicReference<Gate?>()
        var gatedUri: Uri? = null
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val reads = AtomicInteger()
        var metadataReads = 0
        var acquisitions = 0
        fun add(name: String): Uri = Uri.parse("content://source.test/document/${LocalAudioLimits.newId()}").also {
            names[it] = name; data[it] = byteArrayOf(7, 8, 9); modified[it] = 100L
        }
        override fun describe(uri: Uri): LocalAudioDocument {
            describeErrors[uri]?.let { throw LocalAudioFailure(it) }
            val bytes = data[uri] ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
            return LocalAudioDocument(uri, names.getValue(uri), "audio/mpeg",
                bytes.size.toLong().takeUnless { uri in unknownSize }, modifiedAtMs = modified[uri])
        }
        override fun metadata(document: LocalAudioDocument): LocalAudioMetadata {
            metadataReads++
            return LocalAudioMetadata(document.name, sizeBytes = document.size, modifiedAtMs = document.modifiedAtMs)
        }
        override fun open(uri: Uri): ByteArrayInputStream {
            opens.incrementAndGet()
            val bytes = data[uri] ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
            val failure = openErrors[uri]
            if (uri == gatedUri) gate.getAndSet(null)?.let {
                it.entered.countDown()
                check(it.release.await(10, TimeUnit.SECONDS))
                it.error?.let { reason -> throw LocalAudioFailure(reason) }
            }
            failure?.let { throw LocalAudioFailure(it) }
            return object : ByteArrayInputStream(bytes) {
                override fun read(): Int { reads.incrementAndGet(); return super.read() }
                override fun close() { closes.incrementAndGet(); super.close() }
            }
        }
        override fun acquireRead(grant: Uri, flags: Int) { acquisitions++ }
        override fun releaseUnused(retained: Set<String>) {}
        override fun children(tree: Uri, recursive: Boolean, checkCancelled: () -> Unit) = emptyList<LocalAudioDocument>()
    }
}
