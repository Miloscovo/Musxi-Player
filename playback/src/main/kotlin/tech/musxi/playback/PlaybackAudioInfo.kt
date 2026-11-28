package tech.musxi.playback

import kotlinx.coroutines.flow.MutableStateFlow

/** A fact at one boundary only. Null means unobserved, never a guessed standard value. */
data class AudioFormatFacts(val codec: String? = null, val sampleRateHz: Int? = null,
    val bitsPerSample: Int? = null, val channelCount: Int? = null, val bitrate: Int? = null)
enum class AudioOutputMode { PCM, OFFLOAD, ENCODED, TUNNELING }
/** Application output before Android mixing, Bluetooth or the external DAC. */
data class AudioTrackFacts(val format: AudioFormatFacts, val mode: AudioOutputMode)
data class PlaybackAudioInfo(
    val mediaId: String? = null,
    val source: AudioFormatFacts? = null,
    val decoderName: String? = null,
    /** Current API does not observe codec output. Never copy source or sink values here. */
    val decoded: AudioFormatFacts? = null,
    val appOutput: AudioTrackFacts? = null,
    /** Physical output cannot be inferred from AudioTrack or device capabilities. */
    val deviceOutput: AudioFormatFacts? = null,
)
internal val audioInfoSnapshot = MutableStateFlow(PlaybackAudioInfo())

/** Main-thread state fenced by the current MediaPeriod UID; no private Format or URI escapes. */
internal class AudioInfoTracker {
    private var token: Any? = null
    var state = PlaybackAudioInfo(); private set
    fun select(token: Any?, mediaId: String?) {
        if (this.token != token || state.mediaId != mediaId) {
            this.token = token; state = PlaybackAudioInfo(mediaId = mediaId)
        }
    }
    fun input(token: Any, value: AudioFormatFacts) { if (token == this.token) state = state.copy(source = value) }
    fun decoder(token: Any, name: String?) {
        if (token == this.token) state = state.copy(decoderName = name?.takeIf {
            it.length in 1..160 && it.matches(Regex("[A-Za-z0-9_.-]+"))
        })
    }
    fun output(token: Any, value: AudioTrackFacts?) { if (token == this.token) state = state.copy(appOutput = value) }
    fun disabled(token: Any) {
        if (token == this.token) state = state.copy(decoderName = null, decoded = null, appOutput = null)
    }
    fun reset() { token = null; state = PlaybackAudioInfo() }
}
