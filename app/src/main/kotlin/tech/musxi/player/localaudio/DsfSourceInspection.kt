package tech.musxi.player.localaudio

import java.io.File
import java.io.RandomAccessFile
import tech.musxi.core.localaudio.*

internal data class DsfSourceInspection(val info: DsfFileInfo, val tags: DsfTextTags?, val tagStatus: DsfTagStatus)

internal fun dsfImportMetadata(documents: LocalAudioDocuments, document: LocalAudioDocument,
    mode: LocalImportMode, checkCancelled: () -> Unit): LocalAudioMetadata {
    if (!documents.dsfPlaybackEnabled) throw LocalAudioFailure(LocalAudioError.UNSUPPORTED_AUDIO)
    val inspected = try { inspectDsfSource(documents, document, checkCancelled) }
        catch (_: DsfParseFailure) { throw LocalAudioFailure(LocalAudioError.UNSUPPORTED_AUDIO) }
    if (!DsfPlaybackSupport.supports(inspected.info)) throw LocalAudioFailure(LocalAudioError.UNSUPPORTED_AUDIO)
    if (mode == LocalImportMode.REFERENCE) requireDsfReferenceAccess(documents, document, inspected.info, checkCancelled)
    requireDsfSourceUnchanged(documents, document, checkCancelled)
    return LocalAudioMetadata(document.name, LocalAudioNames.clean(inspected.tags?.title), LocalAudioNames.clean(inspected.tags?.artist),
        inspected.info.durationMs, document.size ?: inspected.info.fileSize, document.modifiedAtMs)
}

internal fun requireDsfSourceUnchanged(documents: LocalAudioDocuments, before: LocalAudioDocument, checkCancelled: () -> Unit) {
    checkCancelled()
    val after = documents.describe(before.uri)
    after.readError?.let { throw LocalAudioFailure(it) }
    if (before.size != after.size || before.modifiedAtMs != after.modifiedAtMs)
        throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
}

/** Shared bounded header/tag read for preview and import. No grant, payload traversal or decode. */
internal fun inspectDsfSource(documents: LocalAudioDocuments, before: LocalAudioDocument,
    checkCancelled: () -> Unit): DsfSourceInspection {
    checkCancelled()
    before.readError?.let { throw LocalAudioFailure(it) }
    if (before.size != null && before.size > LocalAudioLimits.MAX_FILE_BYTES)
        throw LocalAudioFailure(LocalAudioError.TOO_LARGE)
    val header = documents.readRange(before.uri, 0, DsfFileParser.HEADER_SIZE, checkCancelled)
        ?: throw LocalAudioFailure(LocalAudioError.READ_FAILED)
    val info = DsfFileParser.parse(header, before.size)
    if (info.fileSize > LocalAudioLimits.MAX_FILE_BYTES || info.durationMs > LocalAudioLimits.MAX_POSITION_MS)
        throw LocalAudioFailure(LocalAudioError.TOO_LARGE)
    var tags: DsfTextTags? = null
    var status = DsfTagStatus.ABSENT
    info.metadataOffset?.let { offset ->
        status = DsfTagStatus.UNAVAILABLE
        try {
            if (info.fileSize - offset >= 10) {
                val tagHeader = documents.readRange(before.uri, offset, 10, checkCancelled)
                val count = tagHeader?.let(DsfId3Tags::requiredBytes)
                if (count != null && count.toLong() <= info.fileSize - offset) {
                    tags = documents.readRange(before.uri, offset, count, checkCancelled)?.let(DsfId3Tags::parse)
                    if (tags != null) status = DsfTagStatus.READ
                }
            }
        } catch (error: java.util.concurrent.CancellationException) { throw error }
        catch (error: SecurityException) { throw error }
        catch (error: LocalAudioFailure) {
            if (error.error == LocalAudioError.PERMISSION_REQUIRED || error.error == LocalAudioError.SOURCE_CHANGED) throw error
        } catch (_: java.io.IOException) { /* Optional tags may be unavailable on a pipe. */ }
    }
    requireDsfSourceUnchanged(documents, before, checkCancelled)
    return DsfSourceInspection(info, tags, status)
}

internal fun requireDsfReferenceAccess(documents: LocalAudioDocuments, document: LocalAudioDocument,
    info: DsfFileInfo, checkCancelled: () -> Unit) {
    // A successful last-byte read establishes bounded random access and catches known truncation.
    // Never replace this with InputStream.skip over gigabytes of payload.
    val tail = documents.readRange(document.uri, info.dataOffset + info.dataSize - 1, 1, checkCancelled)
    if (tail == null) throw LocalAudioFailure(LocalAudioError.READ_FAILED, LocalAudioRecoveryAction.COPY_REQUIRED)
    if (tail.size != 1) throw LocalAudioFailure(LocalAudioError.READ_FAILED)
    checkCancelled()
}

/** Validate the actual managed copy, not just the source metadata read before a long copy. */
internal fun validateDsfCopy(file: File, expected: DsfFileInfo, checkCancelled: () -> Unit): DsfTextTags? {
    checkCancelled()
    return try {
        RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(DsfFileParser.HEADER_SIZE)
            input.readFully(header)
            val actual = DsfFileParser.parse(header, input.length())
            if (actual != expected) throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
            checkCancelled()
            // Pipes cannot read tail tags before copying; the complete private file can now do so.
            val offset = actual.metadataOffset ?: return@use null
            if (actual.fileSize - offset < 10) return@use null
            input.seek(offset)
            val tagHeader = ByteArray(10).also(input::readFully)
            val count = DsfId3Tags.requiredBytes(tagHeader) ?: return@use null
            if (count.toLong() > actual.fileSize - offset) return@use null
            input.seek(offset)
            val bytes = ByteArray(count)
            var done = 0
            while (done < count) {
                checkCancelled()
                val read = input.read(bytes, done, minOf(8192, count - done))
                if (read < 0) return@use null
                done += read
            }
            checkCancelled()
            DsfId3Tags.parse(bytes)
        }
    } catch (_: DsfParseFailure) { throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED) }
    catch (error: LocalAudioFailure) { throw error }
    catch (_: java.io.IOException) { throw LocalAudioFailure(LocalAudioError.READ_FAILED) }
}
