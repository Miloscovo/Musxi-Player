package tech.musxi.player.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Duration text must stay unambiguous: a 90-minute track is `1:30:12`, never `90:12`. */
class FormatDurationTest {

    @Test fun underOneMinute() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:07", formatDuration(7_000))
        assertEquals("0:59", formatDuration(59_999))
    }

    @Test fun minutesAndSeconds() {
        assertEquals("1:00", formatDuration(60_000))
        assertEquals("3:21", formatDuration(201_000))
        assertEquals("59:59", formatDuration(3_599_000))
    }

    @Test fun pastAnHourGainsAnHourField() {
        assertEquals("1:00:00", formatDuration(3_600_000))
        assertEquals("1:30:12", formatDuration(5_412_000))
        assertEquals("2:05:07", formatDuration(7_507_000))
    }

    @Test fun negativeIsClampedToZero() {
        assertEquals("0:00", formatDuration(-1))
        assertEquals("0:00", formatDuration(Long.MIN_VALUE))
    }
}
