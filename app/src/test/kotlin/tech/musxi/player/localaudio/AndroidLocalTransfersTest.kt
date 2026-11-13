package tech.musxi.player.localaudio

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.MatrixCursor
import android.net.Uri
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
class AndroidLocalTransfersTest {
    private val target = Uri.parse("content://transfer-provider/tree/root/document/new")
    private val flags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private lateinit var provider: Provider
    private lateinit var transfers: AndroidLocalAudioTransfers

    @Before fun setup() {
        provider = Provider()
        ShadowContentResolver.registerProviderInternal("transfer-provider", provider)
        transfers = AndroidLocalAudioTransfers(RuntimeEnvironment.getApplication())
    }

    @Test fun onlyEmptyWritableDocumentIsAccepted() {
        transfers.validateDestination(target, flags, emptySet())
        provider.size = 9L
        assertEquals(LocalAudioError.INVALID_TARGET, assertThrows(LocalAudioFailure::class.java) {
            transfers.validateDestination(target, flags, emptySet())
        }.error)
        provider.size = null
        assertEquals(LocalAudioError.INVALID_TARGET, assertThrows(LocalAudioFailure::class.java) {
            transfers.validateDestination(target, flags, emptySet())
        }.error)
    }

    @Test fun sourceCannotBeOverwrittenThroughAnotherTreeAlias() {
        val sameDocument = "content://transfer-provider/tree/other/document/new"
        assertEquals(LocalAudioError.INVALID_TARGET, assertThrows(LocalAudioFailure::class.java) {
            transfers.validateDestination(target, flags, setOf(sameDocument))
        }.error)
        assertEquals(LocalAudioError.PERMISSION_REQUIRED, assertThrows(LocalAudioFailure::class.java) {
            transfers.validateDestination(target, 0, emptySet())
        }.error)
        assertEquals(LocalAudioError.INVALID_TARGET, assertThrows(LocalAudioFailure::class.java) {
            transfers.validateDestination(Uri.parse("file:///tmp/source"), flags, emptySet())
        }.error)
    }

    @Test fun pickersUseExplicitCreationAndDoNotPersistWritePermission() {
        val export = LocalAudioPickers.exportAudio("音声.opus", "audio/opus")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, export.action)
        assertEquals("音声.opus", export.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals("audio/opus", export.type)
        assertEquals(0, export.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, LocalAudioPickers.restoreOrganization().action)
        assertEquals(0, LocalAudioPickers.restoreOrganization().flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertThrows(IllegalArgumentException::class.java) { LocalAudioPickers.exportAudio("../file.mp3") }
    }

    class Provider : ContentProvider() {
        var size: Long? = 0L
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
            MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply { addRow(arrayOf(size)) }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
