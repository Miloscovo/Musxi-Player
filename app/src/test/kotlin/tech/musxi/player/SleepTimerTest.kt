package tech.musxi.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.musxi.core.playback.SleepTimerMode
import tech.musxi.core.playback.SleepTimerPhase
import tech.musxi.core.playback.SleepTimerState

class SleepTimerTest {
    @Test fun zeroReadsAsEmptyClock() {
        assertEquals("00:00", formatRemaining(0))
    }

    @Test fun belowAnHourUsesMinutesAndSeconds() {
        assertEquals("00:59", formatRemaining(59_000))
        assertEquals("01:01", formatRemaining(61_000))
        assertEquals("59:59", formatRemaining(3_599_000))
    }

    @Test fun fromAnHourUsesHours() {
        assertEquals("1:00:00", formatRemaining(3_600_000))
        assertEquals("1:30:05", formatRemaining(5_405_000))
    }

    @Test fun partialSecondsRoundUp() {
        // A countdown that has just started must not already read one second short.
        assertEquals("00:01", formatRemaining(1))
        assertEquals("15:00", formatRemaining(15 * 60_000L - 400))
    }

    @Test fun negativeIsTreatedAsZero() {
        assertEquals("00:00", formatRemaining(-1))
        assertEquals("00:00", formatRemaining(-3_600_000))
    }

    private fun timer(mode: SleepTimerMode, phase: SleepTimerPhase, deadline: Long? = null) =
        SleepTimerState(mode = mode, phase = phase, deadlineElapsedMs = deadline, revision = 1)

    @Test fun noTimerHasNoStatus() {
        assertNull(sleepStatusText(SleepTimerState(), 0, "歌曲"))
    }

    @Test fun aCountdownShowsTheTimeLeft() {
        val state = timer(SleepTimerMode.AFTER_DURATION, SleepTimerPhase.COUNTING, deadline = 10_000 + 15 * 60_000L)
        assertEquals("剩余 15:00", sleepStatusText(state, 10_000, "歌曲"))
    }

    @Test fun aCountdownThatFinishesTheTrackSaysSo() {
        val state = timer(SleepTimerMode.AFTER_DURATION_FINISH_TRACK, SleepTimerPhase.COUNTING, deadline = 90_000)
        assertEquals("剩余 01:30，到点后播完歌曲", sleepStatusText(state, 0, "歌曲"))
    }

    @Test fun waitingForTheTrackEndHasNoClock() {
        // Both the direct mode and a finished countdown end up here; neither has a time to show.
        listOf(SleepTimerMode.END_OF_TRACK, SleepTimerMode.AFTER_DURATION_FINISH_TRACK).forEach { mode ->
            val state = timer(mode, SleepTimerPhase.WAITING_FOR_TRACK_END)
            assertEquals("播完当前音频后停止", sleepStatusText(state, 0, "音频"))
        }
    }

    @Test fun aCountingTimerWithoutDeadlineStillShowsItIsOn() {
        val state = timer(SleepTimerMode.AFTER_DURATION, SleepTimerPhase.COUNTING, deadline = null)
        assertEquals("定时停止已开启", sleepStatusText(state, 0, "歌曲"))
    }

    @Test fun noticesNameTheMode() {
        assertEquals("将在 30 分钟后停止播放", sleepStartedNotice(30, finishTrack = false, noun = "歌曲"))
        assertEquals("30 分钟后，播完当前音频再停止", sleepStartedNotice(30, finishTrack = true, noun = "音频"))
        assertEquals("将在当前歌曲播完后停止", sleepStartedNotice(null, finishTrack = true, noun = "歌曲"))
    }
}
