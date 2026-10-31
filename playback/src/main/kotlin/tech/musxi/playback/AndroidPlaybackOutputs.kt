package tech.musxi.playback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.Handler
import java.util.UUID
import java.util.concurrent.Executor

/** Service-owned passive observation. Never requests Bluetooth permission or changes audio mode. */
internal class AndroidPlaybackOutputs(private val context: Context, private val main: Handler,
    private val changed: (PlaybackOutputs) -> Unit) : OutputPort {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val identities = mutableMapOf<String, String>()
    private var routesById = emptyMap<String, MediaRoute2Info>()
    private var pending: Pair<String, (Result<Unit>) -> Unit>? = null
    private val timeout = Runnable { finish(OutputFailure.TIMEOUT) }
    private val observer = OutputObserver(this, { delay, task ->
        val runnable = Runnable(task); main.postDelayed(runnable, delay)
        val cancel: () -> Unit = { main.removeCallbacks(runnable) }; cancel
    }, changed)
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = observer.refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = observer.refresh()
    }
    private var router: Router? = null
    fun start() {
        audio.registerAudioDeviceCallback(callback, main)
        if (Build.VERSION.SDK_INT >= 30) router = runCatching { Router() }.getOrNull()
        observer.refresh()
    }
    fun close() {
        observer.close(); audio.unregisterAudioDeviceCallback(callback)
        router?.close(); router = null
        finish(OutputFailure.DISCONNECTED)
    }
    fun selectOutput(id: String, result: (Result<Unit>) -> Unit) = observer.select(id, result)
    override fun read(): PlaybackOutputs {
        val devices = runCatching { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink && isAvailableMediaOutputType(it.type) } }.getOrDefault(emptyList())
        val actual = if (Build.VERSION.SDK_INT >= 33) runCatching {
            audio.getAudioDevicesForAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).map { it.id }.toSet()
        }.getOrNull() else null
        val inferred = actual.isNullOrEmpty()
        val selected = actual?.takeIf { it.isNotEmpty() } ?: devices.maxByOrNull { priority(outputType(it.type)) }?.let { setOf(it.id) }.orEmpty()
        val local = devices.map { device ->
            val type = outputType(device.type)
            OutputDevice(id("audio:${device.id}"), type, safeName(type) { device.productName }, device.id in selected)
        }
        val routeDevices = if (Build.VERSION.SDK_INT >= 30) runCatching { router?.devices().orEmpty() }.getOrDefault(emptyList()) else emptyList()
        val remoteSelected = routeDevices.any { it.selected && it.type == OutputType.REMOTE }
        val routeTypes = routeDevices.map { it.type }.toSet() - OutputType.UNKNOWN
        val combined = local.filter { it.type !in routeTypes }.map { if (remoteSelected) it.copy(selected = false) else it } + routeDevices
        return PlaybackOutputs(combined, inferred && !remoteSelected)
    }
    override fun select(id: String, result: (Result<Unit>) -> Unit) {
        if (pending != null) return result(Result.failure(OutputSelectionException(OutputFailure.REJECTED)))
        val route = routesById[id]
        if (Build.VERSION.SDK_INT < 35 || route == null || router == null)
            return result(Result.failure(OutputSelectionException(OutputFailure.SYSTEM_SWITCHER_REQUIRED)))
        pending = id to result
        main.postDelayed(timeout, 8_000)
        try { router!!.transfer(route) } catch (_: RuntimeException) { finish(OutputFailure.REJECTED) }
    }
    private fun finish(failure: OutputFailure? = null) {
        main.removeCallbacks(timeout)
        val result = pending?.second; pending = null
        result?.invoke(if (failure == null) Result.success(Unit) else Result.failure(OutputSelectionException(failure)))
    }
    private fun id(key: String) = identities.getOrPut(key) { UUID.randomUUID().toString() }
    private fun safeName(type: OutputType, read: () -> CharSequence): String? {
        if (type == OutputType.SPEAKER) return null
        if (type == OutputType.BLUETOOTH && Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching { read().toString().filterNot(Char::isISOControl).take(120).trim().takeIf(String::isNotBlank) }.getOrNull()
    }
    @android.annotation.TargetApi(30)
    private inner class Router {
        private val manager = MediaRouter2.getInstance(context)
        private val executor = Executor { main.post(it) }
        private val routeCallback = object : MediaRouter2.RouteCallback() {
            override fun onRoutesAdded(routes: MutableList<MediaRoute2Info>) = observer.refresh()
            override fun onRoutesRemoved(routes: MutableList<MediaRoute2Info>) = observer.refresh()
            override fun onRoutesChanged(routes: MutableList<MediaRoute2Info>) = observer.refresh()
            override fun onRoutesUpdated(routes: MutableList<MediaRoute2Info>) = observer.refresh()
        }
        private val controllerCallback = object : MediaRouter2.ControllerCallback() {
            override fun onControllerUpdated(controller: MediaRouter2.RoutingController) {
                checkTransfer(); observer.refresh()
            }
        }
        private val transferCallback = object : MediaRouter2.TransferCallback() {
            override fun onTransfer(oldController: MediaRouter2.RoutingController, newController: MediaRouter2.RoutingController) {
                checkTransfer(); observer.refresh()
            }
            override fun onTransferFailure(requestedRoute: MediaRoute2Info) { finish(OutputFailure.REJECTED); observer.refresh() }
            override fun onStop(controller: MediaRouter2.RoutingController) = observer.refresh()
        }
        init {
            manager.registerRouteCallback(executor, routeCallback,
                RouteDiscoveryPreference.Builder(listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO), false).build())
            manager.registerControllerCallback(executor, controllerCallback)
            manager.registerTransferCallback(executor, transferCallback)
        }
        private fun selected(): List<MediaRoute2Info> {
            val remote = manager.controllers.filter { it.id != manager.systemController.id && !it.isReleased }
                .flatMap { it.selectedRoutes }
            return remote.ifEmpty { manager.systemController.selectedRoutes }
        }
        fun devices(): List<OutputDevice> {
            val selected = selected().map { it.id }.toSet()
            val transferable = if (Build.VERSION.SDK_INT >= 35)
                manager.controllers.flatMap { it.transferableRoutes }.map { it.id }.toSet() else emptySet()
            val routes = (manager.routes + selected()).distinctBy { it.id }.filter {
                (it.id in selected || it.id in transferable) &&
                    (Build.VERSION.SDK_INT >= 34 || !it.isSystemRoute)
            }
            routesById = routes.associateBy { id("route:${it.id}") }
            return routesById.map { (id, route) ->
                val type = if (!route.isSystemRoute || route.features.any { it == MediaRoute2Info.FEATURE_REMOTE_PLAYBACK || it == MediaRoute2Info.FEATURE_REMOTE_AUDIO_PLAYBACK })
                    OutputType.REMOTE else if (Build.VERSION.SDK_INT >= 34) outputType(route.type) else OutputType.UNKNOWN
                OutputDevice(id, type, safeName(type) { route.name }, route.id in selected,
                    Build.VERSION.SDK_INT >= 35 && route.id in transferable)
            }
        }
        fun transfer(route: MediaRoute2Info) = manager.transferTo(route)
        private fun checkTransfer() {
            val id = pending?.first ?: return
            if (selected().any { id("route:${it.id}") == id }) finish()
        }
        fun close() {
            manager.unregisterRouteCallback(routeCallback)
            manager.unregisterControllerCallback(controllerCallback)
            manager.unregisterTransferCallback(transferCallback)
        }
    }
}

internal fun isAvailableMediaOutputType(type: Int): Boolean =
    type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE && type != AudioDeviceInfo.TYPE_TELEPHONY

internal fun outputType(type: Int): OutputType = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> OutputType.SPEAKER
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> OutputType.WIRED
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID -> OutputType.BLUETOOTH
    AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> OutputType.USB
    AudioDeviceInfo.TYPE_REMOTE_SUBMIX, AudioDeviceInfo.TYPE_IP, AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> OutputType.REMOTE
    AudioDeviceInfo.TYPE_UNKNOWN -> OutputType.UNKNOWN
    else -> OutputType.OTHER
}
private fun priority(type: OutputType): Int = when (type) {
    OutputType.BLUETOOTH -> 5; OutputType.WIRED, OutputType.USB -> 4; OutputType.REMOTE -> 3
    OutputType.SPEAKER -> 2; else -> 1
}
