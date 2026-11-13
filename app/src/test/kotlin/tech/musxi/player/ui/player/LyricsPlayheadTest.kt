package tech.musxi.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The word sweep's clock between two playback samples. */
class LyricsPlayheadTest {
    private var clock = 10_000L
    private val playhead = LyricsPlayhead(now = { clock })

    private fun sample(positionMs: Long, canAdvance: Boolean = true, speed: Float = 1f) =
        playhead.update(positionMs, sampledAtMs = clock, speed = speed, canAdvance = canAdvance)

    @Test fun aPlayingSongMovesForwardBetweenSamples() {
        sample(5_000)
        clock += 250
        assertEquals(5_250, playhead.positionNow())
        assertTrue(playhead.playing)
    }

    @Test fun theSampleTimeNotTheArrivalTimeIsTheAnchor() {
        // A sample read 100ms before it reached the page is already 100ms old.
        playhead.update(5_000, sampledAtMs = clock - 100, speed = 1f, canAdvance = true)
        assertEquals(5_100, playhead.positionNow())
    }

    @Test fun aFasterSpeedMovesFaster() {
        sample(5_000, speed = 1.5f)
        clock += 200
        assertEquals(5_300, playhead.positionNow())
    }

    @Test fun aPausedSongStaysWhereItWasSampled() {
        sample(5_000, canAdvance = false)
        clock += 2_000
        assertEquals(5_000, playhead.positionNow())
        assertFalse(playhead.playing)
    }

    @Test fun aSmallStepBackIsJitterAndDoesNotUnfillAWord() {
        sample(5_000)
        clock += 500
        // The new sample says 5_420 while the estimate is already at 5_500.
        sample(5_420)
        assertEquals(5_500, playhead.positionNow())
    }

    @Test fun aLargeStepBackIsASeek() {
        sample(60_000)
        clock += 200
        sample(12_000)
        assertEquals(12_000, playhead.positionNow())
    }

    @Test fun aStallDoesNotRunTheSweepAheadOfTheVoice() {
        sample(5_000)
        clock += 10_000
        assertEquals(6_200, playhead.positionNow())
    }

    @Test fun theSameSampleTwiceIsIgnored() {
        sample(5_000)
        val at = clock
        clock += 300
        val revision = playhead.revision
        playhead.update(5_000, sampledAtMs = at, speed = 1f, canAdvance = true)
        assertEquals(5_300, playhead.positionNow())
        assertEquals(revision, playhead.revision)
    }

    @Test fun noSampleTimeAnchorsAtArrival() {
        playhead.update(5_000, sampledAtMs = 0, speed = 1f, canAdvance = true)
        clock += 100
        assertEquals(5_100, playhead.positionNow())
    }
}
