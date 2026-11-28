package tech.musxi.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackDiagnosticHistoryTest {
    @Test fun observationsAreBoundedAndRepeatedEventsDoNotEvictOtherStages() {
        val history = PlaybackDiagnosticHistory(3)
        fun record(at: Long, stage: PlaybackDiagnosticStage) = history.record(PlaybackDiagnosticEvent(at, stage))
        record(10, PlaybackDiagnosticStage.PREPARING)
        record(11, PlaybackDiagnosticStage.BUFFERING)
        repeat(100) { record(12L + it, PlaybackDiagnosticStage.BUFFERING) }
        record(120, PlaybackDiagnosticStage.SOURCE_FAILED)
        record(122, PlaybackDiagnosticStage.PAUSED)
        assertEquals(listOf(11L, 120L, 122L), history.state.value.map { it.elapsedMs })
        assertEquals(listOf(PlaybackDiagnosticStage.BUFFERING, PlaybackDiagnosticStage.SOURCE_FAILED,
            PlaybackDiagnosticStage.PAUSED), history.state.value.map { it.stage })
    }
}
