package tech.musxi.player.localaudio

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
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
import tech.musxi.core.localaudio.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidLocalAudioDocumentsTest {
    private lateinit var provider: AudioProvider
    private lateinit var documents: AndroidLocalAudioDocuments
    private val tree = Uri.parse("content://local-audio-test/tree/root")

    @Before fun setup() {
        provider = AudioProvider()
        ShadowContentResolver.registerProviderInternal("local-audio-test", provider)
        documents = AndroidLocalAudioDocuments(RuntimeEnvironment.getApplication())
    }

    @Test fun directoryUsesGrantedTreeAndSortsByNamesRatherThanProviderIds() {
        val result = documents.children(tree, false)
        assertEquals(listOf("1.opus", "2.mp3", "10.m4b"), result.map { it.name })
        assertTrue(result.all { it.uri.toString().contains("/tree/root/document/") })
        assertFalse(provider.queries.any { it.contains("sub/children") })
    }

    @Test fun recursionIsExplicitAndSkipsImagesAndText() {
        val result = documents.children(tree, true)
        assertEquals(listOf("1.opus", "2.mp3", "3.wav", "10.m4b"), result.map { it.name })
        assertTrue(provider.queries.any { it.contains("sub/children") })
    }

    @Test fun fileSelectionReadsNameAndSizeAndRejectsNonAudioMetadata() {
        val audio = documents.describe(Uri.parse("content://local-audio-test/document/a"))
        assertEquals("2.mp3", audio.name)
        assertEquals(123L, audio.size)
        val failure = assertThrows(LocalAudioFailure::class.java) {
            documents.metadata(LocalAudioDocument(Uri.parse("content://local-audio-test/document/img"), "image.jpg", "image/jpeg", 30))
        }
        assertEquals(LocalAudioError.UNSUPPORTED_AUDIO, failure.error)
    }

    @Test fun missingDirectoryResponseFailsRatherThanRepresentingAnEmptyDirectory() {
        provider.fail = true
        val error = assertThrows(LocalAudioFailure::class.java) { documents.children(tree, false) }
        assertEquals(LocalAudioError.READ_FAILED, error.error)
    }

    @Test fun revokedPermissionAndUnpersistableSelectionHaveSpecificErrors() {
        provider.denied = true
        assertEquals(LocalAudioError.PERMISSION_REQUIRED,
            assertThrows(LocalAudioFailure::class.java) { documents.describe(Uri.parse("content://local-audio-test/document/a")) }.error)
        assertEquals(LocalAudioError.PERMISSION_REQUIRED,
            assertThrows(LocalAudioFailure::class.java) { documents.acquireRead(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.error)
    }

    @Test fun documentIdentityIgnoresWhichTreeGrantedAccessAndPickersOnlyAskForRead() {
        val one = Uri.parse("content://local-audio-test/tree/root/document/a")
        val two = Uri.parse("content://local-audio-test/tree/other/document/a")
        assertEquals(AndroidLocalAudioDocuments.identity(one), AndroidLocalAudioDocuments.identity(two))
        assertEquals("*/*", LocalAudioPickers.files().type)
        assertEquals(listOf("audio/*", "video/*"),
            LocalAudioPickers.files().getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
        assertEquals("*/*", LocalAudioPickers.files(true).type)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, LocalAudioPickers.directory().action)
        assertEquals(0, LocalAudioPickers.directory().flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test fun partialCloudListingDoesNotBecomeAnEmptySnapshot() {
        provider.loading = true
        assertEquals(LocalAudioError.READ_FAILED,
            assertThrows(LocalAudioFailure::class.java) { documents.children(tree, false) }.error)
    }

    @Test fun relinkPickerCanRequireOneFileWithoutChangingExistingPickerArguments() {
        val default = LocalAudioPickers.files()
        assertTrue(default.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        val single = LocalAudioPickers.files(allowMultiple = false)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, single.action)
        assertEquals("*/*", single.type)
        assertFalse(single.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, true))
        assertEquals(default.flags, single.flags)
        assertEquals(0, single.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val allTypes = LocalAudioPickers.files(showAllFileTypes = true, allowMultiple = false)
        assertEquals("*/*", allTypes.type)
        assertFalse(allTypes.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, true))
        // The existing positional argument still means showAllFileTypes.
        assertEquals("*/*", LocalAudioPickers.files(true).type)
    }

    @Test fun coverPickerSelectsOneImageWithAOneShotReadGrant() {
        val picker = LocalAudioPickers.image()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.action)
        assertTrue(picker.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("image/*", picker.type)
        assertEquals(0, picker.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, picker.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        assertEquals(0, picker.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
            picker.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertFalse("A cover is exactly one picture", picker.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        // The audio/video picker keeps accepting media only, so a cover cannot be imported as audio.
        assertEquals(listOf("audio/*", "video/*"),
            LocalAudioPickers.files().getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
    }

    @Test fun modificationHintsAreOptionalAndReadWhenAvailable() {
        provider.modifiedAtMs = 1234L
        assertEquals(1234L, documents.describe(Uri.parse("content://local-audio-test/document/a")).modifiedAtMs)
        assertTrue(documents.children(tree, false).all { it.modifiedAtMs == 1234L })
        provider.modifiedAtMs = 0
        assertNull(documents.describe(Uri.parse("content://local-audio-test/document/a")).modifiedAtMs)
    }

    @Test fun providersWithoutModificationColumnUseTheOriginalProjection() {
        provider.rejectModificationColumn = true
        val result = documents.describe(Uri.parse("content://local-audio-test/document/a"))
        assertEquals("2.mp3", result.name)
        assertEquals(123L, result.size)
        assertNull(result.modifiedAtMs)
        assertEquals(3, documents.children(tree, false).size)
    }

    @Test fun missingQueryAndEmptyCursorAreReportedAsDistinctFailures() {
        provider.fail = true
        assertEquals(LocalAudioError.READ_FAILED,
            assertThrows(LocalAudioFailure::class.java) { documents.describe(Uri.parse("content://local-audio-test/document/a")) }.error)
        provider.fail = false
        provider.empty = true
        assertEquals(LocalAudioError.NOT_FOUND,
            assertThrows(LocalAudioFailure::class.java) { documents.describe(Uri.parse("content://local-audio-test/document/a")) }.error)
    }

    @Test fun temporaryProviderStatesAreReadFailuresNotMissingFiles() {
        provider.loading = true
        assertEquals(LocalAudioError.READ_FAILED,
            assertThrows(LocalAudioFailure::class.java) { documents.describe(Uri.parse("content://local-audio-test/document/a")) }.error)
        provider.loading = false
        provider.error = "Provider unavailable"
        assertEquals(LocalAudioError.READ_FAILED,
            assertThrows(LocalAudioFailure::class.java) { documents.describe(Uri.parse("content://local-audio-test/document/a")) }.error)
    }

    class AudioProvider : ContentProvider() {
        var fail = false
        var denied = false
        var loading = false
        var empty = false
        var error: String? = null
        var modifiedAtMs = 0L
        var rejectModificationColumn = false
        val queries = mutableListOf<String>()
        override fun onCreate() = true
        override fun getType(uri: Uri) = "audio/mpeg"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            if (denied) throw SecurityException("Test permission denied")
            if (fail) return null
            if (rejectModificationColumn && projection?.contains(DocumentsContract.Document.COLUMN_LAST_MODIFIED) == true) {
                throw IllegalArgumentException("Optional column not supported")
            }
            queries += uri.toString()
            val columns = projection!!.map { it }.toTypedArray()
            val cursor = object : MatrixCursor(columns) {
                override fun getExtras() = android.os.Bundle().apply {
                    putBoolean(DocumentsContract.EXTRA_LOADING, loading)
                    putString(DocumentsContract.EXTRA_ERROR, error)
                }
            }
            fun row(id: String, name: String, mime: String, size: Long) {
                val values: List<Any?> = columns.map { column -> when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                    OpenableColumns.DISPLAY_NAME -> name
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> mime
                    OpenableColumns.SIZE -> size
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> modifiedAtMs
                    else -> null
                } }
                cursor.addRow(values.toTypedArray())
            }
            if (uri.toString().endsWith("/children")) {
                if (uri.toString().contains("/sub/children")) row("d", "3.wav", "audio/wav", 10)
                else {
                    row("z", "10.m4b", "audio/mp4", 123)
                    row("a", "2.mp3", "audio/mpeg", 123)
                    row("q", "1.opus", "application/octet-stream", 123)
                    row("sub", "子目录", DocumentsContract.Document.MIME_TYPE_DIR, 0)
                    row("img", "cover.jpg", "image/jpeg", 123)
                    row("txt", "说明.txt", "text/plain", 123)
                }
            } else if (!empty) row("a", "2.mp3", "audio/mpeg", 123)
            return cursor
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
