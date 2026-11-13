package tech.musxi.player.localaudio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.InputStream
import java.io.OutputStream
import tech.musxi.core.localaudio.*

/** Output is always a freshly created, empty ACTION_CREATE_DOCUMENT result. */
internal interface LocalAudioTransferDocuments {
    fun validateDestination(uri: Uri, flags: Int, protected: Set<String>)
    fun openInput(uri: Uri): InputStream
    fun openOutput(uri: Uri): OutputStream
    fun discardIncomplete(uri: Uri): Boolean
}

internal class AndroidLocalAudioTransfers(context: Context) : LocalAudioTransferDocuments {
    private val resolver = context.applicationContext.contentResolver

    override fun validateDestination(uri: Uri, flags: Int, protected: Set<String>) {
        requireContent(uri)
        if (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0) throw LocalAudioFailure(LocalAudioError.PERMISSION_REQUIRED)
        if ((DocumentsContract.isTreeUri(uri) && !uri.path.orEmpty().contains("/document/")) || uri.toString() in protected ||
            protected.any { AndroidLocalAudioDocuments.identity(Uri.parse(it)) == AndroidLocalAudioDocuments.identity(uri) }) {
            throw LocalAudioFailure(LocalAudioError.INVALID_TARGET)
        }
        // Do not truncate an existing or unverifiably empty file. Some cloud providers cannot
        // report a fresh size: they must be retried with another provider rather than overwritten.
        try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && column >= 0 && !cursor.isNull(column) && cursor.getLong(column) == 0L) return
            }
        } catch (_: SecurityException) { throw LocalAudioFailure(LocalAudioError.PERMISSION_REQUIRED) }
        catch (_: Exception) { throw LocalAudioFailure(LocalAudioError.INVALID_TARGET) }
        throw LocalAudioFailure(LocalAudioError.INVALID_TARGET)
    }

    override fun openInput(uri: Uri): InputStream {
        requireContent(uri)
        return try { resolver.openInputStream(uri) ?: throw LocalAudioFailure(LocalAudioError.READ_FAILED) }
        catch (failure: LocalAudioFailure) { throw failure }
        catch (_: SecurityException) { throw LocalAudioFailure(LocalAudioError.PERMISSION_REQUIRED) }
        catch (_: java.io.IOException) { throw LocalAudioFailure(LocalAudioError.READ_FAILED) }
    }

    override fun openOutput(uri: Uri): OutputStream {
        requireContent(uri)
        return try { resolver.openOutputStream(uri, "wt") ?: throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED) }
        catch (failure: LocalAudioFailure) { throw failure }
        catch (_: SecurityException) { throw LocalAudioFailure(LocalAudioError.PERMISSION_REQUIRED) }
        catch (_: java.io.IOException) { throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED) }
    }

    override fun discardIncomplete(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (_: Exception) { false }

    private fun requireContent(uri: Uri) {
        if (!LocalAudioLimits.isContentUri(uri.toString())) throw LocalAudioFailure(LocalAudioError.INVALID_TARGET)
    }
}
