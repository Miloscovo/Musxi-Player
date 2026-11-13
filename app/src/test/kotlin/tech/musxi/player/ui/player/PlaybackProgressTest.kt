package tech.musxi.player.ui.player

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.lyrics.LyricsState
import tech.musxi.playback.PlaybackState

/**
 * Spec section 12: the shell reads the playback state without the moving position, so a report that
 * only moves the song along does not recompose the screen, and the position reaches the leaves that
 * draw it on its own path.
 */
class PlaybackProgressTest {
    private val playing = PlaybackState(
        connected = true, playing = true, playWhenReady = true, mediaId = "qq:1", title = "晴天",
        durationMs = 269_000, positionMs = 10_000, positionSampledAtElapsedMs = 5_000, playbackSpeed = 1f,
    )

    @Test fun twoReportsThatOnlyMoveThePositionAreTheSameSlowState() {
        val later = playing.copy(positionMs = 10_500, positionSampledAtElapsedMs = 5_500)
        assertNotEquals(playing, later)
        assertEquals(slowPlaybackStateOf(playing), slowPlaybackStateOf(later))
    }

    @Test fun theSlowStateKeepsEverythingButThePosition() {
        val slow = slowPlaybackStateOf(playing)
        assertEquals(0L, slow.positionMs)
        assertEquals(0L, slow.positionSampledAtElapsedMs)
        assertEquals(269_000L, slow.durationMs)
        assertEquals(1f, slow.playbackSpeed)
        assertTrue(slow.playing)
        assertTrue("the advance flag does not depend on the position", slow.canExtrapolatePosition)
        assertEquals(playing.copy(positionMs = 0, positionSampledAtElapsedMs = 0), slow)
    }

    @Test fun aChangeOtherThanThePositionIsStillAChange() {
        assertNotEquals(slowPlaybackStateOf(playing), slowPlaybackStateOf(playing.copy(playing = false)))
        assertNotEquals(slowPlaybackStateOf(playing), slowPlaybackStateOf(playing.copy(mediaId = "qq:2")))
        assertNotEquals(slowPlaybackStateOf(playing), slowPlaybackStateOf(playing.copy(durationMs = 1_000)))
        assertNotEquals(slowPlaybackStateOf(playing), slowPlaybackStateOf(playing.copy(playbackSpeed = 1.5f)))
    }

    @Test fun theSlowFlowSkipsPositionOnlyReports() = runBlocking {
        val reports = flowOf(
            playing,
            playing.copy(positionMs = 10_500, positionSampledAtElapsedMs = 5_500),
            playing.copy(positionMs = 11_000, positionSampledAtElapsedMs = 6_000),
            playing.copy(positionMs = 11_000, positionSampledAtElapsedMs = 6_000, playing = false),
        )
        val slow = reports.withoutPosition().toList()
        assertEquals(2, slow.size)
        assertTrue(slow[0].playing)
        assertFalse(slow[1].playing)
    }

    @Test fun theProgressFlowCarriesEveryNewSampleAndNothingElse() = runBlocking {
        val reports = flowOf(
            playing,
            playing.copy(title = "七里香"),
            playing.copy(positionMs = 10_500, positionSampledAtElapsedMs = 5_500),
        )
        val progress = reports.positionOnly().toList()
        assertEquals(listOf(10_000L, 10_500L), progress.map { it.positionMs })
        assertEquals(5_500L, progress[1].sampledAtElapsedMs)
        assertTrue(progress[1].canExtrapolate)
        assertEquals(269_000L, progress[1].durationMs)
    }

    @Test fun pausingStopsTheExtrapolationInTheProgress() {
        assertTrue(playbackProgressOf(playing).canExtrapolate)
        assertFalse(playbackProgressOf(playing.copy(playing = false)).canExtrapolate)
    }

    @Test fun theFractionIsClampedAndZeroWithoutADuration() {
        assertEquals(0.5f, PlaybackProgress(positionMs = 50, durationMs = 100).fraction, 1e-6f)
        assertEquals(1f, PlaybackProgress(positionMs = 900, durationMs = 100).fraction, 0f)
        assertEquals(0f, PlaybackProgress(positionMs = -5, durationMs = 100).fraction, 0f)
        assertEquals(0f, PlaybackProgress(positionMs = 50, durationMs = 0).fraction, 0f)
    }

    @Test fun theShellsLyricsStateIgnoresTheWordSweep() {
        val line = LyricsState(activeIndex = 3, activeWordIndex = 1, activeWordProgress = 0.2f)
        val later = line.copy(activeWordIndex = 2, activeWordProgress = 0.7f)
        assertEquals(lyricsWithoutWordProgress(line), lyricsWithoutWordProgress(later))
        assertNotEquals(lyricsWithoutWordProgress(line), lyricsWithoutWordProgress(line.copy(activeIndex = 4)))
    }
}
