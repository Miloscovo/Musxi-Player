package tech.musxi.player

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import tech.musxi.core.localaudio.*
import tech.musxi.player.localaudio.*

/** Opt-in fixed sample on actual system providers; never touches the user's library or player. */
class DsfActualProviderDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext
    private val picker = Class.forName("tech.musxi.player.LocalAudioPickerValidationActivity")
    private val companion get() = picker.getDeclaredField("Companion").get(null)
    private val base get() = File(app.filesDir, "dsf-actual-20261116")
    private val marker get() = File(app.filesDir, "dsf-actual-20261116-marker.json")
    private fun main(action: () -> Unit) = instrument.runOnMainSync(action)
    private fun record(out: JSONObject) = File(app.filesDir, "dsf-actual-20261116-results.json").writeText(out.toString(2))

    @Test fun actualSystemProviders(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowDsfActualProvider") == "true")
        val owner = JSONObject(marker.readText())
        check(owner.getString("cycle") == "dsf-actual-provider-device-20261116" && !owner.has("mediaUri"))
        val fixture = File(base, "sample.dsf")
        check(fixture.length() == 31_752_472L)
        val originalGrants = grants()
        val report = JSONObject().put("startedAtMs", System.currentTimeMillis()).put("phase", "prepare")
            .put("realCloudProviderAcceptance", false).put("playbackAcceptance", false)
        val rows = JSONArray(); report.put("providers", rows); record(report)
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("dsf-actual-20261116-$name", mode)
        }
        val dbName = "dsf-actual-20261116.db"
        check(!app.getDatabasePath(dbName).exists())
        val root = File(base, "library"); check(!root.exists())
        val docs = AndroidLocalAudioDocuments(isolated) { true }
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var store = SqliteLocalAudioStore(app, dbName)
        var repository = AppLocalAudioRepository(store, LocalAudioFiles(root), docs, scope)
        var mediaUri: Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "musxi-actual-sample.dsf")
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Musxi-Actual-20261116")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            mediaUri = requireNotNull(app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            owner.put("mediaUri", mediaUri.toString()); marker.writeText(owner.toString())
            app.contentResolver.openOutputStream(mediaUri)!!.use { output -> fixture.inputStream().use { it.copyTo(output) } }
            app.contentResolver.update(mediaUri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            check(app.contentResolver.openInputStream(mediaUri)!!.use { hash(it) } == fixture.inputStream().use { hash(it) })
            report.put("ownedMediaStoreSampleHashMatched", true); record(report)
            waitFor(10_000) { repository.state.value.ready }
            val folder = await<LocalAudioFolder> { repository.createFolder("实际提供方隔离验收", it) }
            var referenceId: String? = null
            for (kind in listOf("external", "downloads")) {
                report.put("phase", "pick_$kind"); record(report)
                val selection = pick(kind)
                val uri = selection.first
                val expectedAuthority = if (kind == "external") "com.android.externalstorage.documents" else "com.android.providers.downloads.documents"
                assertEquals(expectedAuthority, uri.authority)
                check(uri.toString() !in originalGrants.map { it.substringBefore('|') })
                val providerInfo = requireNotNull(app.packageManager.resolveContentProvider(expectedAuthority, 0))
                val row = JSONObject().put("kind", kind).put("authority", expectedAuthority).put("package", providerInfo.packageName)
                    .put("flagsRead", selection.second and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                    .put("flagsPersistable", selection.second and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
                rows.put(row); record(report)
                app.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                    assertEquals(100L, Os.lseek(pfd.fileDescriptor, 100, OsConstants.SEEK_SET))
                }
                row.put("randomAccessConfirmed", true)
                val preview = await<DsfPreviewInfo> { repository.dsfPreview.preview(uri, it) }
                assertEquals(64, preview.dsdMultiplier); assertTrue(preview.playbackSupported)
                assertEquals("Musxi SYNTHETIC DSD64 - stereo check 45s", preview.tags?.title)
                if (kind == "external") {
                    val imported = await<List<LocalAudioImportResult>> {
                        repository.importFiles(folder.id, listOf(uri), LocalImportMode.REFERENCE, selection.second, it)
                    }.single()
                    assertNull(imported.error); referenceId = requireNotNull(imported.audioId)
                    check(app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
                    await<Unit> { repository.renameAudio(referenceId, "提供方重关联保留名称", it) }
                    await<Unit> { repository.saveProgress(folder.id, LocalAudioProgress(referenceId, 7000, false, 1), it) }
                    await<LocalAudioBookmark> { repository.addBookmark(referenceId, 8000, "保留书签", it) }
                    // Reopen the actual SQLite/prefs repository, with no flags from the original picker.
                    scope.coroutineContext[Job]!!.cancelAndJoin(); store.close()
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                    store = SqliteLocalAudioStore(app, dbName)
                    repository = AppLocalAudioRepository(store, LocalAudioFiles(root), docs, scope)
                    waitFor(10_000) { repository.state.value.ready }
                    assertEquals(uri, repository.resolveForPlayback(referenceId).readUri())
                    row.put("referenceReadAfterRepositoryReopen", true)
                } else {
                    val id = requireNotNull(referenceId)
                    await<Unit> { repository.relink(id, uri, selection.second, it) }
                    assertEquals(uri, repository.resolveForPlayback(id).readUri())
                    assertEquals("提供方重关联保留名称", repository.state.value.items.single().title)
                    assertEquals(7000L, repository.state.value.progress.single().positionMs)
                    assertEquals(8000L, repository.state.value.bookmarks.single().positionMs)
                    assertEquals(id, repository.state.value.items.single().id)
                    row.put("crossProviderRelinkRetainedIdNameProgressBookmark", true)
                    await<Unit> { repository.deleteAudio(id, it) }
                    assertEquals(originalGrants, grants())
                }
                val copied = await<List<LocalAudioImportResult>> {
                    repository.importFiles(folder.id, listOf(uri), LocalImportMode.COPY, selection.second, it)
                }.single()
                assertNull(copied.error)
                val copiedId = requireNotNull(copied.audioId)
                val copyUri = repository.resolveForPlayback(copiedId).readUri()
                assertEquals("file", copyUri.scheme)
                assertEquals(fixture.inputStream().use { hash(it) }, File(requireNotNull(copyUri.path)).inputStream().use { hash(it) })
                await<Unit> { repository.deleteAudio(copiedId, it) }
                check(root.walkTopDown().none { it.isFile })
                row.put("copyWholeFileHashMatchedAndCollected", true); record(report)
            }
            await<Unit> { repository.deleteFolder(folder.id, it) }
            assertEquals(originalGrants, grants())
            report.put("persistedGrantsPreserved", true).put("result", "passed")
        } catch (error: Throwable) {
            report.put("result", "failed").put("failureType", error.javaClass.simpleName)
                .put("message", error.message?.takeIf { !it.contains("content:") }?.take(300))
            throw error
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            docs.releaseUnused(emptySet()); store.close()
            main { companion.javaClass.getMethod("finishSelection").invoke(companion) }
            mediaUri?.let { report.put("ownedMediaRowDeleted", app.contentResolver.delete(it, null, null) == 1) }
            report.put("finishedAtMs", System.currentTimeMillis()); record(report)
        }
    }

    private fun hash(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65536)
        while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun grants() = app.contentResolver.persistedUriPermissions.map { "${it.uri}|${it.isReadPermission}|${it.isWritePermission}" }.toSet()
    private fun pick(kind: String): Pair<Uri, Int> {
        instrument.uiAutomation.serviceInfo = instrument.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        main { companion.javaClass.getMethod("finishSelection").invoke(companion) }; Thread.sleep(300)
        main {
            companion.javaClass.getMethod("clearSelection").invoke(companion)
            app.startActivity(Intent(app, picker).putExtra("operation", "dsf_actual_$kind").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        fun selected() = companion.javaClass.getMethod("getSelection").invoke(companion)
        waitFor(20_000) {
            val list = nodes()
            if (list.none { it.text?.toString() == "musxi-actual-sample.dsf" })
                list.firstOrNull { it.text?.toString() == "Musxi-Actual-20261116" }?.let(::click)
            nodes().any { it.text?.toString() == "musxi-actual-sample.dsf" }
        }
        click(nodes().first { it.text?.toString() == "musxi-actual-sample.dsf" })
        waitFor(12_000) {
            if (selected() == null) nodes().firstOrNull { it.isEnabled && it.text?.toString() in setOf("打开", "选择", "确定", "OPEN", "SELECT") }?.let(::click)
            selected() != null
        }
        val selection = requireNotNull(selected()); check(selection.javaClass.getMethod("getAccepted").invoke(selection) as Boolean)
        @Suppress("UNCHECKED_CAST")
        return (selection.javaClass.getMethod("getUris").invoke(selection) as List<Uri>).single() to
            (selection.javaClass.getMethod("getFlags").invoke(selection) as Int)
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target = node; repeat(6) { if (!target.isClickable) target.parent?.let { target = it } }
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || node.packageName?.toString() != "com.android.documentsui") return
            add(node); repeat(node.childCount) { walk(node.getChild(it)) }
        }
        instrument.uiAutomation.windows.forEach { walk(it.root) }
    }
    private fun waitFor(ms: Long, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + ms
        while (!condition()) { check(SystemClock.elapsedRealtime() < until) { "Actual provider timeout" }; Thread.sleep(100) }
    }
    private fun <T> await(command: ((Result<T>) -> Unit) -> Unit): T {
        val result = CompletableFuture<Result<T>>(); main { command { result.complete(it) } }
        return result.get(120, TimeUnit.SECONDS).getOrThrow()
    }
}
