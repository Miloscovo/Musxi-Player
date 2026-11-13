package tech.musxi.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** Order of the queue list and the make-way arithmetic, without Compose. */
class QueueSectionsTest {
    private fun rows(entries: List<QueueEntry>) = entries.map {
        when (it) {
            is QueueEntry.Heading -> "#${it.title}"
            is QueueEntry.Row -> if (it.played) "p${it.slot}" else "${it.slot}"
        }
    }

    @Test fun upNextComesFirstThenWhatWasPlayed() {
        val order = queueDisplayOrder(size = 6, currentIndex = 2, upcoming = emptyList())
        assertEquals(listOf("#接下来播放", "3", "4", "5", "#已播放", "p0", "p1"), rows(queueSections(6, 2, order)))
    }

    @Test fun theFirstSongHasNothingPlayedAndTheLastNothingAhead() {
        assertEquals(listOf("#接下来播放", "1", "2"), rows(queueSections(3, 0, queueDisplayOrder(3, 0, emptyList()))))
        assertEquals(listOf("#已播放", "p0", "p1"), rows(queueSections(3, 2, queueDisplayOrder(3, 2, emptyList()))))
    }

    @Test fun shuffledShowsTheRealOrderAndNoPlayedPart() {
        val order = queueDisplayOrder(size = 5, currentIndex = 1, upcoming = listOf(4, 0, 3, 2))
        assertEquals(listOf("#接下来播放", "4", "0", "3", "2"), rows(queueSections(5, 1, order)))
    }

    @Test fun neighboursMakeWayForTheCarriedRow() {
        val row = 100f
        // Row 2 carried down past rows 3 and 4: those two slide up one row, the rest stay.
        assertEquals(-row, makeWayOffset(3, dragging = 2, offset = 2.2f * row, rowHeightPx = row), 0f)
        assertEquals(-row, makeWayOffset(4, dragging = 2, offset = 2.2f * row, rowHeightPx = row), 0f)
        assertEquals(0f, makeWayOffset(5, dragging = 2, offset = 2.2f * row, rowHeightPx = row), 0f)
        assertEquals(0f, makeWayOffset(1, dragging = 2, offset = 2.2f * row, rowHeightPx = row), 0f)
        // Carried up past row 1: it slides down.
        assertEquals(row, makeWayOffset(1, dragging = 2, offset = -1.4f * row, rowHeightPx = row), 0f)
        // Less than half a row: nothing moves yet.
        assertEquals(0f, makeWayOffset(3, dragging = 2, offset = 0.4f * row, rowHeightPx = row), 0f)
    }
}
