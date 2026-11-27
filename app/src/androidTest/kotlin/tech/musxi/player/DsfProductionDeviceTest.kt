package tech.musxi.player

import android.content.Intent
import android.net.Uri
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
import tech.musxi.playback.PlaybackRepeatMode

/** Explicit, owned local files only. No account writes or persisted decoder gate. */
class DsfProductionDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as MusxiApplication
    private val marker get() = File(app.filesDir, "audio-expansion-20261114-marker.json")
    private val report get() = File(app.filesDir, "dsf-production-device-results.json")
    private val picker get() = Class.forName("tech.musxi.player.LocalAudioPickerValidationActivity")
    private val companion get() = picker.getDeclaredField("Companion").get(null)
    private val cycle = "dsf-enablement-20261117"
    private fun main(action: () -> Unit) = instrument.runOnMainSync(action)
    private fun gate(enabled: Boolean) = main {
        app.javaClass.getMethod("setDsfDeviceAcceptance", Boolean::class.javaPrimitiveType).invoke(app, enabled)
    }
    private fun read(): JSONObject = JSONObject(marker.readText()).also { check(it.getString("cycle") == cycle) }
    private fun save(value: JSONObject) = marker.writeText(value.toString(2))
    private fun strings(value: JSONArray) = (0 until value.length()).map(value::getString)

    @Test fun authorizedPhase() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allowDsfProduction") == "true")
        val phase = requireNotNull(args.getString("dsfPhase"))
        val out = JSONObject().put("phase", phase).put("startedAtMs", System.currentTimeMillis())
        try {
            main { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
            waitFor(20_000) { app.localAudio.state.value.ready && app.playback.state.value.connected }
            when (phase) {
                "import" -> imports(out)
                "revoke" -> revoke(out)
                "denied_relink" -> deniedRelink(out)
                "queue" -> queue(out)
                "real64" -> realMusic(out, 64)
                "real128" -> realMusic(out, 128)
                "copy_cancel" -> copyCancel(out)
                "switch_setup" -> switchSetup(out)
                "battery_setup" -> batterySetup(out)
                "normal_setup" -> normalSetup(out)
                "normal_restart", "normal_restart_single", "normal_restart_observed" -> normalRestart(out)
                "switch" -> switching(out)
                "cleanup" -> cleanup(out)
                else -> error("Unknown fixed phase")
            }
            out.put("result", "passed")
        } catch (error: Throwable) {
            out.put("result", "failed").put("failureType", error.javaClass.simpleName)
                .put("message", error.message?.takeIf { !it.contains("://") }?.take(500))
            out.put("playbackOnFailure", diagnosticState())
            throw error
        } finally {
            main { app.playback.pause() }
            if (!phase.startsWith("normal_") && phase != "cleanup") gate(false)
            main { companion.javaClass.getMethod("finishSelection").invoke(companion) }
            out.put("gateReset", true).put("finishedAtMs", System.currentTimeMillis())
            out.put("normalCapabilityEnabled", app.dsfPlaybackEnabled)
            val history = if (report.exists()) JSONArray(report.readText()) else JSONArray()
            report.writeText(history.put(out).toString(2))
        }
    }

    private fun diagnosticState(): JSONObject = app.playback.state.value.let { state ->
        JSONObject().put("connected", state.connected).put("playing", state.playing)
            .put("buffering", state.buffering).put("playWhenReady", state.playWhenReady)
            .put("positionMs", state.positionMs).put("durationMs", state.durationMs)
            .put("ended", state.ended).put("stopped", state.stopped)
            .put("queueSize", state.queueSize).put("queueIndex", state.queueIndex)
            .put("localId", state.localAudio?.id).put("repeatMode", state.repeatMode.name)
            .put("suppression", state.suppression.name).put("failure", state.failure?.name)
            .put("localError", state.localError?.name).put("hasError", state.error != null)
    }

    private fun switchSetup(out: JSONObject) {
        check(!marker.exists())
        val (saved, folder) = ownedFolder()
        gate(true)
        for (name in listOf("sample.dsf", "ordinary.flac")) {
            val selected = pick(name)
            val imported = import(folder, selected, LocalImportMode.REFERENCE)
            assertNull(imported.error)
            val id = requireNotNull(imported.audioId)
            saved.getJSONArray("ids").put(id); saved.put(if (name == "sample.dsf") "dsfId" else "ordinaryId", id); save(saved)
        }
        out.put("twoOwnedReferencesImported", true)
    }

    private fun batterySetup(out: JSONObject) {
        check(!marker.exists())
        val (saved, folder) = ownedFolder()
        gate(true)
        val imported = import(folder, pick("sample.dsf"), LocalImportMode.REFERENCE)
        assertNull(imported.error)
        saved.getJSONArray("ids").put(requireNotNull(imported.audioId)); save(saved)
        out.put("singleOwnedDsfReferenceImported", true)
    }

    private fun requireNormalCapability() {
        assertTrue(app.dsfPlaybackEnabled)
        assertFalse(app.javaClass.getMethod("getDsfDeviceAcceptance").invoke(app) as Boolean)
        val productionGetter = MusxiApplication::class.java.getMethod("getDsfPlaybackEnabled")
        assertTrue(productionGetter.invoke(MusxiApplication()) as Boolean)
    }

    /** Never calls the debug gate setter: exercises the ordinary Application capability. */
    private fun normalSetup(out: JSONObject) {
        check(!marker.exists()); requireNormalCapability()
        val (saved, folder) = ownedFolder()
        val importedRows = JSONArray(); saved.put("normalItems", importedRows); save(saved)
        val checks = JSONArray(); out.put("controls", checks)
        for (rate in listOf(64, 128)) {
            val selected = pick("normal$rate.dsf")
            val preview = await<DsfPreviewInfo> { app.localAudio.dsfPreview.preview(selected.uri, it) }
            assertTrue(preview.playbackSupported); assertEquals(rate, preview.dsdMultiplier)
            for (mode in LocalImportMode.entries) {
                val result = import(folder, selected, mode)
                assertNull(result.error)
                val id = requireNotNull(result.audioId)
                saved.getJSONArray("ids").put(id)
                importedRows.put(JSONObject().put("id", id).put("rate", rate).put("mode", mode.name)
                    .put("durationMs", preview.durationMs)); save(saved)
                checks.put(normalControls(id, preview.durationMs))
            }
        }
        requireNormalCapability()
        out.put("normalGateWithoutOverride", true).put("copyAndReference64And128Controls", true)
            .put("importedItems", importedRows.length())
    }

    private fun normalRestart(out: JSONObject) {
        requireNormalCapability()
        assertFalse(app.playback.state.value.playing)
        val saved = read()
        val rows = saved.getJSONArray("normalItems")
        val checks = JSONArray(); out.put("controls", checks)
        assertEquals(4, rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val id = row.getString("id")
            assertTrue(app.localAudio.state.value.items.any { it.id == id })
            checks.put(normalControls(id, row.getLong("durationMs")))
        }
        requireNormalCapability()
        out.put("freshProcessNormalCapability", true).put("allFourSourcesPlayedAfterRestart", true)
    }

    private fun normalControls(id: String, duration: Long): JSONObject {
        // A folder queue advances to its next member. Isolate this source so ENDED
        // actually means its natural end, including after all four items exist.
        play(null, id)
        assertEquals(1, app.playback.state.value.queueSize)
        assertEquals(PlaybackRepeatMode.OFF, app.playback.state.value.repeatMode)
        waitFor(8_000) { app.playback.audioInfo.value.appOutput?.format?.sampleRateHz == 176_400 }
        main { app.playback.seekTo(10_000) }
        waitFor(8_000) { app.playback.state.value.let { it.playing && it.positionMs in 10_000..13_000 } }
        settle()
        val paused = app.playback.state.value.positionMs
        Thread.sleep(1_500)
        assertTrue(kotlin.math.abs(app.playback.state.value.positionMs - paused) <= 100)
        main { app.playback.resume() }
        waitFor(8_000) { app.playback.state.value.let { it.playing && it.positionMs >= paused + 500 } }
        main { app.playback.seekTo(duration - 2_500) }
        waitFor(10_000) { app.playback.state.value.let { it.ended && !it.playing && kotlin.math.abs(it.positionMs - duration) <= 100 } }
        assertNull(app.playback.state.value.failure); assertNull(app.playback.state.value.localError)
        assertNull(app.playback.state.value.error)
        assertEquals(id, app.playback.state.value.localAudio?.id)
        return JSONObject().put("id", id).put("singleItemQueue", true)
            .put("pausedPositionMs", paused).put("naturalEndPositionMs", app.playback.state.value.positionMs)
            .put("durationMs", duration)
    }

    private fun switching(out: JSONObject) {
        val saved = read(); val folder = saved.getJSONArray("folders").getString(0)
        val dsf = saved.getString("dsfId"); val ordinary = saved.getString("ordinaryId")
        gate(true)
        play(folder, dsf)
        main { app.playback.seekTo(25_000) }
        waitFor(10_000) { app.playback.state.value.let { it.playing && it.positionMs in 25_000..29_000 } }
        settle()
        val expectedDsf = app.localAudio.state.value.progress.single { it.audioId == dsf }.positionMs
        play(folder, ordinary)
        assertEquals(ordinary, app.playback.state.value.localAudio?.id)
        assertTrue(app.playback.audioInfo.value.source?.sampleRateHz != 176_400)
        settle()
        await<Unit> { app.localAudioPlayback.play(folder, dsf, fromStart = false, callback = it) }
        waitFor(15_000) { app.playback.state.value.let { it.playing && it.localAudio?.id == dsf && it.positionMs >= expectedDsf - 1_000 } }
        out.put("dsfOrdinaryDsfPreservesProgress", true).put("dsfProgressBeforeRestoreMs", expectedDsf)
        settle()
        val platforms = listOf(tech.musxi.core.account.Platform.KUGOU, tech.musxi.core.account.Platform.QQ, tech.musxi.core.account.Platform.NETEASE)
        waitFor(45_000) {
            platforms.any { app.playbackJournal.state.value.getValue(it).let { state -> state.accountVerified && !state.loading && state.queue != null } }
        }
        val platform = platforms.first { app.playbackJournal.state.value.getValue(it).let { state -> state.accountVerified && !state.loading && state.queue != null } }
        val original = requireNotNull(app.playbackJournal.state.value.getValue(platform).queue)
        out.put("platform", platform.name)
        main { app.playbackJournal.restore(platform) }
        waitFor(20_000) { app.playback.state.value.let { it.localAudio == null && it.queuePlatform == platform && it.queueSize == original.songs.size } }
        assertFalse(app.playback.state.value.playing)
        main { app.playback.resume() }
        waitFor(45_000) { app.playback.state.value.let { it.playing || it.failure != null || it.error != null } }
        assertNull(app.playback.state.value.failure); assertNull(app.playback.state.value.error)
        assertTrue(app.playback.state.value.playing)
        Thread.sleep(2_000)
        settle()
        await<Unit> { app.localAudioPlayback.restoreLastQueue(it) }
        waitFor(20_000) { app.playback.state.value.let { it.localAudio?.id == dsf && !it.playing && !it.playWhenReady } }
        main { app.playback.resume() }
        waitFor(15_000) { app.playback.state.value.let { it.playing && it.localAudio?.id == dsf } }
        out.put("platformPlayedAndPausedLocalQueueRestored", true)
        settle()
        main { app.playbackJournal.restore(platform) }
        waitFor(20_000) { app.playback.state.value.let { it.localAudio == null && it.queuePlatform == platform && !it.playing } }
        assertEquals(original.songs, app.playbackJournal.state.value.getValue(platform).queue?.songs)
        out.put("platformQueueIdentityRetained", true)
    }

    private fun ownedFolder(): Pair<JSONObject, String> {
        val saved = if (marker.exists()) read() else JSONObject().put("cycle", cycle)
            .put("ids", JSONArray()).put("folders", JSONArray()).also(::save)
        if (saved.getJSONArray("folders").length() == 0) {
            val folder = await<LocalAudioFolder> { app.localAudio.createFolder("真实DSF音乐验收20261116", it) }
            saved.getJSONArray("folders").put(folder.id); save(saved)
        }
        return saved to saved.getJSONArray("folders").getString(0)
    }

    /** Actual SAF stream cancellation, without introducing a slow/fake provider. */
    private fun copyCancel(out: JSONObject) {
        val (_, folder) = ownedFolder()
        gate(true)
        val picked = pick("real128.dsf")
        val before = app.localAudio.state.value.items.map { it.id }.toSet()
        val base = File(app.filesDir, "local-audio-v1")
        fun names() = base.walkTopDown().filter { it.isFile }.map { it.relativeTo(base).path }.toSet()
        val beforeFiles = names()
        val done = CompletableFuture<Unit>()
        var observed = 0L
        main {
            val job = app.localAudio.importFiles(folder, listOf(picked.uri), LocalImportMode.COPY, picked.flags)
            job.invokeOnCompletion { done.complete(Unit) }
        }
        waitFor(15_000) {
            val state = app.localAudio.imports.value
            if (state.running && state.copiedBytes > 0 && state.copiedBytes < (state.expectedBytes ?: 0)) {
                observed = state.copiedBytes
                main { app.localAudio.cancelImport() }
                true
            } else {
                check(!done.isDone) { "Copy finished before cancellation observation; no cancellation verdict" }
                false
            }
        }
        done.get(20, TimeUnit.SECONDS)
        assertTrue(app.localAudio.imports.value.cancelled)
        assertEquals(before, app.localAudio.state.value.items.map { it.id }.toSet())
        assertEquals(beforeFiles, names())
        out.put("copiedBytesBeforeCancel", observed).put("actualCopyCancelledWithoutItemOrFileResidue", true)
    }

    private fun realMusic(out: JSONObject, multiplier: Int) {
        val (saved, folder) = ownedFolder()
        check(!saved.has("real$multiplier")) { "Do not replay a completed/imported phase implicitly" }
        val selected = pick("real$multiplier.dsf")
        val preview = await<DsfPreviewInfo> { app.localAudio.dsfPreview.preview(selected.uri, it) }
        assertFalse(preview.playbackSupported)
        assertEquals(multiplier, preview.dsdMultiplier); assertEquals(2, preview.channelCount)
        assertEquals(DsfTagStatus.READ, preview.tagStatus)
        val title = if (multiplier == 64) "星灯" else "The levee broke"
        val artist = if (multiplier == 64) "Suara" else "Robert Len"
        assertEquals(title, preview.tags?.title); assertEquals(artist, preview.tags?.artist)
        gate(true)
        val imported = import(folder, selected, LocalImportMode.REFERENCE)
        assertNull(imported.error)
        val id = requireNotNull(imported.audioId)
        saved.getJSONArray("ids").put(id); saved.put("real$multiplier", id); save(saved)
        val metadata = app.localAudio.state.value.items.single { it.id == id }.metadata
        assertEquals(title, metadata.title); assertEquals(artist, metadata.creator)
        assertEquals(preview.durationMs, metadata.durationMs)
        out.put("multiplier", multiplier).put("durationMs", preview.durationMs).put("title", title)
            .put("artist", artist).put("metadataAndPreview", true)
        val start = SystemClock.elapsedRealtime()
        play(folder, id)
        main { app.playback.setRepeatMode(PlaybackRepeatMode.OFF) }
        waitFor(10_000) { app.playback.audioInfo.value.appOutput != null }
        assertEquals(176_400, app.playback.audioInfo.value.source?.sampleRateHz)
        assertEquals(176_400, app.playback.audioInfo.value.appOutput?.format?.sampleRateHz)
        out.put("startupMs", SystemClock.elapsedRealtime() - start).put("pcmRate", 176_400)
        val wall = SystemClock.elapsedRealtime(); val initial = app.playback.state.value.positionMs
        val samples = JSONArray(); out.put("samples", samples)
        var previous = initial
        while (!app.playback.state.value.ended && SystemClock.elapsedRealtime() - wall < preview.durationMs + 20_000) {
            Thread.sleep(1_000)
            val state = app.playback.state.value
            assertEquals(id, state.localAudio?.id)
            assertNull(state.failure); assertNull(state.localError); assertNull(state.error)
            assertFalse("Sampled buffering", state.buffering)
            assertTrue("Unexpected pause/focus loss", state.playing || state.ended)
            assertTrue("Position reversed", state.positionMs + 100 >= previous)
            previous = state.positionMs
            samples.put(JSONObject().put("wallMs", SystemClock.elapsedRealtime() - wall)
                .put("positionMs", state.positionMs).put("playing", state.playing).put("ended", state.ended)
                .put("pssKb", android.os.Debug.getPss()))
        }
        val end = app.playback.state.value
        assertTrue(end.ended); assertFalse(end.playing)
        assertTrue(kotlin.math.abs(end.positionMs - preview.durationMs) <= 100)
        assertTrue(kotlin.math.abs((SystemClock.elapsedRealtime() - wall) - (end.positionMs - initial)) <= 4_000)
        out.put("fullTrackFromStart", true).put("endPositionMs", end.positionMs)
        play(folder, id); main { app.playback.pause() }
        waitFor(3_000) { !app.playback.state.value.playWhenReady }
        // Reuse the established controller/state acknowledgement check and 100ms pause bound.
        val helper = AudioExpansionDeviceTest()
        helper.javaClass.getDeclaredMethod("assertDsfPausedStable", JSONObject::class.java).apply { isAccessible = true }.invoke(helper, out)
        main { app.playback.resume() }
        waitFor(8_000) { app.playback.state.value.playing }
        val seeks = JSONArray(); out.put("seeks", seeks)
        for (target in listOf(10_000L, preview.durationMs / 2, 5_000L)) {
            main { app.playback.seekTo(target) }
            waitFor(10_000) { app.playback.state.value.let {
                assertNull(it.failure); assertNull(it.localError); assertNull(it.error)
                it.playing && it.positionMs in target..target + 1_500
            } }
            Thread.sleep(500)
            seeks.put(JSONObject().put("targetMs", target).put("actualMs", app.playback.state.value.positionMs))
        }
        main { app.playback.seekTo(preview.durationMs - 2_500) }
        waitFor(10_000) { app.playback.state.value.let { it.ended && !it.playing && kotlin.math.abs(it.positionMs - preview.durationMs) <= 100 } }
        out.put("pauseResumeThreeSeeksAndEnd", true)
    }

    private fun imports(out: JSONObject) {
        check(!marker.exists())
        val saved = JSONObject().put("cycle", cycle).put("ids", JSONArray()).put("folders", JSONArray())
        save(saved)
        val folder = await<LocalAudioFolder> { app.localAudio.createFolder("DSF正式入口验收20261115", it) }
        saved.getJSONArray("folders").put(folder.id); save(saved)
        val selected = pick("acceptance-original.dsf")
        val before = app.localAudio.state.value.items.size
        val closed = await<DsfPreviewInfo> { app.localAudio.dsfPreview.preview(selected.uri, it) }
        assertFalse(closed.playbackSupported)
        for (mode in LocalImportMode.entries) {
            val rejected = import(folder.id, selected, mode)
            assertEquals(LocalAudioError.UNSUPPORTED_AUDIO, rejected.error)
        }
        assertEquals(before, app.localAudio.state.value.items.size)
        out.put("closedGateRejectsCopyAndReference", true)
        gate(true)
        val preview = await<DsfPreviewInfo> { app.localAudio.dsfPreview.preview(selected.uri, it) }
        assertTrue(preview.playbackSupported); assertEquals(64, preview.dsdMultiplier)
        val copy = import(folder.id, selected, LocalImportMode.COPY)
        assertNull(copy.error)
        val copyId = requireNotNull(copy.audioId)
        saved.getJSONArray("ids").put(copyId); saved.put("copyId", copyId); save(saved)
        val reference = import(folder.id, selected, LocalImportMode.REFERENCE)
        assertNull(reference.error)
        val id = requireNotNull(reference.audioId)
        saved.getJSONArray("ids").put(id); saved.put("referenceId", id).put("referenceUri", selected.uri.toString()); save(saved)
        assertNotEquals(copyId, id)
        for (audioId in listOf(copyId, id)) {
            val metadata = app.localAudio.state.value.items.single { it.id == audioId }.metadata
            assertEquals("Musxi SYNTHETIC DSD64 - stereo check 45s", metadata.title)
            assertFalse(metadata.creator.isNullOrBlank()); assertEquals(45_000L, metadata.durationMs)
            assertNull(metadata.artworkKey)
        }
        await<Unit> { app.localAudio.renameAudio(id, "DSF验收自定义名称", it) }
        await<Unit> { app.localAudio.saveProgress(folder.id, LocalAudioProgress(id, 7_000, false, System.currentTimeMillis()), it) }
        val bookmark = await<LocalAudioBookmark> { app.localAudio.addBookmark(id, 8_000, "保留书签", it) }
        saved.put("bookmarkId", bookmark.id); save(saved)
        assertEquals(id, import(folder.id, selected, LocalImportMode.REFERENCE).audioId)
        assertUserData(id, bookmark.id)
        out.put("copyAndReferenceTags", true).put("duplicatePreservesUserData", true)
        // Use a separately selected tree; directory enumeration must not create duplicate tracks.
        val tree = pick(null)
        val directory = await<List<LocalAudioImportResult>> {
            app.localAudio.importDirectory(folder.id, tree.uri, LocalImportMode.COPY, tree.flags, result = it)
        }
        directory.filter { it.audioId != null }.forEach {
            if (it.audioId !in strings(saved.getJSONArray("ids"))) { saved.getJSONArray("ids").put(it.audioId); save(saved) }
        }
        assertTrue(directory.any { it.fileName == "acceptance-original.dsf" && it.audioId == copyId })
        assertTrue(directory.any { it.fileName == "acceptance-unsupported.dsf" && it.error == LocalAudioError.UNSUPPORTED_AUDIO })
        assertTrue(directory.any { it.fileName == "acceptance-broken.dsf" && it.error != null })
        out.put("directoryResults", JSONArray(directory.map { JSONObject().put("name", it.fileName).put("error", it.error?.name ?: "NONE") }))
        val revision = app.localAudio.state.value.revision
        pick("cancel")
        assertEquals(revision, app.localAudio.state.value.revision)
        out.put("pickerCancelPreservesLibrary", true)
        play(folder.id, copyId)
        out.put("copyPlays", true)
    }

    private fun revoke(out: JSONObject) {
        val saved = read()
        val uri = Uri.parse(saved.getString("referenceUri"))
        assertTrue(app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
        app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertFalse(app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
        out.put("ownedReferenceGrantReleased", true)
    }

    private fun deniedRelink(out: JSONObject) {
        val saved = read(); gate(true)
        val id = saved.getString("referenceId")
        val check = await<List<LocalAudioSourceCheckResult>> {
            app.localAudio.checkSources(listOf(id), app.localAudio.state.value.revision, it)
        }.single()
        assertEquals(LocalAudioError.PERMISSION_REQUIRED, check.error)
        assertUserData(id, saved.getString("bookmarkId"))
        play(saved.getJSONArray("folders").getString(0), saved.getString("copyId"))
        settle()
        out.put("permissionDeniedAfterRestart", true).put("copySurvivesRevocation", true)
        val bad = pick("acceptance-broken.dsf")
        val failure = runCatching { await<Unit> { app.localAudio.relink(id, bad.uri, bad.flags, it) } }.exceptionOrNull()
        assertEquals(LocalAudioError.UNSUPPORTED_AUDIO, (failure as? LocalAudioFailure)?.error)
        assertUserData(id, saved.getString("bookmarkId"))
        val good = pick("acceptance-relinked.dsf")
        await<Unit> { app.localAudio.relink(id, good.uri, good.flags, it) }
        assertUserData(id, saved.getString("bookmarkId"))
        assertEquals("acceptance-relinked.dsf", app.localAudio.state.value.items.single { it.id == id }.metadata.fileName)
        play(saved.getJSONArray("folders").getString(0), id)
        out.put("failedRelinkPreservesIdentityAndUserData", true).put("successfulRelinkPreservesUserDataAndPlays", true)
    }

    private fun queue(out: JSONObject) {
        val saved = read(); gate(true)
        val folder = saved.getJSONArray("folders").getString(0)
        val copy = saved.getString("copyId"); val reference = saved.getString("referenceId")
        play(folder, copy)
        val q = app.playback.localQueueState.value
        assertTrue(q.items.size >= 2)
        val index = q.items.indexOfFirst { it.id == reference }; assertTrue(index >= 0)
        await<Unit> { app.playback.jumpToLocalQueueIndex(index, q.revision, fromStart = true, callback = it) }
        waitFor(15_000) { app.playback.state.value.let { it.playing && it.localAudio?.id == reference } }
        main { app.playback.setRepeatMode(PlaybackRepeatMode.ONE); app.playback.seekTo(43_000) }
        waitFor(8_000) { app.playback.state.value.positionMs in 43_000..44_999 }
        waitFor(10_000) { app.playback.state.value.let { it.playing && it.positionMs < 5_000 && it.localAudio?.id == reference } }
        out.put("queueJumpAndRepeatOne", true)
        await<Unit> { app.playback.stopAfterCurrentTrack(it) }
        main { app.playback.seekTo(43_000) }
        waitFor(12_000) { !app.playback.state.value.playWhenReady && !app.playback.state.value.playing && !app.playback.sleepTimer.value.active }
        assertEquals(reference, app.playback.state.value.localAudio?.id)
        out.put("sleepAtTrackEndOverridesRepeat", true)
        settle()
        gate(false)
        val checked = await<List<LocalAudioSourceCheckResult>> {
            app.localAudio.checkSources(listOf(copy, reference), app.localAudio.state.value.revision, it)
        }
        assertEquals(2, checked.size); assertTrue(checked.all { it.error == LocalAudioError.UNSUPPORTED_AUDIO })
        out.put("closedGateRejectsExistingSources", true)
    }

    private fun assertUserData(id: String, bookmark: String) {
        val state = app.localAudio.state.value
        assertEquals("DSF验收自定义名称", state.items.single { it.id == id }.displayName)
        assertEquals(7_000L, state.progress.single { it.audioId == id }.positionMs)
        assertEquals(8_000L, state.bookmarks.single { it.id == bookmark && it.audioId == id }.positionMs)
    }

    private fun cleanup(out: JSONObject) {
        val saved = read(); settle()
        await<Unit> { app.playback.cancelSleepTimer(it) }
        val queue = app.playback.localQueueState.value
        check(queue.items.all { it.id in strings(saved.getJSONArray("ids")) })
        if (queue.items.isNotEmpty()) await<Unit> { app.playback.clearLocalQueue(queue.revision, it) }
        for (id in strings(saved.getJSONArray("ids")).distinct())
            if (app.localAudio.state.value.items.any { it.id == id }) await<Unit> { app.localAudio.deleteAudio(id, it) }
        for (id in strings(saved.getJSONArray("folders")))
            if (app.localAudio.state.value.folders.any { it.id == id }) await<Unit> { app.localAudio.deleteFolder(id, it) }
        check(marker.delete()); out.put("onlyOwnedItemsRemoved", true)
    }

    private data class Picked(val uri: Uri, val flags: Int)
    private fun import(folder: String, picked: Picked, mode: LocalImportMode) = await<List<LocalAudioImportResult>> {
        app.localAudio.importFiles(folder, listOf(picked.uri), mode, picked.flags, it)
    }.single()
    private fun pick(name: String?): Picked {
        instrument.uiAutomation.serviceInfo = instrument.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        main { companion.javaClass.getMethod("finishSelection").invoke(companion) }
        Thread.sleep(300)
        main {
            companion.javaClass.getMethod("clearSelection").invoke(companion)
            app.startActivity(Intent(app, picker).putExtra("operation", if (name == null) "expansion_tree" else "expansion_files")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        fun selected() = companion.javaClass.getMethod("getSelection").invoke(companion)
        waitFor(15_000) { nodes().isNotEmpty() }
        if (name == "cancel") {
            // Back may navigate to the parent first. Only send it while SAF owns focus.
            repeat(6) {
                if (selected() == null) {
                    var window: android.view.accessibility.AccessibilityWindowInfo? = null
                    waitFor(5_000) {
                        window = instrument.uiAutomation.windows.firstOrNull {
                            it.isActive && it.isFocused && it.root?.packageName?.toString() == "com.android.documentsui"
                        }
                        selected() != null || window != null
                    }
                    if (selected() == null) {
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(
                            "input -d ${requireNotNull(window).displayId} keyevent KEYCODE_BACK")).use { it.readBytes() }
                        val deadline = SystemClock.elapsedRealtime() + 1_000
                        while (selected() == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
                    }
                }
            }
        } else if (name != null) {
            waitFor(15_000) { nodes().any { it.text?.toString() == name } }
            click(nodes().first { it.text?.toString() == name })
        }
        waitFor(12_000) {
            if (selected() == null && name != "cancel") nodes().firstOrNull {
                it.isEnabled && it.text?.toString()?.matches(Regex("(?:使用此文件夹|使用此文件夾|允许|允許|添加|打开|选择|确定|USE THIS FOLDER|ALLOW|ADD|OPEN|SELECT)(?:[（(]1[）)])?", RegexOption.IGNORE_CASE)) == true
            }?.let(::click)
            selected() != null
        }
        val value = requireNotNull(selected())
        val accepted = value.javaClass.getMethod("getAccepted").invoke(value) as Boolean
        if (name == "cancel") { assertFalse(accepted); return Picked(Uri.EMPTY, 0) }
        assertTrue(accepted)
        @Suppress("UNCHECKED_CAST")
        return Picked((value.javaClass.getMethod("getUris").invoke(value) as List<Uri>).single(), value.javaClass.getMethod("getFlags").invoke(value) as Int)
    }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || node.packageName?.toString() != "com.android.documentsui") return
            add(node); repeat(node.childCount) { walk(node.getChild(it)) }
        }
        instrument.uiAutomation.windows.forEach { walk(it.root) }
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target = node
        repeat(6) { if (!target.isClickable) target.parent?.let { target = it } }
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(200)
    }
    private fun settle() {
        main { app.playback.pause() }
        waitFor(5_000) { !app.playback.state.value.playWhenReady }
        var revision = app.localAudio.state.value.revision; var since = SystemClock.elapsedRealtime()
        waitFor(10_000) {
            val now = app.localAudio.state.value.revision
            if (now != revision) { revision = now; since = SystemClock.elapsedRealtime() }
            SystemClock.elapsedRealtime() - since >= 600
        }
        await<Unit> { app.playback.checkpointBeforeInstall(it) }
    }
    private fun play(folder: String?, id: String) {
        await<Unit> { app.localAudioPlayback.play(folder, id, fromStart = true, callback = it) }
        waitFor(25_000) { app.playback.state.value.let { it.playing && it.localAudio?.id == id && it.positionMs >= 500 || it.failure != null || it.localError != null } }
        assertNull(app.playback.state.value.localError); assertNull(app.playback.state.value.failure)
        assertTrue(app.playback.state.value.playing)
    }
    private fun waitFor(ms: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + ms
        while (!condition()) { check(SystemClock.elapsedRealtime() < deadline) { "DSF device condition timed out" }; Thread.sleep(100) }
    }
    private fun <T> await(command: ((Result<T>) -> Unit) -> Unit): T {
        val future = CompletableFuture<Result<T>>()
        main { command { future.complete(it) } }
        return future.get(120, TimeUnit.SECONDS).getOrThrow()
    }
}
