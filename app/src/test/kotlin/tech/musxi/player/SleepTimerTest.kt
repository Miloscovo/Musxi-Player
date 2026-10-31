package tech.musxi.player

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
