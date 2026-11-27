package tech.musxi.player.localaudio

import android.net.Uri
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException
import tech.musxi.core.localaudio.*

/** Bounded readability probe shared with playback; does not decode, hash, copy or acquire grants. */
internal class LocalAudioSourceProbe(private val files: LocalAudioFiles, private val documents: LocalAudioDocuments) {
    fun read(stored: StoredLocalAudio, checkCancelled: () -> Unit): Uri = try {
        checkCancelled()
        val uri = if (stored.source.mode == LocalImportMode.COPY) {
            val file = files.resolve(stored.source.location)
            if (stored.item.metadata.sizeBytes != null && file.length() != stored.item.metadata.sizeBytes) {
                throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
            }
            file.inputStream().use { input ->
                val header = input.readBytesUpToDsfHeader(checkCancelled)
                if (header.isEmpty()) throw LocalAudioFailure(LocalAudioError.READ_FAILED)
                if (header.hasDsfMagic() || DsfPlaybackSupport.isCandidate(stored.item.metadata.fileName))
                    validateDsfHeader(header, file.length())
            }
            Uri.fromFile(file)
        } else {
            val value = Uri.parse(stored.source.location)
            val observed = documents.describe(value)
            observed.readError?.let { throw LocalAudioFailure(it) }
            if ((observed.size != null && stored.item.metadata.sizeBytes != null && observed.size != stored.item.metadata.sizeBytes) ||
                (observed.modifiedAtMs != null && stored.item.metadata.modifiedAtMs != null &&
                    observed.modifiedAtMs != stored.item.metadata.modifiedAtMs)) {
                throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
            }
            checkCancelled()
            documents.open(value).use { input ->
                val header = input.readBytesUpToDsfHeader(checkCancelled)
                if (header.isEmpty()) throw LocalAudioFailure(LocalAudioError.READ_FAILED)
                if (header.hasDsfMagic() || DsfPlaybackSupport.isCandidate(observed.name, observed.mime) ||
                    DsfPlaybackSupport.isCandidate(stored.item.metadata.fileName)) {
                    val info = validateDsfHeader(header, observed.size)
                    requireDsfReferenceAccess(documents, observed, info, checkCancelled)
                    requireDsfSourceUnchanged(documents, observed, checkCancelled)
                }
            }
            value
        }
        checkCancelled()
        uri
    } catch (error: CancellationException) { throw error }
    catch (error: LocalAudioFailure) { throw error }
    catch (_: FileNotFoundException) { throw LocalAudioFailure(LocalAudioError.NOT_FOUND) }
    catch (_: SecurityException) {
        throw LocalAudioFailure(if (stored.source.mode == LocalImportMode.REFERENCE)
            LocalAudioError.PERMISSION_REQUIRED else LocalAudioError.READ_FAILED)
    }
    catch (_: IOException) { throw LocalAudioFailure(LocalAudioError.READ_FAILED) }
    catch (_: Exception) { throw LocalAudioFailure(LocalAudioError.READ_FAILED) }

    private fun validateDsfHeader(bytes: ByteArray, size: Long?): DsfFileInfo {
        if (!documents.dsfPlaybackEnabled) throw LocalAudioFailure(LocalAudioError.UNSUPPORTED_AUDIO)
        val info = try { DsfFileParser.parse(bytes, size) }
            catch (_: DsfParseFailure) { throw LocalAudioFailure(LocalAudioError.UNSUPPORTED_AUDIO) }
        if (!DsfPlaybackSupport.supports(info)) throw LocalAudioFailure(LocalAudioError.UNSUPPORTED_AUDIO)
        return info
    }
}

private fun ByteArray.hasDsfMagic(): Boolean = size >= 4 && this[0] == 68.toByte() && this[1] == 83.toByte() &&
    this[2] == 68.toByte() && this[3] == 32.toByte()

private fun java.io.InputStream.readBytesUpToDsfHeader(checkCancelled: () -> Unit): ByteArray {
    val bytes = ByteArray(DsfFileParser.HEADER_SIZE)
    checkCancelled()
    val first = read()
    if (first < 0) return byteArrayOf()
    if (first != 68) return byteArrayOf(first.toByte())
    bytes[0] = first.toByte()
    var count = 1
    while (count < bytes.size) {
        if (count >= 4 && !bytes.hasDsfMagic()) break
        checkCancelled()
        val next = read(bytes, count, (if (count < 4) 4 else bytes.size) - count)
        if (next < 0) break
        if (next == 0) {
            val one = read()
            if (one < 0) break
            bytes[count++] = one.toByte()
        } else count += next
    }
    return bytes.copyOf(count)
}
