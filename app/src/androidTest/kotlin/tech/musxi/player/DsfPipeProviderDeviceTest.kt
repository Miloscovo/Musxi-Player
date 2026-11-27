package tech.musxi.player

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
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

/** Real Android provider/pipe + production repository, with an isolated library and preferences. */
class DsfPipeProviderDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext
    private val picker = Class.forName("tech.musxi.player.LocalAudioPickerValidationActivity")
    private val companion get() = picker.getDeclaredField("Companion").get(null)
    private val provider = Class.forName("tech.musxi.player.DsfPipeValidationProvider")
    private fun main(action: () -> Unit) = instrument.runOnMainSync(action)

    @Test fun authorizedPipeImport(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowDsfPipeProvider") == "true")
        val marker = File(app.filesDir, "dsf-provider-20261116-marker.json")
        check(JSONObject(marker.readText()).getString("cycle") == "dsf-provider-device-20261116")
        val fixture = File(app.filesDir, "dsf-provider-20261116/sample.dsf")
        check(fixture.isFile)
        val report = JSONObject().put("startedAtMs", System.currentTimeMillis()).put("fixture", "original_synthetic_dsd64_45s")
            .put("realCloudProviderAcceptance", false).put("playbackAcceptance", false)
        val beforeGrants = grants()
        val root = File(app.filesDir, "dsf-provider-20261116/library")
        check(!root.exists())
        val dbName = "dsf-provider-20261116.db"
        check(!app.getDatabasePath(dbName).exists())
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("dsf-provider-20261116-$name", mode)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val docs = AndroidLocalAudioDocuments(isolated) { true }
        val store = SqliteLocalAudioStore(app, dbName)
        val repository = AppLocalAudioRepository(store, LocalAudioFiles(root), docs, scope)
        try {
            waitFor(10_000) { repository.state.value.ready }
            val folder = await<LocalAudioFolder> { repository.createFolder("管道隔离验收", it) }
            val selected = pick("normal")
            app.contentResolver.openFileDescriptor(selected.first, "r")!!.use { descriptor ->
                try { Os.lseek(descriptor.fileDescriptor, 1, OsConstants.SEEK_SET); fail("Expected actual nonseekable pipe") }
                catch (error: android.system.ErrnoException) { assertEquals(OsConstants.ESPIPE, error.errno) }
            }
            report.put("androidPipeEsPipeConfirmed", true)
            val preview = await<DsfPreviewInfo> { repository.dsfPreview.preview(selected.first, it) }
            assertEquals(64, preview.dsdMultiplier); assertFalse(preview.playbackSupported)
            val failed = await<List<LocalAudioImportResult>> {
                repository.importFiles(folder.id, listOf(selected.first), LocalImportMode.REFERENCE, selected.second, it)
            }.single()
            assertEquals(LocalAudioError.READ_FAILED, failed.error)
            assertEquals(LocalAudioRecoveryAction.COPY_REQUIRED, failed.recoveryAction)
            assertTrue(repository.state.value.items.isEmpty()); assertEquals(beforeGrants, grants())
            report.put("referenceRejectedWithCopyRequiredAndNoGrantResidue", true)
            val title = "Musxi SYNTHETIC DSD64 - stereo check 45s"
            for (name in listOf("normal", "unknown")) {
                val picked = pick(name)
                val row = await<List<LocalAudioImportResult>> {
                    repository.importFiles(folder.id, listOf(picked.first), LocalImportMode.COPY, picked.second, it)
                }.single()
                assertNull(row.error)
                val id = requireNotNull(row.audioId)
                assertEquals(title, repository.state.value.items.single().metadata.title)
                val uri = repository.resolveForPlayback(id).readUri()
                assertEquals("file", uri.scheme)
                assertEquals(hash(fixture), hash(File(requireNotNull(uri.path))))
                assertEquals(beforeGrants, grants())
                await<Unit> { repository.deleteAudio(id, it) }
                assertTrue(repository.state.value.items.isEmpty()); assertTrue(files(root).isEmpty())
                report.put("${name}SizeCopyMatchesWholeFixtureAndTailTags", true)
            }
            val slow = pick("slow")
            val done = CompletableFuture<Unit>()
            main { repository.importFiles(folder.id, listOf(slow.first), LocalImportMode.COPY, slow.second)
                .invokeOnCompletion { done.complete(Unit) } }
            var observed = 0L
            waitFor(15_000) {
                val state = repository.imports.value
                if (state.running && state.copiedBytes > 0) {
                    observed = state.copiedBytes
                    main { repository.cancelImport() }; true
                } else { check(!done.isDone) { "No in-flight cancellation observed" }; false }
            }
            done.get(15, TimeUnit.SECONDS)
            assertTrue(repository.imports.value.cancelled)
            assertTrue(repository.state.value.items.isEmpty()); assertTrue(files(root).isEmpty())
            report.put("cancelledPipeCopyBytes", observed).put("cancelledWithoutItemOrFileResidue", true)
            val truncated = pick("truncated")
            val bad = await<List<LocalAudioImportResult>> {
                repository.importFiles(folder.id, listOf(truncated.first), LocalImportMode.COPY, truncated.second, it)
            }.single()
            assertEquals(LocalAudioError.SOURCE_CHANGED, bad.error)
            assertTrue(repository.state.value.items.isEmpty()); assertTrue(files(root).isEmpty())
            report.put("unknownSizeTruncatedCopyRejectedWithoutResidue", true)
            await<Unit> { repository.deleteFolder(folder.id, it) }
            waitFor(5_000) { provider.getMethod("activeWriters").invoke(null) == 0 }
            assertEquals(beforeGrants, grants())
            report.put("pipeWritersExited", true).put("persistedGrantsPreserved", true).put("result", "passed")
        } catch (error: Throwable) {
            report.put("result", "failed").put("failureType", error.javaClass.simpleName)
                .put("message", error.message?.take(400))
            throw error
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            docs.releaseUnused(emptySet())
            store.close()
            main { companion.javaClass.getMethod("finishSelection").invoke(companion) }
            report.put("finishedAtMs", System.currentTimeMillis())
            File(app.filesDir, "dsf-provider-20261116-results.json").writeText(report.toString(2))
        }
    }

    private fun hash(file: File) = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65536)
        while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun files(root: File) = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toSet()
    private fun grants() = app.contentResolver.persistedUriPermissions.map { "${it.uri}:${it.isReadPermission}:${it.isWritePermission}" }.toSet()
    private fun pick(id: String): Pair<Uri, Int> {
        instrument.uiAutomation.serviceInfo = instrument.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        main { companion.javaClass.getMethod("finishSelection").invoke(companion) }
        Thread.sleep(300)
        main {
            companion.javaClass.getMethod("clearSelection").invoke(companion)
            app.startActivity(Intent(app, picker).putExtra("operation", "dsf_pipe_files").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        fun selected() = companion.javaClass.getMethod("getSelection").invoke(companion)
        waitFor(15_000) {
            val current = nodes()
            if (current.none { it.text?.toString() == "pipe-$id.dsf" }) {
                current.firstOrNull { it.contentDescription?.toString() == "显示根目录" }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                nodes().firstOrNull { it.text?.toString() == "Musxi DSF 管道验收" }?.let { node ->
                    var parent = node
                    repeat(6) { if (!parent.isClickable) parent.parent?.let { parent = it } }
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            nodes().any { it.text?.toString() == "pipe-$id.dsf" }
        }
        var target = nodes().first { it.text?.toString() == "pipe-$id.dsf" }
        repeat(6) { if (!target.isClickable) target.parent?.let { target = it } }
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitFor(12_000) {
            if (selected() == null) nodes().firstOrNull { it.isEnabled && it.text?.toString() in setOf("打开", "选择", "确定", "OPEN", "SELECT") }
                ?.let { node -> var button = node; repeat(6) { if (!button.isClickable) button.parent?.let { button = it } }; button.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            selected() != null
        }
        val selection = requireNotNull(selected())
        check(selection.javaClass.getMethod("getAccepted").invoke(selection) as Boolean)
        @Suppress("UNCHECKED_CAST")
        val uri = (selection.javaClass.getMethod("getUris").invoke(selection) as List<Uri>).single()
        check(uri.authority == "${app.packageName}.dsf.validation.documents")
        return uri to (selection.javaClass.getMethod("getFlags").invoke(selection) as Int)
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
        while (!condition()) { check(SystemClock.elapsedRealtime() < until) { "Pipe validation timeout" }; Thread.sleep(100) }
    }
    private fun <T> await(command: ((Result<T>) -> Unit) -> Unit): T {
        val result = CompletableFuture<Result<T>>()
        main { command { result.complete(it) } }
        return result.get(120, TimeUnit.SECONDS).getOrThrow()
    }
}
