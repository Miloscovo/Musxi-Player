package tech.musxi.player.localaudio

import android.app.Application
import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
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
import tech.musxi.core.settings.AppSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalMediaRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val documents = Documents()
    private val settings = MutableStateFlow(AppSettings())
    private lateinit var store: SqliteLocalAudioStore
    private lateinit var repository: AppLocalAudioRepository
    private lateinit var images: Images
    private lateinit var folder: LocalAudioFolder
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val dbName = "media-${LocalAudioLimits.newId()}.db"

    @Before fun setup() = runBlocking {
        store = SqliteLocalAudioStore(context, dbName)
        images = Images(File(temporary.root, "covers"))
        repository = AppLocalAudioRepository(store, LocalAudioFiles(File(temporary.root, "media")), documents, scope, artwork = images, settings = settings)
        repository.createFolder("视频声音") { folder = it.getOrThrow() }.join()
    }
    @After fun close() {
        images.gate?.second?.countDown()
        scope.cancel(); store.close(); context.deleteDatabase(dbName)
    }

    @Test fun copyRetainsTheWholeVideoAndItsKindAndUsesTheCopyForCoverExtraction() = runBlocking {
        val bytes = byteArrayOf(0, 1, 2, 3, 4, 5)
        val uri = documents.add("movie.mp4", bytes)
        repository.importFiles(folder.id, listOf(uri)).join()
        val item = repository.state.value.items.single()
        assertEquals(LocalMediaKind.VIDEO, item.kind)
        assertEquals(images.key, item.artworkKey)
        assertEquals("file", images.prepared.single().scheme)
        documents.data.clear()
        assertArrayEquals(bytes, File(repository.resolveForPlayback(item.id).readUri().path!!).readBytes())
        assertNotNull(repository.artworkFile(item.artworkKey!!))
        assertFalse(repository.state.value.toString().contains("content://"))
    }

    @Test fun sharedCoverSurvivesUntilLastLibraryDeletionAndFolderRemovalNeverCollectsIt() = runBlocking {
        val first = imported("one.mp4")
        val second = imported("two.mp4")
        val image = repository.artworkFile(images.key)!!
        repository.removeFromFolder(folder.id, first).join()
        assertTrue(image.exists())
        repository.deleteAudio(first).join()
        assertTrue(image.exists())
        assertEquals(images.key, store.source(second)!!.item.artworkKey)
        repository.deleteAudio(second).join()
        assertFalse(image.exists())
        assertEquals(0, images.pending)
    }

    @Test fun relinkReplacesTheCoverWithoutChangingNamesBookmarksOrProgress() = runBlocking {
        val id = imported("old.mp4")
        repository.renameAudio(id, "别名").join()
        repository.addBookmark(id, 3_000, "书签").join()
        repository.saveProgress(folder.id, LocalAudioProgress(id, 9_000, false, 1)).join()
        val before = repository.state.value
        val oldImage = repository.artworkFile(images.key)!!
        images.key = "b".repeat(64)
        val replacement = documents.add("new.mp4", byteArrayOf(8, 8))
        repository.relink(id, replacement, 65).join()
        val after = repository.state.value
        assertEquals(id, after.items.single().id)
        assertEquals("别名", after.items.single().title)
        assertEquals(before.progress, after.progress)
        assertEquals(before.bookmarks, after.bookmarks)
        assertEquals(images.key, after.items.single().artworkKey)
        assertFalse(oldImage.exists())
        images.enabled = false
        repository.relink(id, documents.add("sound.mp3"), 65).join()
        assertNull(repository.state.value.items.single().artworkKey)
        assertEquals(LocalMediaKind.AUDIO, repository.state.value.items.single().kind)
        assertNull(repository.artworkFile(images.key))
    }

    @Test fun explicitRepairAddsImagesWithoutChangingSourceVersionOrMakingSourceChecksDecode() = runBlocking {
        images.enabled = false
        val id = imported("old.mp4")
        val version = AppLocalAudioRepository.sourceVersion(store.source(id)!!)
        val before = repository.state.value.revision
        images.enabled = true
        repository.fillArtwork(listOf(id), before).join()
        assertTrue(repository.artworkFill.value.completed)
        assertEquals(images.key, repository.artworkFill.value.results.single().artworkKey)
        assertTrue(repository.state.value.revision > before)
        assertEquals(version, AppLocalAudioRepository.sourceVersion(store.source(id)!!))
        val count = images.prepared.size
        repository.checkSources(listOf(id), repository.state.value.revision).join()
        assertEquals(count, images.prepared.size)
        repository.fillArtwork(listOf(id), repository.state.value.revision).join()
        assertEquals(count, images.prepared.size)
        assertEquals(0, images.pending)
    }

    @Test fun deletingAnItemDuringRepairRejectsTheLateImageAndCollectsItsLease() = runBlocking {
        images.enabled = false
        val id = imported("old.mp4")
        images.enabled = true
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        images.gate = entered to release
        val job = repository.fillArtwork(listOf(id), repository.state.value.revision)
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        repository.deleteAudio(id).join()
        release.countDown(); job.join()
        assertEquals(LocalAudioError.NOT_FOUND, repository.artworkFill.value.results.single().error)
        assertNull(repository.artworkFile(images.key))
        assertEquals(0, images.pending)
    }

    @Test fun cancellingRepairReleasesImagesAndDoesNotCommitAnUnfinishedItem() = runBlocking {
        images.enabled = false
        val id = imported("old.mp4")
        images.enabled = true
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        images.gate = entered to release
        val job = repository.fillArtwork(listOf(id), repository.state.value.revision)
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        repository.cancelArtworkFill()
        release.countDown(); job.join()
        assertTrue(repository.artworkFill.value.cancelled)
        assertNull(store.source(id)!!.item.artworkKey)
        assertNull(repository.artworkFile(images.key))
        assertEquals(0, images.pending)
    }

    @Test fun sourceChangeOnRepeatImportKeepsTheOldCoverAndCollectsTheUncommittedReplacement() = runBlocking {
        val id = imported("old.mp4")
        val oldKey = images.key
        val uri = documents.data.keys.single()
        images.key = "c".repeat(64)
        documents.data[uri] = byteArrayOf(1, 2, 3, 4)
        repository.importFiles(folder.id, listOf(uri), LocalImportMode.REFERENCE, 65).join()
        assertEquals(LocalAudioError.SOURCE_CHANGED, repository.imports.value.results.single().error)
        assertEquals(oldKey, store.source(id)!!.item.artworkKey)
        assertNotNull(repository.artworkFile(oldKey))
        assertNull(repository.artworkFile(images.key))
    }

    @Test fun repairRejectsStaleSelectionsBeforeOpeningSources() = runBlocking {
        images.enabled = false
        val id = imported("old.mp4")
        val revision = repository.state.value.revision
        repository.renameAudio(id, "新名称").join()
        val count = images.prepared.size
        repository.fillArtwork(listOf(id), revision).join()
        assertEquals(LocalAudioError.STALE_STATE, repository.artworkFill.value.error)
        assertEquals(count, images.prepared.size)
    }

    @Test fun offHidesAutomaticImagesWithoutDeletingThemAndAutoNeverStartsBackgroundExtraction() = runBlocking {
        val id = imported("old.mp4")
        val item = repository.state.value.items.single()
        val automatic = repository.artworkFile(item.artworkKey!!)!!
        settings.value = settings.value.copy(localArtworkMode = "OFF")
        assertNull(repository.displayArtworkFile(item))
        assertTrue(automatic.exists())
        val count = images.prepared.size
        val noCover = imported("new.mp4")
        assertNull(store.source(noCover)!!.item.artworkKey)
        assertEquals(count, images.prepared.size)
        repository.fillArtwork(listOf(id), repository.state.value.revision).join()
        assertEquals(LocalAudioError.INVALID_TARGET, repository.artworkFill.value.error)
        assertEquals(count, images.prepared.size)
        settings.value = settings.value.copy(localArtworkMode = "AUTO")
        assertEquals(automatic, repository.displayArtworkFile(item))
        assertEquals(count, images.prepared.size)
        assertNull(store.source(noCover)!!.item.artworkKey)
    }

    @Test fun manualCoverWinsInOffAndSurvivesRelinkRepairAndRestoreDefault() = runBlocking {
        val id = imported("old.mp4")
        val autoKey = images.key
        val oldAuto = repository.artworkFile(autoKey)!!
        images.key = "d".repeat(64)
        val manualKey = images.key
        val imageUri = Uri.parse("content://photos/image")
        settings.value = settings.value.copy(localArtworkMode = "OFF")
        var result: Result<Unit>? = null
        repository.setCustomArtwork(id, imageUri, 1) { result = it }.join()
        result!!.getOrThrow()
        var item = repository.state.value.items.single()
        assertTrue(item.artworkCustom)
        assertEquals(manualKey, item.artworkKey)
        assertEquals(autoKey, item.metadata.artworkKey)
        assertNotNull(repository.displayArtworkFile(item))
        assertTrue(oldAuto.exists())
        assertFalse(store.retainedGrantUris().contains(imageUri.toString()))
        val manual = repository.artworkFile(manualKey)!!
        settings.value = settings.value.copy(localArtworkMode = "AUTO")
        val count = images.prepared.size
        repository.fillArtwork(listOf(id), repository.state.value.revision).join()
        assertEquals(count, images.prepared.size)
        images.key = "e".repeat(64)
        repository.relink(id, documents.add("new.mp4"), 65).join()
        item = repository.state.value.items.single()
        assertEquals(manualKey, item.artworkKey)
        assertEquals(images.key, item.metadata.artworkKey)
        assertTrue(manual.exists())
        assertFalse(oldAuto.exists())
        settings.value = settings.value.copy(localArtworkMode = "OFF")
        repository.clearCustomArtwork(id).join()
        item = repository.state.value.items.single()
        assertFalse(item.artworkCustom)
        assertEquals(images.key, item.artworkKey)
        assertNull(repository.displayArtworkFile(item))
        assertFalse(manual.exists())
        assertNotNull(repository.artworkFile(images.key))
        settings.value = settings.value.copy(localArtworkMode = "AUTO")
        assertNotNull(repository.displayArtworkFile(item))
    }

    @Test fun manualImageCanBeSetWithoutAReadableAudioSourceAndFailedReplacementKeepsIt() = runBlocking {
        val id = imported("old.mp4")
        documents.data.clear()
        images.key = "d".repeat(64)
        repository.setCustomArtwork(id, Uri.parse("content://photos/one"), 0).join()
        val image = repository.displayArtworkFile(repository.state.value.items.single())!!
        images.enabled = false
        var failed: Result<Unit>? = null
        repository.setCustomArtwork(id, Uri.parse("content://photos/broken"), 1) { failed = it }.join()
        assertEquals(LocalAudioError.READ_FAILED, (failed!!.exceptionOrNull() as LocalAudioFailure).error)
        assertTrue(image.exists())
        assertTrue(repository.state.value.items.single().artworkCustom)
    }

    @Test fun cropIsForwardedAndOldPositionalCallbackStillMeansWholeImage() = runBlocking {
        val id = imported("old.mp4")
        val image = Uri.parse("content://photos/crop")
        val crop = LocalArtworkCrop(0.25f, 0f, 0.75f, 1f)
        var completed = false
        repository.setCustomArtwork(id, image, 0, crop = crop) { it.getOrThrow(); completed = true }.join()
        assertTrue(completed)
        assertEquals(crop, images.pickedCrops.last())
        val oldCallback: (Result<Unit>) -> Unit = { it.getOrThrow() }
        repository.setCustomArtwork(id, image, 0, oldCallback).join()
        assertNull(images.pickedCrops.last())
    }

    @Test fun invalidCropDoesNotOpenImageOrReplaceThePreviousCover() = runBlocking {
        val id = imported("old.mp4")
        val image = Uri.parse("content://photos/crop")
        images.key = "c".repeat(64)
        repository.setCustomArtwork(id, image, 0).join()
        val prior = store.source(id)!!.item.customArtworkKey
        val opened = images.pickedCrops.size
        var response: Result<Unit>? = null
        repository.setCustomArtwork(id, image, 0, crop = LocalArtworkCrop(Float.NaN, 0f, 1f, 1f)) { response = it }.join()
        assertEquals(LocalAudioError.INVALID_TARGET, (response!!.exceptionOrNull() as LocalAudioFailure).error)
        assertEquals(opened, images.pickedCrops.size)
        assertEquals(prior, store.source(id)!!.item.customArtworkKey)
        assertEquals(0, images.pending)
    }

    @Test fun restoringDefaultDuringImageReadRejectsTheLateCustomCommit() = runBlocking {
        val id = imported("old.mp4")
        images.key = "d".repeat(64)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        images.gate = entered to release
        var response: Result<Unit>? = null
        val job = repository.setCustomArtwork(id, Uri.parse("content://photos/slow"), 1,
            crop = LocalArtworkCrop(0f, 0f, 1f, 1f)) { response = it }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        repository.clearCustomArtwork(id).join()
        release.countDown(); job.join()
        assertEquals(LocalAudioError.STALE_STATE, (response!!.exceptionOrNull() as LocalAudioFailure).error)
        assertFalse(store.source(id)!!.item.artworkCustom)
        assertNull(repository.artworkFile(images.key))
        assertEquals(0, images.pending)
    }

    @Test fun newerCropSelectionWinsAndCancelledCropNeverReplacesIt() = runBlocking {
        val id = imported("old.mp4")
        images.key = "d".repeat(64)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        images.gate = entered to release
        var firstResponse: Result<Unit>? = null
        val oldJob = repository.setCustomArtwork(id, Uri.parse("content://photos/old"), 1,
            crop = LocalArtworkCrop(0f, 0f, .5f, .5f)) { firstResponse = it }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        images.gate = null
        images.key = "e".repeat(64)
        repository.setCustomArtwork(id, Uri.parse("content://photos/new"), 1,
            crop = LocalArtworkCrop(.5f, .5f, 1f, 1f)).join()
        release.countDown(); oldJob.join()
        assertEquals(LocalAudioError.STALE_STATE, (firstResponse!!.exceptionOrNull() as LocalAudioFailure).error)
        assertEquals("e".repeat(64), store.source(id)!!.item.customArtworkKey)
        assertNull(repository.artworkFile("d".repeat(64)))

        val cancelledEntered = CountDownLatch(1); val cancelledRelease = CountDownLatch(1)
        images.gate = cancelledEntered to cancelledRelease
        images.key = "f".repeat(64)
        var cancelledCallback = false
        val cancelled = repository.setCustomArtwork(id, Uri.parse("content://photos/cancelled"), 1,
            crop = LocalArtworkCrop(0f, 0f, 1f, 1f)) { cancelledCallback = true }
        assertTrue(cancelledEntered.await(10, TimeUnit.SECONDS))
        cancelled.cancel(); cancelledRelease.countDown(); cancelled.join()
        assertFalse(cancelledCallback)
        assertEquals("e".repeat(64), store.source(id)!!.item.customArtworkKey)
        assertNull(repository.artworkFile("f".repeat(64)))
        assertEquals(0, images.pending)
    }

    @Test fun deletingDuringImageReadRejectsAndCollectsTheLateCustomImage() = runBlocking {
        val id = imported("old.mp4")
        images.key = "d".repeat(64)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        images.gate = entered to release
        var response: Result<Unit>? = null
        val job = repository.setCustomArtwork(id, Uri.parse("content://photos/slow"), 1) { response = it }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        repository.deleteAudio(id).join()
        release.countDown(); job.join()
        assertTrue(response!!.isFailure)
        assertTrue(repository.state.value.items.isEmpty())
        assertNull(repository.artworkFile(images.key))
        assertEquals(0, images.pending)
    }

    @Test fun itemSpeedsSurviveSourceRelinkAndBookmarkRenameKeepsItsPosition() = runBlocking {
        val first = imported("one.mp4"); val second = imported("two.mp4")
        val version = AppLocalAudioRepository.sourceVersion(store.source(first)!!)
        repository.setPlaybackSpeed(first, 1.75f).join()
        assertEquals(1.75f, store.source(first)!!.item.playbackSpeed)
        assertEquals(1f, store.source(second)!!.item.playbackSpeed)
        assertEquals(version, AppLocalAudioRepository.sourceVersion(store.source(first)!!))
        var failure: Result<Unit>? = null
        repository.setPlaybackSpeed(first, Float.NaN) { failure = it }.join()
        assertTrue(failure!!.isFailure)
        repository.relink(first, documents.add("replacement.mp4"), 65).join()
        assertEquals(1.75f, store.source(first)!!.item.playbackSpeed)
        repository.addBookmark(first, 9_000, "旧名").join()
        val bookmark = repository.state.value.bookmarks.single()
        val revision = repository.state.value.revision
        repository.renameBookmark(bookmark.id, " 新名 ").join()
        assertEquals(bookmark.copy(name = "新名"), repository.state.value.bookmarks.single())
        assertTrue(repository.state.value.revision > revision)
    }

    private suspend fun imported(name: String): String {
        repository.importFiles(folder.id, listOf(documents.add(name)), LocalImportMode.REFERENCE, 65).join()
        return repository.imports.value.results.single().audioId!!
    }

    private class Documents : LocalAudioDocuments {
        val data = linkedMapOf<Uri, ByteArray>()
        private val names = mutableMapOf<Uri, String>()
        fun add(name: String, bytes: ByteArray = byteArrayOf(1, 2)): Uri = Uri.parse("content://media/${names.size}").also {
            names[it] = name; data[it] = bytes
        }
        override fun describe(uri: Uri) = LocalAudioDocument(uri, names.getValue(uri), "application/octet-stream", data.getValue(uri).size.toLong())
        override fun metadata(document: LocalAudioDocument) = LocalAudioMetadata(document.name, sizeBytes = document.size,
            kind = if (document.name.endsWith(".mp4")) LocalMediaKind.VIDEO else LocalMediaKind.AUDIO)
        override fun open(uri: Uri) = ByteArrayInputStream(data.getValue(uri))
        override fun acquireRead(grant: Uri, flags: Int) = Unit
        override fun releaseUnused(retained: Set<String>) = Unit
        override fun children(tree: Uri, recursive: Boolean, checkCancelled: () -> Unit) = emptyList<LocalAudioDocument>()
    }

    private class Images(private val directory: File) : LocalAudioArtwork {
        var key = "a".repeat(64)
        var enabled = true
        @Volatile var gate: Pair<CountDownLatch, CountDownLatch>? = null
        var pending = 0
        val prepared = mutableListOf<Uri>()
        val pickedCrops = mutableListOf<LocalArtworkCrop?>()
        private val active = mutableMapOf<String, Int>()
        override fun prepareImage(uri: Uri, crop: LocalArtworkCrop?, checkCancelled: () -> Unit): LocalArtworkLease? {
            pickedCrops += crop
            return prepare(uri, LocalAudioMetadata("manual.jpg"), checkCancelled)
        }
        override fun prepare(uri: Uri, metadata: LocalAudioMetadata, checkCancelled: () -> Unit): LocalArtworkLease? {
            if (!enabled) return null
            val lease = synchronized(this) {
                prepared += uri
                directory.mkdirs()
                val imageKey = key
                File(directory, "$imageKey.jpg").writeBytes(byteArrayOf(1))
                pending++; active[imageKey] = (active[imageKey] ?: 0) + 1
                LocalArtworkLease(imageKey) { synchronized(this) {
                    pending--; active[imageKey] = active.getValue(imageKey) - 1
                } }
            }
            try {
                gate?.let { it.first.countDown(); check(it.second.await(10, TimeUnit.SECONDS)) }
                checkCancelled()
                return lease
            } catch (error: Throwable) { lease.close(); throw error }
        }
        @Synchronized override fun resolve(key: String) = File(directory, "$key.jpg").takeIf { it.isFile }
        @Synchronized override fun prune(retained: Set<String>) {
            directory.listFiles()?.forEach { if (it.nameWithoutExtension !in retained && (active[it.nameWithoutExtension] ?: 0) == 0) it.delete() }
        }
    }
}
