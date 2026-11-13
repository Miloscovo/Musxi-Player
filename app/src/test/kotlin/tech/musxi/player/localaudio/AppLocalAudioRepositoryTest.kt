package tech.musxi.player.localaudio

import android.app.Application
import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.musxi.core.localaudio.*
import tech.musxi.playback.LocalAudioPlaybackQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppLocalAudioRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var store: SqliteLocalAudioStore
    private lateinit var repository: AppLocalAudioRepository
    private lateinit var files: LocalAudioFiles
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val docs = FakeDocuments()
    private lateinit var folder: LocalAudioFolder

    @Before fun setup() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        store = SqliteLocalAudioStore(context, "test-${LocalAudioLimits.newId()}.db")
        files = LocalAudioFiles(File(temporary.root, "audio"))
        repository = AppLocalAudioRepository(store, files, docs, work)
        var created: Result<LocalAudioFolder>? = null
        repository.createFolder("音声") { created = it }.join()
        folder = created!!.getOrThrow()
    }

    @After fun tearDown() { work.cancel(); store.close() }

    @Test fun copyIsDefaultAndSurvivesSourceDeletionWithoutHoldingPermission() = runBlocking {
        val uri = docs.add("雨夜.mp3", byteArrayOf(1, 2, 3))
        repository.importFiles(folder.id, listOf(uri)).join()
        val item = repository.state.value.items.single()
        assertEquals(LocalImportMode.COPY, item.mode)
        assertTrue(docs.acquired.isEmpty())
        docs.data.remove(uri)
        val resolved = repository.resolveForPlayback(item.id).readUri()
        assertEquals("file", resolved.scheme)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(resolved.path!!).readBytes())
        assertFalse(repository.state.value.toString().contains("content://"))
    }

    @Test fun referenceDoesNotCopyAndRepresentsRevokedPermissionWithoutErasingUserData() = runBlocking {
        val uri = docs.add("耳语.opus")
        repository.importFiles(folder.id, listOf(uri), LocalImportMode.REFERENCE, 65).join()
        val id = repository.state.value.items.single().id
        repository.renameAudio(id, "自定义标题").join()
        repository.addBookmark(id, 12_000, "片段").join()
        assertEquals(setOf(uri.toString()), store.retainedGrantUris())
        assertTrue(store.retainedCopyNames().isEmpty())
        docs.denied = true
        try { repository.resolveForPlayback(id); fail("Should require permission") }
        catch (error: LocalAudioFailure) { assertEquals(LocalAudioError.PERMISSION_REQUIRED, error.error) }
        val state = repository.state.value
        assertEquals("自定义标题", state.items.single().title)
        assertEquals(LocalAudioAvailability.PERMISSION_REQUIRED, state.items.single().availability)
        assertEquals(1, state.bookmarks.size)
    }

    @Test fun batchKeepsSuccessAndReportsFailedReadPerFile() = runBlocking {
        val good = docs.add("02.mp3")
        val broken = docs.add("01.mp3").also { docs.broken.add(it) }
        repository.importFiles(folder.id, listOf(good, broken)).join()
        assertEquals(1, repository.state.value.items.size)
        assertEquals(2, repository.imports.value.results.size)
        assertEquals(LocalAudioError.READ_FAILED, repository.imports.value.results.first().error)
        assertNotNull(repository.imports.value.results.last().audioId)
        assertFalse(repository.imports.value.running)
        assertEquals(1, store.retainedCopyNames().size)
    }

    @Test fun duplicateContentAcrossDocumentsUsesOneCopyAndKeepsNamesProgressAndBookmarks() = runBlocking {
        val first = docs.add("01.mp3")
        val second = docs.add("02.mp3")
        repository.importFiles(folder.id, listOf(first)).join()
        val id = repository.state.value.items.single().id
        repository.renameAudio(id, "保留名称").join()
        repository.saveProgress(folder.id, LocalAudioProgress(id, 25L * 60 * 60 * 1000, false, 20)).join()
        repository.addBookmark(id, 1234, "书签").join()
        repository.importFiles(folder.id, listOf(second)).join()
        assertEquals(1, repository.state.value.items.size)
        assertEquals("保留名称", repository.state.value.items.single().title)
        assertEquals(25L * 60 * 60 * 1000, repository.state.value.progress.single().positionMs)
        assertEquals(1, repository.state.value.bookmarks.size)
        assertEquals(1, File(temporary.root, "audio").listFiles()!!.count { it.extension == "audio" })
    }

    @Test fun folderRemovalRetainsAudioAndExplicitLibraryDeletionCollectsOnlyOwnedCopy() = runBlocking {
        val uri = docs.add("声.mp3")
        repository.importFiles(folder.id, listOf(uri)).join()
        val id = repository.state.value.items.single().id
        val copiedFile = File(repository.resolveForPlayback(id).readUri().path!!)
        repository.deleteFolder(folder.id).join()
        assertEquals(1, repository.state.value.items.size)
        assertTrue(copiedFile.exists())
        repository.deleteAudio(id).join()
        assertFalse(copiedFile.exists())
        assertTrue(docs.data.containsKey(uri))
        assertTrue(repository.state.value.items.isEmpty())
    }

    @Test fun relinkPreservesIdentityAndUserDataAndReleasesUnusedGrant() = runBlocking {
        val original = docs.add("旧.mp3")
        val replacement = docs.add("新.mp3", byteArrayOf(4, 5, 6))
        repository.importFiles(folder.id, listOf(original), LocalImportMode.REFERENCE, 65).join()
        val id = repository.state.value.items.single().id
        repository.renameAudio(id, "显示名").join()
        repository.saveProgress(folder.id, LocalAudioProgress(id, 42_000, false, 12)).join()
        repository.addBookmark(id, 4000, "喜欢").join()
        repository.relink(id, replacement, 65).join()
        assertEquals(id, repository.state.value.items.single().id)
        assertEquals("显示名", repository.state.value.items.single().title)
        assertEquals(42_000, repository.state.value.progress.single().positionMs)
        assertEquals(replacement, repository.resolveForPlayback(id).readUri())
        assertEquals(setOf(replacement.toString()), docs.acquired)
        assertEquals(1, repository.state.value.bookmarks.size)
    }

    @Test fun directoryImportsNaturallyAndRetainsTreeGrantInsteadOfIndividualPermissions() = runBlocking {
        val tree = Uri.parse("content://test/tree/root")
        val tenth = docs.add("10.opus")
        val second = docs.add("2.opus")
        val first = docs.add("1.opus")
        docs.directory = listOf(tenth, second, first)
        repository.importDirectory(folder.id, tree, LocalImportMode.REFERENCE, 65).join()
        val items = repository.state.value.items.associateBy { it.id }
        assertEquals(listOf("1.opus", "2.opus", "10.opus"), repository.state.value.members.sortedBy { it.position }.map { items.getValue(it.audioId).metadata.fileName })
        assertEquals(setOf(tree.toString()), store.retainedGrantUris())
        assertFalse(docs.recursive)
    }

    @Test fun cancellationRemovesPartialCopyAndKeepsEarlierCommittedItems() = runBlocking {
        val first = docs.add("1.mp3")
        val blocked = docs.add("2.mp3")
        docs.blocked = blocked
        val job = repository.importFiles(folder.id, listOf(first, blocked))
        assertTrue(docs.entered.await(10, TimeUnit.SECONDS))
        repository.cancelImport()
        docs.unblock.countDown()
        job.join()
        assertTrue(repository.imports.value.cancelled)
        assertFalse(repository.imports.value.running)
        assertEquals(1, repository.state.value.items.size)
        assertEquals(1, File(temporary.root, "audio").listFiles()!!.size)
    }

    @Test fun staleReorderFailsWithoutChangingMembers() = runBlocking {
        val uri = docs.add("1.mp3")
        repository.importFiles(folder.id, listOf(uri)).join()
        val old = repository.state.value.revision
        repository.renameAudio(repository.state.value.items.single().id, "改名").join()
        var response: Result<Unit>? = null
        repository.reorder(folder.id, repository.state.value.members.map { it.audioId }, old) { response = it }.join()
        assertEquals(LocalAudioError.STALE_STATE, (response!!.exceptionOrNull() as LocalAudioFailure).error)
        assertEquals(1, repository.state.value.members.size)
    }

    @Test fun referenceSizeChangeIsDetectedBeforePlaybackWithoutResettingProgress() = runBlocking {
        val uri = docs.add("1.mp3")
        repository.importFiles(folder.id, listOf(uri), LocalImportMode.REFERENCE, 65).join()
        val id = repository.state.value.items.single().id
        repository.saveProgress(folder.id, LocalAudioProgress(id, 5000, false, 1)).join()
        docs.data[uri] = byteArrayOf(1, 2, 3, 4)
        try { repository.resolveForPlayback(id); fail("Expected source changed") }
        catch (error: LocalAudioFailure) { assertEquals(LocalAudioError.SOURCE_CHANGED, error.error) }
        assertEquals(5000L, repository.state.value.progress.single().positionMs)
        assertEquals(LocalAudioAvailability.UNREADABLE, repository.state.value.items.single().availability)
    }

    @Test fun folderPreparationContinuesCursorAndEachTrackIndependentProgress() = runBlocking {
        val one = docs.add("1.mp3")
        val two = docs.add("2.mp3", byteArrayOf(4, 5))
        repository.importFiles(folder.id, listOf(two, one)).join()
        val ids = repository.state.value.members.sortedBy { it.position }.map { it.audioId }
        repository.saveProgress(folder.id, LocalAudioProgress(ids[0], 12_000, false, 1)).join()
        repository.saveProgress(folder.id, LocalAudioProgress(ids[1], 25L * 60 * 60 * 1000, false, 2)).join()
        val queue = repository.preparePlayback(folder.id)
        assertEquals(1, queue.startIndex)
        assertEquals(25L * 60 * 60 * 1000, queue.positionMs)
        assertEquals(listOf(12_000L, 25L * 60 * 60 * 1000), queue.entries.map { it.resumePositionMs })
        val beginning = repository.preparePlayback(folder.id, fromStart = true)
        assertEquals(0, beginning.startIndex)
        assertEquals(0, beginning.positionMs)
        assertTrue(beginning.entries.all { it.resumePositionMs == 0L })
    }

    @Test fun completedAudioAndDisabledContinuationStartFromZero() = runBlocking {
        repository.importFiles(folder.id, listOf(docs.add("1.mp3"))).join()
        val id = repository.state.value.items.single().id
        repository.saveProgress(folder.id, LocalAudioProgress(id, 90_000, true, 1)).join()
        assertEquals(0, repository.preparePlayback(folder.id).positionMs)
        repository.saveProgress(folder.id, LocalAudioProgress(id, 10_000, false, 2)).join()
        repository.setContinuePlayback(folder.id, false).join()
        val queue = repository.preparePlayback(folder.id)
        assertFalse(queue.continuePlayback)
        assertEquals(0, queue.positionMs)
        assertEquals(0, queue.entries.single().resumePositionMs)
    }

    @Test fun sourceVersionIsStableForRenameAndProgressButChangesWhenRelinked() = runBlocking {
        val uri = docs.add("1.mp3")
        repository.importFiles(folder.id, listOf(uri), LocalImportMode.REFERENCE, 65).join()
        val queue = repository.preparePlayback(folder.id)
        val id = queue.entries.single().audio.id
        val before = queue.entries.single().sourceVersion
        repository.renameAudio(id, "自定义").join()
        repository.saveProgress(folder.id, LocalAudioProgress(id, 1000, false, 1)).join()
        assertEquals(before, repository.preparePlayback(folder.id).entries.single().sourceVersion)
        repository.relink(id, docs.add("换文件.mp3", byteArrayOf(7, 7)), 65).join()
        assertNotEquals(before, repository.preparePlayback(folder.id).entries.single().sourceVersion)
        try { repository.resolveForPlayback(id, before); fail("Old queue must be invalid") }
        catch (error: LocalAudioFailure) { assertEquals(LocalAudioError.SOURCE_CHANGED, error.error) }
    }

    @Test fun singleAudioCanResumeOutsideAFolderAndMissingTargetsFail() = runBlocking {
        repository.importFiles(folder.id, listOf(docs.add("1.mp3"))).join()
        val id = repository.state.value.items.single().id
        repository.saveProgress(null, LocalAudioProgress(id, 8000, false, 1)).join()
        assertNull(repository.preparePlayback(null, id).folderId)
        assertEquals(8000, repository.preparePlayback(null, id).positionMs)
        try { repository.preparePlayback(folder.id, LocalAudioLimits.newId()); fail("Missing member") }
        catch (error: LocalAudioFailure) { assertEquals(LocalAudioError.NOT_FOUND, error.error) }
    }

    @Test fun checkpointAfterFolderDeletionStillSavesAudioAndCompletedCanBeReplayed() = runBlocking {
        repository.importFiles(folder.id, listOf(docs.add("1.mp3"))).join()
        val id = repository.state.value.items.single().id
        repository.deleteFolder(folder.id).join()
        val adapter = AppLocalAudioPlaybackAdapter(repository, { _, _, cb -> cb(Result.success(Unit)) }, work)
        suspend fun saved(position: Long, ended: Boolean) {
            val done = CompletableDeferred<Result<Unit>>()
            adapter.checkpoint(folder.id, id, position, ended) { done.complete(it) }
            withTimeout(5000) { done.await() }.getOrThrow()
        }
        saved(0, true)
        saved(5000, false)
        assertEquals(5000L, store.read().progress.single().positionMs)
        assertFalse(store.read().progress.single().completed)
        assertTrue(store.read().cursors.isEmpty())
    }

    @Test fun playFlushesCurrentServiceProgressBeforePreparingFolderResume() = runBlocking {
        repository.importFiles(folder.id, listOf(docs.add("1.mp3"))).join()
        val id = repository.state.value.items.single().id
        var dispatched: LocalAudioPlaybackQueue? = null
        val adapter = AppLocalAudioPlaybackAdapter(repository, { q, _, cb -> dispatched = q; cb(Result.success(Unit)) }, work,
            beforePrepare = { cb -> repository.saveProgress(folder.id, LocalAudioProgress(id, 23_000, false, 1), cb) })
        adapter.play(folder.id).join()
        assertEquals(23_000, dispatched!!.positionMs)
    }

    @Test fun newerPlayRequestSupersedesLateOlderPreparation() = runBlocking {
        repository.importFiles(folder.id, listOf(docs.add("1.mp3"))).join()
        val pending = mutableListOf<(Result<Unit>) -> Unit>()
        var dispatches = 0
        val adapter = AppLocalAudioPlaybackAdapter(repository, { _, _, cb -> dispatches++; cb(Result.success(Unit)) }, work,
            beforePrepare = { pending.add(it) })
        val older = adapter.play(folder.id)
        val newer = adapter.play(folder.id, fromStart = true)
        pending[1](Result.success(Unit))
        newer.join()
        pending[0](Result.success(Unit))
        older.join()
        assertEquals(1, dispatches)
    }

    @Test fun readingAndCheckpointingExistingAudioDoNotWaitForBlockedLargeImport() = runBlocking {
        val first = docs.add("1.mp3")
        repository.importFiles(folder.id, listOf(first)).join()
        val id = repository.state.value.items.single().id
        val blocked = docs.add("2.mp3", byteArrayOf(4, 5, 6))
        docs.blocked = blocked
        val copying = repository.importFiles(folder.id, listOf(blocked))
        assertTrue(docs.entered.await(10, TimeUnit.SECONDS))
        try {
            withTimeout(5000) {
                repository.saveProgress(folder.id, LocalAudioProgress(id, 12_000, false, 1)).join()
                assertEquals("file", repository.resolveForPlayback(id).readUri().scheme)
            }
            assertEquals(12_000L, store.read().progress.single().positionMs)
        } finally { docs.unblock.countDown(); copying.join() }
    }

    @Test fun restoresEditedQueueMetadataAndLongProgress() = runBlocking {
        repository.importFiles(folder.id, listOf(docs.add("1.mp3"), docs.add("2.mp3", byteArrayOf(4, 5)))).join()
        val prepared = repository.preparePlayback(folder.id)
        val saved = saved(prepared).copy(index = 1, repeatMode = "ALL", shuffleEnabled = true, shuffleOrder = listOf(1, 0))
        val id = prepared.entries[1].audio.id
        repository.renameAudio(id, "新的显示名").join()
        repository.saveProgress(folder.id, LocalAudioProgress(id, 25L * 60 * 60 * 1000, false, 1)).join()
        docs.denied = true
        val restored = repository.prepareSavedPlayback(saved)
        assertEquals("新的显示名", restored.entries[1].audio.title)
        assertEquals(25L * 60 * 60 * 1000, restored.positionMs)
        assertEquals("ALL", restored.repeatMode)
        assertTrue(restored.shuffleEnabled)
        assertEquals(listOf(1, 0), restored.shuffleOrder)
    }

    @Test fun prunesDeletedSlotsAndFolderWithoutRebuilding() = runBlocking {
        repository.importFiles(folder.id, listOf(docs.add("1.mp3"), docs.add("2.mp3", byteArrayOf(4, 5)),
            docs.add("3.mp3", byteArrayOf(6, 7)))).join()
        val prepared = repository.preparePlayback(folder.id)
        val saved = saved(prepared).copy(index = 1, shuffleOrder = listOf(2, 0, 1))
        repository.deleteAudio(prepared.entries[1].audio.id).join()
        repository.deleteFolder(folder.id).join()
        val restored = repository.prepareSavedPlayback(saved)
        assertNull(restored.folderId)
        assertEquals(listOf(prepared.entries[0].audio.id, prepared.entries[2].audio.id), restored.entries.map { it.audio.id })
        assertEquals(1, restored.startIndex)
        assertEquals(listOf(1, 0), restored.shuffleOrder)
    }

    @Test fun relinkedSavedRecordingRejectsOldQueueInsteadOfApplyingItsPosition() = runBlocking {
        repository.importFiles(folder.id, listOf(docs.add("旧.mp3")), LocalImportMode.REFERENCE, 65).join()
        val saved = saved(repository.preparePlayback(folder.id))
        repository.relink(saved.entries.single().audioId, docs.add("新.mp3", byteArrayOf(4, 5)), 65).join()
        try { repository.prepareSavedPlayback(saved); fail("Changed recording") }
        catch (error: LocalAudioFailure) { assertEquals(LocalAudioError.SOURCE_CHANGED, error.error) }
    }

    @Test fun idleFromStartQueueRestoresZeroWithoutOverwritingPreviouslyCompletedProgress() = runBlocking {
        repository.importFiles(folder.id, listOf(docs.add("1.mp3"))).join()
        val id = repository.state.value.items.single().id
        repository.saveProgress(folder.id, LocalAudioProgress(id, 0, true, 1)).join()
        val prepared = repository.preparePlayback(folder.id, fromStart = true)
        val journal = MemoryJournal()
        val adapter = adapter(journal)
        checkpointQueue(adapter, saved(prepared).copy(useStoredProgress = false), null)
        val resumed = restore(adapter).getOrThrow()!!
        assertEquals(0, resumed.positionMs)
        assertTrue(store.read().progress.single().completed)
        assertFalse(resumed.useStoredProgress)
    }

    @Test fun positionSavesAvoidQueueRewriteAndRestoreFresh() = runBlocking {
        repository.importFiles(folder.id, listOf(docs.add("1.mp3"))).join()
        val prepared = repository.preparePlayback(folder.id)
        val saved = saved(prepared)
        val journal = MemoryJournal()
        val adapter = adapter(journal)
        for (position in listOf(1000L, 2000L, 3000L)) {
            checkpointQueue(adapter, saved.copy(positionMs = position, savedAtEpochMs = position),
                LocalAudioProgress(saved.entries.single().audioId, position, false, position))
        }
        assertEquals(1, journal.writes)
        assertEquals(3000L, restore(adapter(journal)).getOrThrow()!!.positionMs)
    }

    @Test fun deactivatedLocalQueueDoesNotAutoRestoreButExplicitRestoreInstallsPaused() = runBlocking {
        repository.importFiles(folder.id, listOf(docs.add("1.mp3"))).join()
        val journal = MemoryJournal()
        var played: Boolean? = null
        val adapter = AppLocalAudioPlaybackAdapter(repository, { _, ready, cb -> played = ready; cb(Result.success(Unit)) },
            work, journalStore = journal)
        checkpointQueue(adapter, saved(repository.preparePlayback(folder.id)), null)
        adapter.deactivateQueue()
        checkpointQueueBarrier(adapter)
        assertNull(restore(adapter).getOrThrow())
        var result: Result<Unit>? = null
        adapter.restoreLastQueue { result = it }.join()
        result!!.getOrThrow()
        assertEquals(false, played)
        assertTrue(adapter.restoreState.value.available)
    }

    @Test fun newerIntentRejectsLateStartupRestoreAndClearingForgetsSavedQueue() = runBlocking {
        repository.importFiles(folder.id, listOf(docs.add("1.mp3"))).join()
        val memory = MemoryJournal(LocalAudioPlaybackJournal(saved(repository.preparePlayback(folder.id)), true))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = object : LocalAudioPlaybackJournalStore {
            override fun read(): LocalAudioPlaybackJournal {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); return memory.value
            }
            override fun write(value: LocalAudioPlaybackJournal) { memory.write(value) }
        }
        val adapter = adapter(blocking)
        val restored = CompletableDeferred<Result<LocalAudioPlaybackQueue?>>()
        adapter.restoreQueue { restored.complete(it) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        adapter.cancelPendingPlay()
        release.countDown()
        assertNull(withTimeout(5000) { restored.await() }.getOrThrow())
        adapter.deactivateQueue(clear = true)
        checkpointQueueBarrier(adapter)
        assertNull(memory.value.queue)
        assertFalse(adapter.restoreState.value.restoring)
    }

    @Test fun failedJournalWriteReportsStorageFailureAndPreservesPreviousJournal() = runBlocking {
        repository.importFiles(folder.id, listOf(docs.add("1.mp3"))).join()
        val failing = object : LocalAudioPlaybackJournalStore {
            override fun read() = LocalAudioPlaybackJournal()
            override fun write(value: LocalAudioPlaybackJournal) { throw java.io.IOException("private path must not escape") }
        }
        val adapter = adapter(failing)
        val done = CompletableDeferred<Result<Unit>>()
        adapter.checkpointQueue(saved(repository.preparePlayback(folder.id)), null, true) { done.complete(it) }
        val response = withTimeout(5000) { done.await() }
        assertEquals(LocalAudioError.STORAGE_FAILED, (response.exceptionOrNull() as LocalAudioFailure).error)
        assertFalse(response.exceptionOrNull()!!.message!!.contains("private path"))
        assertEquals(LocalAudioError.STORAGE_FAILED, adapter.restoreState.value.error)
    }

    @Test fun platformLoadInvalidatesAlreadyPreparedLocalQueue() = runBlocking {
        repository.importFiles(folder.id, listOf(docs.add("1.mp3"))).join()
        var staged: LocalAudioPlaybackQueue? = null
        val adapter = AppLocalAudioPlaybackAdapter(repository, { queue, _, cb -> staged = queue; cb(Result.success(Unit)) }, work)
        adapter.play(folder.id, playWhenReady = false).join()
        assertTrue(adapter.isQueueCurrent(staged!!))
        var loading: ((Result<tech.musxi.core.library.PlaybackQueue>) -> Unit)? = null
        val platform = tech.musxi.player.NeteasePlaybackUseCase(
            loadQueue = { _, _, callback -> loading = callback }, submitQueue = { _, callback -> callback(Result.success(Unit)) },
            stopPlayer = {}, onNewIntent = adapter::cancelPendingPlay)
        val key = tech.musxi.core.library.MediaKey(tech.musxi.core.account.Platform.NETEASE, "1")
        platform.playPlaylist(key, key)
        assertNotNull(loading)
        assertTrue(platform.state.value.loading)
        assertFalse(adapter.isQueueCurrent(staged!!))
    }

    @Test fun returnedStartupQueueStillChecksTheLatestIntent() = runBlocking {
        repository.importFiles(folder.id, listOf(docs.add("1.mp3"))).join()
        val memory = MemoryJournal(LocalAudioPlaybackJournal(saved(repository.preparePlayback(folder.id)), true))
        val adapter = adapter(memory)
        val restored = restore(adapter).getOrThrow()!!
        assertTrue(adapter.isQueueCurrent(restored))
        adapter.cancelPendingPlay()
        assertFalse(adapter.isQueueCurrent(restored))
    }

    private fun saved(queue: LocalAudioPlaybackQueue) = SavedLocalAudioQueue(queue.folderId,
        queue.entries.map { SavedLocalAudioEntry(it.audio.id, it.sourceVersion, it.resumePositionMs) }, queue.startIndex,
        queue.positionMs, queue.repeatMode, queue.shuffleEnabled, queue.shuffleOrder ?: queue.entries.indices.toList(),
        queue.continuePlayback, 1, useStoredProgress = true)
    private fun adapter(journal: LocalAudioPlaybackJournalStore) = AppLocalAudioPlaybackAdapter(repository,
        { _, _, cb -> cb(Result.success(Unit)) }, work, journalStore = journal)
    private suspend fun checkpointQueue(adapter: AppLocalAudioPlaybackAdapter, saved: SavedLocalAudioQueue, progress: LocalAudioProgress?) {
        val done = CompletableDeferred<Result<Unit>>()
        adapter.checkpointQueue(saved, progress, true) { done.complete(it) }
        withTimeout(5000) { done.await() }.getOrThrow()
    }
    private suspend fun checkpointQueueBarrier(adapter: AppLocalAudioPlaybackAdapter) {
        val done = CompletableDeferred<Result<Unit>>()
        val id = repository.state.value.items.first().id
        adapter.checkpoint(null, id, 0, false) { done.complete(it) }
        withTimeout(5000) { done.await() }.getOrThrow()
    }
    private suspend fun restore(adapter: AppLocalAudioPlaybackAdapter): Result<LocalAudioPlaybackQueue?> {
        val done = CompletableDeferred<Result<LocalAudioPlaybackQueue?>>()
        adapter.restoreQueue { done.complete(it) }
        return withTimeout(5000) { done.await() }
    }
    private class MemoryJournal(var value: LocalAudioPlaybackJournal = LocalAudioPlaybackJournal()) : LocalAudioPlaybackJournalStore {
        var writes = 0
        override fun read() = value
        override fun write(value: LocalAudioPlaybackJournal) { this.value = value; writes++ }
    }

    private class FakeDocuments : LocalAudioDocuments {
        val data = mutableMapOf<Uri, ByteArray>()
        val names = mutableMapOf<Uri, String>()
        val acquired = mutableSetOf<String>()
        val broken = mutableSetOf<Uri>()
        var denied = false
        var directory = emptyList<Uri>()
        var recursive = false
        var blocked: Uri? = null
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        fun add(name: String, bytes: ByteArray = byteArrayOf(7, 8, 9)): Uri {
            val uri = Uri.parse("content://test/document/${LocalAudioLimits.newId()}")
            names[uri] = name; data[uri] = bytes
            return uri
        }
        override fun describe(uri: Uri) = LocalAudioDocument(uri, names.getValue(uri), "audio/mpeg", data[uri]?.size?.toLong())
        override fun metadata(document: LocalAudioDocument) = LocalAudioMetadata(document.name, sizeBytes = document.size)
        override fun open(uri: Uri): ByteArrayInputStream {
            if (denied) throw LocalAudioFailure(LocalAudioError.PERMISSION_REQUIRED)
            if (uri in broken) throw LocalAudioFailure(LocalAudioError.READ_FAILED)
            if (uri == blocked) { entered.countDown(); check(unblock.await(10, TimeUnit.SECONDS)) }
            return ByteArrayInputStream(data[uri] ?: throw IOException("Missing test input"))
        }
        override fun acquireRead(grant: Uri, flags: Int) { acquired.add(grant.toString()) }
        override fun releaseUnused(retained: Set<String>) { acquired.retainAll(retained) }
        override fun children(tree: Uri, recursive: Boolean, checkCancelled: () -> Unit): List<LocalAudioDocument> {
            this.recursive = recursive
            return directory.map(::describe).sortedWith { a, b -> LocalAudioNames.compare(a.name, b.name) }
        }
    }
}
