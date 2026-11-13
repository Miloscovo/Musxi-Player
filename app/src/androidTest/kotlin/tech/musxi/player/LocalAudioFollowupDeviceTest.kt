package tech.musxi.player

import android.app.NotificationManager
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import tech.musxi.core.localaudio.*

/** Fixed generated files only; each invocation explicitly selects a phase and retains failures. */
class LocalAudioFollowupDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as MusxiApplication
    private val marker get() = File(app.filesDir, "local-audio-followup-20261110.json")
    private val report get() = File(app.filesDir, "local-audio-followup-results-20261110.json")
    private val pickerClass get() = Class.forName("tech.musxi.player.LocalAudioPickerValidationActivity")
    private val companion get() = pickerClass.getDeclaredField("Companion").get(null)
    private val pickerEvidence = JSONArray()

    @Test fun selectedFollowupPhase() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allowLocalAudioFollowup") == "true")
        val phase = requireNotNull(args.getString("localPhase"))
        val row = JSONObject().put("phase", phase).put("startedAtMs", System.currentTimeMillis()).put("pickerEvidence", pickerEvidence)
        try {
            open()
            when (phase) {
                "setup" -> setup(row)
                "oem" -> oem(row)
                "notifications" -> notifications(row)
                "after_background" -> afterBackground(row)
                "cleanup" -> cleanup(row)
                else -> error("Unknown followup phase")
            }
            row.put("result", "passed")
        } catch (failure: Throwable) {
            row.put("result", "failed").put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally {
            main { app.playback.pause() }
            row.put("finishedAtMs", System.currentTimeMillis())
            val rows = if (report.isFile) JSONArray(report.readText()) else JSONArray()
            rows.put(row)
            report.writeText(rows.toString(2))
        }
    }

    private fun setup(row: JSONObject) {
        check(!marker.exists()) { "Inspect existing followup marker before another setup" }
        waitFor(15_000) { app.localAudio.state.value.ready }
        val baseline = app.localAudio.state.value
        val folder = await<LocalAudioFolder> { app.localAudio.createFolder("补验 20261110 格式与熄屏", it) }
        val saved = JSONObject().put("folderId", folder.id).put("audioIds", JSONArray())
            .put("baselineIds", JSONArray(baseline.items.map { it.id }))
            .put("baselineFolderIds", JSONArray(baseline.folders.map { it.id }))
        marker.writeText(saved.toString(2))
        val samples = JSONArray()
        row.put("samples", samples)
        for (name in listOf("11-book.m4b", "12-vorbis.ogg", "13-raw.aac", "14-stereo24.flac", "15-background.m4a")) {
            val selected = pick("followup_files", listOf(name))
            val imported = await<List<LocalAudioImportResult>> {
                app.localAudio.importFiles(folder.id, selected.uris, LocalImportMode.COPY, selected.flags, it)
            }.single()
            main { companion.javaClass.getMethod("finishSelection").invoke(companion) }
            assertNull("Import $name", imported.error)
            val id = requireNotNull(imported.audioId)
            check(baseline.items.none { it.id == id }) { "Fixture unexpectedly matched user audio; preserve and stop" }
            saved.getJSONArray("audioIds").put(id)
            if (name == "15-background.m4a") saved.put("backgroundId", id)
            marker.writeText(saved.toString(2))
            await<Unit> { app.localAudioPlayback.play(null, id, fromStart = true, callback = it) }
            waitFor(20_000) { app.playback.state.value.let { it.playing && it.localAudio?.id == id && it.positionMs >= 500 || it.localError != null || it.error != null } }
            assertTrue("Decode $name: ${app.playback.state.value.localError ?: app.playback.state.value.error}", app.playback.state.value.playing)
            main { app.playback.seekTo(40_000) }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (app.playback.state.value.positionMs < 39_500 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            val sought = app.playback.state.value.positionMs >= 39_500
            // Headerless ADTS does not necessarily expose a seek map; retain actual behavior.
            if (name != "13-raw.aac") assertTrue("Seek $name", sought)
            main { app.playback.pause() }
            await<Unit> { app.playback.checkpointBeforeInstall(it) }
            samples.put(JSONObject().put("fileName", name).put("durationMs", app.playback.state.value.durationMs)
                .put("positionMs", app.playback.state.value.positionMs).put("decoded", true).put("seekTo40s", sought))
        }
    }

    private fun oem(row: JSONObject) {
        val saved = JSONObject(marker.readText())
        val folder = saved.getString("folderId")
        val baselineIds = strings(saved.getJSONArray("baselineIds")).toSet()
        val selected = pick("followup_oem_files", listOf("16-oem-one.wav", "17-oem-two.mp3"))
        assertEquals(2, selected.uris.size)
        row.put("selectedCount", selected.uris.size).put("readFlag", selected.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val imported = await<List<LocalAudioImportResult>> {
            app.localAudio.importFiles(folder, selected.uris, LocalImportMode.COPY, selected.flags, it)
        }
        main { companion.javaClass.getMethod("finishSelection").invoke(companion) }
        imported.mapNotNull { it.audioId }.forEach { id ->
            check(id !in baselineIds)
            if (id !in strings(saved.getJSONArray("audioIds"))) saved.getJSONArray("audioIds").put(id)
        }
        marker.writeText(saved.toString(2))
        assertEquals(2, imported.size)
        assertTrue(imported.all { it.error == null })
        for (item in imported) {
            await<Unit> { app.localAudioPlayback.play(null, item.audioId!!, fromStart = true, callback = it) }
            waitFor(15_000) { app.playback.state.value.playing && app.playback.state.value.localAudio?.id == item.audioId }
            main { app.playback.pause() }
        }
        row.put("importedAndDecoded", true)
        val before = app.localAudio.state.value
        val cancelled = pick("followup_oem_files", emptyList(), cancel = true)
        assertFalse(cancelled.accepted)
        assertEquals(before.items, app.localAudio.state.value.items)
        assertEquals(before.members, app.localAudio.state.value.members)
        row.put("cancelLeavesLibraryUnchanged", true)
    }

    private fun notifications(row: JSONObject) {
        val id = JSONObject(marker.readText()).getString("backgroundId")
        await<Unit> { app.localAudioPlayback.play(null, id, fromStart = true, callback = it) }
        waitFor(15_000) { app.playback.state.value.playing }
        val manager = app.getSystemService(NotificationManager::class.java)
        fun action(labels: Set<String>) {
            var found: android.app.Notification.Action? = null
            waitFor(10_000) {
                found = manager.activeNotifications.filter { it.packageName == app.packageName }
                    .flatMap { it.notification.actions.orEmpty().toList() }
                    .firstOrNull { it.title?.toString()?.trim()?.lowercase(java.util.Locale.ROOT) in labels && it.actionIntent?.creatorPackage == app.packageName }
                found != null
            }
            found!!.actionIntent.send()
        }
        action(setOf("pause", "暂停"))
        waitFor(10_000) { !app.playback.state.value.playWhenReady }
        action(setOf("play", "播放"))
        waitFor(10_000) { app.playback.state.value.playing }
        assertEquals(id, app.playback.state.value.localAudio?.id)
        action(setOf("pause", "暂停"))
        waitFor(10_000) { !app.playback.state.value.playWhenReady }
        row.put("realNotificationPendingIntents", true).put("pausePlayPause", true).put("localIdentityPreserved", true)
    }

    private fun afterBackground(row: JSONObject) {
        val result = JSONObject(File(app.filesDir, "local-audio-followup-background-20261110.json").readText())
        assertEquals("passed", result.getString("status"))
        val saved = JSONObject(marker.readText())
        val id = saved.getString("backgroundId")
        assertEquals(id, result.getString("audioId"))
        val samples = result.getJSONArray("samples")
        val last = samples.getJSONObject(samples.length() - 1)
        assertFalse(last.getBoolean("playWhenReady"))
        assertFalse(last.getBoolean("timerActive"))
        assertFalse(last.getBoolean("interactive"))
        assertTrue(result.getLong("timerStopObservedAfterMs") in 55_000..80_000)
        val events = result.getJSONArray("screenEvents")
        assertTrue((0 until events.length()).any { events.getJSONObject(it).getString("action") == Intent.ACTION_SCREEN_OFF })
        val expectedPosition = last.getLong("positionMs")
        waitFor(10_000) { app.localAudio.state.value.ready && app.localAudio.state.value.progress.any {
            it.audioId == id && kotlin.math.abs(it.positionMs - expectedPosition) < 2_000
        } }
        val position = app.localAudio.state.value.progress.single { it.audioId == id }.positionMs
        assertTrue(position > 350_000)
        await<Unit> { app.localAudioPlayback.play(null, id, callback = it) }
        waitFor(15_000) { app.playback.state.value.playing && app.playback.state.value.positionMs >= position - 500 }
        row.put("savedPositionMs", position).put("observedStopPositionMs", expectedPosition).put("resumedAfterScreenOffTimer", true)
    }

    private fun cleanup(row: JSONObject) {
        val saved = JSONObject(marker.readText())
        main { app.playback.pause() }
        await<Unit> { app.playback.cancelSleepTimer(it) }
        val queue = app.playback.localQueueState.value
        if (queue.items.isNotEmpty()) await<Unit> { app.playback.clearLocalQueue(queue.revision, it) }
        strings(saved.getJSONArray("audioIds")).distinct().forEach { id ->
            if (app.localAudio.state.value.items.any { it.id == id }) await<Unit> { app.localAudio.deleteAudio(id, it) }
        }
        await<Unit> { app.localAudio.deleteFolder(saved.getString("folderId"), it) }
        assertEquals(strings(saved.getJSONArray("baselineIds")).toSet(), app.localAudio.state.value.items.map { it.id }.toSet())
        assertEquals(strings(saved.getJSONArray("baselineFolderIds")).toSet(), app.localAudio.state.value.folders.map { it.id }.toSet())
        assertFalse(app.playback.state.value.playWhenReady)
        check(marker.delete())
        row.put("originalLibraryIdsPreserved", true).put("testDataRemoved", true)
    }

    private class Selected(val accepted: Boolean, val uris: List<android.net.Uri>, val flags: Int)
    private fun selection(): Selected? {
        val value = companion.javaClass.getMethod("getSelection").invoke(companion) ?: return null
        @Suppress("UNCHECKED_CAST")
        return Selected(value.javaClass.getMethod("getAccepted").invoke(value) as Boolean,
            value.javaClass.getMethod("getUris").invoke(value) as List<android.net.Uri>,
            value.javaClass.getMethod("getFlags").invoke(value) as Int)
    }

    private fun pick(operation: String, names: List<String>, cancel: Boolean = false): Selected {
        instrument.uiAutomation.serviceInfo = instrument.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        companion.javaClass.getMethod("clearSelection").invoke(companion)
        open()
        main {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().first()
            activity.startActivity(Intent(activity, pickerClass).putExtra("operation", operation))
        }
        val pkg = if (operation.contains("oem")) "com.coloros.filemanager" else "com.android.documentsui"
        waitFor(10_000) { nodes(pkg).isNotEmpty() }
        Thread.sleep(700)
        if (cancel) {
            // OEM Back first navigates parent directories. Cancel only after the real result
            // callback, and never send another Back once focus has left this picker.
            repeat(6) {
                if (selection() == null) {
                    var window: android.view.accessibility.AccessibilityWindowInfo? = null
                    waitFor(5_000) {
                        window = instrument.uiAutomation.windows.firstOrNull {
                            it.isActive && it.isFocused && it.root?.packageName?.toString() == pkg
                        }
                        selection() != null || window != null
                    }
                    if (selection() == null) {
                        val target = requireNotNull(window)
                        pickerEvidence.put(JSONObject().put("package", pkg).put("displayId", target.displayId)
                            .put("windowId", target.id).put("windowActive", target.isActive)
                            .put("windowFocused", target.isFocused).put("action", "display_targeted_back"))
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(
                            "input -d ${target.displayId} keyevent KEYCODE_BACK")).use { it.readBytes() }
                        val deadline = SystemClock.elapsedRealtime() + 1_000
                        while (selection() == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
                    }
                }
            }
        } else {
            for (name in names) {
                var node: AccessibilityNodeInfo? = null
                waitFor(10_000) { node = nodes(pkg).firstOrNull { it.text?.toString() == name }; node != null }
                if (pkg == "com.coloros.filemanager") {
                    var row = node!!
                    var checkbox: AccessibilityNodeInfo? = null
                    repeat(5) {
                        if (checkbox == null) checkbox = descendants(row).firstOrNull { it.isCheckable }
                        if (checkbox == null) row.parent?.let { row = it }
                    }
                    tap(checkbox ?: node!!, pkg)
                    val count = names.indexOf(name) + 1
                    var confirmed = false
                    repeat(20) {
                        if (!confirmed) {
                            confirmed = nodes(pkg).any { it.text?.toString() in setOf("添加($count)", "添加（$count）", "ADD($count)") }
                            if (!confirmed) Thread.sleep(100)
                        }
                    }
                    if (!confirmed) {
                        // Some OEM windows acknowledge the first touch during their opening animation.
                        val fresh = nodes(pkg).first { it.text?.toString() == name }
                        click(fresh)
                        Thread.sleep(350)
                    }
                    waitFor(5_000) { nodes(pkg).any { it.text?.toString() in setOf("添加($count)", "添加（$count）", "ADD($count)") } }
                } else click(requireNotNull(node))
                Thread.sleep(350)
            }
            if (selection() == null) {
                var confirm: AccessibilityNodeInfo? = null
                waitFor(7_000) {
                    confirm = nodes(pkg).firstOrNull { it.text?.toString()?.matches(Regex("(?:添加|打开|选择|确定|ADD|OPEN|SELECT)\\s*(?:[（(]${names.size}[）)])?", RegexOption.IGNORE_CASE)) == true && it.isEnabled }
                    selection() != null || confirm != null
                }
                if (selection() == null) {
                    if (pkg == "com.coloros.filemanager") tap(requireNotNull(confirm), pkg)
                    else click(requireNotNull(confirm))
                }
            }
        }
        waitFor(15_000) { selection() != null }
        return requireNotNull(selection()).also { if (!cancel) assertTrue(it.accepted) }
    }

    private fun nodes(pkg: String): List<AccessibilityNodeInfo> = instrument.uiAutomation.windows.flatMap { window ->
        val root = window.root
        if (root?.packageName?.toString() == pkg) descendants(root) else emptyList()
    }
    private fun descendants(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        add(root)
        repeat(root.childCount) { root.getChild(it)?.let { addAll(descendants(it)) } }
    }
    private fun tap(node: AccessibilityNodeInfo, pkg: String) {
        val window = instrument.uiAutomation.windows.first { it.id == node.windowId && it.root?.packageName?.toString() == pkg }
        val bounds = Rect().also(node::getBoundsInScreen)
        check(!bounds.isEmpty)
        // `input` must target the picker window's display, not implicitly display 0 (the game).
        val display = window.displayId
        pickerEvidence.put(JSONObject().put("package", pkg).put("displayId", display).put("windowId", window.id)
            .put("windowActive", window.isActive).put("bounds", bounds.toShortString()).put("action", "display_targeted_tap"))
        val command = "input -d $display tap ${bounds.centerX()} ${bounds.centerY()}"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target = node
        repeat(5) { if (!target.isClickable) target.parent?.let { target = it } }
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Picker semantic action rejected" }
    }
    private fun open() {
        main { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        waitFor(15_000) { app.playback.state.value.connected }
        waitFor(15_000) {
            var resumed = false
            main {
                resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).any { it is MainActivity }
            }
            resumed
        }
    }
    private fun strings(values: JSONArray) = (0 until values.length()).map(values::getString)
    private fun main(block: () -> Unit) = instrument.runOnMainSync(block)
    private fun waitFor(timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) { check(SystemClock.elapsedRealtime() < deadline) { "Followup condition timed out" }; Thread.sleep(50) }
    }
    private fun <T> await(block: ((Result<T>) -> Unit) -> Unit): T {
        val result = CompletableFuture<Result<T>>()
        main { block { result.complete(it) } }
        return result.get(45, TimeUnit.SECONDS).getOrThrow()
    }
}
