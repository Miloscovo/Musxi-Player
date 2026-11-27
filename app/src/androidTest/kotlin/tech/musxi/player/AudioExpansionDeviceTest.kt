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

/** Explicit local-only phases. All created IDs are journalled before playback; no platform writes. */
class AudioExpansionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val app get() = instrumentation.targetContext.applicationContext as MusxiApplication
    private val marker get() = File(app.filesDir, "audio-expansion-20261114-marker.json")
    private val report get() = File(app.filesDir, "audio-expansion-20261114-results.json")
    private val picker get() = Class.forName("tech.musxi.player.LocalAudioPickerValidationActivity")
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test fun runAuthorizedPhase() {
        assumeTrue(args.getString("allowAudioExpansion") == "true")
        val phase = requireNotNull(args.getString("expansionPhase"))
        val out = JSONObject().put("phase", phase).put("startedAtMs", System.currentTimeMillis())
        try {
            main { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
            waitFor(20_000) { app.localAudio.state.value.ready && app.playback.state.value.connected }
            when (phase) {
                "cue" -> cue(out)
                "cold" -> cold(out)
                "formats" -> formats(out)
                "cancel" -> cancelPicker(out)
                "dsf" -> dsf(out)
                "dsf64_short" -> dsfShort(out, 64)
                "dsf128_short" -> dsfShort(out, 128)
                "dsf64_short_recheck" -> dsfShort(out, 64, reuseOwned = true)
                "dsf128_short_recheck" -> dsfShort(out, 128, reuseOwned = true)
                "dsf128_pause_probe" -> dsf128PauseProbe(out)
                "dsf_controls" -> dsfControls(out)
                "deletion" -> deletion(out)
                "revoke" -> revoke(out)
                "denied" -> denied(out)
                "cleanup" -> cleanup(out)
                else -> error("Unknown expansion phase")
            }
            out.put("result", "passed")
        } catch (error: Throwable) {
            out.put("result", "failed").put("failureType", error.javaClass.simpleName)
                .put("message", error.message?.takeIf { !it.contains("://") }?.take(500))
            throw error
        } finally {
            main { app.playback.pause() }
            out.put("finishedAtMs", System.currentTimeMillis())
            val history = if (report.exists()) JSONArray(report.readText()) else JSONArray()
            report.writeText(history.put(out).toString(2))
        }
    }

    private fun cue(out: JSONObject) {
        check(!marker.exists()) { "Inspect existing expansion marker first" }
        val saved = JSONObject().put("ids", JSONArray()).put("folders", JSONArray())
        save(saved)
        val cue = pick("album.cue")
        val audio = pick("album-good.flac")
        val preview = await<CueImportPreview> { app.localAudio.cuePreview.preview(cue.uri, listOf(audio.uri), mapOf(0 to 0), it) }
        assertTrue(preview.canProceed); assertEquals(24, preview.tracks.size)
        out.put("cueTracks", preview.tracks.size)
        val copyFolder = folder(saved, "CUE复制")
        val copied = importCue(copyFolder.id, cue, audio, LocalImportMode.COPY)
        copied.forEach { saved.getJSONArray("ids").put(it.id) }; saved.put("copyIds", JSONArray(copied.map { it.id })); save(saved)
        val store = app.localAudio.javaClass.getDeclaredField("store").apply { isAccessible = true }.get(app.localAudio) as LocalAudioStore
        val locations = copied.map { requireNotNull(store.source(it.id)).source.location }.toSet()
        assertEquals(1, locations.size)
        out.put("copySourceCount", locations.size)
        val duplicate = importCue(copyFolder.id, cue, audio, LocalImportMode.COPY)
        assertEquals(copied.map { it.id }, duplicate.map { it.id })
        out.put("duplicateIdsPreserved", true)
        val decoded = JSONArray()
        for (index in listOf(0, 11, 23)) {
            val item = copied[index]
            play(copyFolder.id, item.id)
            val segment = requireNotNull(item.segment)
            assertTrue(app.playback.state.value.durationMs in segment.durationMs - 30..segment.durationMs + 30)
            main { app.playback.seekTo(2_000) }
            waitFor(12_000) { app.playback.state.value.positionMs in 1800..5000 }
            main { app.playback.pause() }
            val facts = await<LocalAudioFormatInfo> { app.localAudio.inspectFormat(item.id, it) }
            assertEquals(44100, facts.sampleRateHz); assertEquals(16, facts.bitsPerSample)
            decoded.put(JSONObject().put("track", index + 1).put("durationMs", app.playback.state.value.durationMs)
                .put("sourceRate", facts.sampleRateHz).put("inputRate", app.playback.audioInfo.value.source?.sampleRateHz)
                .put("appRate", app.playback.audioInfo.value.appOutput?.format?.sampleRateHz))
        }
        out.put("decodedCue", decoded)
        play(copyFolder.id, copied[0].id)
        await<Unit> { app.playback.stopAfterCurrentTrack(it) }
        main { app.playback.seekTo(copied[0].segment!!.durationMs - 1500) }
        waitFor(15_000) { !app.playback.state.value.playing && app.playback.state.value.positionMs >= copied[0].segment!!.durationMs - 100 }
        assertEquals(copied[0].id, app.playback.state.value.localAudio?.id)
        out.put("cueEndTimerStoppedInsideFirstSlice", true)
        await<Unit> { app.playback.cancelSleepTimer(it) }
        play(copyFolder.id, copied[0].id)
        main { app.playback.setRepeatMode(PlaybackRepeatMode.ONE); app.playback.seekTo(copied[0].segment!!.durationMs - 1000) }
        waitFor(10_000) { app.playback.state.value.positionMs >= copied[0].segment!!.durationMs - 1500 }
        waitFor(15_000) { app.playback.state.value.let { it.playing && it.positionMs in 0..3000 && it.localAudio?.id == copied[0].id } }
        out.put("cueRepeatOneStayedInsideSlice", true)
        main { app.playback.pause(); app.playback.setRepeatMode(PlaybackRepeatMode.OFF) }
        play(copyFolder.id, copied[0].id)
        main { app.playback.seekTo(copied[0].segment!!.durationMs - 1000) }
        waitFor(15_000) { app.playback.state.value.let { it.playing && it.localAudio?.id == copied[1].id && it.positionMs < 4000 } }
        main { app.playback.previous() }
        waitFor(10_000) { app.playback.state.value.localAudio?.id == copied[0].id }
        out.put("adjacentNextAndPrevious", true)
        settleLocalWrites()
        await<LocalAudioBookmark> { app.localAudio.addBookmark(copied[0].id, 2_000, "分曲设备检查", it) }
        val refFolder = folder(saved, "CUE引用")
        val referenced = importCue(refFolder.id, cue, audio, LocalImportMode.REFERENCE)
        referenced.forEach { saved.getJSONArray("ids").put(it.id) }
        saved.put("referenceId", referenced[0].id).put("referenceFolder", refFolder.id).put("referenceUri", audio.uri.toString())
        save(saved)
        play(refFolder.id, referenced[0].id)
        main { app.playback.seekTo(3500) }
        waitFor(10_000) { app.playback.state.value.positionMs in 3400..5000 }
        main { app.playback.pause(); app.playback.setRepeatMode(PlaybackRepeatMode.ONE) }
        await<Unit> { app.playback.checkpointBeforeInstall(it) }
        out.put("referencePersisted", app.contentResolver.persistedUriPermissions.any { it.uri == audio.uri && it.isReadPermission })
    }

    private fun cold(out: JSONObject) {
        val saved = read()
        val id = saved.getString("referenceId")
        // Queue metadata and MediaController state arrive through separate callbacks.
        // Seeing the ID alone can still precede the restored repeat/position events.
        waitFor(20_000) { app.playback.localQueueState.value.items.any { it.id == id } &&
            app.playback.state.value.let { it.localAudio?.id == id && it.repeatMode == PlaybackRepeatMode.ONE } }
        assertFalse(app.playback.state.value.playWhenReady)
        assertEquals(PlaybackRepeatMode.ONE, app.playback.state.value.repeatMode)
        assertTrue("Restored paused position missing", app.playback.state.value.positionMs >= 2500)
        assertTrue(app.localAudio.state.value.bookmarks.any { it.audioId in strings(saved.getJSONArray("copyIds")) && it.positionMs == 2000L })
        play(saved.getString("referenceFolder"), id, false)
        waitFor(10_000) { app.playback.state.value.positionMs >= 2500 }
        out.put("pausedQueueRestored", true).put("referenceDecodedAfterRestart", true)
    }

    private fun dsf(out: JSONObject) {
        val saved = read()
        val selected = pick("sample.dsf")
        val preview = await<DsfPreviewInfo> { app.localAudio.dsfPreview.preview(selected.uri, it) }
        assertEquals(256, preview.dsdMultiplier); assertEquals(1, preview.bitsPerSample)
        out.put("previewRate", preview.sampleRateHz).put("tagStatus", preview.tagStatus.name).put("durationMs", preview.durationMs)
        // Debug-only switch is deliberately not persisted and not present in release.
        app.javaClass.getMethod("setDsfDeviceAcceptance", Boolean::class.javaPrimitiveType).invoke(app, true)
        val folder = folder(saved, "DSF引用")
        val imported = await<List<LocalAudioImportResult>> { app.localAudio.importFiles(folder.id, listOf(selected.uri), LocalImportMode.REFERENCE, selected.flags, it) }
        assertNull(imported.single().error)
        val id = requireNotNull(imported.single().audioId)
        saved.getJSONArray("ids").put(id); save(saved)
        val start = SystemClock.elapsedRealtime()
        play(folder.id, id)
        out.put("startupMs", SystemClock.elapsedRealtime() - start)
        waitFor(25_000) { app.playback.audioInfo.value.appOutput != null }
        out.put("pcmInputRate", app.playback.audioInfo.value.source?.sampleRateHz)
            .put("appRate", app.playback.audioInfo.value.appOutput?.format?.sampleRateHz)
            .put("deviceOutputUnknown", app.playback.audioInfo.value.deviceOutput == null)
        val positions = JSONArray()
        out.put("continuous", positions)
        val wall = SystemClock.elapsedRealtime()
        val cpu = android.os.Process.getElapsedCpuTime()
        val startPosition = app.playback.state.value.positionMs
        repeat(6) {
            Thread.sleep(5000)
            val state = app.playback.state.value
            positions.put(JSONObject().put("wallMs", SystemClock.elapsedRealtime() - wall).put("positionMs", state.positionMs)
                .put("playing", state.playing).put("playWhenReady", state.playWhenReady).put("buffering", state.buffering)
                .put("suppression", state.suppression.name))
            out.put("cpuMs", android.os.Process.getElapsedCpuTime() - cpu).put("pssKb", android.os.Debug.getPss())
            assertNull(state.error); assertNull(state.localError)
            assertTrue("DSD playback stalled", state.playing)
        }
        out.put("continuous", positions)
        out.put("cpuMs", android.os.Process.getElapsedCpuTime() - cpu).put("pssKb", android.os.Debug.getPss())
        assertTrue("DSD timeline did not sustain real time", app.playback.state.value.positionMs - startPosition >= 27_000)
        main { app.playback.seekTo(60_000) }
        waitFor(25_000) { app.playback.state.value.let { it.playing && it.positionMs in 59000..65000 } }
        out.put("seekMs", app.playback.state.value.positionMs)
        main { app.playback.seekTo((preview.durationMs - 2500).coerceAtLeast(0)); app.playback.setRepeatMode(PlaybackRepeatMode.OFF) }
        waitFor(25_000) { app.playback.state.value.positionMs >= preview.durationMs - 100 && !app.playback.state.value.playing }
        out.put("endedWithoutPadding", true)
    }

    /** Fixed synthetic samples only; complete short track, then bounded transport controls. */
    private fun dsfShort(out: JSONObject, multiplier: Int, reuseOwned: Boolean = false) {
        check(multiplier == 64 || multiplier == 128)
        val saved = read()
        check(saved.getString("cycle") == "dsf-multirate-device-20261115-v1")
        val name = "musxi-synthetic-dsd$multiplier-stereo-45s.dsf"
        val existing = app.localAudio.state.value.items.filter {
            it.id in strings(saved.getJSONArray("ids")) && it.metadata.fileName == name
        }
        check(if (reuseOwned) existing.size == 1 else existing.isEmpty()) {
            "Owned sample count does not match the fixed phase; inspect before replaying"
        }
        val selected = pick(name)
        val preview = await<DsfPreviewInfo> { app.localAudio.dsfPreview.preview(selected.uri, it) }
        assertEquals(multiplier, preview.dsdMultiplier)
        assertEquals(44_100 * multiplier, preview.sampleRateHz)
        assertEquals(1, preview.bitsPerSample); assertEquals(2, preview.channelCount)
        assertEquals(45_000L, preview.durationMs); assertFalse(preview.playbackSupported)
        assertEquals(DsfTagStatus.READ, preview.tagStatus)
        assertEquals("Musxi SYNTHETIC DSD$multiplier - stereo check 45s", preview.tags?.title)
        out.put("synthetic", true).put("multiplier", multiplier).put("previewRate", preview.sampleRateHz)
            .put("durationMs", preview.durationMs).put("tagStatus", preview.tagStatus.name)
        app.javaClass.getMethod("setDsfDeviceAcceptance", Boolean::class.javaPrimitiveType).invoke(app, true)
        var ownedId: String? = null
        try {
            val folder = if (reuseOwned) {
                val folderId = app.localAudio.state.value.members.single {
                    it.audioId == existing.single().id && it.folderId in strings(saved.getJSONArray("folders"))
                }.folderId
                app.localAudio.state.value.folders.single { it.id == folderId }
            } else folder(saved, "DSD$multiplier 短样")
            val id = if (reuseOwned) existing.single().id else {
                val imported = await<List<LocalAudioImportResult>> {
                    app.localAudio.importFiles(folder.id, listOf(selected.uri), LocalImportMode.REFERENCE, selected.flags, it)
                }.single()
                assertNull(imported.error)
                requireNotNull(imported.audioId).also { saved.getJSONArray("ids").put(it); save(saved) }
            }
            ownedId = id
            out.put("reusedOwnedImport", reuseOwned)
            val start = SystemClock.elapsedRealtime()
            play(folder.id, id)
            main { app.playback.setRepeatMode(PlaybackRepeatMode.OFF) }
            out.put("startupMs", SystemClock.elapsedRealtime() - start)
            waitFor(10_000) { app.playback.audioInfo.value.appOutput != null }
            val audioInfo = app.playback.audioInfo.value
            assertEquals(176_400, audioInfo.source?.sampleRateHz)
            assertEquals(176_400, audioInfo.appOutput?.format?.sampleRateHz)
            out.put("pcmInputRate", audioInfo.source?.sampleRateHz)
                .put("appRate", audioInfo.appOutput?.format?.sampleRateHz)
                .put("deviceOutputUnknown", audioInfo.deviceOutput == null)
            val samples = JSONArray(); out.put("samples", samples)
            val wall = SystemClock.elapsedRealtime()
            val initialPosition = app.playback.state.value.positionMs
            assertTrue("Short sample did not start at the beginning", initialPosition <= 2_000)
            var previous = initialPosition
            var maxGap = 0L; var previousWall = wall
            while (!app.playback.state.value.ended && SystemClock.elapsedRealtime() - wall < 55_000) {
                Thread.sleep(500)
                val state = app.playback.state.value
                assertEquals(id, state.localAudio?.id)
                assertNull(state.error); assertNull(state.localError); assertNull(state.failure)
                assertFalse("Buffering observed in short track", state.buffering)
                assertTrue("Unexpected pause or focus loss", state.playing || state.ended)
                assertTrue("Position reversed", state.positionMs + 100 >= previous)
                val now = SystemClock.elapsedRealtime()
                maxGap = maxOf(maxGap, now - previousWall); previousWall = now; previous = state.positionMs
                samples.put(JSONObject().put("wallMs", now - wall).put("positionMs", state.positionMs)
                    .put("playing", state.playing).put("ended", state.ended).put("buffering", state.buffering))
            }
            val end = app.playback.state.value
            assertTrue("Short track did not end", end.ended)
            // Media3 may retain playWhenReady at STATE_ENDED; ended + !playing is terminal.
            assertFalse(end.playing)
            assertTrue("Wrong end position", kotlin.math.abs(end.positionMs - preview.durationMs) <= 100)
            val elapsed = SystemClock.elapsedRealtime() - wall
            val advance = end.positionMs - initialPosition
            assertTrue("Short sample did not sustain real time", kotlin.math.abs(elapsed - advance) <= 3_000)
            out.put("fullTrackFromStart", true).put("wallMs", elapsed).put("advanceMs", advance)
                .put("endPositionMs", end.positionMs).put("endedPlayWhenReady", end.playWhenReady)
                .put("maxSampleGapMs", maxGap)
            play(folder.id, id)
            Thread.sleep(1_000); main { app.playback.pause() }
            waitFor(3_000) { !app.playback.state.value.playing && !app.playback.state.value.playWhenReady }
            assertDsfPausedStable(out)
            main { app.playback.resume() }
            waitFor(8_000) { app.playback.state.value.playing }
            val seeks = JSONArray(); out.put("seeks", seeks)
            for (target in listOf(10_000L, 30_000L, 5_000L)) {
                main { app.playback.seekTo(target) }
                waitFor(8_000) { app.playback.state.value.let {
                    assertNull(it.failure); assertNull(it.localError); assertNull(it.error)
                    it.playing && it.positionMs in target..target + 1_500
                } }
                Thread.sleep(500)
                val state = app.playback.state.value
                assertEquals(id, state.localAudio?.id); assertTrue(state.playing)
                seeks.put(JSONObject().put("targetMs", target).put("actualMs", state.positionMs))
            }
            main { app.playback.seekTo(42_500) }
            waitFor(8_000) { app.playback.state.value.let {
                assertNull(it.failure); assertNull(it.localError); assertNull(it.error)
                it.ended && !it.playing && kotlin.math.abs(it.positionMs - 45_000) <= 100
            } }
            out.put("pauseResume", true).put("seekAndEnd", true)
        } finally {
            main {
                if (ownedId != null && app.playback.state.value.localAudio?.id == ownedId) app.playback.pause()
                app.javaClass.getMethod("setDsfDeviceAcceptance", Boolean::class.javaPrimitiveType).invoke(app, false)
            }
            out.put("gateResetRequested", true)
        }
    }

    /** MediaController masks pause immediately; await bounded read-back, then retain the 100ms limit. */
    private fun assertDsfPausedStable(out: JSONObject) {
        val controllerField = app.playback.javaClass.getDeclaredField("controller").apply { isAccessible = true }
        val samples = JSONArray(); out.put("pauseReadback", samples)
        val started = SystemClock.elapsedRealtime()
        var stableSince = started
        var anchor: Long? = null
        fun observe(): Long {
            var position = 0L
            main {
                val player = controllerField.get(app.playback) as androidx.media3.common.Player
                val state = app.playback.state.value
                assertFalse(state.playing); assertFalse(state.playWhenReady)
                assertFalse(player.isPlaying); assertFalse(player.playWhenReady)
                assertNull(state.failure); assertNull(state.localError); assertNull(state.error)
                position = player.currentPosition
                samples.put(JSONObject().put("wallMs", SystemClock.elapsedRealtime() - started)
                    .put("statePositionMs", state.positionMs).put("controllerPositionMs", position))
                if (kotlin.math.abs(state.positionMs - position) > 100 || anchor == null ||
                    kotlin.math.abs(position - requireNotNull(anchor)) > 100) {
                    anchor = position; stableSince = SystemClock.elapsedRealtime()
                }
            }
            return position
        }
        waitFor(3_000) {
            observe()
            SystemClock.elapsedRealtime() - stableSince >= 500
        }
        val paused = observe()
        out.put("pauseReadbackSettledMs", SystemClock.elapsedRealtime() - started).put("pausedPositionMs", paused)
        val stableStart = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - stableStart < 1_500) {
            Thread.sleep(100)
            assertTrue("Paused controller position changed after settling", kotlin.math.abs(observe() - paused) <= 100)
            assertTrue("Paused state position changed after settling", kotlin.math.abs(app.playback.state.value.positionMs - paused) <= 100)
        }
        out.put("pauseStableObservedMs", SystemClock.elapsedRealtime() - stableStart)
    }

    /** Records controller acknowledgement separately from the optimistic pause notification. */
    private fun dsf128PauseProbe(out: JSONObject) {
        val saved = read()
        check(saved.getString("cycle") == "dsf-multirate-device-20261115-v1")
        val id = app.localAudio.state.value.items.single {
            it.id in strings(saved.getJSONArray("ids")) &&
                it.metadata.fileName == "musxi-synthetic-dsd128-stereo-45s.dsf"
        }.id
        val folder = app.localAudio.state.value.members.single {
            it.audioId == id && it.folderId in strings(saved.getJSONArray("folders"))
        }.folderId
        val controllerField = app.playback.javaClass.getDeclaredField("controller").apply { isAccessible = true }
        val samples = JSONArray(); out.put("pauseSamples", samples)
        fun record(start: Long) = main {
            val state = app.playback.state.value
            val player = controllerField.get(app.playback) as androidx.media3.common.Player
            samples.put(JSONObject().put("wallMs", SystemClock.elapsedRealtime() - start)
                .put("positionMs", state.positionMs).put("sampleAtMs", state.positionSampledAtElapsedMs)
                .put("playing", state.playing).put("playWhenReady", state.playWhenReady)
                .put("controllerPositionMs", player.currentPosition).put("controllerPlaying", player.isPlaying)
                .put("controllerReady", player.playWhenReady).put("controllerState", player.playbackState))
            assertEquals(id, state.localAudio?.id)
            assertNull(state.failure); assertNull(state.localError); assertNull(state.error)
        }
        app.javaClass.getMethod("setDsfDeviceAcceptance", Boolean::class.javaPrimitiveType).invoke(app, true)
        try {
            play(folder, id)
            main { app.playback.setRepeatMode(PlaybackRepeatMode.OFF); app.playback.seekTo(42_500) }
            waitFor(8_000) { app.playback.state.value.ended }
            play(folder, id)
            Thread.sleep(1_000)
            val start = SystemClock.elapsedRealtime()
            record(start)
            main { app.playback.pause() }
            repeat(40) { record(start); Thread.sleep(100) }
            val rows = (0 until samples.length()).map(samples::getJSONObject)
            val strict = rows.first { it.getLong("wallMs") >= 300 }
            val after = rows.first { it.getLong("wallMs") >= 1_800 }
            out.put("originalWindowDeltaMs", after.getLong("positionMs") - strict.getLong("positionMs"))
            val settled = rows.filter { it.getLong("wallMs") >= 2_000 }
            assertTrue(settled.all { !it.getBoolean("playing") && !it.getBoolean("playWhenReady") &&
                !it.getBoolean("controllerPlaying") && !it.getBoolean("controllerReady") })
            assertTrue("Pause still changing after acknowledgement",
                settled.maxOf { it.getLong("controllerPositionMs") } - settled.minOf { it.getLong("controllerPositionMs") } <= 100)
            out.put("pauseStableAfter2s", true)
            main { app.playback.resume() }
            waitFor(8_000) { app.playback.state.value.playing }
            val seeks = JSONArray(); out.put("seeks", seeks)
            for (target in listOf(10_000L, 30_000L, 5_000L)) {
                main { app.playback.seekTo(target) }
                waitFor(8_000) { app.playback.state.value.let { it.playing && it.positionMs in target..target + 1_500 } }
                Thread.sleep(500)
                val state = app.playback.state.value
                assertEquals(id, state.localAudio?.id)
                assertNull(state.failure); assertNull(state.localError); assertNull(state.error)
                seeks.put(JSONObject().put("targetMs", target).put("actualMs", state.positionMs))
            }
            main { app.playback.seekTo(42_500) }
            waitFor(8_000) { app.playback.state.value.let { it.ended && !it.playing && kotlin.math.abs(it.positionMs - 45_000) <= 100 } }
            out.put("resumeAndSeeks", true)
        } finally {
            main {
                if (app.playback.state.value.localAudio?.id == id) app.playback.pause()
                app.javaClass.getMethod("setDsfDeviceAcceptance", Boolean::class.javaPrimitiveType).invoke(app, false)
            }
        }
    }

    private fun formats(out: JSONObject) {
        val saved = read()
        val folder = folder(saved, "格式观察")
        val rows = JSONArray()
        out.put("formats", rows)
        for ((name, codec, rate) in listOf(Triple("format-96k24-plain.wav", "PCM", 96000), Triple("format-48k.mp3", "MP3", 48000))) {
            val selected = pick(name)
            val imported = await<List<LocalAudioImportResult>> {
                app.localAudio.importFiles(folder.id, listOf(selected.uri), LocalImportMode.REFERENCE, selected.flags, it)
            }.single()
            assertNull(imported.error)
            val id = requireNotNull(imported.audioId)
            saved.getJSONArray("ids").put(id); save(saved)
            play(folder.id, id)
            waitFor(10_000) { app.playback.audioInfo.value.source?.sampleRateHz == rate }
            val info = app.playback.audioInfo.value
            assertEquals(codec, info.source?.codec)
            assertNull(info.deviceOutput); assertNull(info.decoded)
            val row = JSONObject().put("file", name).put("inputCodec", info.source?.codec)
                .put("inputRate", info.source?.sampleRateHz).put("inputBits", info.source?.bitsPerSample)
                .put("decoder", info.decoderName).put("appRate", info.appOutput?.format?.sampleRateHz)
                .put("deviceOutputUnknown", info.deviceOutput == null)
            if (codec == "PCM") {
                val facts = await<LocalAudioFormatInfo> { app.localAudio.inspectFormat(id, it) }
                assertEquals(96000, facts.sampleRateHz); assertEquals(24, facts.bitsPerSample)
                row.put("headerBits", facts.bitsPerSample)
            }
            rows.put(row)
        }
        main { app.playback.stop() }
        waitFor(10_000) { app.playback.audioInfo.value.appOutput == null && app.playback.audioInfo.value.decoderName == null }
        out.put("stoppedOutputCleared", true)
    }

    private fun dsfControls(out: JSONObject) {
        val saved = read()
        val owned = strings(saved.getJSONArray("ids")).toSet()
        val folders = strings(saved.getJSONArray("folders")).toSet()
        val item = app.localAudio.state.value.items.single { it.id in owned && it.metadata.fileName == "sample.dsf" }
        val folder = app.localAudio.state.value.members.first { it.audioId == item.id && it.folderId in folders }.folderId
        app.javaClass.getMethod("setDsfDeviceAcceptance", Boolean::class.javaPrimitiveType).invoke(app, true)
        play(folder, item.id)
        main { app.playback.setRepeatMode(PlaybackRepeatMode.OFF); app.playback.seekTo(60_000) }
        waitFor(30_000) { app.playback.state.value.let { it.playing && it.positionMs in 59000..65000 } }
        out.put("seekMs", app.playback.state.value.positionMs)
        val duration = requireNotNull(item.metadata.durationMs)
        main { app.playback.seekTo(duration - 2500) }
        waitFor(30_000) { app.playback.state.value.let { it.ended && !it.playing && it.positionMs >= duration - 100 } }
        out.put("endedAtMs", app.playback.state.value.positionMs).put("durationMs", duration)
            .put("endedWithoutPadding", true).put("realtimeAcceptance", false)
    }

    private fun deletion(out: JSONObject) {
        val saved = read()
        val ids = strings(saved.getJSONArray("copyIds"))
        val folder = app.localAudio.state.value.members.first { it.audioId == ids[1] }.folderId
        settleLocalWrites()
        await<Unit> { app.localAudio.deleteAudio(ids[0], it) }
        assertFalse(app.localAudio.state.value.items.any { it.id == ids[0] })
        play(folder, ids[1])
        val store = app.localAudio.javaClass.getDeclaredField("store").apply { isAccessible = true }.get(app.localAudio) as LocalAudioStore
        assertTrue(requireNotNull(store.source(ids[1])).source.location in store.retainedCopyNames())
        out.put("oneSliceDeleted", true).put("sharedCopyRetainedAndOtherSlicePlays", true)
    }

    private fun cancelPicker(out: JSONObject) {
        val info = instrumentation.uiAutomation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo = info
        val before = app.localAudio.state.value
        val companion = picker.getDeclaredField("Companion").get(null)
        main {
            companion.javaClass.getMethod("clearSelection").invoke(companion)
            app.startActivity(Intent(app, picker).putExtra("operation", "expansion_files").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        waitFor(15_000) { nodes().isNotEmpty() }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        var selection: Any? = null
        waitFor(10_000) { selection = companion.javaClass.getMethod("getSelection").invoke(companion); selection != null }
        assertFalse(requireNotNull(selection).javaClass.getMethod("getAccepted").invoke(selection) as Boolean)
        assertEquals(before.items, app.localAudio.state.value.items)
        assertEquals(before.folders, app.localAudio.state.value.folders)
        out.put("cancelledWithoutLibraryMutation", true)
    }

    private fun revoke(out: JSONObject) {
        val saved = read()
        val id = saved.getString("referenceId")
        val uri = Uri.parse(saved.getString("referenceUri"))
        app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        out.put("grantReleased", true).put("audioIdRetained", app.localAudio.state.value.items.any { it.id == id })
        // Actual denial is checked after the next process restart, without temporary picker grants.
    }

    private fun denied(out: JSONObject) {
        val saved = read()
        val id = saved.getString("referenceId")
        val uri = Uri.parse(saved.getString("referenceUri"))
        assertFalse(app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
        val failure = runCatching { await<LocalAudioFormatInfo> { app.localAudio.inspectFormat(id, it) } }.exceptionOrNull()
        assertEquals(LocalAudioError.PERMISSION_REQUIRED, (failure as? LocalAudioFailure)?.error)
        val checked = await<List<LocalAudioSourceCheckResult>> {
            app.localAudio.checkSources(listOf(id), app.localAudio.state.value.revision, it)
        }
        assertEquals(LocalAudioError.PERMISSION_REQUIRED, checked.single().error)
        assertTrue(app.localAudio.state.value.items.any { it.id == id })
        out.put("permissionRequiredAfterRestart", true).put("referenceIdPreserved", true)
        // The independent COPY must still work after the original document's grant is removed.
        val copyId = strings(saved.getJSONArray("copyIds")).first()
        val copyFolder = app.localAudio.state.value.members.first { it.audioId == copyId }.folderId
        play(copyFolder, copyId)
        out.put("independentCopyStillPlays", true)
    }

    private fun cleanup(out: JSONObject) {
        val saved = read()
        main { app.playback.pause() }
        await<Unit> { app.playback.cancelSleepTimer(it) }
        val queue = app.playback.localQueueState.value
        if (queue.items.isNotEmpty()) await<Unit> { app.playback.clearLocalQueue(queue.revision, it) }
        for (id in strings(saved.getJSONArray("ids")).distinct())
            if (app.localAudio.state.value.items.any { it.id == id }) await<Unit> { app.localAudio.deleteAudio(id, it) }
        for (id in strings(saved.getJSONArray("folders")))
            if (app.localAudio.state.value.folders.any { it.id == id }) await<Unit> { app.localAudio.deleteFolder(id, it) }
        app.javaClass.getMethod("setDsfDeviceAcceptance", Boolean::class.javaPrimitiveType).invoke(app, false)
        check(marker.delete()); out.put("onlyMarkedItemsRemoved", true)
    }

    private fun folder(saved: JSONObject, label: String): LocalAudioFolder {
        val folder = await<LocalAudioFolder> { app.localAudio.createFolder("扩展验收20261114 $label", it) }
        saved.getJSONArray("folders").put(folder.id); save(saved); return folder
    }
    private fun importCue(folder: String, cue: Picked, audio: Picked, mode: LocalImportMode): List<LocalAudioItem> {
        // A pause queues a progress write. Flush it before freezing the library's revision;
        // otherwise the correct import fence rejects our own preceding playback checkpoint.
        settleLocalWrites()
        await<CueImportPreview> { app.localAudio.cuePreview.preview(cue.uri, listOf(audio.uri), mapOf(0 to 0), it) }
        return await { app.localAudio.importCue(folder, cue.uri, listOf(audio.uri), app.localAudio.cuePreview.state.value.revision,
            app.localAudio.state.value.revision, mapOf(0 to 0), mode, audio.flags, it) }
    }
    private fun settleLocalWrites() {
        main { app.playback.pause() }
        waitFor(10_000) { !app.playback.state.value.playWhenReady }
        // onEvents coalesces a checkpoint 200ms later, including the pause/repeat events.
        // An explicit flush alone does not cancel that pending checkpoint.
        var revision = app.localAudio.state.value.revision
        var unchangedSince = SystemClock.elapsedRealtime()
        waitFor(10_000) {
            val current = app.localAudio.state.value.revision
            if (current != revision) { revision = current; unchangedSince = SystemClock.elapsedRealtime() }
            SystemClock.elapsedRealtime() - unchangedSince >= 500
        }
        await<Unit> { app.playback.checkpointBeforeInstall(it) }
    }
    private fun play(folder: String, id: String, fromStart: Boolean = true) {
        await<Unit> { app.localAudioPlayback.play(folder, id, fromStart = fromStart, callback = it) }
        waitFor(30_000) { app.playback.state.value.let { it.localAudio?.id == id && it.playing && it.positionMs >= 350 || it.error != null || it.localError != null } }
        val state = app.playback.state.value
        assertTrue("Playback failed: ${state.error}/${state.localError}", state.playing)
    }
    private data class Picked(val uri: Uri, val flags: Int)
    private fun pick(name: String): Picked {
        val info = instrumentation.uiAutomation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo = info
        val companion = picker.getDeclaredField("Companion").get(null)
        main {
            companion.javaClass.getMethod("clearSelection").invoke(companion)
            app.startActivity(Intent(app, picker).putExtra("operation", "expansion_files").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        fun selected(): Any? = companion.javaClass.getMethod("getSelection").invoke(companion)
        var node: AccessibilityNodeInfo? = null
        waitFor(15_000) { node = nodes().firstOrNull { it.text?.toString() == name }; node != null }
        click(requireNotNull(node))
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (selected() == null && SystemClock.elapsedRealtime() < deadline) {
            nodes().firstOrNull { it.text?.toString()?.matches(Regex("(?:添加|打开|选择|确定|ADD|OPEN|SELECT)(?:[（(]1[）)])?", RegexOption.IGNORE_CASE)) == true }?.let(::click)
            Thread.sleep(100)
        }
        val value = requireNotNull(selected()) { "System picker did not return selection" }
        assertTrue(value.javaClass.getMethod("getAccepted").invoke(value) as Boolean)
        @Suppress("UNCHECKED_CAST")
        return Picked((value.javaClass.getMethod("getUris").invoke(value) as List<Uri>).single(), value.javaClass.getMethod("getFlags").invoke(value) as Int)
    }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || node.packageName?.toString()?.contains("documentsui") != true) return
            add(node); repeat(node.childCount) { walk(node.getChild(it)) }
        }
        instrumentation.uiAutomation.windows.forEach { walk(it.root) }
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target = node
        repeat(6) { if (!target.isClickable) target.parent?.let { target = it } }
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(200)
    }
    private fun read() = JSONObject(marker.readText())
    private fun save(saved: JSONObject) = marker.writeText(saved.toString(2))
    private fun strings(array: JSONArray) = (0 until array.length()).map(array::getString)
    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) { check(SystemClock.elapsedRealtime() < deadline) { "Expansion condition timed out" }; Thread.sleep(80) }
    }
    private fun <T> await(command: ((Result<T>) -> Unit) -> Unit): T {
        val future = CompletableFuture<Result<T>>()
        main { command { future.complete(it) } }
        return future.get(180, TimeUnit.SECONDS).getOrThrow()
    }
}
