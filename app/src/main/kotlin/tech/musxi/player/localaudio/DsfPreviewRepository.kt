package tech.musxi.player.localaudio

import android.net.Uri
import java.io.InputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tech.musxi.core.localaudio.*

/** Explicit temporary selection only; does not import, acquire grants, decode or scan directories. */
class DsfPreviewRepository internal constructor(private val documents: LocalAudioDocuments, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(DsfPreviewState())
    val state = mutable.asStateFlow()
    private var epoch = 0L
    private var pending: Job? = null

    /** Main-thread API. Latest request wins, including when an old provider ignores cancellation. */
    fun preview(uri: Uri, result: (Result<DsfPreviewInfo>) -> Unit = {}): Job {
        pending?.cancel()
        val revision = ++epoch
        mutable.value = DsfPreviewState(revision, running = true)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val response = try { Result.success(withContext(Dispatchers.IO) { inspect(uri) }) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { Result.failure(DsfPreviewFailure(classify(error))) }
            currentCoroutineContext().ensureActive()
            if (epoch == revision) {
                mutable.value = DsfPreviewState(revision, completed = true, preview = response.getOrNull(),
                    error = (response.exceptionOrNull() as? DsfPreviewFailure)?.reason)
                result(response)
            }
        }
        pending = job
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) mutable.update {
                if (it.revision == revision && it.running) DsfPreviewState(revision, cancelled = true) else it
            }
        }
        job.start()
        return job
    }

    fun cancel() {
        pending?.cancel(); pending = null
        mutable.value = DsfPreviewState(revision = ++epoch, cancelled = true)
    }
    fun clear() {
        pending?.cancel(); pending = null
        mutable.value = DsfPreviewState(revision = ++epoch)
    }

    private suspend fun inspect(uri: Uri): DsfPreviewInfo {
        if (!LocalAudioLimits.isContentUri(uri.toString())) throw DsfPreviewFailure(DsfPreviewError.INVALID_ARGUMENT)
        val context = currentCoroutineContext()
        val before = documents.describe(uri)
        val inspected = inspectDsfSource(documents, before) { context.ensureActive() }
        val info = inspected.info
        val supported = documents.dsfPlaybackEnabled && DsfPlaybackSupport.supports(info) &&
            documents.readRange(uri, info.dataOffset + info.dataSize - 1, 1) { context.ensureActive() }?.size == 1
        requireDsfSourceUnchanged(documents, before) { context.ensureActive() }
        val name = LocalAudioNames.clean(before.name) ?: "DSF"
        return DsfPreviewInfo(name, info.sampleRateHz, info.channelCount, info.durationMs, info.dsdMultiplier,
            tags = inspected.tags, tagStatus = inspected.tagStatus,
            playbackSupported = supported)
    }

    private fun classify(error: Exception): DsfPreviewError = when (error) {
        is DsfPreviewFailure -> error.reason
        is DsfParseFailure -> if (error.reason == DsfParseError.UNSUPPORTED_FORMAT)
            DsfPreviewError.UNSUPPORTED_DSF else DsfPreviewError.INVALID_DSF
        is SecurityException -> DsfPreviewError.PERMISSION_REQUIRED
        is java.io.FileNotFoundException -> DsfPreviewError.NOT_FOUND
        is LocalAudioFailure -> when (error.error) {
            LocalAudioError.NOT_FOUND -> DsfPreviewError.NOT_FOUND
            LocalAudioError.PERMISSION_REQUIRED -> DsfPreviewError.PERMISSION_REQUIRED
            LocalAudioError.SOURCE_CHANGED -> DsfPreviewError.SOURCE_CHANGED
            LocalAudioError.TOO_LARGE -> DsfPreviewError.TOO_LARGE
            else -> DsfPreviewError.READ_FAILED
        }
        else -> DsfPreviewError.READ_FAILED
    }
}

/** Reads exactly count bytes, with bounded allocation and cooperative cancellation on every read. */
internal fun readDsfRange(input: InputStream, count: Int, checkCancelled: () -> Unit): ByteArray? {
    require(count in 1..DsfId3Tags.MAX_TAG_BYTES)
    val bytes = ByteArray(count)
    var offset = 0
    while (offset < count) {
        checkCancelled()
        val read = input.read(bytes, offset, minOf(8192, count - offset))
        if (read < 0) return null
        if (read == 0) {
            val single = input.read()
            if (single < 0) return null
            bytes[offset++] = single.toByte()
        } else offset += read
    }
    checkCancelled()
    return bytes
}
