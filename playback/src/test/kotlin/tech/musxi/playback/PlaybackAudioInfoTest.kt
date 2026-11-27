package tech.musxi.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackAudioInfoTest {
    @Test fun inputFactsNeverGuessUnknownDepthOrUsePrivateFormatStrings() {
        val f = Format.Builder().setSampleMimeType("audio/flac").setSampleRate(192000).setChannelCount(2)
            .setLabel("content://private").setCodecs("https://private/token").build()
        val actual = AudioFormatObservation.input(f)
        assertEquals("FLAC", actual.codec)
        assertEquals(192000, actual.sampleRateHz)
        assertNull(actual.bitsPerSample)
        assertFalse(actual.toString().contains("private"))
    }
    @Test fun rawPcmEncodingConfirmsOnlyItsOwnDepth() {
        val actual = AudioFormatObservation.input(Format.Builder().setSampleMimeType("audio/raw")
            .setPcmEncoding(C.ENCODING_PCM_24BIT).build())
        assertEquals(24, actual.bitsPerSample)
        assertNull(actual.sampleRateHz)
        assertNull(actual.channelCount)
    }
    @Test fun sinkReportsApplicationBoundaryNotDeviceOrDecoder() {
        val sink = AudioFormatObservation.output(AudioSink.AudioTrackConfig(C.ENCODING_PCM_FLOAT, 48000, 12, false, false, 4096))
        assertEquals(AudioOutputMode.PCM, sink.mode)
        assertEquals(32, sink.format.bitsPerSample)
        assertEquals(2, sink.format.channelCount)
        val tracker = AudioInfoTracker(); tracker.select("a", "song")
        tracker.output("a", sink)
        assertNull(tracker.state.decoded)
        assertNull(tracker.state.deviceOutput)
    }
    @Test fun encodedOffloadIsNotPresentedAsPcm() {
        val actual = AudioFormatObservation.output(AudioSink.AudioTrackConfig(C.ENCODING_AC3, 48000, 12, false, true, 4096))
        assertEquals(AudioOutputMode.OFFLOAD, actual.mode)
        assertNull(actual.format.bitsPerSample)
        assertNull(actual.format.codec)
    }
    @Test fun invalidAndUnknownInputValuesStayUnknown() {
        val actual = AudioFormatObservation.input(Format.Builder().setSampleMimeType("audio/private")
            .setSampleRate(-1).setChannelCount(-1).build())
        assertEquals(AudioFormatFacts(), actual)
    }
    @Test fun replacingPeriodClearsAllFactsEvenForSameSongAndOldEventsAreIgnored() {
        val tracker = AudioInfoTracker(); tracker.select("period1", "song")
        tracker.input("period1", AudioFormatFacts("FLAC", 44100, 16, 2))
        tracker.decoder("period1", "c2.android.flac.decoder")
        tracker.select("period2", "song")
        tracker.input("period1", AudioFormatFacts("MP3"))
        tracker.decoder("period1", "old")
        assertEquals(PlaybackAudioInfo(mediaId = "song"), tracker.state)
    }
    @Test fun decoderNamesAreBoundedAndDoNotExposeDiagnostics() {
        val tracker = AudioInfoTracker(); tracker.select("a", "song")
        tracker.decoder("a", "decoder /private/source")
        assertNull(tracker.state.decoderName)
        tracker.decoder("a", "c2.android.aac.decoder")
        assertEquals("c2.android.aac.decoder", tracker.state.decoderName)
    }
    @Test fun stopClearsActiveDecoderAndSinkAndResetClearsSource() {
        val tracker = AudioInfoTracker(); tracker.select("a", "song")
        tracker.input("a", AudioFormatFacts("FLAC")); tracker.decoder("a", "flac")
        tracker.output("a", AudioTrackFacts(AudioFormatFacts("PCM"), AudioOutputMode.PCM))
        tracker.disabled("a")
        assertEquals("FLAC", tracker.state.source!!.codec)
        assertNull(tracker.state.appOutput); assertNull(tracker.state.decoderName)
        tracker.reset(); assertEquals(PlaybackAudioInfo(), tracker.state)
    }
}
