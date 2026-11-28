package tech.musxi.player

import android.app.KeyguardManager
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import tech.musxi.core.playback.PlaybackFailure
import tech.musxi.playback.PlaybackRepeatMode
import tech.musxi.playback.PlaybackState
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fixed, local, marked test item only. No production gate or system setting is persisted. */
internal object DsfDeviceValidationRunner {
    private var job: Job? = null
    private val steps = setOf("audio_expansion_dsf_start", "audio_expansion_dsf_full",
        "audio_expansion_dsf_controls", "audio_expansion_dsf_stop", "audio_expansion_dsf_snapshot", "audio_expansion_dsf_soak60", "audio_expansion_dsf_battery30")

    fun accepts(step: String) = step in steps

    /** Keep the receiver alive for bounded startup, never for the observation period. */
    fun run(app: MusxiApplication, step: String, startupFinished: () -> Unit) {
        if (step == "audio_expansion_dsf_stop") { job?.cancel(); startupFinished(); return }
        if (step == "audio_expansion_dsf_snapshot") {
            try {
                File(app.filesDir, "audio-expansion-20261114-state.json").writeText(
                    snapshot(app, app.playback.state.value).put("gate", (app as? DebugMusxiApplication)?.dsfDeviceAcceptance)
                        .put("normalCapabilityEnabled", app.dsfPlaybackEnabled)
                        .put("jobActive", job?.isActive == true).toString(2))
            } finally { startupFinished() }
            return
        }
        if (job?.isActive == true) { startupFinished(); return }
        val debug = app as? DebugMusxiApplication ?: run { startupFinished(); return }
        val report = File(app.filesDir, "audio-expansion-20261114-standalone.json")
        val out = JSONObject().put("instrumentationRunning", false).put("status", "starting")
            .put("step", step).put("phase", "waiting_ready")
            .put("startedAtMs", System.currentTimeMillis()).put("samples", JSONArray())
        fun write() { out.put("updatedAtMs", System.currentTimeMillis()); report.writeText(out.toString(2)) }
        var startupPending = true
        fun finishStartup() { if (startupPending) { startupPending = false; startupFinished() } }
        try { write() } catch (error: Exception) { finishStartup(); throw error }
        job = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            var ownedId: String? = null
            var events: Job? = null
            try {
                val started = SystemClock.elapsedRealtime()
                withTimeout(8_000) {
                    combine(app.localAudio.state, app.playback.state) { library, playback ->
                        library.ready && playback.connected
                    }.first { it }
                    out.put("readyMs", SystemClock.elapsedRealtime() - started).put("phase", "loading_owned_item"); write()
                    val saved = JSONObject(File(app.filesDir, "audio-expansion-20261114-marker.json").readText())
                    if (step == "audio_expansion_dsf_soak60") check(saved.getString("cycle") == "dsf-switch-soak-20261116") { "wrong_soak_cycle" }
                    if (step == "audio_expansion_dsf_battery30") check(saved.getString("cycle") == "dsf-battery-device-20261116") { "wrong_battery_cycle" }
                    val ids = saved.getJSONArray("ids").let { array -> (0 until array.length()).map(array::getString).toSet() }
                    val folders = saved.getJSONArray("folders").let { array -> (0 until array.length()).map(array::getString).toSet() }
                    val item = app.localAudio.state.value.items.single { it.id in ids && it.metadata.fileName == "sample.dsf" }
                    val folder = app.localAudio.state.value.members.first { it.audioId == item.id && it.folderId in folders }.folderId
                    ownedId = item.id
                    debug.dsfDeviceAcceptance = true
                    out.put("phase", "submitting_play"); write()
                    command<Unit> { app.localAudioPlayback.play(folder, item.id, fromStart = true, callback = it) }
                    app.playback.setRepeatMode(if (step in setOf("audio_expansion_dsf_soak60", "audio_expansion_dsf_battery30")) PlaybackRepeatMode.ONE else PlaybackRepeatMode.OFF)
                    out.put("phase", "waiting_playback"); write()
                    // A polling delay may never resume after a denied process becomes cached/frozen.
                    val initial = app.playback.state.first { it.playing || it.failure != null || it.localError != null || it.error != null }
                    healthy(initial); check(initial.localAudio?.id == ownedId) { "test_item_changed" }
                }
                out.put("startupMs", SystemClock.elapsedRealtime() - started).put("status", "observing")
                    .put("phase", if (step == "audio_expansion_dsf_controls") "controls" else "continuous")
                write(); finishStartup()
                val wall = SystemClock.elapsedRealtime()
                val cpu = android.os.Process.getElapsedCpuTime()
                val initial = app.playback.state.value
                var bufferingTransitions = 0
                var wasBuffering = false
                events = launch {
                    app.playback.state.collect { state ->
                        if (state.buffering && !wasBuffering) ++bufferingTransitions
                        wasBuffering = state.buffering
                    }
                }
                if (step == "audio_expansion_dsf_battery30") {
                    out.put("status", "waiting_unplug").put("phase", "waiting_physical_power_disconnect")
                        .put("backgroundLoad", "user_game_untouched; whole_device_observation_not_decoder_attribution")
                        .put("batteryBeforeUnplug", battery(app)); write()
                    val waitStarted = SystemClock.elapsedRealtime()
                    while (battery(app).getInt("plugged") != 0) {
                        check(SystemClock.elapsedRealtime() - waitStarted < 15 * 60_000L) { "physical_unplug_not_observed" }
                        healthy(app.playback.state.value)
                        check(app.playback.state.value.playing && app.playback.state.value.localAudio?.id == ownedId) { "interrupted_before_unplug" }
                        delay(1_000)
                    }
                    // Start only once Android reports no AC/USB/wireless/dock power.
                    val startBattery = battery(app)
                    out.put("batteryStart", startBattery).put("unpluggedStartedAtMs", System.currentTimeMillis())
                        .put("status", "observing_unplugged").put("phase", "battery30").put("targetMs", 30 * 60_000L)
                    val baseline = app.playback.state.value
                    val unplugWall = SystemClock.elapsedRealtime()
                    val unplugCpu = android.os.Process.getElapsedCpuTime()
                    var previous = baseline.positionMs
                    var previousWall = unplugWall
                    var advance = 0L
                    var wraps = 0
                    var maxGap = 0L
                    bufferingTransitions = 0
                    write()
                    while (SystemClock.elapsedRealtime() - unplugWall < 30 * 60_000L) {
                        delay(15_000)
                        val power = battery(app)
                        if (power.getInt("plugged") != 0) {
                            out.put("status", "power_reconnected").put("reconnectedBattery", power)
                            break
                        }
                        val state = app.playback.state.value
                        healthy(state)
                        check(state.playing && !state.buffering && state.localAudio?.id == ownedId) { "battery_playback_interrupted" }
                        check(state.durationMs == baseline.durationMs && state.durationMs > 30_000) { "battery_duration_changed" }
                        val delta = state.positionMs - previous
                        if (delta < -1_000) {
                            check(previous >= state.durationMs - 20_000 && state.positionMs < 20_000) { "battery_unexpected_reversal" }
                            advance += state.durationMs - previous + state.positionMs; wraps++
                        } else { check(delta >= -100); advance += delta }
                        val now = SystemClock.elapsedRealtime()
                        maxGap = maxOf(maxGap, now - previousWall); previousWall = now; previous = state.positionMs
                        out.getJSONArray("samples").put(snapshot(app, state).put("wallMs", now - unplugWall).put("battery", power))
                        out.put("wallMs", now - unplugWall).put("advanceMs", advance).put("wraps", wraps)
                            .put("maxSampleGapMs", maxGap).put("bufferingTransitions", bufferingTransitions)
                            .put("cpuMs", android.os.Process.getElapsedCpuTime() - unplugCpu).put("batteryEnd", power)
                        write()
                    }
                    if (out.getString("status") == "observing_unplugged") {
                        check(maxGap <= 30_000 && kotlin.math.abs(advance - out.getLong("wallMs")) <= 15_000) { "battery_throughput_failed" }
                        check(bufferingTransitions == 0) { "battery_buffering_observed" }
                        out.put("status", "passed").put("completed30MinutesUnplugged", true)
                    }
                } else if (step == "audio_expansion_dsf_soak60") {
                    val targetMs = 60 * 60_000L
                    var previous = initial.positionMs
                    var previousWall = wall
                    var wraps = 0
                    var totalAdvance = 0L
                    var maxGap = 0L
                    out.put("phase", "soak60").put("targetMs", targetMs)
                    while (SystemClock.elapsedRealtime() - wall < targetMs) {
                        delay(5_000)
                        val state = app.playback.state.value
                        healthy(state)
                        check(state.localAudio?.id == ownedId && state.playing && !state.buffering) { "soak_playback_interrupted" }
                        check(state.durationMs == initial.durationMs && state.durationMs > 10_000) { "soak_duration_changed" }
                        val delta = state.positionMs - previous
                        if (delta < -1_000) {
                            check(previous >= state.durationMs - 8_000 && state.positionMs < 8_000) { "soak_unexpected_reversal" }
                            wraps++; totalAdvance += state.durationMs - previous + state.positionMs
                        } else {
                            check(delta >= -100) { "soak_position_reversed" }
                            totalAdvance += delta
                        }
                        val now = SystemClock.elapsedRealtime()
                        maxGap = maxOf(maxGap, now - previousWall)
                        previousWall = now; previous = state.positionMs
                        out.getJSONArray("samples").put(snapshot(app, state).put("wallMs", now - wall))
                        out.put("wallMs", now - wall).put("advanceMs", totalAdvance).put("wraps", wraps)
                            .put("maxSampleGapMs", maxGap).put("bufferingTransitions", bufferingTransitions)
                            .put("cpuMs", android.os.Process.getElapsedCpuTime() - cpu)
                        write()
                    }
                    check(wraps >= 10 && maxGap <= 10_000 && kotlin.math.abs(totalAdvance - out.getLong("wallMs")) <= 10_000) { "soak_throughput_failed" }
                    out.put("status", "passed").put("completed60Minutes", true)
                } else if (step == "audio_expansion_dsf_controls") {
                    val actions = JSONArray(); out.put("actions", actions)
                    fun record(label: String) {
                        val state = app.playback.state.value
                        healthy(state); check(state.localAudio?.id == ownedId) { "test_item_changed" }
                        actions.put(snapshot(app, state).put("action", label)); write()
                    }
                    delay(3_000); app.playback.pause()
                    withTimeout(3_000) { app.playback.state.first { !it.playing && !it.playWhenReady } }
                    delay(300)
                    val paused = app.playback.state.value.positionMs
                    delay(1_500)
                    check(kotlin.math.abs(app.playback.state.value.positionMs - paused) <= 100) { "paused_position_advanced" }
                    record("pause_stable")
                    app.playback.resume()
                    withTimeout(8_000) { app.playback.state.first { healthy(it); it.playing } }
                    delay(2_000); record("resume")
                    for (target in listOf(60_000L, 240_000L, 120_000L, 300_000L, 30_000L, 180_000L)) {
                        app.playback.seekTo(target)
                        withTimeout(10_000) {
                            app.playback.state.first { healthy(it); it.playing && it.positionMs in target..target + 2_000 }
                        }
                        delay(750); record("seek_$target")
                    }
                    out.put("status", "passed").put("pauseResumeAndSixSeeks", true)
                } else {
                    val full = step == "audio_expansion_dsf_full"
                    val limit = if (full) initial.durationMs + 30_000 else 125_000L
                    var previousPosition = initial.positionMs
                    var previousWall = wall
                    var maxGapMs = 0L
                    var finished = false
                    out.put("initialPositionMs", initial.positionMs).put("durationMs", initial.durationMs)
                    if (full) check(initial.durationMs > 300_000 && initial.positionMs <= 1_000) { "not_full_track_from_start" }
                    while (SystemClock.elapsedRealtime() - wall < limit) {
                        delay(1_000)
                        val state = app.playback.state.value
                        check(state.localAudio?.id == ownedId) { "test_item_changed" }; healthy(state)
                        check(state.positionMs + 100 >= previousPosition) { "unexpected_position_reversal" }
                        previousPosition = state.positionMs
                        val now = SystemClock.elapsedRealtime()
                        maxGapMs = maxOf(maxGapMs, now - previousWall); previousWall = now
                        out.getJSONArray("samples").put(snapshot(app, state).put("wallMs", now - wall))
                        out.put("cpuMs", android.os.Process.getElapsedCpuTime() - cpu)
                            .put("advanceMs", state.positionMs - initial.positionMs).put("wallMs", now - wall)
                            .put("maxSampleGapMs", maxGapMs).put("bufferingTransitions", bufferingTransitions)
                        write()
                        if (state.ended) { finished = true; break }
                        if (!full && out.getJSONArray("samples").length() >= 120) break
                    }
                    if (full) {
                        check(finished && app.playback.state.value.positionMs >= initial.durationMs - 100) { "track_did_not_finish" }
                        check(kotlin.math.abs(out.getLong("wallMs") - out.getLong("advanceMs")) <= 5_000) { "insufficient_throughput" }
                        out.put("fullTrackFromStart", true)
                    } else check(out.getLong("advanceMs") >= 115_000) { "insufficient_throughput" }
                    check(bufferingTransitions == 0) { "buffering_observed" }
                    out.put("status", "passed")
                }
            } catch (error: TimeoutCancellationException) {
                out.put("status", "timed_out").put("failureType", error.javaClass.simpleName)
            } catch (_: CancellationException) {
                out.put("status", "cancelled")
            } catch (error: Exception) {
                out.put("status", if (app.playback.state.value.failure == PlaybackFailure.BACKGROUND_RESTRICTED)
                    "background_restricted" else "failed").put("failureType", error.javaClass.simpleName)
            } finally {
                events?.cancel()
                if (ownedId != null && app.playback.state.value.localAudio?.id == ownedId) app.playback.pause()
                debug.dsfDeviceAcceptance = false
                out.put("finalState", snapshot(app, app.playback.state.value)).put("gateReset", !debug.dsfDeviceAcceptance)
                    .put("finishedAtMs", System.currentTimeMillis())
                try { write() } finally { finishStartup() }
            }
        }
    }

    private fun healthy(state: PlaybackState) {
        check(state.failure == null && state.localError == null && state.error == null) { "playback_error" }
    }

    private fun battery(app: MusxiApplication): JSONObject {
        val status = requireNotNull(app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        val manager = app.getSystemService(BatteryManager::class.java)
        return JSONObject().put("atElapsedMs", SystemClock.elapsedRealtime())
            .put("plugged", status.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1))
            .put("level", status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
            .put("scale", status.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
            .put("status", status.getIntExtra(BatteryManager.EXTRA_STATUS, -1))
            .put("temperatureTenthsC", status.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1))
            .put("voltageMv", status.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1))
            .put("chargeCounterUah", manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
            .put("currentNowUa", manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
            .put("thermalStatus", if (android.os.Build.VERSION.SDK_INT >= 29)
                app.getSystemService(PowerManager::class.java).currentThermalStatus else JSONObject.NULL)
    }

    private fun snapshot(app: MusxiApplication, state: PlaybackState) = JSONObject()
        .put("atElapsedMs", SystemClock.elapsedRealtime()).put("positionMs", state.positionMs).put("playing", state.playing)
        .put("connected", state.connected).put("buffering", state.buffering).put("playWhenReady", state.playWhenReady)
        .put("ended", state.ended).put("suppression", state.suppression.name)
        .put("failure", state.failure?.name ?: JSONObject.NULL).put("localError", state.localError?.name ?: JSONObject.NULL)
        .put("pssKb", android.os.Debug.getPss()).put("javaHeapBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
        .put("screenInteractive", app.getSystemService(PowerManager::class.java).isInteractive)
        .put("deviceLocked", app.getSystemService(KeyguardManager::class.java).isDeviceLocked)

    private suspend fun <T> command(action: ((Result<T>) -> Unit) -> Job): T = suspendCancellableCoroutine { continuation ->
        val preparation = action { result -> if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException) }
        continuation.invokeOnCancellation { preparation.cancel() }
    }
}
