package tech.musxi.player.localaudio

import android.app.Application
import android.content.Intent
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.musxi.core.localaudio.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CuePreviewRepositoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val documents = Documents()
    private val repository = CuePreviewRepository(documents, scope)
    private val cue = Uri.parse("content://selected/cue")
    private val audio = Uri.parse("content://selected/audio")

    @After fun close() = runBlocking {
        documents.release.countDown()
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test fun readsOnlyFlacHeaderAndDoesNotAcquireGrantsOrWriteLibrary() = runBlocking {
        prepare()
        var callback: Result<CueImportPreview>? = null
        repository.preview(cue, listOf(audio)) { callback = it }.join()
        val preview = callback!!.getOrThrow()
        assertTrue(preview.canProceed)
        assertEquals(2, preview.tracks.size)
        assertEquals(42, documents.audioBytes.get())
        assertEquals(2, documents.closed.get())
        assertEquals(0, documents.grantCalls.get())
        assertFalse(repository.state.value.toString().contains("content://"))
        assertEquals(60_000_000L, preview.tracks.last().durationUs)
    }

    @Test fun missingAudioIsACompletedEditablePreview() = runBlocking {
        prepare()
        repository.preview(cue, emptyList()).join()
        assertTrue(repository.state.value.completed)
        assertFalse(repository.state.value.preview!!.canProceed)
        assertEquals(CueAssociationStatus.MISSING, repository.state.value.preview!!.files.single().status)
    }

    @Test fun explicitBindingChoosesDifferentNamedAudioWithoutChangingCue() = runBlocking {
        prepare(name = "album (2).flac")
        val original = documents.data.getValue(cue).copyOf()
        repository.preview(cue, listOf(audio), mapOf(0 to 0)).join()
        assertTrue(repository.state.value.preview!!.canProceed)
        assertEquals(CueAssociationStatus.MANUAL, repository.state.value.preview!!.files.single().status)
        assertArrayEquals(original, documents.data.getValue(cue))
    }

    @Test fun unreadableCandidateDoesNotHideOtherCandidates() = runBlocking {
        prepare()
        val denied = Uri.parse("content://selected/denied")
        documents.names[denied] = "other.flac"
        documents.denied = denied
        repository.preview(cue, listOf(audio, denied)).join()
        val preview = repository.state.value.preview!!
        assertTrue(preview.canProceed)
        assertEquals(CuePreviewError.PERMISSION_REQUIRED, preview.candidates[1].error)
    }

    @Test fun invalidAndTruncatedFlacHeadersAreRejected() = runBlocking {
        prepare()
        documents.data[audio] = ByteArray(42)
        repository.preview(cue, listOf(audio)).join()
        assertEquals(CuePreviewError.INVALID_FLAC, repository.state.value.preview!!.candidates.single().error)
        documents.data[audio] = flac().take(20).toByteArray()
        repository.preview(cue, listOf(audio)).join()
        assertEquals(CuePreviewError.INVALID_FLAC, repository.state.value.preview!!.candidates.single().error)
    }

    @Test fun unknownFlacDurationCannotConfirmRanges() = runBlocking {
        prepare()
        documents.data[audio] = flac(samples = 0)
        repository.preview(cue, listOf(audio)).join()
        assertFalse(repository.state.value.preview!!.canProceed)
        assertEquals(CuePreviewError.INVALID_FLAC, repository.state.value.preview!!.candidates.single().error)
    }

    @Test fun hugeAudioReadsHeaderOnlyAndOverLimitIsNotOpened() = runBlocking {
        prepare()
        documents.sizes[audio] = 40L * 1024 * 1024 * 1024
        repository.preview(cue, listOf(audio)).join()
        assertTrue(repository.state.value.preview!!.canProceed)
        documents.audioBytes.set(0)
        documents.sizes[audio] = LocalAudioLimits.MAX_FILE_BYTES + 1
        repository.preview(cue, listOf(audio)).join()
        assertEquals(0, documents.audioBytes.get())
        assertEquals(CuePreviewError.TOO_LARGE, repository.state.value.preview!!.candidates.single().error)
    }

    @Test fun malformedCueNeverOpensCandidateAudio() = runBlocking {
        prepare()
        documents.data[cue] = "FILE \"../private.flac\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00".toByteArray()
        repository.preview(cue, listOf(audio)).join()
        assertFalse(repository.state.value.preview!!.canProceed)
        assertEquals(0, documents.audioBytes.get())
    }

    @Test fun oversizedCueIsBoundedAndErrorDoesNotExposeSource() = runBlocking {
        prepare()
        documents.data[cue] = ByteArray(CueSheetParser.MAX_BYTES + 100) { 32 }
        var callback: Result<CueImportPreview>? = null
        repository.preview(cue, listOf(audio)) { callback = it }.join()
        assertEquals(CuePreviewError.TOO_LARGE, repository.state.value.error)
        assertFalse(callback!!.exceptionOrNull()!!.toString().contains("content://"))
        assertEquals(0, documents.audioBytes.get())
    }

    @Test fun deniedCueHasPermissionErrorAndNoCandidateReads() = runBlocking {
        prepare()
        documents.denied = cue
        repository.preview(cue, listOf(audio)).join()
        assertEquals(CuePreviewError.PERMISSION_REQUIRED, repository.state.value.error)
        assertEquals(0, documents.audioBytes.get())
    }

    @Test fun invalidInputAndDuplicateUrisAreRejectedBeforeRead() = runBlocking {
        prepare()
        repository.preview(Uri.parse("file:///private/album.cue"), listOf(audio)).join()
        assertEquals(CuePreviewError.INVALID_ARGUMENT, repository.state.value.error)
        repository.preview(cue, listOf(audio, audio)).join()
        assertEquals(CuePreviewError.INVALID_ARGUMENT, repository.state.value.error)
        assertEquals(0, documents.closed.get())
    }

    @Test fun invalidMappingAndTooManyCandidatesAreRejected() = runBlocking {
        prepare()
        repository.preview(cue, listOf(audio), mapOf(0 to 4)).join()
        assertEquals(CuePreviewError.INVALID_ARGUMENT, repository.state.value.error)
        assertEquals(0, documents.audioBytes.get())
        repository.preview(cue, listOf(audio), mapOf(4 to 0)).join()
        assertEquals(CuePreviewError.INVALID_ARGUMENT, repository.state.value.error)
        repository.preview(cue, (0..100).map { Uri.parse("content://selected/$it") }).join()
        assertEquals(CuePreviewError.LIMIT_REACHED, repository.state.value.error)
    }

    @Test fun manualBindingToInvalidCandidateIsBlocked() = runBlocking {
        prepare()
        documents.data[audio] = ByteArray(42)
        repository.preview(cue, listOf(audio), mapOf(0 to 0)).join()
        val preview = repository.state.value.preview!!
        assertEquals(CueAssociationStatus.INVALID_AUDIO, preview.files.single().status)
        assertTrue(preview.issues.any { it.code == CuePreviewIssueCode.INVALID_AUDIO })
        assertFalse(preview.canProceed)
    }

    @Test fun clearDuringReadAndDirectJobCancellationCannotPublish() = runBlocking {
        prepare()
        documents.block = cue
        var callbacks = 0
        val job = repository.preview(cue, listOf(audio)) { callbacks++ }
        assertTrue(documents.entered.await(10, TimeUnit.SECONDS))
        repository.clear()
        documents.release.countDown(); job.join()
        assertNull(repository.state.value.preview)
        assertFalse(repository.state.value.running)
        assertFalse(repository.state.value.cancelled)
        assertEquals(0, callbacks)
        documents.block = null
        val cancelled = repository.preview(cue, listOf(audio)) { callbacks++ }
        cancelled.cancelAndJoin()
        assertFalse(repository.state.value.running)
        assertEquals(0, callbacks)
    }

    @Test fun cancelClearsPreviewAndDiscardsLateIoAndCallback() = runBlocking {
        prepare()
        documents.block = cue
        var callbacks = 0
        val job = repository.preview(cue, listOf(audio)) { callbacks++ }
        assertTrue(documents.entered.await(10, TimeUnit.SECONDS))
        repository.cancel()
        documents.release.countDown()
        job.join()
        assertTrue(repository.state.value.cancelled)
        assertNull(repository.state.value.preview)
        assertEquals(0, callbacks)
        assertEquals(0, documents.audioBytes.get())
    }

    @Test fun newerPreviewAndClearFenceEarlierWork() = runBlocking {
        prepare()
        val blocked = Uri.parse("content://selected/old-cue")
        documents.data[blocked] = documents.data.getValue(cue)
        documents.block = blocked
        var oldCallback = false
        val old = repository.preview(blocked, listOf(audio)) { oldCallback = true }
        assertTrue(documents.entered.await(10, TimeUnit.SECONDS))
        repository.preview(cue, listOf(audio)).join()
        assertTrue(repository.state.value.preview!!.canProceed)
        val latestRevision = repository.state.value.revision
        documents.release.countDown(); old.join()
        assertFalse(oldCallback)
        assertEquals(latestRevision, repository.state.value.revision)
        repository.clear()
        assertNull(repository.state.value.preview)
        assertFalse(repository.state.value.running)
        assertFalse(repository.state.value.cancelled)
    }

    @Test fun cuePickerRequestsSingleTemporaryReadOnlySelection() {
        val intent = LocalAudioPickers.cue()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("*/*", intent.type)
        assertFalse(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, true))
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
    }

    private fun prepare(name: String = "album.flac") {
        documents.data[cue] = "FILE \"album.flac\" WAVE\nTRACK 01 AUDIO\nTITLE \"一\"\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nINDEX 01 01:00:00".toByteArray()
        documents.data[audio] = flac()
        documents.names[audio] = name
    }

    private fun flac(samples: Long = 120L * 44100): ByteArray = ByteArray(42).apply {
        byteArrayOf(102, 76, 97, 67).copyInto(this)
        this[4] = 0x80.toByte(); this[7] = 34
        val packed = (44100L shl 44) or (1L shl 41) or (15L shl 36) or samples
        for (i in 0..7) this[18 + i] = (packed ushr (56 - 8 * i)).toByte()
    }

    private class Documents : LocalAudioDocuments {
        val data = ConcurrentHashMap<Uri, ByteArray>()
        val names = ConcurrentHashMap<Uri, String>()
        val sizes = ConcurrentHashMap<Uri, Long>()
        val audioBytes = AtomicInteger()
        val closed = AtomicInteger()
        val grantCalls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var block: Uri? = null
        @Volatile var denied: Uri? = null
        override fun describe(uri: Uri): LocalAudioDocument {
            if (uri == denied) throw SecurityException("private source $uri")
            return LocalAudioDocument(uri, names[uri] ?: "album.cue", null, sizes[uri] ?: data[uri]?.size?.toLong())
        }
        override fun open(uri: Uri): InputStream {
            if (uri == denied) throw SecurityException("private source $uri")
            if (uri == block) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            return object : ByteArrayInputStream(data[uri] ?: error("missing $uri")) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, length).also {
                    if (uri.path == "/audio" && it > 0) audioBytes.addAndGet(it)
                }
                override fun close() { closed.incrementAndGet(); super.close() }
            }
        }
        override fun metadata(document: LocalAudioDocument): LocalAudioMetadata = error("Preview must not use platform metadata or decode audio")
        override fun acquireRead(grant: Uri, flags: Int) { grantCalls.incrementAndGet(); error("No persistent grants") }
        override fun releaseUnused(retained: Set<String>) { grantCalls.incrementAndGet(); error("No grant cleanup") }
        override fun children(tree: Uri, recursive: Boolean, checkCancelled: () -> Unit): List<LocalAudioDocument> = error("No inferred sibling access")
    }
}
