package tech.musxi.player.localaudio

import android.app.Application
import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
class LocalOrganizationRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val documents = Documents()
    private val readGate = AtomicReference<ReadGate?>()
    private val dbName = "org-${LocalAudioLimits.newId().take(8)}.db"
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private lateinit var store: SqliteLocalAudioStore
    private lateinit var repository: AppLocalAudioRepository
    private lateinit var folder: LocalAudioFolder

    @Before fun setup() = runBlocking {
        store = SqliteLocalAudioStore(context, dbName)
        val gatedStore = object : LocalAudioStore by store {
            override fun read(): LocalAudioLibraryState {
                val snapshot = store.read()
                readGate.getAndSet(null)?.awaitRelease()
                return snapshot
            }
        }
        repository = AppLocalAudioRepository(gatedStore, LocalAudioFiles(File(temporary.root, "audio")), documents, scope)
        var created: Result<LocalAudioFolder>? = null
        repository.createFolder("音声") { created = it }.join()
        folder = created!!.getOrThrow()
        repository.importFiles(folder.id, listOf(documents.add("10.opus", 10), documents.add("2.opus", 2), documents.add("1.opus", 1))).join()
    }
    @After fun close() { scope.cancel(); store.close(); context.deleteDatabase(dbName) }

    @Test fun staleReorderUsesStaleStateButInvalidMembershipUsesInvalidOrder() = runBlocking {
        val revision = repository.state.value.revision
        val id = repository.state.value.items.first().id
        repository.renameAudio(id, "修改后的名称").join()
        val members = repository.state.value.members
        val order = members.filter { it.folderId == folder.id }.sortedBy { it.position }.map { it.audioId }
        var stale: Result<Unit>? = null
        repository.reorder(folder.id, order.reversed(), revision) { stale = it }.join()
        assertEquals(LocalAudioError.STALE_STATE, (stale!!.exceptionOrNull() as LocalAudioFailure).error)
        assertEquals(members, repository.state.value.members)

        var invalid: Result<Unit>? = null
        repository.reorder(folder.id, order.dropLast(1), repository.state.value.revision) { invalid = it }.join()
        assertEquals(LocalAudioError.INVALID_ORDER, (invalid!!.exceptionOrNull() as LocalAudioFailure).error)
        assertEquals(members, repository.state.value.members)
    }

    @Test fun searchesPublishedMetadataWithoutReopeningSources() = runBlocking {
        val chosen = repository.state.value.items.first()
        repository.renameAudio(chosen.id, "睡前 雨声").join()
        repository.addBookmark(chosen.id, 1000, "喜欢").join()
        val opened = documents.opens
        documents.forbidReads = true
        val page = search(LocalAudioQuery(text = "雨声", bookmarkedOnly = true)).getOrThrow()
        assertEquals(listOf(chosen.id), page.items.map { it.id })
        assertEquals(opened, documents.opens)
        assertEquals(page, repository.searchState.value.page)
        assertFalse(repository.searchState.value.toString().contains("content://"))
    }

    @Test fun libraryEditsMarkSearchStaleAndRejectTheNextOldPage() = runBlocking {
        val page = search(LocalAudioQuery(limit = 1)).getOrThrow()
        repository.renameAudio(page.items.single().id, "新名称").join()
        assertEquals(LocalAudioError.STALE_STATE, repository.searchState.value.error)
        assertEquals(page, repository.searchState.value.page)
        val next = search(LocalAudioQuery(offset = page.nextOffset!!, limit = 1, expectedRevision = page.revision))
        assertEquals(LocalAudioError.STALE_STATE, (next.exceptionOrNull() as LocalAudioFailure).error)
        repository.clearSearch()
        assertNull(repository.searchState.value.page)
        assertFalse(repository.searchState.value.loading)
    }

    @Test fun newerSearchOwnsResultsWhenAnOlderReadFinishesLate() = runBlocking {
        val gate = ReadGate()
        readGate.set(gate)
        var oldCallback = false
        val old = repository.search(LocalAudioQuery(text = "1.opus")) { oldCallback = true }
        try {
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            var response: Result<LocalAudioSearchPage>? = null
            val newer = repository.search(LocalAudioQuery(text = "2.opus")) { response = it }
            gate.release.countDown()
            withTimeout(10_000) { old.join(); newer.join() }
            assertTrue(old.isCancelled)
            assertFalse(oldCallback)
            val page = response!!.getOrThrow()
            assertEquals(listOf("2.opus"), page.items.map { it.metadata.fileName })
            assertEquals(page, repository.searchState.value.page)
            assertFalse(repository.searchState.value.loading)
            assertNull(repository.searchState.value.error)
        } finally { gate.release.countDown() }
    }

    @Test fun clearSearchRejectsAnInFlightResultAndAllowsANewQuery() = runBlocking {
        val gate = ReadGate()
        readGate.set(gate)
        var callback = false
        val job = repository.search(LocalAudioQuery()) { callback = true }
        try {
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
            repository.clearSearch()
            gate.release.countDown()
            withTimeout(10_000) { job.join() }
            assertTrue(job.isCancelled)
            assertFalse(callback)
            assertEquals(LocalAudioSearchState(), repository.searchState.value)
            assertEquals(3, search(LocalAudioQuery()).getOrThrow().total)
        } finally { gate.release.countDown() }
    }

    @Test fun batchCopyAndMoveUseStableIdsAndKeepAudioUserData() = runBlocking {
        var created: Result<LocalAudioFolder>? = null
        repository.createFolder("常听") { created = it }.join()
        val other = created!!.getOrThrow()
        val selected = repository.state.value.items.take(2).map { it.id }
        repository.saveProgress(folder.id, LocalAudioProgress(selected.first(), 42_000, false, 1)).join()
        repository.addBookmark(selected.first(), 20_000, "片段").join()
        var response: Result<LocalAudioBatchResult>? = null
        repository.addToFolderBatch(other.id, selected, repository.state.value.revision) { response = it }.join()
        assertEquals(2, response!!.getOrThrow().addedMemberships)
        repository.moveToFolderBatch(folder.id, other.id, selected, repository.state.value.revision) { response = it }.join()
        assertEquals(0, response!!.getOrThrow().addedMemberships)
        assertEquals(2, response!!.getOrThrow().removedMemberships)
        val state = repository.state.value
        assertEquals(3, state.items.size)
        assertEquals(2, state.members.count { it.folderId == other.id })
        assertEquals(42_000L, state.progress.single().positionMs)
        assertEquals(1, state.bookmarks.size)
    }

    @Test fun staleBatchRejectsAllMembershipChanges() = runBlocking {
        val revision = repository.state.value.revision
        val ids = repository.state.value.items.map { it.id }
        repository.renameFolder(folder.id, "改名").join()
        val before = repository.state.value
        var response: Result<LocalAudioBatchResult>? = null
        repository.removeFromFolderBatch(folder.id, ids, revision) { response = it }.join()
        assertEquals(LocalAudioError.STALE_STATE, (response!!.exceptionOrNull() as LocalAudioFailure).error)
        assertEquals(before.members, repository.state.value.members)
        assertEquals(before.revision, repository.state.value.revision)
    }

    @Test fun sortFolderAlwaysIncludesMembersOutsideTheSearchPage() = runBlocking {
        search(LocalAudioQuery(text = "2.opus", limit = 1)).getOrThrow()
        var result: Result<LocalAudioSortResult>? = null
        repository.sortFolder(folder.id, LocalAudioSort.FILE_NAME, descending = true,
            expectedRevision = repository.state.value.revision) { result = it }.join()
        assertEquals(3, result!!.getOrThrow().members)
        val state = repository.state.value
        val names = state.items.associateBy { it.id }
        assertEquals(listOf("10.opus", "2.opus", "1.opus"), state.members.filter { it.folderId == folder.id }
            .sortedBy { it.position }.map { names.getValue(it.audioId).metadata.fileName })
        assertEquals(LocalAudioError.STALE_STATE, repository.searchState.value.error)
    }

    private suspend fun search(query: LocalAudioQuery): Result<LocalAudioSearchPage> {
        var result: Result<LocalAudioSearchPage>? = null
        repository.search(query) { result = it }.join()
        return result!!
    }

    private class ReadGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun awaitRelease() {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Test did not release the pending library read" }
        }
    }

    private class Documents : LocalAudioDocuments {
        val names = mutableMapOf<Uri, String>()
        val bytes = mutableMapOf<Uri, ByteArray>()
        var opens = 0
        var forbidReads = false
        fun add(name: String, value: Int): Uri {
            val uri = Uri.parse("content://org.test/document/$value")
            names[uri] = name; bytes[uri] = byteArrayOf(value.toByte())
            return uri
        }
        override fun describe(uri: Uri): LocalAudioDocument {
            check(!forbidReads)
            return LocalAudioDocument(uri, names.getValue(uri), "audio/opus", bytes.getValue(uri).size.toLong())
        }
        override fun metadata(document: LocalAudioDocument) = LocalAudioMetadata(document.name, sizeBytes = document.size)
        override fun open(uri: Uri): ByteArrayInputStream {
            check(!forbidReads); opens++
            return ByteArrayInputStream(bytes.getValue(uri))
        }
        override fun acquireRead(grant: Uri, flags: Int) {}
        override fun releaseUnused(retained: Set<String>) {}
        override fun children(tree: Uri, recursive: Boolean, checkCancelled: () -> Unit) = emptyList<LocalAudioDocument>()
    }
}
