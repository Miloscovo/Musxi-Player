package tech.musxi.player.localaudio

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import tech.musxi.core.localaudio.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ShadowMediaMetadataRetriever::class])
class LocalVideoDocumentsTest {
    private lateinit var documents: AndroidLocalAudioDocuments
    private lateinit var app: Application
    private val tree = Uri.parse("content://local-video-test/tree/root")

    @Before fun setup() {
        ShadowMediaMetadataRetriever.reset()
        ShadowContentResolver.registerProviderInternal("local-video-test", MediaProvider())
        app = RuntimeEnvironment.getApplication()
        documents = AndroidLocalAudioDocuments(app)
    }

    @Test fun pickerRequestsAudioAndVideoWithoutChangingAllFileTypesBehaviour() {
        val picker = LocalAudioPickers.files()
        assertEquals("*/*", picker.type)
        assertEquals(listOf("audio/*", "video/*"),
            picker.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
        assertTrue(picker.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        val all = LocalAudioPickers.files(showAllFileTypes = true, allowMultiple = false)
        assertEquals("*/*", all.type)
        assertNull(all.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertFalse(all.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, true))
    }

    @Test fun directoryAcceptsVideoCandidatesAndStillSkipsImagesAndText() {
        val names = documents.children(tree, false).map { it.name }
        assertEquals(listOf("clip.mkv", "movie.mp4", "song.mp3"), names)
    }

    @Test fun actualVideoTrackMarksTheEntryAsVideo() {
        val uri = document("movie.mp4")
        tag(uri, MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO, "yes")
        tag(uri, MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO, "yes")
        val metadata = documents.metadata(documentOf(uri, "movie.mp4", "video/mp4"))
        assertEquals(LocalMediaKind.VIDEO, metadata.kind)
        assertEquals("movie.mp4", metadata.fileName)
    }

    @Test fun videoTrackInsideAnAudioMimeIsDetectedFromTheFileItself() {
        val uri = document("recording.m4a")
        tag(uri, MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO, "yes")
        tag(uri, MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO, "yes")
        assertEquals(LocalMediaKind.VIDEO, documents.metadata(documentOf(uri, "recording.m4a", "audio/mp4")).kind)
    }

    @Test fun videoWithoutSoundTrackIsRejected() {
        val uri = document("silent.mp4")
        tag(uri, MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO, "yes")
        assertEquals(LocalAudioError.UNSUPPORTED_AUDIO, assertThrows(LocalAudioFailure::class.java) {
            documents.metadata(documentOf(uri, "silent.mp4", "video/mp4"))
        }.error)
    }

    @Test fun videoMimeAndVideoExtensionWithoutSoundTrackAreRejected() {
        val mimeOnly = document("clip")
        tag(mimeOnly, MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO, "no")
        assertEquals(LocalAudioError.UNSUPPORTED_AUDIO, assertThrows(LocalAudioFailure::class.java) {
            documents.metadata(documentOf(mimeOnly, "clip", "video/x-matroska"))
        }.error)
        val extensionOnly = document("clip.mkv")
        assertEquals(LocalAudioError.UNSUPPORTED_AUDIO, assertThrows(LocalAudioFailure::class.java) {
            documents.metadata(documentOf(extensionOnly, "clip.mkv", "application/octet-stream"))
        }.error)
    }

    @Test fun unreadableVideoMetadataIsAReadFailureInsteadOfAnImportFallback() {
        val uri = document("broken.mp4")
        ShadowMediaMetadataRetriever.addException(DataSource.toDataSource(uri.toString()), RuntimeException("no metadata"))
        assertEquals(LocalAudioError.READ_FAILED, assertThrows(LocalAudioFailure::class.java) {
            documents.metadata(documentOf(uri, "broken.mp4", "video/mp4"))
        }.error)
    }

    @Test fun audioOnlyFilesKeepTheTolerantMetadataFallback() {
        val uri = document("song.mp3")
        ShadowMediaMetadataRetriever.addException(DataSource.toDataSource(uri.toString()), RuntimeException("no tags"))
        val metadata = documents.metadata(documentOf(uri, "song.mp3", "audio/mpeg"))
        assertEquals(LocalMediaKind.AUDIO, metadata.kind)
        assertNull(metadata.title)
    }

    @Test fun imagesAreStillRejectedBeforeAnyMetadataRead() {
        assertEquals(LocalAudioError.UNSUPPORTED_AUDIO, assertThrows(LocalAudioFailure::class.java) {
            documents.metadata(documentOf(document("cover.jpg"), "cover.jpg", "image/jpeg"))
        }.error)
    }

    private fun document(id: String): Uri = Uri.parse("content://local-video-test/document/$id")

    private fun documentOf(uri: Uri, name: String, mime: String) = LocalAudioDocument(uri, name, mime, 1024)

    private fun tag(uri: Uri, key: Int, value: String) {
        ShadowMediaMetadataRetriever.addMetadata(DataSource.toDataSource(uri.toString()), key, value)
    }

    class MediaProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "video/mp4"

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            val columns = projection!!.map { it }.toTypedArray()
            // AbstractCursor returns a null Bundle; the scan requires a non-null extras bundle.
            val cursor = object : MatrixCursor(columns) {
                override fun getExtras() = android.os.Bundle()
            }
            fun row(id: String, name: String, mime: String, size: Long) {
                val values: List<Any?> = columns.map { column -> when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                    OpenableColumns.DISPLAY_NAME -> name
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> mime
                    OpenableColumns.SIZE -> size
                    else -> null
                } }
                cursor.addRow(values.toTypedArray())
            }
            if (uri.toString().endsWith("/children")) {
                row("v1", "movie.mp4", "video/mp4", 1024)
                row("v2", "clip.mkv", "application/octet-stream", 1024)
                row("a1", "song.mp3", "audio/mpeg", 1024)
                row("img", "cover.jpg", "image/jpeg", 1024)
                row("txt", "notes.txt", "text/plain", 1024)
            } else {
                row("a1", "song.mp3", "audio/mpeg", 1024)
            }
            return cursor
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
