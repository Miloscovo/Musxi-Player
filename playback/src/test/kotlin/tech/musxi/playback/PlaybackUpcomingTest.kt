package tech.musxi.playback

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackUpcomingTest {
    @Test
    fun offReturnsOnlyWindowsAfterCurrentInTimelineOrder() {
        val timeline = OrderedTimeline(listOf(0, 1, 2, 3))

        assertEquals(
            listOf(2, 3),
            upcomingWindowIndices(
                timeline = timeline,
                currentIndex = 1,
                shuffleEnabled = false,
                repeatMode = Player.REPEAT_MODE_OFF,
            ),
        )
    }

    @Test
    fun shuffleUsesTimelineNextWindowOrder() {
        val timeline = OrderedTimeline(listOf(2, 0, 3, 1))

        assertEquals(
            listOf(0, 3, 1),
            upcomingWindowIndices(
                timeline = timeline,
                currentIndex = 2,
                shuffleEnabled = true,
                repeatMode = Player.REPEAT_MODE_OFF,
            ),
        )
        assertEquals(
            emptyList<Int>(),
            upcomingWindowIndices(
                timeline = timeline,
                currentIndex = 1,
                shuffleEnabled = true,
                repeatMode = Player.REPEAT_MODE_OFF,
            ),
        )
    }

    @Test
    fun repeatAllWrapsOnceAndOmitsCurrentWindow() {
        val timeline = OrderedTimeline(listOf(2, 0, 3, 1))

        val upcoming = upcomingWindowIndices(
            timeline = timeline,
            currentIndex = 1,
            shuffleEnabled = true,
            repeatMode = Player.REPEAT_MODE_ALL,
        )

        assertEquals(listOf(2, 0, 3), upcoming)
        assertEquals(upcoming.size, upcoming.toSet().size)
    }

    @Test
    fun repeatOneHasNoDistinctAutomaticNextWindow() {
        val timeline = OrderedTimeline(listOf(2, 0, 3, 1))

        assertEquals(
            emptyList<Int>(),
            upcomingWindowIndices(
                timeline = timeline,
                currentIndex = 0,
                shuffleEnabled = true,
                repeatMode = Player.REPEAT_MODE_ONE,
            ),
        )
    }

    @Test
    fun removedWindowsAreNotReturnedByStaleIndices() {
        val timelineAfterDeletion = OrderedTimeline(listOf(0, 2, 1))

        assertEquals(
            listOf(0, 2),
            upcomingWindowIndices(
                timeline = timelineAfterDeletion,
                currentIndex = 1,
                shuffleEnabled = true,
                repeatMode = Player.REPEAT_MODE_ALL,
            ),
        )
    }

    @Test
    fun emptyTimelineAndUnsetCurrentIndexReturnEmptyList() {
        assertEquals(
            emptyList<Int>(),
            upcomingWindowIndices(
                timeline = Timeline.EMPTY,
                currentIndex = 0,
                shuffleEnabled = true,
                repeatMode = Player.REPEAT_MODE_ALL,
            ),
        )
        assertEquals(
            emptyList<Int>(),
            upcomingWindowIndices(
                timeline = OrderedTimeline(listOf(0, 1, 2)),
                currentIndex = -1,
                shuffleEnabled = true,
                repeatMode = Player.REPEAT_MODE_ALL,
            ),
        )
    }

    private class OrderedTimeline(
        private val shuffledOrder: List<Int>,
    ) : Timeline() {
        override fun getWindowCount(): Int = shuffledOrder.size

        override fun getWindow(
            windowIndex: Int,
            window: Window,
            defaultPositionUs: Long,
        ): Window = window

        override fun getPeriodCount(): Int = shuffledOrder.size

        override fun getPeriod(
            periodIndex: Int,
            period: Period,
            setIds: Boolean,
        ): Period = period

        override fun getIndexOfPeriod(uid: Any): Int =
            (uid as? Int)?.takeIf { it in shuffledOrder.indices } ?: -1

        override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex

        override fun getNextWindowIndex(
            windowIndex: Int,
            repeatMode: Int,
            shuffleModeEnabled: Boolean,
        ): Int {
            val order = if (shuffleModeEnabled) shuffledOrder else shuffledOrder.indices.toList()
            val position = order.indexOf(windowIndex)
            if (position < 0) return -1
            if (position < order.lastIndex) return order[position + 1]
            return when (repeatMode) {
                Player.REPEAT_MODE_ALL -> order.first()
                Player.REPEAT_MODE_ONE -> windowIndex
                else -> -1
            }
        }
    }
}
