package tech.musxi.player.localaudio

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.InputStream
import java.io.FileNotFoundException
import tech.musxi.core.localaudio.*

/** Short-lived backend input, never part of published library/import state. */
internal class LocalAudioDocument(
    val uri: Uri,
    val name: String,
    val mime: String?,
    val size: Long?,
    val readError: LocalAudioError? = null,
    val modifiedAtMs: Long? = null,
) {
    override fun toString(): String = "LocalAudioDocument(redacted)"
}

internal interface LocalAudioDocuments {
    fun describe(uri: Uri): LocalAudioDocument
    fun metadata(document: LocalAudioDocument): LocalAudioMetadata
    fun open(uri: Uri): InputStream
    fun acquireRead(grant: Uri, flags: Int)
    fun releaseUnused(retained: Set<String>)
    fun children(tree: Uri, recursive: Boolean, checkCancelled: () -> Unit = {}): List<LocalAudioDocument>
}

/** SAF and platform metadata APIs only; no storage permission or third-party tag parser. */
internal class AndroidLocalAudioDocuments(context: Context) : LocalAudioDocuments {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val grants = app.getSharedPreferences("local-audio-grants-v1", Context.MODE_PRIVATE)

    override fun describe(uri: Uri): LocalAudioDocument = readable {
        requireContent(uri)
        val cursor = queryMetadata(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            ?: throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        cursor.use {
            // A partial cursor from a cloud provider is temporary, not a missing file.
            val extras: android.os.Bundle? = cursor.extras
            if (extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true ||
                !extras?.getString(DocumentsContract.EXTRA_ERROR).isNullOrBlank()) {
                throw LocalAudioFailure(LocalAudioError.READ_FAILED)
            }
            if (!cursor.moveToFirst()) throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = LocalAudioNames.clean(if (nameIndex >= 0) cursor.getString(nameIndex) else null) ?: "未命名音频"
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex).takeIf { it >= 0 } else null
            val modified = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            return@use LocalAudioDocument(uri, name, resolver.getType(uri), size,
                modifiedAtMs = if (modified >= 0 && !cursor.isNull(modified)) cursor.getLong(modified).takeIf { it > 0 } else null)
        }
    }

    override fun metadata(document: LocalAudioDocument): LocalAudioMetadata = readable {
        val extension = document.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        val videoMime = document.mime?.startsWith("video/") == true
        val videoExtension = extension in VIDEO_EXTENSIONS
        val audioMime = document.mime?.startsWith("audio/") == true
        if (!audioMime && !videoMime && extension !in MEDIA_EXTENSIONS) {
            throw LocalAudioFailure(LocalAudioError.UNSUPPORTED_AUDIO)
        }
        // Audio-only import still needs one sound track; the container itself may be a video.
        val videoCandidate = videoMime || videoExtension
        if (document.size == 0L) throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        if (document.size != null && document.size > LocalAudioLimits.MAX_FILE_BYTES) throw LocalAudioFailure(LocalAudioError.TOO_LARGE)
        // A missing optional tag is not an invalid recording. Playback performs final decoder validation.
        var title: String? = null
        var creator: String? = null
        var duration: Long? = null
        var videoTrack = false
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(app, document.uri)
            videoTrack = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            if ((videoCandidate || videoTrack) &&
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes") {
                throw LocalAudioFailure(LocalAudioError.UNSUPPORTED_AUDIO)
            }
            title = LocalAudioNames.clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
            creator = LocalAudioNames.clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?.takeIf { it in 0..LocalAudioLimits.MAX_POSITION_MS }
        } catch (error: LocalAudioFailure) { throw error }
        catch (error: SecurityException) { throw error }
        catch (_: RuntimeException) {
            // Media3 can support audio a platform metadata reader cannot inspect, but an unreadable
            // video container must not silently import as a soundless entry.
            if (videoCandidate || videoTrack) throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        }
        finally { runCatching { retriever.release() } }
        LocalAudioMetadata(document.name, title, creator, duration, document.size, document.modifiedAtMs,
            kind = if (videoTrack) LocalMediaKind.VIDEO else LocalMediaKind.AUDIO)
    }

    override fun open(uri: Uri): InputStream = readable {
        requireContent(uri)
        resolver.openInputStream(uri) ?: throw LocalAudioFailure(LocalAudioError.READ_FAILED)
    }

    override fun acquireRead(grant: Uri, flags: Int) {
        requireContent(grant)
        if (resolver.persistedUriPermissions.any { it.uri == grant && it.isReadPermission }) return
        val needed = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        if (flags and needed != needed) throw LocalAudioFailure(LocalAudioError.PERMISSION_REQUIRED)
        try {
            resolver.takePersistableUriPermission(grant, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val owned = grants.getStringSet("owned", emptySet()).orEmpty().toMutableSet().apply { add(grant.toString()) }
            if (!grants.edit().putStringSet("owned", owned).commit()) {
                resolver.releasePersistableUriPermission(grant, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
            }
        } catch (_: SecurityException) { throw LocalAudioFailure(LocalAudioError.PERMISSION_REQUIRED) }
    }

    override fun releaseUnused(retained: Set<String>) {
        val owned = grants.getStringSet("owned", emptySet()).orEmpty().toMutableSet()
        for (value in owned.toList()) {
            if (value in retained) continue
            try {
                resolver.releasePersistableUriPermission(Uri.parse(value), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                owned.remove(value)
            } catch (_: SecurityException) {
                if (resolver.persistedUriPermissions.none { it.uri.toString() == value && it.isReadPermission }) owned.remove(value)
            }
        }
        if (!grants.edit().putStringSet("owned", owned).commit()) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
    }

    override fun children(tree: Uri, recursive: Boolean, checkCancelled: () -> Unit): List<LocalAudioDocument> = readable {
        requireContent(tree)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val pending = ArrayDeque<String>().apply { add(rootId) }
        val visited = mutableSetOf<String>()
        val result = mutableListOf<LocalAudioDocument>()
        var inspected = 0
        while (pending.isNotEmpty()) {
            checkCancelled()
            val parentId = pending.removeFirst()
            if (!visited.add(parentId)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            queryMetadata(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            ))?.use { cursor ->
                // Cloud providers can return a partial cursor while loading. It is not a full scan.
                if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
                    !cursor.extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrBlank()) {
                    throw LocalAudioFailure(LocalAudioError.READ_FAILED)
                }
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    checkCancelled()
                    if (++inspected > LocalAudioLimits.MAX_ITEMS) throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
                    val id = cursor.getString(0)
                    val name = LocalAudioNames.clean(cursor.getString(1)) ?: "未命名音频"
                    val mime = cursor.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (recursive) pending.add(id)
                    } else if (mime?.startsWith("audio/") == true || mime?.startsWith("video/") == true ||
                        name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in MEDIA_EXTENSIONS) {
                        result += LocalAudioDocument(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, mime,
                            if (cursor.isNull(3)) null else cursor.getLong(3).takeIf { it >= 0 },
                            modifiedAtMs = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex))
                                cursor.getLong(modifiedIndex).takeIf { it > 0 } else null)
                        if (result.size > LocalAudioLimits.MAX_BATCH) throw LocalAudioFailure(LocalAudioError.LIMIT_REACHED)
                    }
                }
            } ?: throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        }
        result.sortedWith { a, b -> LocalAudioNames.compare(a.name, b.name) }
    }

    private fun queryMetadata(uri: Uri, columns: Array<String>): android.database.Cursor? = try {
        resolver.query(uri, columns + DocumentsContract.Document.COLUMN_LAST_MODIFIED, null, null, null)
    } catch (_: IllegalArgumentException) {
        // Non-Documents providers may expose only the OpenableColumns contract.
        resolver.query(uri, columns, null, null, null)
    }

    companion object {
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "amr", "3ga", "aif", "aiff")
        // Containers Media3 (ExoPlayer) can demux for their audio track.
        private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "mkv", "webm", "3gp")
        private val MEDIA_EXTENSIONS = AUDIO_EXTENSIONS + VIDEO_EXTENSIONS
        fun identity(uri: Uri): String = try {
            "${uri.authority}:${DocumentsContract.getDocumentId(uri)}"
        } catch (_: IllegalArgumentException) { uri.normalizeScheme().toString() }
        private fun requireContent(uri: Uri) {
            if (!LocalAudioLimits.isContentUri(uri.toString())) throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        }
        private inline fun <T> readable(action: () -> T): T = try { action() }
        catch (error: java.util.concurrent.CancellationException) { throw error }
        catch (error: LocalAudioFailure) { throw error }
        catch (_: FileNotFoundException) { throw LocalAudioFailure(LocalAudioError.NOT_FOUND) }
        catch (_: SecurityException) { throw LocalAudioFailure(LocalAudioError.PERMISSION_REQUIRED) }
        catch (_: Exception) { throw LocalAudioFailure(LocalAudioError.READ_FAILED) }
    }
}
