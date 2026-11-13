package tech.musxi.player.localaudio

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
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
class LocalDirectoryRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val docs = Documents()
    private lateinit var store: SqliteLocalAudioStore
    private lateinit var repository: AppLocalAudioRepository
    private lateinit var files: LocalAudioFiles
    private lateinit var folder: LocalAudioFolder
    private val tree = Uri.parse("content://directory-test/tree/root")
    private val dbName = "dir-${LocalAudioLimits.newId().take(8)}.db"
    private val context get() = RuntimeEnvironment.getApplication() as Context

    @Before fun setup() = runBlocking {
        store = SqliteLocalAudioStore(context, dbName)
        files = LocalAudioFiles(File(temporary.root, "audio"))
        repository = AppLocalAudioRepository(store, files, docs, work)
        var response: Result<LocalAudioFolder>? = null
        repository.createFolder("音声") { response = it }.join()
        folder = response!!.getOrThrow()
    }
    @After fun close() { work.cancel(); store.close(); context.deleteDatabase(dbName) }

    @Test fun remembersCopyGrantAndReusesCopiesAfterRestart() = runBlocking {
        docs.add("1.opus", byteArrayOf(1, 2))
        val link = link()
        refresh(link.id).getOrThrow()
        val audio = repository.state.value.items.single()
        val version = repository.preparePlayback(folder.id).entries.single().sourceVersion
        assertEquals(1, docs.opens)
        assertEquals(setOf(tree.toString()), docs.grants)
        store.close()
        store = SqliteLocalAudioStore(context, dbName)
        repository = AppLocalAudioRepository(store, files, docs, work)
        refresh(link.id).getOrThrow()
        assertEquals(1, docs.opens)
        assertEquals(audio.id, repository.state.value.items.single().id)
        assertEquals(version, repository.preparePlayback(folder.id).entries.single().sourceVersion)
        assertFalse(repository.state.value.toString().contains("content://"))
    }

    @Test fun refreshAppendsAndPreservesUserOrganization() = runBlocking {
        docs.add("1.opus", byteArrayOf(1, 2)); docs.add("2.opus", byteArrayOf(3, 4))
        val link = link()
        refresh(link.id).getOrThrow()
        val ids = repository.state.value.members.sortedBy { it.position }.map { it.audioId }
        repository.reorder(folder.id, ids.reversed(), repository.state.value.revision).join()
        repository.renameAudio(ids.first(), "保留名称").join()
        repository.saveProgress(folder.id, LocalAudioProgress(ids.first(), 1234, false, 1)).join()
        repository.addBookmark(ids.first(), 1200, "片段").join()
        docs.add("0.opus", byteArrayOf(5, 6))
        refresh(link.id).getOrThrow()
        val state = repository.state.value
        assertEquals(ids.reversed(), state.members.sortedBy { it.position }.take(2).map { it.audioId })
        assertEquals("保留名称", state.items.first { it.id == ids.first() }.title)
        assertEquals(1234L, state.progress.single().positionMs)
        assertEquals(1, state.bookmarks.size)
        assertEquals(3, state.members.size)
    }

    @Test fun failedListingKeepsLastSuccessfulScan() = runBlocking {
        docs.add("1.mp3", byteArrayOf(1))
        val link = link(LocalImportMode.REFERENCE)
        refresh(link.id).getOrThrow()
        val old = repository.state.value.directories.single()
        docs.listError = LocalAudioError.READ_FAILED
        assertFalse(refresh(link.id).isSuccess)
        assertEquals(old.lastRefreshedAtMs, repository.state.value.directories.single().lastRefreshedAtMs)
        assertEquals(0, repository.state.value.directories.single().missingCount)
        assertEquals(LocalAudioAvailability.AVAILABLE, repository.state.value.items.single().availability)
        assertEquals(LocalAudioError.READ_FAILED, repository.state.value.directories.single().error)
    }

    @Test fun missingCopyKeepsPlayingAndUnlinkKeepsLibrary() = runBlocking {
        val uri = docs.add("1.mp3", byteArrayOf(1, 2))
        val link = link()
        refresh(link.id).getOrThrow()
        val id = repository.state.value.items.single().id
        docs.listing.clear(); docs.data.remove(uri)
        assertEquals(1, refresh(link.id).getOrThrow().missingCount)
        assertEquals("file", repository.resolveForPlayback(id).readUri().scheme)
        repository.unlinkDirectory(link.id).join()
        assertTrue(repository.state.value.directories.isEmpty())
        assertEquals(1, repository.state.value.items.size)
        assertEquals(1, repository.state.value.members.size)
        assertTrue(docs.grants.isEmpty())
    }

    @Test fun manualRemovalIsNotUndoneByRefresh() = runBlocking {
        docs.add("1.mp3", byteArrayOf(1, 2))
        val link = link()
        refresh(link.id).getOrThrow()
        repository.removeFromFolder(folder.id, repository.state.value.items.single().id).join()
        refresh(link.id).getOrThrow()
        assertTrue(repository.state.value.members.isEmpty())
        assertEquals(1, repository.state.value.items.size)
    }

    @Test fun changedCopyKeepsOldAudioAndImportsNewVersion() = runBlocking {
        val uri = docs.add("1.mp3", byteArrayOf(1, 2))
        val link = link()
        refresh(link.id).getOrThrow()
        val oldId = repository.state.value.items.single().id
        repository.renameAudio(oldId, "旧版保留").join()
        docs.data[uri] = byteArrayOf(3, 4); docs.modified[uri] = 2
        refresh(link.id).getOrThrow()
        assertEquals(2, repository.state.value.items.size)
        assertEquals("旧版保留", repository.state.value.items.first { it.id == oldId }.title)
        assertNotEquals(oldId, store.directoryEntry(link.id, AndroidLocalAudioDocuments.identity(uri))!!.audioId)
    }

    @Test fun changedReferenceRejectsOldQueueAndMissingKeepsUserData() = runBlocking {
        val uri = docs.add("1.mp3", byteArrayOf(1, 2))
        val link = link(LocalImportMode.REFERENCE)
        refresh(link.id).getOrThrow()
        val id = repository.state.value.items.single().id
        repository.addBookmark(id, 100, "保留").join()
        docs.modified[uri] = 2
        val changed = refresh(link.id).getOrThrow()
        assertEquals(LocalAudioError.SOURCE_CHANGED, changed.results.single().error)
        assertEquals(LocalAudioError.SOURCE_CHANGED,
            try { repository.resolveForPlayback(id); null } catch (e: LocalAudioFailure) { e.error })
        docs.listing.clear()
        assertEquals(1, refresh(link.id).getOrThrow().missingCount)
        assertEquals(LocalAudioAvailability.MISSING, repository.state.value.items.single().availability)
        assertEquals(1, repository.state.value.bookmarks.size)
    }

    @Test fun unlinkDuringCopyRejectsLateCommit() = runBlocking {
        docs.add("1.mp3", byteArrayOf(1))
        val blocked = docs.add("2.mp3", byteArrayOf(2))
        val link = link()
        docs.blocked = blocked
        var result: Result<LocalAudioDirectoryRefreshResult>? = null
        val job = repository.refreshDirectory(link.id) { result = it }
        assertTrue(docs.entered.await(10, TimeUnit.SECONDS))
        withTimeout(5000) { repository.unlinkDirectory(link.id).join() }
        assertEquals(setOf(tree.toString()), docs.grants)
        docs.release.countDown()
        job.join()
        assertFalse(result!!.isSuccess)
        assertEquals(1, repository.state.value.items.size)
        assertTrue(repository.state.value.directories.isEmpty())
        assertTrue(docs.grants.isEmpty())
        assertEquals(1, File(temporary.root, "audio").listFiles()!!.size)
    }

    @Test fun copyLinkRequiresPersistentGrantButOneShotDoesNot() = runBlocking {
        val uri = docs.add("1.mp3", byteArrayOf(1))
        var error: Result<LocalAudioDirectoryLink>? = null
        repository.linkDirectory(folder.id, tree, "目录", grantedFlags = 0) { error = it }.join()
        assertEquals(LocalAudioError.PERMISSION_REQUIRED, (error!!.exceptionOrNull() as LocalAudioFailure).error)
        repository.importFiles(folder.id, listOf(uri)).join()
        assertEquals(1, repository.state.value.items.size)
    }

    @Test fun unknownModificationHintRereadsBeforeContentDeduplication() = runBlocking {
        val uri = docs.add("1.mp3", byteArrayOf(1, 2))
        val link = link()
        refresh(link.id).getOrThrow()
        val id = repository.state.value.items.single().id
        docs.modified.remove(uri)
        refresh(link.id).getOrThrow()
        assertEquals(2, docs.opens)
        assertEquals(id, repository.state.value.items.single().id)
        docs.data[uri] = byteArrayOf(3, 4)
        refresh(link.id).getOrThrow()
        assertEquals(3, docs.opens)
        assertEquals(2, repository.state.value.items.size)
        assertNotEquals(id, store.directoryEntry(link.id, AndroidLocalAudioDocuments.identity(uri))!!.audioId)
    }

    @Test fun revokedGrantCanBeRestoredOnlyForTheSameTree() = runBlocking {
        docs.add("1.mp3", byteArrayOf(1))
        val link = link()
        docs.grants.clear()
        assertEquals(LocalAudioError.PERMISSION_REQUIRED,
            (refresh(link.id).exceptionOrNull() as LocalAudioFailure).error)
        var response: Result<Unit>? = null
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        repository.reauthorizeDirectory(link.id, Uri.parse("content://directory-test/tree/other"), flags) { response = it }.join()
        assertEquals(LocalAudioError.SOURCE_CHANGED, (response!!.exceptionOrNull() as LocalAudioFailure).error)
        assertTrue(docs.grants.isEmpty())
        repository.reauthorizeDirectory(link.id, tree, flags) { response = it }.join()
        response!!.getOrThrow()
        assertNull(repository.state.value.directories.single().error)
        assertNull(repository.state.value.directories.single().lastRefreshedAtMs)
        refresh(link.id).getOrThrow()
        assertEquals(1, repository.state.value.items.size)
    }

    @Test fun cancelledRefreshDoesNotMarkUnseenReferencesMissing() = runBlocking {
        docs.add("1.mp3", byteArrayOf(1))
        val link = link(LocalImportMode.REFERENCE)
        refresh(link.id).getOrThrow()
        val previous = repository.state.value.directories.single()
        docs.listing.clear()
        docs.blocked = docs.add("2.mp3", byteArrayOf(2))
        val job = repository.refreshDirectory(link.id)
        try {
            assertTrue(docs.entered.await(10, TimeUnit.SECONDS))
            repository.cancelImport()
        } finally { docs.release.countDown() }
        withTimeout(5000) { job.join() }
        assertTrue(repository.imports.value.cancelled)
        assertEquals(previous.lastRefreshedAtMs, repository.state.value.directories.single().lastRefreshedAtMs)
        assertEquals(0, repository.state.value.directories.single().missingCount)
        assertEquals(LocalAudioAvailability.AVAILABLE, repository.state.value.items.single().availability)
    }

    @Test fun recursivePreferenceSurvivesRepositoryRestart() = runBlocking {
        var response: Result<LocalAudioDirectoryLink>? = null
        repository.linkDirectory(folder.id, tree, "子目录", grantedFlags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            includeSubdirectories = true) { response = it }.join()
        val id = response!!.getOrThrow().id
        store.close()
        store = SqliteLocalAudioStore(context, dbName)
        repository = AppLocalAudioRepository(store, files, docs, work)
        refresh(id).getOrThrow()
        assertEquals(true, docs.lastRecursive)
        assertTrue(repository.state.value.directories.single().includeSubdirectories)
    }

    private suspend fun link(mode: LocalImportMode = LocalImportMode.COPY): LocalAudioDirectoryLink {
        var result: Result<LocalAudioDirectoryLink>? = null
        repository.linkDirectory(folder.id, tree, "音声目录", mode,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) { result = it }.join()
        return result!!.getOrThrow()
    }
    private suspend fun refresh(id: String): Result<LocalAudioDirectoryRefreshResult> {
        var result: Result<LocalAudioDirectoryRefreshResult>? = null
        repository.refreshDirectory(id) { result = it }.join()
        return result!!
    }

    private class Documents : LocalAudioDocuments {
        val data = mutableMapOf<Uri, ByteArray>()
        val names = mutableMapOf<Uri, String>()
        val modified = mutableMapOf<Uri, Long>()
        val listing = mutableListOf<Uri>()
        val grants = mutableSetOf<String>()
        var opens = 0
        var listError: LocalAudioError? = null
        var lastRecursive: Boolean? = null
        var blocked: Uri? = null
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun add(name: String, bytes: ByteArray): Uri {
            val uri = Uri.parse("content://directory-test/document/${LocalAudioLimits.newId()}")
            data[uri] = bytes; names[uri] = name; modified[uri] = 1; listing += uri
            return uri
        }
        override fun describe(uri: Uri) = LocalAudioDocument(uri, names.getValue(uri), "audio/mpeg",
            data[uri]?.size?.toLong(), modifiedAtMs = modified[uri])
        override fun metadata(document: LocalAudioDocument) = LocalAudioMetadata(document.name,
            sizeBytes = document.size, modifiedAtMs = document.modifiedAtMs)
        override fun open(uri: Uri): ByteArrayInputStream {
            opens++
            if (uri == blocked) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            return ByteArrayInputStream(data[uri] ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND))
        }
        override fun acquireRead(grant: Uri, flags: Int) {
            if (grant.toString() in grants) return
            val needed = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            if (flags and needed != needed) throw LocalAudioFailure(LocalAudioError.PERMISSION_REQUIRED)
            grants += grant.toString()
        }
        override fun releaseUnused(retained: Set<String>) { grants.retainAll(retained) }
        override fun children(tree: Uri, recursive: Boolean, checkCancelled: () -> Unit): List<LocalAudioDocument> {
            lastRecursive = recursive
            listError?.let { throw LocalAudioFailure(it) }
            return listing.map { checkCancelled(); describe(it) }.sortedWith { a, b -> LocalAudioNames.compare(a.name, b.name) }
        }
    }
}
