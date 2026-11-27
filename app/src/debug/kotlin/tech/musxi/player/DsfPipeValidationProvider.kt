package tech.musxi.player

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** Fixed owned fixture only. Never registered in release; no path or configuration input. */
class DsfPipeValidationProvider : DocumentsProvider() {
    override fun onCreate() = true

    private fun sample(): File {
        val app = requireNotNull(context)
        val marker = File(app.filesDir, "dsf-provider-20261116-marker.json")
        if (!marker.isFile || JSONObject(marker.readText()).optString("cycle") != CYCLE)
            throw FileNotFoundException("Validation not armed")
        return File(app.filesDir, "dsf-provider-20261116/sample.dsf").also {
            if (!it.isFile || it.length() != 31_752_472L) throw FileNotFoundException("Owned fixture absent")
        }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES)
        val cursor = MatrixCursor(columns)
        if (runCatching { sample() }.isSuccess) cursor.addRow(columns.map { name -> when (name) {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID -> "root"
            Root.COLUMN_TITLE -> "Musxi DSF 管道验收"
            Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_IS_CHILD
            Root.COLUMN_MIME_TYPES -> "application/octet-stream"
            else -> null
        } })
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val file = sample()
        checkId(documentId)
        val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS, Document.COLUMN_LAST_MODIFIED)
        return MatrixCursor(columns).apply { addRow(columns.map { name -> when (name) {
            Document.COLUMN_DOCUMENT_ID -> documentId
            Document.COLUMN_DISPLAY_NAME -> if (documentId == "root") "Musxi DSF 管道验收" else "pipe-$documentId.dsf"
            Document.COLUMN_MIME_TYPE -> if (documentId == "root") Document.MIME_TYPE_DIR else "application/octet-stream"
            Document.COLUMN_SIZE -> if (documentId == "root" || documentId in setOf("unknown", "truncated")) null else file.length()
            Document.COLUMN_FLAGS -> 0
            Document.COLUMN_LAST_MODIFIED -> file.lastModified()
            else -> null
        } }) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        if (parentDocumentId != "root") throw FileNotFoundException("Unknown parent")
        val children = arrayOf("normal", "unknown", "slow", "truncated")
        val first = queryDocument(children.first(), projection)
        return MatrixCursor(first.columnNames).apply {
            first.close()
            children.forEach { id -> queryDocument(id, projection).use { child ->
                check(child.moveToFirst())
                addRow(child.columnNames.indices.map { index -> when (child.getType(index)) {
                    Cursor.FIELD_TYPE_INTEGER -> child.getLong(index)
                    Cursor.FIELD_TYPE_STRING -> child.getString(index)
                    else -> null
                } })
            } }
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        parentDocumentId == "root" && documentId in setOf("normal", "unknown", "slow", "truncated")

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        checkId(documentId)
        if (documentId == "root" || mode != "r") throw FileNotFoundException("Read-only file required")
        val file = sample()
        signal?.throwIfCanceled()
        val pipe = ParcelFileDescriptor.createReliablePipe()
        writers.incrementAndGet()
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        var left = file.length() - if (documentId == "truncated") 4096 else 0
                        while (left > 0) {
                            signal?.throwIfCanceled()
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            left -= count
                            if (documentId == "slow") Thread.sleep(8)
                        }
                    }
                }
            } catch (_: IOException) { // A bounded metadata read or cancelled copy closes its reader.
            } catch (_: android.os.OperationCanceledException) {
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                runCatching { pipe[1].close() }
                writers.decrementAndGet()
            }
        }, "musxi-dsf-owned-pipe").apply { isDaemon = true; start() }
        return pipe[0]
    }

    private fun checkId(id: String) {
        if (id !in setOf("root", "normal", "unknown", "slow", "truncated")) throw FileNotFoundException("Unknown fixture")
    }

    companion object {
        const val CYCLE = "dsf-provider-device-20261116"
        private val writers = AtomicInteger()
        @JvmStatic fun activeWriters() = writers.get()
    }
}
