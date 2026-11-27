package tech.musxi.player.localaudio

import android.app.Application
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.musxi.core.localaudio.*

/** Written during the user's verification pause; not executed in batch 4. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DsfPreviewRepositoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val documents = Documents()
    private val repository = DsfPreviewRepository(documents, scope)
    private val uri = Uri.parse("content://selected/private-dsf")

    @After fun close() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }

    @Test fun hugeSourceReadsOnlyHeaderAndNeverEnablesPlaybackOrGrants() = runBlocking {
        repository.preview(uri).join()
        val info = repository.state.value.preview!!
        assertEquals(11_289_600, info.sampleRateHz)
        assertEquals(256, info.dsdMultiplier)
        assertEquals(listOf(0L to 92), documents.ranges)
        assertFalse(info.playbackSupported)
        assertFalse(repository.state.value.toString().contains("content://"))
    }

    @Test fun unavailableRandomAccessLeavesHeaderValidWithUnknownTags() = runBlocking {
        documents.hasTags = true
        repository.preview(uri).join()
        assertEquals(DsfTagStatus.UNAVAILABLE, repository.state.value.preview!!.tagStatus)
        assertEquals(listOf(0L to 92, documents.dataEnd to 10), documents.ranges)
    }

    @Test fun optionalTagReadsAreBoundedAndDoNotTraverseAudioPayload() = runBlocking {
        documents.hasTags = true
        documents.tag = byteArrayOf(73, 68, 51, 3, 0, 0, 0, 0, 0, 0)
        repository.preview(uri).join()
        assertEquals(DsfTagStatus.READ, repository.state.value.preview!!.tagStatus)
        assertEquals(listOf(0L to 92, documents.dataEnd to 10, documents.dataEnd to 10), documents.ranges)
    }

    @Test fun enabledPreviewRequiresBothSupportedFormatAndRandomReadAccess() = runBlocking {
        documents.enabled = true
        documents.seekable = true
        repository.preview(uri).join()
        assertTrue(repository.state.value.preview!!.playbackSupported)
        assertTrue(documents.ranges.contains(documents.dataEnd - 1 to 1))
        documents.seekable = false
        repository.preview(uri).join()
        assertFalse(repository.state.value.preview!!.playbackSupported)
        documents.seekable = true
        documents.rate = 22_579_200
        repository.preview(uri).join()
        assertEquals(512, repository.state.value.preview!!.dsdMultiplier)
        assertFalse(repository.state.value.preview!!.playbackSupported)
    }

    @Test fun sourceVersionChangeRejectsCompletedPreview() = runBlocking {
        documents.change = true
        repository.preview(uri).join()
        assertEquals(DsfPreviewError.SOURCE_CHANGED, repository.state.value.error)
        assertNull(repository.state.value.preview)
    }

    @Test fun permissionFailureIsSanitizedAndNotAnUnavailableTag() = runBlocking {
        documents.denied = true
        var callback: Result<DsfPreviewInfo>? = null
        repository.preview(uri) { callback = it }.join()
        assertEquals(DsfPreviewError.PERMISSION_REQUIRED, repository.state.value.error)
        assertFalse(callback!!.exceptionOrNull()!!.toString().contains("private-dsf"))
    }

    @Test fun invalidSourceSchemeIsNeverOpenedAndClearDropsFacts() = runBlocking {
        repository.preview(Uri.parse("file:///private.dsf")).join()
        assertEquals(DsfPreviewError.INVALID_ARGUMENT, repository.state.value.error)
        assertTrue(documents.ranges.isEmpty())
        repository.preview(uri).join()
        assertNotNull(repository.state.value.preview)
        repository.clear()
        assertNull(repository.state.value.preview)
        assertFalse(repository.state.value.running)
    }

    @Test fun shortReadsAndZeroLengthReadAreBoundedAndCancelledCooperatively() {
        var checks = 0
        val source = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), readDsfRange(source, 3) { checks++ })
        assertTrue(checks >= 4)
        assertNull(readDsfRange(ByteArrayInputStream(byteArrayOf(1)), 2) {})
    }

    private class Documents : LocalAudioDocuments {
        val ranges = mutableListOf<Pair<Long, Int>>()
        val groups = 600_000L // sparse synthetic source beyond 4 GiB, no audio allocation
        val dataEnd = 92L + groups * 8192
        var hasTags = false
        var tag: ByteArray? = null
        var change = false
        var denied = false
        var enabled = false
        var seekable = false
        var rate = 11_289_600
        override val dsfPlaybackEnabled get() = enabled
        private var descriptions = 0
        override fun describe(uri: Uri): LocalAudioDocument {
            if (denied) throw SecurityException("content://selected/private-dsf")
            descriptions++
            return LocalAudioDocument(uri, "album.dsf", "audio/dsf", dataEnd + if (hasTags) 10 else 0,
                modifiedAtMs = if (change && descriptions > 1) 2L else 1L)
        }
        override fun readRange(uri: Uri, offset: Long, count: Int, checkCancelled: () -> Unit): ByteArray? {
            checkCancelled(); ranges += offset to count
            if (offset == dataEnd - 1 && count == 1 && seekable) return byteArrayOf(0)
            if (offset != 0L) return tag?.copyOf(count)
            return ByteBuffer.allocate(92).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("DSD ".toByteArray()); putLong(28); putLong(dataEnd + if (hasTags) 10 else 0)
                putLong(if (hasTags) dataEnd else 0)
                put("fmt ".toByteArray()); putLong(52); putInt(1); putInt(0); putInt(2); putInt(2)
                putInt(rate); putInt(1); putLong(groups * 32768); putInt(4096); putInt(0)
                put("data".toByteArray()); putLong(12 + groups * 8192)
            }.array()
        }
        override fun open(uri: Uri): InputStream = error("No sequential audio reads")
        override fun metadata(document: LocalAudioDocument): LocalAudioMetadata = error("No platform decode")
        override fun acquireRead(grant: Uri, flags: Int) = error("No grants")
        override fun releaseUnused(retained: Set<String>) = error("No grant release")
        override fun children(tree: Uri, recursive: Boolean, checkCancelled: () -> Unit): List<LocalAudioDocument> = error("No scans")
    }
}
