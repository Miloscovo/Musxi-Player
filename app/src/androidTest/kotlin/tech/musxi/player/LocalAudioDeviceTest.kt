package tech.musxi.player

import android.content.Intent
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
import tech.musxi.core.playback.SleepTimerPhase
import tech.musxi.playback.PlaybackRepeatMode

/** Explicit phases operate only on this run's marked folders/files; no platform writes. */
class LocalAudioDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val app get() = instrumentation.targetContext.applicationContext as MusxiApplication
    private val marker get() = File(app.filesDir, "local-audio-device-20261110.json")
    private val report get() = File(app.filesDir, "local-audio-device-20261110-results.json")
    private val pickerClass get() = Class.forName("tech.musxi.player.LocalAudioPickerValidationActivity")

    @Test fun selectedRealDevicePhase() {
        assumeTrue("Explicit local device authorization required", arguments.getString("allowLocalAudioDevice") == "true")
        val phase = requireNotNull(arguments.getString("localPhase"))
        openProduct()
        val entry = JSONObject().put("phase", phase).put("startedAtMs", System.currentTimeMillis())
        try {
            when (phase) {
                "import" -> importAndDecode(entry)
                "cold" -> coldRestore(entry)
                "missing" -> missingAndRelink(entry)
                "refresh" -> refresh(entry)
                "transfers" -> transfers(entry)
                "timers" -> timers(entry)
                "background" -> background(entry)
                "focus" -> focus(entry)
                "system" -> systemControls(entry)
                "revoke" -> revokeTree(entry)
                "reauthorize" -> reauthorizeTree(entry)
                "cleanup" -> cleanup(entry)
                else -> error("Unknown local phase")
            }
            entry.put("result", "passed")
        } catch (error: Throwable) {
            entry.put("result", "failed").put("failureType", error.javaClass.simpleName)
            throw error
        } finally {
            main { app.playback.pause() }
            entry.put("finishedAtMs", System.currentTimeMillis())
            val history = if (report.exists()) JSONArray(report.readText()) else JSONArray()
            history.put(entry)
            report.writeText(history.toString(2))
        }
    }

    private fun importAndDecode(out: JSONObject) {
        check(!marker.exists()) { "Previous device marker must be cleaned or inspected first" }
        waitFor(15_000) { app.localAudio.state.value.ready && app.playback.state.value.connected }
        val saved = JSONObject().put("audioIds", JSONArray()).put("folderIds", JSONArray())
        fun save() = marker.writeText(saved.toString(2))
        save()
        val copy = await<LocalAudioFolder> { app.localAudio.createFolder("实机验收 20261110 复制", it) }
        saved.getJSONArray("folderIds").put(copy.id); saved.put("copyFolder", copy.id); save()
        val decoded = JSONArray()
        out.put("decoded", decoded)
        for (name in listOf("01-tone.wav", "02-tone.mp3", "03-tone.m4a", "04-tone.flac", "05-tone.opus", "06-long.m4a")) {
            val picked = pick("files", name)
            val results = await<List<LocalAudioImportResult>> {
                app.localAudio.importFiles(copy.id, picked.uris, LocalImportMode.COPY, picked.flags, it)
            }
            assertEquals(1, results.size)
            assertNull("Import error for $name", results.single().error)
            val id = requireNotNull(results.single().audioId)
            saved.getJSONArray("audioIds").put(id); save()
            await<Unit> { app.localAudioPlayback.play(null, id, fromStart = true, callback = it) }
            waitFor(20_000) { app.playback.state.value.let { it.playing && it.localAudio?.id == id && it.positionMs >= 600 || it.localError != null || it.error != null } }
            val playing = app.playback.state.value
            assertTrue("Decoder failed for $name: ${playing.localError ?: playing.error}", playing.playing)
            assertNull(playing.queuePlatform)
            assertNull(playing.sourceKey)
            val seek = if (name == "06-long.m4a") 3_500_000L else 12_000L
            main { app.playback.seekTo(seek) }
            waitFor(12_000) { app.playback.state.value.positionMs >= seek - 500 }
            main { app.playback.pause() }
            await<Unit> { app.playback.checkpointBeforeInstall(it) }
            decoded.put(JSONObject().put("format", name.substringAfterLast('.')).put("durationMs", app.playback.state.value.durationMs)
                .put("seekMs", seek).put("positionMs", app.playback.state.value.positionMs))
        }
        val reference = await<LocalAudioFolder> { app.localAudio.createFolder("实机验收 20261110 引用", it) }
        saved.getJSONArray("folderIds").put(reference.id); saved.put("referenceFolder", reference.id); save()
        val tree = pick("tree")
        val link = await<LocalAudioDirectoryLink> {
            app.localAudio.linkDirectory(reference.id, tree.uris.single(), "本轮测试目录", LocalImportMode.REFERENCE, tree.flags, false, it)
        }
        saved.put("directoryId", link.id); save()
        val refreshed = await<LocalAudioDirectoryRefreshResult> { app.localAudio.refreshDirectory(link.id, it) }
        refreshed.results.mapNotNull { it.audioId }.forEach { saved.getJSONArray("audioIds").put(it) }; save()
        assertTrue(refreshed.results.isNotEmpty())
        assertTrue(refreshed.results.all { it.error == null })
        val importedIds = refreshed.results.mapNotNull { it.audioId }.toSet()
        val referenceId = app.localAudio.state.value.items.single { it.id in importedIds && it.mode == LocalImportMode.REFERENCE && it.metadata.fileName == "07-reference.wav" }.id
        saved.put("referenceId", referenceId)
        await<Unit> { app.localAudio.renameAudio(referenceId, "音声验收书签", it) }
        val bookmark = await<LocalAudioBookmark> { app.localAudio.addBookmark(referenceId, 9_000, "保留测试位置", it) }
        saved.put("bookmarkId", bookmark.id); save()
        await<Unit> { app.localAudioPlayback.play(reference.id, referenceId, fromStart = true, callback = it) }
        waitFor(15_000) { app.playback.state.value.playing && app.playback.state.value.localAudio?.id == referenceId }
        main { app.playback.seekTo(12_000); app.playback.pause(); app.playback.setRepeatMode(PlaybackRepeatMode.ALL) }
        waitFor(10_000) { app.playback.state.value.positionMs >= 11_500 && app.playback.state.value.repeatMode == PlaybackRepeatMode.ALL }
        await<Unit> { app.playback.checkpointBeforeInstall(it) }
        val queue = app.playback.localQueueState.value
        await<Unit> { app.playback.moveLocalQueueItem(0, queue.items.lastIndex, queue.revision, it) }
        await<Unit> { app.playback.checkpointBeforeInstall(it) }
        saved.put("queueIds", JSONArray(app.playback.localQueueState.value.items.map { it.id }))
        saved.put("resumePositionMs", app.playback.state.value.positionMs); save()
        out.put("copyCount", decoded.length()).put("referenceCount", refreshed.results.size)
            .put("persistedReadGrant", app.contentResolver.persistedUriPermissions.any { it.uri == tree.uris.single() && it.isReadPermission })
        assertTrue(out.getBoolean("persistedReadGrant"))
    }

    private fun coldRestore(out: JSONObject) {
        val saved = readMarker()
        val id = saved.getString("referenceId")
        waitFor(15_000) { app.playback.state.value.connected && app.playback.localQueueState.value.items.isNotEmpty() }
        val state = app.playback.state.value
        assertEquals(id, state.localAudio?.id)
        assertFalse(state.playing)
        assertFalse(state.playWhenReady)
        assertEquals(PlaybackRepeatMode.ALL, state.repeatMode)
        assertEquals(strings(saved.getJSONArray("queueIds")), app.playback.localQueueState.value.items.map { it.id })
        assertTrue(kotlin.math.abs(state.positionMs - saved.getLong("resumePositionMs")) <= 1_000)
        assertFalse(app.playback.sleepTimer.value.active)
        await<Unit> { app.localAudioPlayback.play(null, id, callback = it) }
        waitFor(15_000) { app.playback.state.value.playing && app.playback.state.value.positionMs >= saved.getLong("resumePositionMs") }
        main { app.playback.pause() }
        val copyId = strings(saved.getJSONArray("audioIds")).first()
        await<Unit> { app.localAudioPlayback.play(null, copyId, fromStart = true, callback = it) }
        waitFor(15_000) { app.playback.state.value.playing && app.playback.state.value.localAudio?.id == copyId }
        out.put("pausedColdRestore", true).put("queueOrderPreserved", true).put("referenceGrantSurvived", true)
            .put("copyPlaysWithoutOriginal", true).put("positionMs", state.positionMs)
    }

    private fun missingAndRelink(out: JSONObject) {
        val saved = readMarker()
        val id = saved.getString("referenceId")
        waitFor(15_000) { app.localAudio.state.value.ready }
        val results = await<List<LocalAudioSourceCheckResult>> {
            app.localAudio.checkSources(listOf(id), app.localAudio.state.value.revision, it)
        }
        val observed = results.single()
        // ExternalStorageProvider can return a null query for a deleted document. The contract
        // conservatively reports READ_FAILED rather than treating every null provider as missing.
        assertTrue(observed.availability == LocalAudioAvailability.MISSING && observed.error == LocalAudioError.NOT_FOUND ||
            observed.availability == LocalAudioAvailability.UNREADABLE && observed.error == LocalAudioError.READ_FAILED)
        out.put("sourceAvailability", observed.availability?.name).put("sourceError", observed.error?.name)
        val owned = strings(saved.getJSONArray("audioIds")).toSet()
        app.localAudio.state.value.items.filter { it.id in owned && it.id != id &&
            it.mode == LocalImportMode.REFERENCE && it.metadata.fileName == "08-replacement.wav" }.forEach { item ->
            await<Unit> { app.localAudio.deleteAudio(item.id, it) }
        }
        val before = app.localAudio.state.value
        val picked = pick("files", "08-replacement.wav")
        await<Unit> { app.localAudio.relink(id, picked.uris.single(), picked.flags, it) }
        val after = app.localAudio.state.value
        assertEquals(before.progress, after.progress)
        assertEquals(before.bookmarks, after.bookmarks)
        assertEquals(before.members, after.members)
        assertEquals("音声验收书签", after.items.single { it.id == id }.displayName)
        val restored = await<List<LocalAudioSourceCheckResult>> { app.localAudio.checkSources(listOf(id), after.revision, it) }
        assertEquals(LocalAudioAvailability.AVAILABLE, restored.single().availability)
        await<Unit> { app.localAudioPlayback.play(null, id, callback = it) }
        waitFor(15_000) { app.playback.state.value.playing && app.playback.state.value.localAudio?.id == id }
        out.put("missingClassified", true).put("relinkPreservedData", true).put("relinkedDecoded", true)
    }

    private fun refresh(out: JSONObject) {
        val saved = readMarker()
        waitFor(15_000) { app.localAudio.state.value.ready }
        val folder = saved.getString("referenceFolder")
        val directory = saved.getString("directoryId")
        val removeId = app.localAudio.state.value.items.single {
            it.mode == LocalImportMode.REFERENCE && it.metadata.fileName == "02-tone.mp3" &&
                app.localAudio.state.value.members.any { member -> member.folderId == folder && member.audioId == it.id }
        }.id
        await<Unit> { app.localAudio.removeFromFolder(folder, removeId, it) }
        val before = app.localAudio.state.value
        val priorIds = before.members.filter { it.folderId == folder }.sortedBy { it.position }.map { it.audioId }
        val result = await<LocalAudioDirectoryRefreshResult> { app.localAudio.refreshDirectory(directory, it) }
        val known = strings(saved.getJSONArray("audioIds")).toSet()
        result.results.mapNotNull { it.audioId }.filter { it !in known }.forEach { saved.getJSONArray("audioIds").put(it) }
        marker.writeText(saved.toString(2))
        val after = app.localAudio.state.value
        assertTrue(result.missingCount >= 1)
        assertTrue(after.members.none { it.folderId == folder && it.audioId == removeId })
        val refreshed = after.members.filter { it.folderId == folder }.sortedBy { it.position }.map { it.audioId }
        assertEquals(priorIds, refreshed.take(priorIds.size))
        assertTrue(refreshed.size > priorIds.size)
        assertEquals(before.bookmarks, after.bookmarks)
        assertEquals(before.progress, after.progress)
        out.put("missingCount", result.missingCount).put("manualRemovalPreserved", true)
            .put("newFilesAppended", true).put("listeningDataPreserved", true)
    }

    private fun transfers(out: JSONObject) {
        val saved = readMarker()
        val id = strings(saved.getJSONArray("audioIds")).first()
        waitFor(15_000) { app.localAudio.state.value.ready }
        val target = pick("export", "保存")
        val exported = await<LocalAudioExportResult> { app.localAudio.exportAudio(id, target.uris.single(), target.flags, it) }
        val digest = app.contentResolver.openInputStream(target.uris.single())!!.use { input ->
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65_536)
            while (true) { val read = input.read(buffer); if (read < 0) break; hash.update(buffer, 0, read) }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
        out.put("exportBytes", exported.bytes).put("exportSha256", digest)
        val backup = pick("backup", "保存")
        await<LocalAudioBackupResult> { app.localAudio.backupOrganization(backup.uris.single(), backup.flags, it) }
        val preview = await<LocalAudioRestorePreview> { app.localAudio.previewOrganization(backup.uris.single(), it) }
        await<LocalAudioRestoreResult> { app.localAudio.restoreOrganization(preview.backupId, preview.expectedRevision, it) }
        out.put("backupPreviewRestore", true)
    }

    private fun timers(out: JSONObject) {
        val saved = readMarker()
        val id = saved.getString("referenceId")
        fun play() {
            await<Unit> { app.localAudioPlayback.play(null, id, fromStart = true, callback = it) }
            waitFor(15_000) { app.playback.state.value.playing }
        }
        play()
        await<Unit> { app.playback.stopAfterCurrentTrack(it) }
        main { app.playback.seekTo(app.playback.state.value.durationMs - 2_000) }
        waitFor(10_000) { !app.playback.state.value.playWhenReady && !app.playback.sleepTimer.value.active }
        out.put("endOfTrack", true)
        // A full real minute checks the public duration command without a shortened test clock.
        play()
        await<Unit> { app.playback.startSleepTimer(1, false, it) }
        // Loop the 32-second fixture so the deadline, rather than natural end, stops it.
        main { app.playback.setRepeatMode(PlaybackRepeatMode.ONE) }
        waitFor(75_000) { !app.playback.state.value.playWhenReady && !app.playback.sleepTimer.value.active }
        out.put("afterRealMinute", true)
        play()
        await<Unit> { app.playback.startSleepTimer(1, true, it) }
        main { app.playback.setRepeatMode(PlaybackRepeatMode.ONE) }
        waitFor(72_000) { app.playback.sleepTimer.value.phase == SleepTimerPhase.WAITING_FOR_TRACK_END }
        assertTrue(app.playback.state.value.playWhenReady)
        main { app.playback.seekTo(app.playback.state.value.durationMs - 2_000) }
        waitFor(10_000) { !app.playback.state.value.playWhenReady && !app.playback.sleepTimer.value.active }
        main { app.playback.setRepeatMode(PlaybackRepeatMode.OFF) }
        out.put("afterRealMinuteThenTrackEnd", true)
    }

    private fun background(out: JSONObject) {
        val saved = readMarker()
        val id = strings(saved.getJSONArray("audioIds")).first()
        await<Unit> { app.localAudioPlayback.play(null, id, fromStart = true, callback = it) }
        waitFor(15_000) { app.playback.state.value.playing }
        main { app.playback.setRepeatMode(PlaybackRepeatMode.ONE) }
        instrumentation.runOnMainSync {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>()
                .forEach { it.moveTaskToBack(true) }
        }
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < 65_000) {
            assertTrue("Playback stopped in short background sample", app.playback.state.value.playWhenReady)
            Thread.sleep(250)
        }
        assertTrue(app.playback.state.value.playing)
        main { app.playback.pause(); app.playback.setRepeatMode(PlaybackRepeatMode.OFF) }
        out.put("backgroundMs", SystemClock.elapsedRealtime() - started).put("playingAtEnd", true)
    }

    private fun focus(out: JSONObject) {
        val id = strings(readMarker().getJSONArray("audioIds")).first()
        await<Unit> { app.localAudioPlayback.play(null, id, fromStart = true, callback = it) }
        waitFor(15_000) { app.playback.state.value.playing }
        val probe = Class.forName("tech.musxi.player.ValidationFocusActivity").getDeclaredField("Companion").get(null)
        try {
            main { probe.javaClass.getMethod("launch", android.content.Context::class.java, Boolean::class.javaPrimitiveType)
                .invoke(probe, app, false) }
            waitFor(10_000) { !app.playback.state.value.playing }
            assertEquals(tech.musxi.playback.PlaybackSuppression.AUDIO_FOCUS, app.playback.state.value.suppression)
            main { probe.javaClass.getMethod("release").invoke(probe) }
            waitFor(10_000) { app.playback.state.value.playing }
            out.put("transientFocusPaused", true).put("focusReturnResumed", true)
        } finally { main { probe.javaClass.getMethod("release").invoke(probe); app.playback.pause() } }
    }

    private fun systemControls(out: JSONObject) {
        val saved = readMarker()
        await<Unit> { app.localAudioPlayback.play(saved.getString("copyFolder"), fromStart = true, callback = it) }
        waitFor(15_000) { app.playback.state.value.playing }
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
        try {
            val manager = app.getSystemService(android.media.session.MediaSessionManager::class.java)
            val controller = manager.getActiveSessions(null).single { it.packageName == app.packageName }
            controller.transportControls.pause()
            waitFor(10_000) { !app.playback.state.value.playWhenReady }
            controller.transportControls.seekTo(8_000)
            controller.transportControls.play()
            waitFor(10_000) { app.playback.state.value.playing && app.playback.state.value.positionMs >= 7_500 }
            val originalId = app.playback.state.value.localAudio!!.id
            controller.transportControls.skipToNext()
            waitFor(15_000) { app.playback.state.value.playing && app.playback.state.value.localAudio?.id != originalId }
            assertNull(app.playback.state.value.queuePlatform)
            assertFalse(controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty().contains("content://"))
            controller.transportControls.pause()
            waitFor(10_000) { !app.playback.state.value.playWhenReady }
            out.put("systemPausePlaySeekNext", true).put("localIdentityPreserved", true)
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }

    private fun testTree() = android.provider.DocumentsContract.buildTreeDocumentUri(
        "com.android.externalstorage.documents", "primary:Download/Musxi-Acceptance-20261110")

    private fun revokeTree(out: JSONObject) {
        val saved = readMarker()
        val owned = strings(saved.getJSONArray("audioIds")).toSet()
        val id = app.localAudio.state.value.items.single { it.id in owned && it.mode == LocalImportMode.REFERENCE &&
            it.metadata.fileName == "03-tone.m4a" }.id
        saved.put("revokedAudioId", id)
        marker.writeText(saved.toString(2))
        assertTrue(app.contentResolver.persistedUriPermissions.any { it.uri == testTree() && it.isReadPermission })
        app.contentResolver.releasePersistableUriPermission(testTree(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(app.contentResolver.persistedUriPermissions.none { it.uri == testTree() && it.isReadPermission })
        out.put("onlyTestTreeGrantRevoked", true)
    }

    private fun reauthorizeTree(out: JSONObject) {
        val saved = readMarker()
        waitFor(15_000) { app.localAudio.state.value.ready }
        val id = saved.getString("revokedAudioId")
        val checked = await<List<LocalAudioSourceCheckResult>> { app.localAudio.checkSources(listOf(id), app.localAudio.state.value.revision, it) }
        assertEquals(LocalAudioError.PERMISSION_REQUIRED, checked.single().error)
        assertEquals(LocalAudioAvailability.PERMISSION_REQUIRED, checked.single().availability)
        val before = app.localAudio.state.value
        val tree = pick("tree")
        await<Unit> { app.localAudio.reauthorizeDirectory(saved.getString("directoryId"), tree.uris.single(), tree.flags, it) }
        val checkedAgain = await<List<LocalAudioSourceCheckResult>> { app.localAudio.checkSources(listOf(id), app.localAudio.state.value.revision, it) }
        assertEquals(LocalAudioAvailability.AVAILABLE, checkedAgain.single().availability)
        assertEquals(before.bookmarks, app.localAudio.state.value.bookmarks)
        assertEquals(before.progress, app.localAudio.state.value.progress)
        assertEquals(before.members, app.localAudio.state.value.members)
        await<Unit> { app.localAudioPlayback.play(null, id, fromStart = true, callback = it) }
        waitFor(15_000) { app.playback.state.value.playing && app.playback.state.value.localAudio?.id == id }
        out.put("realRevokedGrantDetected", true).put("sameTreeReauthorized", true).put("listeningDataPreserved", true)
    }

    private fun cleanup(out: JSONObject) {
        val saved = readMarker()
        waitFor(15_000) { app.localAudio.state.value.ready && app.playback.state.value.connected }
        main { app.playback.pause() }
        await<Unit> { app.playback.cancelSleepTimer(it) }
        val queue = app.playback.localQueueState.value
        if (queue.items.isNotEmpty()) await<Unit> { app.playback.clearLocalQueue(queue.revision, it) }
        val directory = saved.optString("directoryId")
        if (directory.isNotEmpty() && app.localAudio.state.value.directories.any { it.id == directory }) {
            await<Unit> { app.localAudio.unlinkDirectory(directory, it) }
        }
        val ids = strings(saved.getJSONArray("audioIds")).distinct()
        ids.filter { id -> app.localAudio.state.value.items.any { it.id == id } }.forEach { id -> await<Unit> { app.localAudio.deleteAudio(id, it) } }
        strings(saved.getJSONArray("folderIds")).forEach { id ->
            if (app.localAudio.state.value.folders.any { it.id == id }) await<Unit> { app.localAudio.deleteFolder(id, it) }
        }
        assertTrue(app.localAudio.state.value.items.none { it.id in ids })
        check(marker.delete())
        out.put("testItemsRemoved", true).put("paused", !app.playback.state.value.playWhenReady)
    }

    private fun openProduct() {
        main { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        waitFor(15_000) { app.playback.state.value.connected }
    }

    private class Picked(val uris: List<android.net.Uri>, val flags: Int)
    private fun pick(operation: String, fileName: String? = null): Picked {
        val service = instrumentation.uiAutomation.serviceInfo
        service.flags = service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo = service
        val companion = pickerClass.getDeclaredField("Companion").get(null)
        companion.javaClass.getMethod("clearSelection").invoke(companion)
        openProduct()
        main {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().first()
            activity.startActivity(Intent(activity, pickerClass).putExtra("operation", operation))
        }
        Thread.sleep(600)
        fun selection(): Any? = pickerClass.getDeclaredField("Companion").get(null).let { companion ->
            companion.javaClass.getMethod("getSelection").invoke(companion)
        }
        waitFor(10_000) { nodes().isNotEmpty() }
        if (operation == "files") {
            click(fileName!!)
            if (selection() == null) {
                var confirm: AccessibilityNodeInfo? = null
                waitFor(5_000) {
                    confirm = nodes().firstOrNull { it.text?.toString()?.matches(Regex("(?:添加|打开|选择|确定|ADD|OPEN|SELECT)(?:[（(]1[）)])?", RegexOption.IGNORE_CASE)) == true }
                    selection() != null || confirm != null
                }
                if (selection() == null) clickNode(requireNotNull(confirm))
            }
        }
        else if (operation == "tree") {
            clickAny(listOf("使用此文件夹", "使用这个文件夹", "USE THIS FOLDER"))
            clickAny(listOf("允许", "ALLOW"))
        } else clickAny(listOf("保存", "SAVE"))
        waitFor(15_000) { selection() != null }
        val value = requireNotNull(selection())
        assertTrue(value.javaClass.getMethod("getAccepted").invoke(value) as Boolean)
        @Suppress("UNCHECKED_CAST")
        return Picked(value.javaClass.getMethod("getUris").invoke(value) as List<android.net.Uri>,
            value.javaClass.getMethod("getFlags").invoke(value) as Int)
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val pkg = node.packageName?.toString().orEmpty()
            if (!pkg.contains("documentsui") &&
                !pkg.contains("permissioncontroller")) return
            result.add(node)
            repeat(node.childCount) { walk(node.getChild(it)) }
        }
        instrumentation.uiAutomation.windows.forEach { walk(it.root) }
        return result
    }
    private fun click(name: String) = clickAny(listOf(name))
    private fun clickAny(names: List<String>) {
        var found: AccessibilityNodeInfo? = null
        waitFor(10_000) { found = nodes().firstOrNull { it.text?.toString() in names || it.contentDescription?.toString() in names }; found != null }
        clickNode(requireNotNull(found))
    }
    private fun clickNode(found: AccessibilityNodeInfo) {
        var target = found
        repeat(5) { if (!target.isClickable) target.parent?.let { target = it } }
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "System picker rejected semantic click" }
        Thread.sleep(250)
    }

    private fun readMarker() = JSONObject(marker.readText())
    private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun waitFor(timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) { check(SystemClock.elapsedRealtime() < deadline) { "Local device condition timed out" }; Thread.sleep(50) }
    }
    private fun <T> await(action: ((Result<T>) -> Unit) -> Unit): T {
        val result = CompletableFuture<Result<T>>()
        main { action { result.complete(it) } }
        return result.get(45, TimeUnit.SECONDS).getOrThrow()
    }
}
