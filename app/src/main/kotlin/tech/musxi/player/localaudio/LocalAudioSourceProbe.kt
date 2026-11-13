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
            file.inputStream().use { if (it.read() < 0) throw LocalAudioFailure(LocalAudioError.READ_FAILED) }
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
            documents.open(value).use { if (it.read() < 0) throw LocalAudioFailure(LocalAudioError.READ_FAILED) }
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
}
