package tech.musxi.player

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import tech.musxi.core.localaudio.*

/** Opt-in device check with real SAF and the normal playback engine; requires staged fixtures. */
class LocalAudioVersionsLifecycleDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as MusxiApplication
    private val repo get() = app.localAudio
    private val state get() = repo.state.value
    private val sourceFolder = "Musxi 20261118 validation source"
    private val targetFolder = "Musxi 20261118 validation target"
    private val directory = "Musxi-Expansion-20261114" // Existing debug picker route.
    private val evidence get() = File(app.filesDir, "local-versions-lifecycle-20261118.json")

    @Test fun realProviderAndPlayback() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowLocalVersionsLifecycle") == "true")
        val out = JSONObject()
        var baseline: LocalAudioLibraryState? = null
        try {
            main { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            waitFor("library and player") { state.ready && app.playback.state.value.connected }
            check(app.playback.localQueueState.value.items.isEmpty() && !app.playback.state.value.playWhenReady)
            check(!FileLocalAudioPlaybackJournalStore(File(app.filesDir, "local-audio-playback-v1")).read().active)
            check(state.folders.none { it.name == sourceFolder || it.name == targetFolder })
            baseline = state
            val from = await<LocalAudioFolder> { repo.createFolder(sourceFolder, it) }.id
            val to = await<LocalAudioFolder> { repo.createFolder(targetFolder, it) }.id
            val (tree, flags) = pickTree()
            check(tree.authority == "com.android.externalstorage.documents")
            check(DocumentsContract.getTreeDocumentId(tree) == "primary:Download/$directory")
            val link = await<LocalAudioDirectoryLink> { repo.linkDirectory(from, tree, mode = LocalImportMode.REFERENCE, grantedFlags = flags, result = it) }
            closePicker()
            assertEquals(directory, link.name)
            assertTrue(app.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission })

            val first = refresh(link.id)
            assertEquals(4, first.results.size)
            assertEquals(LocalAudioError.READ_FAILED, first.results.single { it.fileName == "invalid.wav" }.error)
            val ids = ids(first)
            assertEquals(setOf("a.wav", "b.wav", "c.wav"), ids.keys)
            val scanned = state.directories.single { it.id == link.id }
            assertNull(scanned.error)
            assertEquals(1, scanned.lastRefreshFailures)
            assertTrue(repo.imports.value.completed)
            assertEquals(ids, ids(refresh(link.id)))
            out.put("provider", tree.authority).put("directoryName", link.name)
                .put("initialFailureCount", scanned.lastRefreshFailures).put("repeatSameIds", true)

            val cleared = AtomicBoolean(false)
            val cancelled = AtomicBoolean(false)
            val busy = CompletableFuture<Result<LocalAudioDirectoryRefreshResult>>()
            lateinit var cancelledTask: Job
            lateinit var clearedTask: Job
            main {
                cancelledTask = repo.refreshDirectory(link.id) { cancelled.set(true) }
                check(repo.imports.value.running)
                repo.cancelImport()
            }
            waitFor("cancelled refresh") { cancelledTask.isCompleted }
            assertTrue(repo.imports.value.cancelled)
            assertFalse(cancelled.get())
            val lastComplete = state.directories.single { it.id == link.id }
            main {
                clearedTask = repo.refreshDirectory(link.id) { cleared.set(true) }
                val id = repo.imports.value.runId
                repo.clearImport()
                check(repo.imports.value.runId > id)
                // Within this main turn the cancelled job still owns its slot.
                repo.refreshDirectory(link.id) { busy.complete(it) }
            }
            waitFor("cleared refresh") { clearedTask.isCompleted }
            assertEquals(LocalAudioError.LIMIT_REACHED, failure(busy.get(10, TimeUnit.SECONDS)))
            assertFalse(cleared.get())
            assertEquals(LocalAudioImportState(runId = repo.imports.value.runId), repo.imports.value)
            assertEquals(lastComplete, state.directories.single { it.id == link.id })
            out.put("refreshCancelClearBusy", true)

            // Exchange only our staged fixtures. The original library never references this path.
            val path = "/sdcard/Download/$directory"
            shell("mv $path/invalid.wav $path/invalid.fixture")
            shell("mv $path/d.fixture $path/d.wav")
            val next = refresh(link.id)
            val nextIds = ids(next)
            assertEquals(setOf("a.wav", "b.wav", "c.wav", "d.wav"), nextIds.keys)
            assertTrue(ids.all { (name, id) -> nextIds[name] == id })
            assertEquals(0, state.directories.single { it.id == link.id }.lastRefreshFailures)
            assertEquals(0, next.missingCount)
            main { repo.clearImport() }
            out.put("addedCount", 1).put("recoveredFailureCount", 0)

            val selected = nextIds.values.toList()
            val sourceResult = await<List<LocalAudioSourceCheckResult>> { repo.checkSources(selected, state.organizationToken, it) }
            assertEquals(4, sourceResult.size)
            assertTrue(sourceResult.all { it.applied && it.error == null })
            assertTrue(repo.sourceChecks.value.completed)
            val oldCallback = AtomicBoolean(false)
            lateinit var sourceTask: Job
            main {
                sourceTask = repo.checkSources(selected, state.organizationToken) { oldCallback.set(true) }
                val id = repo.sourceChecks.value.runId
                repo.clearSourceCheck()
                check(repo.sourceChecks.value.runId > id)
            }
            waitFor("cleared source task") { sourceTask.isCompleted }
            assertEquals(LocalAudioSourceCheckState(runId = repo.sourceChecks.value.runId), repo.sourceChecks.value)
            assertFalse(oldCallback.get())
            out.put("sourceCheckCompletedAndCleared", true)

            // Revoke only the newly acquired grant and distinguish tree failure from item errors.
            app.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertEquals(LocalAudioError.PERMISSION_REQUIRED, failure(call<LocalAudioDirectoryRefreshResult> { repo.refreshDirectory(link.id, it) }))
            val denied = state.directories.single { it.id == link.id }
            assertEquals(LocalAudioError.PERMISSION_REQUIRED, denied.error)
            assertEquals(0, denied.lastRefreshFailures)
            val (again, newFlags) = pickTree()
            assertEquals(tree, again)
            await<Unit> { repo.reauthorizeDirectory(link.id, again, newFlags, it) }
            closePicker()
            assertEquals(nextIds, ids(refresh(link.id)))
            assertNull(state.directories.single { it.id == link.id }.error)
            out.put("revokedAndReauthorized", true)

            playback(from, to, nextIds, out)
            out.put("result", "passed")
        } catch (error: Throwable) {
            out.put("result", "failed").put("error", error.javaClass.simpleName)
            throw error
        } finally {
            try {
                closePicker()
                baseline?.let { cleanup(it) }
                out.put("ownedCleanup", baseline != null)
            } finally { evidence.writeText(out.toString(2)) }
        }
    }

    private fun playback(from: String, to: String, ids: Map<String, String>, out: JSONObject) {
        val id = ids.getValue("a.wav")
        await<Unit> { app.localAudioPlayback.play(from, id, fromStart = true, callback = it) }
        waitFor("real playback") { app.playback.state.value.let { it.localAudio?.id == id && it.playing && it.positionMs >= 1800 } }
        val frozen = state
        val ordinary = LocalAudioQuery(folderId = from, sort = LocalAudioSort.FILE_NAME, limit = 2)
        val filtered = ordinary.copy(progress = LocalAudioProgressFilter.UNPLAYED)
        val recent = ordinary.copy(sort = LocalAudioSort.LAST_PROGRESS)
        val first = search(ordinary)
        val firstFiltered = search(filtered)
        val firstRecent = search(recent)
        val positions = JSONArray()
        var last = frozen.progress.firstOrNull { it.audioId == id }?.positionMs ?: 0L
        repeat(2) {
            waitFor("natural playback progress ${it + 1}", 25_000) { state.progress.any { p -> p.audioId == id && p.positionMs >= last + 8_000 } }
            val current = state
            last = current.progress.single { p -> p.audioId == id }.positionMs
            assertEquals(frozen.organizationRevision, current.organizationRevision)
            assertTrue(current.progressRevision > frozen.progressRevision)
            assertTrue(app.playback.state.value.playing)
            positions.put(JSONObject().put("positionMs", last).put("organizationRevision", current.organizationRevision)
                .put("progressRevision", current.progressRevision))
        }
        val second = search(ordinary.copy(offset = requireNotNull(first.nextOffset), expectedToken = requireNotNull(first.token)))
        assertEquals(ids.toSortedMap().values.toList(), (first.items + second.items).map { it.id })
        for ((query, page) in listOf(filtered to firstFiltered, recent to firstRecent)) {
            assertEquals(LocalAudioError.STALE_STATE, failure(call<LocalAudioSearchPage> {
                repo.search(query.copy(offset = requireNotNull(page.nextOffset), expectedToken = requireNotNull(page.token)), it)
            }))
        }
        val movedId = ids.getValue("d.wav")
        val moved = await<LocalAudioBatchResult> { repo.moveToFolderBatch(from, to, listOf(movedId), frozen.organizationToken, it) }
        assertEquals(1, moved.addedMemberships)
        assertEquals(1, moved.removedMemberships)
        assertTrue(app.playback.state.value.playing)
        assertEquals(LocalAudioError.STALE_STATE, failure(call<LocalAudioBatchResult> {
            repo.moveToFolderBatch(from, to, listOf(ids.getValue("c.wav")), frozen.organizationToken, it)
        }))
        assertTrue(state.members.any { it.folderId == to && it.audioId == movedId })
        assertTrue(state.members.any { it.folderId == from && it.audioId == ids.getValue("c.wav") })
        assertEquals(LocalAudioError.STALE_STATE, failure(call<LocalAudioSearchPage> {
            repo.search(ordinary.copy(offset = 2, expectedToken = requireNotNull(first.token)), it)
        }))
        out.put("naturalCheckpoints", positions).put("ordinaryPageNoOverlapOrGap", true)
            .put("progressFilterAndRecentExpired", true).put("moveDuringPlaybackAndRejectOldToken", true)
    }

    private fun cleanup(before: LocalAudioLibraryState) {
        val folders = state.folders.filter { it.name == sourceFolder || it.name == targetFolder }.map { it.id }.toSet()
        val baselineItems = before.items.map { it.id }.toSet()
        val owned = state.members.filter { it.folderId in folders && it.audioId !in baselineItems }.map { it.audioId }.toSet()
        val queue = app.playback.localQueueState.value
        if (queue.items.isNotEmpty()) {
            check(queue.items.all { it.id in owned })
            main { app.playback.pause() }
            await<Unit> { app.playback.checkpointBeforeInstall(it) }
            await<Unit> { app.playback.clearLocalQueue(queue.revision, it) }
        }
        state.directories.filter { it.folderId in folders }.forEach { link -> await<Unit> { repo.unlinkDirectory(link.id, it) } }
        owned.forEach { id -> await<Unit> { repo.deleteAudio(id, it) } }
        folders.forEach { id -> await<Unit> { repo.deleteFolder(id, it) } }
        assertEquals(before.items, state.items)
        assertEquals(before.progress, state.progress)
    }

    private val picker get() = Class.forName("tech.musxi.player.LocalAudioPickerValidationActivity")
    private val pickerCompanion get() = picker.getDeclaredField("Companion").get(null)
    private fun closePicker() = main { pickerCompanion.javaClass.getMethod("finishSelection").invoke(pickerCompanion) }
    private fun pickTree(): Pair<Uri, Int> {
        val info = instrument.uiAutomation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrument.uiAutomation.serviceInfo = info
        val companion = pickerCompanion
        fun selection(): Any? = companion.javaClass.getMethod("getSelection").invoke(companion)
        main {
            companion.javaClass.getMethod("clearSelection").invoke(companion)
            app.startActivity(Intent(app, picker).putExtra("operation", "expansion_tree").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val buttons = setOf("使用此文件夹", "使用此文件夹中的内容", "USE THIS FOLDER", "允许", "ALLOW")
        val deadline = SystemClock.elapsedRealtime() + 22_000
        while (selection() == null && SystemClock.elapsedRealtime() < deadline) {
            val nodes = buildList {
                fun walk(node: AccessibilityNodeInfo?) {
                    if (node == null || node.packageName?.toString() != "com.android.documentsui") return
                    add(node)
                    repeat(node.childCount) { walk(node.getChild(it)) }
                }
                instrument.uiAutomation.windows.forEach { walk(it.root) }
            }
            // Confirm only our fixed fixture directory, or the subsequent system confirmation.
            nodes.firstOrNull { it.isEnabled && it.text?.toString()?.uppercase() in buttons &&
                (nodes.any { n -> n.text?.toString()?.contains(directory) == true } ||
                    (it.text?.toString()?.uppercase() in setOf("允许", "ALLOW") && nodes.none { n -> n.text?.toString()?.uppercase() == "USE THIS FOLDER" })) }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(200)
        }
        val chosen = requireNotNull(selection()) { "SAF directory picker did not return" }
        assertTrue(chosen.javaClass.getMethod("getAccepted").invoke(chosen) as Boolean)
        val uri = (chosen.javaClass.getMethod("getUris").invoke(chosen) as List<*>).single() as Uri
        return uri to (chosen.javaClass.getMethod("getFlags").invoke(chosen) as Int)
    }

    private fun refresh(id: String) = await<LocalAudioDirectoryRefreshResult> { repo.refreshDirectory(id, it) }
    private fun ids(result: LocalAudioDirectoryRefreshResult) = result.results.mapNotNull { row -> row.audioId?.let { row.fileName to it } }.toMap()
    private fun search(query: LocalAudioQuery) = await<LocalAudioSearchPage> { repo.search(query, it) }
    private fun failure(result: Result<*>) = (result.exceptionOrNull() as? LocalAudioFailure)?.error
    private fun main(block: () -> Unit) = instrument.runOnMainSync(block)
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command)).use { it.reader().readText() }
    private fun waitFor(label: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) { check(SystemClock.elapsedRealtime() < deadline) { "Timed out: $label" }; Thread.sleep(75) }
    }
    private fun <T> call(command: ((Result<T>) -> Unit) -> Unit): Result<T> {
        val result = CompletableFuture<Result<T>>()
        main { command { result.complete(it) } }
        return result.get(30, TimeUnit.SECONDS)
    }
    private fun <T> await(command: ((Result<T>) -> Unit) -> Unit): T = call(command).getOrThrow()
}
