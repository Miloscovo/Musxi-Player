package tech.musxi.playback

import kotlinx.coroutines.flow.MutableStateFlow

enum class OutputType { SPEAKER, WIRED, BLUETOOTH, USB, REMOTE, OTHER, UNKNOWN }
data class OutputDevice(val id: String, val type: OutputType, val name: String?, val selected: Boolean,
    val canSelect: Boolean = false)
data class PlaybackOutputs(val devices: List<OutputDevice> = emptyList(), val inferred: Boolean = false)
enum class OutputFailure { SYSTEM_SWITCHER_REQUIRED, NOT_FOUND, REJECTED, TIMEOUT, DISCONNECTED }
class OutputSelectionException(val category: OutputFailure) : Exception(category.name)
internal val outputSnapshot = MutableStateFlow(PlaybackOutputs())

fun OutputDevice.displayName(): String = name?.takeIf { it.isNotBlank() } ?: when (type) {
    OutputType.SPEAKER -> "手机扬声器"
    OutputType.WIRED -> "有线耳机"
    OutputType.BLUETOOTH -> "蓝牙设备"
    OutputType.USB -> "USB 音频设备"
    OutputType.REMOTE -> "投屏设备"
    OutputType.OTHER -> "音频设备"
    OutputType.UNKNOWN -> "未知输出设备"
}

/** Platform access is replaceable, while debounce and selection semantics stay deterministic. */
internal interface OutputPort {
    fun read(): PlaybackOutputs
    fun select(id: String, result: (Result<Unit>) -> Unit)
}
internal class OutputObserver(private val port: OutputPort,
    private val schedule: (Long, () -> Unit) -> (() -> Unit), private val changed: (PlaybackOutputs) -> Unit) {
    var state = PlaybackOutputs(); private set
    private var cancel: (() -> Unit)? = null
    private var closed = false
    fun refresh() {
        if (closed) return
        cancel?.invoke()
        cancel = schedule(150) {
            cancel = null
            if (!closed) {
                val next = port.read()
                if (next != state) { state = next; changed(next) }
            }
        }
    }
    fun select(id: String, result: (Result<Unit>) -> Unit) {
        val device = state.devices.firstOrNull { it.id == id }
        when {
            closed -> result(Result.failure(OutputSelectionException(OutputFailure.DISCONNECTED)))
            device == null -> result(Result.failure(OutputSelectionException(OutputFailure.NOT_FOUND)))
            device.selected -> result(Result.success(Unit))
            !device.canSelect -> result(Result.failure(OutputSelectionException(OutputFailure.SYSTEM_SWITCHER_REQUIRED)))
            else -> port.select(id, result)
        }
    }
    fun close() { closed = true; cancel?.invoke(); cancel = null }
}
