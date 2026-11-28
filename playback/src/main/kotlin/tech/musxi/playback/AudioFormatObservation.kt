package tech.musxi.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object AudioFormatObservation {
    fun input(format: Format) = AudioFormatFacts(
        codec = when (format.sampleMimeType) {
            "audio/flac" -> "FLAC"
            "audio/raw" -> "PCM"
            "audio/mpeg" -> "MP3"
            "audio/mp4a-latm" -> "AAC"
            "audio/opus" -> "Opus"
            "audio/vorbis" -> "Vorbis"
            "audio/alac" -> "ALAC"
            "audio/ac3" -> "AC-3"
            "audio/eac3" -> "E-AC-3"
            else -> null
        },
        sampleRateHz = format.sampleRate.takeIf { it in 1..768_000 },
        bitsPerSample = bits(format.pcmEncoding),
        channelCount = format.channelCount.takeIf { it in 1..32 },
        bitrate = format.averageBitrate.takeIf { it > 0 },
    )
    fun output(config: AudioSink.AudioTrackConfig): AudioTrackFacts {
        val depth = bits(config.encoding)
        return AudioTrackFacts(AudioFormatFacts(
            codec = if (depth != null) "PCM" else null,
            sampleRateHz = config.sampleRate.takeIf { it in 1..768_000 },
            bitsPerSample = depth,
            channelCount = Integer.bitCount(config.channelConfig).takeIf { config.channelConfig > 0 && it in 1..32 },
        ), when { config.tunneling -> AudioOutputMode.TUNNELING; config.offload -> AudioOutputMode.OFFLOAD
            depth != null -> AudioOutputMode.PCM; else -> AudioOutputMode.ENCODED })
    }
    private fun bits(encoding: Int): Int? = when (encoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
        else -> null
    }
}
