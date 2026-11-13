package tech.musxi.playback

import android.app.Application
import android.os.SystemClock
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaybackPositionSampleTest {
    private class Fixture {
        var position = 12_000L
        var speed = 1f
        var positionReads = 0
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getCurrentPosition" -> { positionReads++; position }
                "getPlaybackParameters" -> PlaybackParameters(speed)
                else -> throw AssertionError("Unexpected player call: ${method.name}")
            }
        } as Player
    }

    @Test fun eachPositionReadGetsItsOwnAdjacentElapsedTimestamp() {
        val f = Fixture()
        val first = samplePlaybackPosition(f.player) {
            assertEquals(1, f.positionReads)
            7_000L
        }
        f.position = 9_000 // A seek may move backwards; never carry the previous sample's clock.
        val next = samplePlaybackPosition(f.player) {
            assertEquals(2, f.positionReads)
            7_500L
        }
        assertEquals(PlaybackPositionSample(12_000, 7_000, 1f), first)
        assertEquals(PlaybackPositionSample(9_000, 7_500, 1f), next)
    }

    @Test fun productionClockUsesElapsedRealtimeAndSpeedComesFromPlayer() {
        val f = Fixture().apply { speed = 1.75f }
        ShadowSystemClock.advanceBy(Duration.ofMillis(500))
        val sampledAt = SystemClock.elapsedRealtime()
        val sample = samplePlaybackPosition(f.player)
        assertEquals(sampledAt, sample.sampledAtElapsedMs)
        assertEquals(1.75f, sample.speed, 0f)
        assertEquals(12_000L, sample.positionMs)
    }

    @Test fun pausedSamplesKeepPositionEvenWhenClockAdvances() {
        val f = Fixture()
        val first = samplePlaybackPosition(f.player) { 1_000L }
        val later = samplePlaybackPosition(f.player) { 20_000L }
        val paused = PlaybackState(connected = true, positionMs = later.positionMs,
            positionSampledAtElapsedMs = later.sampledAtElapsedMs, playbackSpeed = later.speed)
        assertEquals(first.positionMs, later.positionMs)
        assertNotEquals(first.sampledAtElapsedMs, later.sampledAtElapsedMs)
        assertFalse(paused.canExtrapolatePosition)
    }

    @Test fun extrapolationStopsForPauseBufferingSuppressionEndErrorAndDisconnect() {
        val playing = PlaybackState(connected = true, playing = true, playWhenReady = true,
            positionMs = 12_000, positionSampledAtElapsedMs = 5_000, playbackSpeed = 1.5f)
        assertTrue(playing.canExtrapolatePosition)
        val stopped = listOf(
            playing.copy(playing = false), playing.copy(playWhenReady = false),
            playing.copy(buffering = true), playing.copy(ended = true),
            playing.copy(connected = false), playing.copy(error = "unavailable"),
            playing.copy(suppression = PlaybackSuppression.AUDIO_FOCUS),
            playing.copy(suppression = PlaybackSuppression.OUTPUT),
            playing.copy(localError = tech.musxi.core.localaudio.LocalAudioError.READ_FAILED),
            playing.copy(playbackSpeed = Float.NaN), playing.copy(playbackSpeed = 0f),
        )
        stopped.forEach { assertFalse(it.canExtrapolatePosition) }
        assertEquals(0L, PlaybackState().positionSampledAtElapsedMs)
        assertEquals(1f, PlaybackState().playbackSpeed, 0f)
    }

    @Test fun newTrackAndSpeedChangeReplaceTheEntireSampleAndNegativePositionIsClamped() {
        val f = Fixture()
        val old = samplePlaybackPosition(f.player) { 5_000L }
        f.position = -1
        f.speed = 2f
        val next = samplePlaybackPosition(f.player) { 5_100L }
        assertEquals(12_000L, old.positionMs)
        assertEquals(PlaybackPositionSample(0, 5_100, 2f), next)
    }
}
