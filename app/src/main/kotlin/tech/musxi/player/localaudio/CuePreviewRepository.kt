package tech.musxi.player.localaudio

import android.net.Uri
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.musxi.core.localaudio.*

/** Ephemeral read-only preview. Uses existing temporary grants, never acquires/releases grants. */
class CuePreviewRepository internal constructor(private val documents: LocalAudioDocuments, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(CuePreviewState())
    val state = mutable.asStateFlow()
    private var epoch = 0L
    private var pending: Job? = null
    private var acceptedStamp: String? = null

    internal fun stampFor(revision: Long): String? = acceptedStamp.takeIf {
        state.value.revision == revision && state.value.completed && state.value.preview?.canProceed == true
    }

    /** Main-thread command. Explicit candidates are the only documents examined; no path traversal. */
    fun preview(cue: Uri, audioUris: List<Uri>, bindings: Map<Int, Int> = emptyMap(),
        result: (Result<CueImportPreview>) -> Unit = {}): Job {
        val uris = audioUris.toList()
        val mapping = bindings.toMap()
        pending?.cancel()
        val revision = ++epoch
        acceptedStamp = null
        mutable.value = CuePreviewState(revision = revision, running = true)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val response = try {
                val value = withContext(Dispatchers.IO) {
                    inspectSelection(cue, uris, mapping)
                }
                currentCoroutineContext().ensureActive()
                if (revision == epoch) acceptedStamp = value.stamp
                Result.success(value.preview)
            } catch (error: CancellationException) {
                if (revision == epoch) mutable.value = CuePreviewState(revision, cancelled = true)
                throw error
            } catch (error: Exception) {
                Result.failure(PreviewFailure(classify(error)))
            }
            currentCoroutineContext().ensureActive()
            if (revision == epoch) {
                mutable.value = CuePreviewState(revision, completed = true, preview = response.getOrNull(),
                    error = (response.exceptionOrNull() as? PreviewFailure)?.reason)
                result(response)
            }
        }
        pending = job
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) mutable.update { state ->
                if (state.revision == revision && state.running) CuePreviewState(revision, cancelled = true) else state
            }
        }
        job.start()
        return job
    }

    /** Cancel and clear results immediately. Late IO cannot publish into a newer preview. */
    fun cancel() {
        acceptedStamp = null
        pending?.cancel(); pending = null
        mutable.value = CuePreviewState(revision = ++epoch, cancelled = true)
    }

    fun clear() {
        acceptedStamp = null
        pending?.cancel(); pending = null
        mutable.value = CuePreviewState(revision = ++epoch)
    }

    /** Re-read on commit, with the same bounded validation; never trust UI-supplied preview metadata. */
    internal suspend fun inspectSelection(cue: Uri, uris: List<Uri>, mapping: Map<Int, Int>): CueSelection {
        requireContent(cue)
        if (uris.size > 100) throw PreviewFailure(CuePreviewError.LIMIT_REACHED)
        if (uris.distinct().size != uris.size || uris.any { it == cue }) throw PreviewFailure(CuePreviewError.INVALID_ARGUMENT)
        uris.forEach(::requireContent)
        val context = currentCoroutineContext()
        val bytes = documents.open(cue).use { readBounded(it, CueSheetParser.MAX_BYTES) { context.ensureActive() } }
        val sheet = CueSheetParser.parse(bytes)
        if (mapping.keys.any { key -> sheet.files.none { it.index == key } } ||
            mapping.values.any { it !in uris.indices }) throw PreviewFailure(CuePreviewError.INVALID_ARGUMENT)
        val documentFacts = mutableListOf<String>()
        val candidates = if (sheet.issues.isEmpty()) uris.mapIndexed { index, uri ->
            context.ensureActive()
            inspect(index, uri, documentFacts) { context.ensureActive() }
        } else emptyList()
        context.ensureActive()
        val preview = CuePreviewPlanner.plan(sheet, candidates, if (candidates.isEmpty()) emptyMap() else mapping)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val stampInput = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(stampInput).use { out ->
            (listOf(digest, cue.toString()) + uris.map { it.toString() } +
                mapping.toSortedMap().map { "${it.key}:${it.value}" } + documentFacts + preview.candidates.map { it.toString() }).forEach {
                val value = it.toByteArray(Charsets.UTF_8)
                out.writeInt(value.size); out.write(value)
            }
        }
        val stamp = java.security.MessageDigest.getInstance("SHA-256").digest(stampInput.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return CueSelection(preview, digest, stamp)
    }
    private fun inspect(index: Int, uri: Uri, facts: MutableList<String>, check: () -> Unit): CueAudioCandidate {
        var name = "音频 ${index + 1}"
        return try {
            val document = documents.describe(uri)
            facts += "$index:${document.size}:${document.modifiedAtMs}"
            name = LocalAudioNames.clean(document.name.substringAfterLast('/').substringAfterLast('\\')) ?: name
            document.readError?.let { throw LocalAudioFailure(it) }
            if (!name.endsWith(".flac", ignoreCase = true)) throw PreviewFailure(CuePreviewError.UNSUPPORTED_AUDIO)
            if (document.size == 0L) throw PreviewFailure(CuePreviewError.READ_FAILED)
            if (document.size != null && document.size > LocalAudioLimits.MAX_FILE_BYTES) throw PreviewFailure(CuePreviewError.TOO_LARGE)
            check()
            val header = documents.open(uri).use { readHeader(it, 42, check) }
            if (!header.copyOfRange(0, 4).contentEquals(byteArrayOf(102, 76, 97, 67)) ||
                (header[4].toInt() and 0x7f) != 0 || header[5] != 0.toByte() || header[6] != 0.toByte() || header[7] != 34.toByte()) {
                throw PreviewFailure(CuePreviewError.INVALID_FLAC)
            }
            var packed = 0L
            for (i in 18..25) packed = (packed shl 8) or (header[i].toLong() and 0xff)
            val rate = (packed ushr 44).toInt()
            val channels = ((packed ushr 41) and 7).toInt() + 1
            val bits = ((packed ushr 36) and 31).toInt() + 1
            val samples = packed and 0xFFFFFFFFFL
            if (rate !in 1..655_350 || samples == 0L || bits !in 4..32) throw PreviewFailure(CuePreviewError.INVALID_FLAC)
            check()
            CueAudioCandidate(index, name, rate, samples, channels, bits)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { CueAudioCandidate(index, name, error = classify(error)) }
    }

    private fun requireContent(uri: Uri) {
        if (!LocalAudioLimits.isContentUri(uri.toString())) throw PreviewFailure(CuePreviewError.INVALID_ARGUMENT)
    }

    private fun readBounded(input: InputStream, max: Int, check: () -> Unit): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            check()
            val size = input.read(buffer, 0, minOf(buffer.size, max + 1 - output.size()))
            if (size < 0) break
            if (size == 0) throw PreviewFailure(CuePreviewError.READ_FAILED)
            output.write(buffer, 0, size)
            if (output.size() > max) throw PreviewFailure(CuePreviewError.TOO_LARGE)
        }
        return output.toByteArray()
    }

    private fun readHeader(input: InputStream, count: Int, check: () -> Unit): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            check()
            val size = input.read(bytes, offset, count - offset)
            if (size <= 0) throw PreviewFailure(CuePreviewError.INVALID_FLAC)
            offset += size
        }
        return bytes
    }

    private fun classify(error: Exception): CuePreviewError = when (error) {
        is PreviewFailure -> error.reason
        is SecurityException -> CuePreviewError.PERMISSION_REQUIRED
        is FileNotFoundException -> CuePreviewError.NOT_FOUND
        is LocalAudioFailure -> when (error.error) {
            LocalAudioError.PERMISSION_REQUIRED -> CuePreviewError.PERMISSION_REQUIRED
            LocalAudioError.NOT_FOUND -> CuePreviewError.NOT_FOUND
            LocalAudioError.TOO_LARGE -> CuePreviewError.TOO_LARGE
            LocalAudioError.UNSUPPORTED_AUDIO -> CuePreviewError.UNSUPPORTED_AUDIO
            else -> CuePreviewError.READ_FAILED
        }
        else -> CuePreviewError.READ_FAILED
    }
}

class PreviewFailure internal constructor(val reason: CuePreviewError) : java.io.IOException("CUE preview: ${reason.name}")

internal class CueSelection(val preview: CueImportPreview, val cueDigest: String, val stamp: String)
