package tech.musxi.player

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Passive, bounded debug probe. Never records names/IDs or sends playback/routing commands. */
internal object OutputTraceValidationRunner {
    private const val REPORT = "output-trace-validation.json"
    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var cleanup: (() -> Unit)? = null
    private var events = JSONArray()
    private var startedMs = 0L
    private val expire = Runnable { stop("TIME_LIMIT") }
    private var application: MusxiApplication? = null

    fun run(app: MusxiApplication, step: String): Boolean {
        when (step) {
            "output_trace_start" -> start(app)
            "output_trace_snapshot" -> if (scope != null) record("CHECKPOINT")
            "output_trace_stop" -> stop("REQUESTED")
            else -> return false
        }
        return true
    }

    private fun start(app: MusxiApplication) {
        stop("RESTARTED")
        application = app
        events = JSONArray()
        startedMs = SystemClock.elapsedRealtime()
        val active = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = active
        val audio = app.getSystemService(AudioManager::class.java)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) =
                deviceEvent("AUDIO_ADDED", addedDevices)
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) =
                deviceEvent("AUDIO_REMOVED", removedDevices)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) record("NOISY")
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else app.registerReceiver(receiver, filter)
        audio.registerAudioDeviceCallback(callback, main)
        cleanup = {
            audio.unregisterAudioDeviceCallback(callback)
            app.unregisterReceiver(receiver)
        }
        record("START")
        active.launch {
            app.playback.outputs.collect { record("OUTPUTS") }
        }
        active.launch {
            app.playback.state.map {
                listOf(it.connected, it.playing, it.playWhenReady, it.buffering,
                    it.queueSize, it.qualityName, it.suppression.name, it.error != null)
            }.distinctUntilChanged().collect { record("STATE") }
        }
        main.postDelayed(expire, 15 * 60_000L)
    }

    private fun deviceEvent(kind: String, devices: Array<out AudioDeviceInfo>) {
        if (devices.none { it.isSink }) return
        record(kind, JSONObject().put("audioTypes", JSONArray(devices.filter { it.isSink }
            .map { it.type }.distinct().sorted())))
    }

    private fun record(kind: String, extra: JSONObject = JSONObject()) {
        val app = application ?: return
        if (scope == null) return
        val state = app.playback.state.value
        val outputs = app.playback.outputs.value
        extra.put("kind", kind)
            .put("elapsedMs", SystemClock.elapsedRealtime())
            .put("connected", state.connected)
            .put("playing", state.playing)
            .put("playWhenReady", state.playWhenReady)
            .put("buffering", state.buffering)
            .put("positionMs", state.positionMs)
            .put("queueSize", state.queueSize)
            .put("qualityName", state.qualityName ?: JSONObject.NULL)
            .put("inferred", outputs.inferred)
            .put("devices", JSONArray(outputs.devices.map { device ->
                JSONObject().put("type", device.type.name).put("selected", device.selected)
                    .put("canSelect", device.canSelect).put("namePresent", !device.name.isNullOrBlank())
            }))
        events.put(extra)
        if (events.length() >= 256) stop("EVENT_LIMIT") else write()
    }

    private fun stop(reason: String) {
        if (scope == null) return
        main.removeCallbacks(expire)
        scope?.cancel()
        scope = null
        cleanup?.invoke()
        cleanup = null
        events.put(JSONObject().put("kind", "STOP").put("reason", reason)
            .put("elapsedMs", SystemClock.elapsedRealtime()))
        write()
    }

    private fun write() {
        val app = application ?: return
        val report = JSONObject().put("schemaVersion", 1).put("sdk", Build.VERSION.SDK_INT)
            .put("startedMs", startedMs).put("active", scope != null)
            .put("bluetoothConnectGranted", Build.VERSION.SDK_INT < 31 ||
                app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
            .put("events", events)
        val target = File(app.filesDir, REPORT)
        val temporary = File(app.filesDir, "$REPORT.tmp")
        temporary.writeText(report.toString())
        check(temporary.renameTo(target))
    }
}
