package tech.musxi.playback

import android.media.AudioDeviceInfo
import org.junit.Assert.*
import org.junit.Test

class PlaybackOutputsTest {
    @Test fun excludesBuiltInEarpieceAndTelephonyFromAvailableMediaOutputs() {
        assertFalse(isAvailableMediaOutputType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertFalse(isAvailableMediaOutputType(AudioDeviceInfo.TYPE_TELEPHONY))
        listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HDMI)
            .forEach { assertTrue(isAvailableMediaOutputType(it)) }
    }
    @Test fun mapsMediaOutputsAndProvidesPermissionFreeNames() {
        assertEquals(OutputType.SPEAKER, outputType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(OutputType.WIRED, outputType(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(OutputType.BLUETOOTH, outputType(AudioDeviceInfo.TYPE_BLE_SPEAKER))
        assertEquals(OutputType.USB, outputType(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(OutputType.REMOTE, outputType(AudioDeviceInfo.TYPE_REMOTE_SUBMIX))
        assertEquals("蓝牙设备", OutputDevice("opaque", OutputType.BLUETOOTH, null, true).displayName())
        assertEquals("耳机", OutputDevice("opaque", OutputType.BLUETOOTH, "耳机", true).displayName())
    }
    @Test fun coalescesChangesOnlyPublishesNewStateAndCancelsOnClose() {
        val f = Fixture()
        f.observer.refresh(); f.observer.refresh(); f.observer.refresh()
        assertEquals(1, f.scheduled.size)
        f.flush()
        assertEquals(1, f.published.size)
        f.observer.refresh(); f.flush()
        assertEquals(1, f.published.size)
        f.next = PlaybackOutputs(listOf(OutputDevice("wire", OutputType.WIRED, null, true)))
        f.observer.refresh(); f.flush()
        assertEquals("wire", f.published.last().devices.single().id)
        f.observer.refresh(); f.observer.close(); f.flush()
        assertEquals(2, f.published.size)
    }
    @Test fun selectionDoesNotClaimSuccessUntilPortConfirmsAndUsesSystemFallback() {
        val f = Fixture(); f.observer.refresh(); f.flush()
        var response: Result<Unit>? = null
        f.observer.select("speaker") { response = it }
        assertTrue(response!!.isSuccess)
        f.observer.select("wire") { response = it }
        assertEquals(OutputFailure.SYSTEM_SWITCHER_REQUIRED, (response!!.exceptionOrNull() as OutputSelectionException).category)
        f.observer.select("gone") { response = it }
        assertEquals(OutputFailure.NOT_FOUND, (response!!.exceptionOrNull() as OutputSelectionException).category)
        response = null
        f.observer.select("bluetooth") { response = it }
        assertNull(response)
        assertEquals("speaker", f.observer.state.devices.first { it.selected }.id)
        f.result!!(Result.failure(OutputSelectionException(OutputFailure.REJECTED)))
        assertTrue(response!!.isFailure)
        assertEquals("speaker", f.observer.state.devices.first { it.selected }.id)
    }
    private class Fixture : OutputPort {
        val scheduled = mutableListOf<() -> Unit>()
        val published = mutableListOf<PlaybackOutputs>()
        var next = PlaybackOutputs(listOf(OutputDevice("speaker", OutputType.SPEAKER, null, true),
            OutputDevice("wire", OutputType.WIRED, null, false),
            OutputDevice("bluetooth", OutputType.BLUETOOTH, null, false, true)))
        var result: ((Result<Unit>) -> Unit)? = null
        val observer = OutputObserver(this, { delay, callback ->
            assertEquals(150L, delay); scheduled.add(callback)
            val cancel: () -> Unit = { scheduled.remove(callback) }; cancel
        }, published::add)
        override fun read() = next
        override fun select(id: String, result: (Result<Unit>) -> Unit) { this.result = result }
        fun flush() { scheduled.toList().also { scheduled.clear() }.forEach { it() } }
    }
}
