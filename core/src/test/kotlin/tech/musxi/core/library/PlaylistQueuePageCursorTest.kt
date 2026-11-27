package tech.musxi.core.library

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform

class PlaylistQueuePageCursorTest {
    private fun songs(count: Int, platform: Platform) = (0 until count).map {
        SongSummary(MediaKey(platform, "$it"), "song-$it", emptyList(), "", 0)
    }

    @Test fun everyPrefixSizePreservesOrderAndLegitimateDuplicatesAcrossPlatforms() {
        for (platform in Platform.entries) {
            val all = songs(253, platform).toMutableList().apply { this[117] = this[5] }
            for (size in listOf(1, 30, 99, 100, 125, 200, 253)) {
                val cursor = PlaylistQueuePageCursor(all.take(size))
                val built = all.take(size).toMutableList()
                while (!cursor.finished) {
                    val request = cursor.request()
                    assertEquals(0, request.offset % request.limit)
                    val rows = all.drop(request.offset).take(request.limit)
                    val end = request.offset + rows.size
                    built += cursor.accept(Page(rows, end.takeIf { it < all.size }, all.size))
                }
                assertEquals("$platform prefix=$size", all, built)
            }
        }
    }

    @Test fun sameTotalInsertionAndRemovalIsRejectedBeforeAnyAppendIncludingAlignedPrefixes() {
        for (size in listOf(3, 100, 125, 200)) {
            val all = songs(253, Platform.QQ)
            val cursor = PlaylistQueuePageCursor(all.take(size))
            val changed = listOf(all.last()) + all.dropLast(1)
            rejected { cursor.accept(Page(changed.take(100), 100, changed.size)) }
            assertEquals(0, cursor.request().offset)
            assertFalse(cursor.finished)
        }
    }

    @Test fun mismatchInLaterKnownPageAndChangingTotalsAreRejectedWithoutAdvancing() {
        val all = songs(253, Platform.KUGOU)
        val cursor = PlaylistQueuePageCursor(all.take(125))
        assertTrue(cursor.accept(Page(all.take(100), 100, 253)).isEmpty())
        val changed = all.subList(100, 200).toMutableList().apply { this[0] = all[0] }
        rejected { cursor.accept(Page(changed, 200, 253)) }
        rejected { cursor.accept(Page(all.subList(100, 200), 200, 254)) }
        assertEquals(100, cursor.request().offset)
        assertEquals(all.subList(125, 200), cursor.accept(Page(all.subList(100, 200), 200, 253)))
    }

    @Test fun malformedContinuationTruncationAndCrossPlatformRowsAreRejected() {
        val all = songs(103, Platform.QQ)
        val cursor = PlaylistQueuePageCursor(all.take(30))
        rejected { cursor.accept(Page(all.take(100), 99, 103)) }
        rejected { cursor.accept(Page(all.take(99), 100, 103)) }
        rejected { cursor.accept(Page(all.take(100), null, 103)) }
        rejected { cursor.accept(Page(all.take(100), 100, null)) }
        rejected { cursor.accept(Page(songs(100, Platform.KUGOU), 100, 103)) }
        assertEquals(0, cursor.request().offset)
        assertEquals(all.subList(30, 100), cursor.accept(Page(all.take(100), 100, 103)))
        assertEquals(all.drop(100), cursor.accept(Page(all.drop(100), null, 103)))
        assertTrue(cursor.finished)
    }

    @Test fun emptyAndExactPageTerminalPrefixesCompleteWithoutExtraRequest() {
        val empty = PlaylistQueuePageCursor(emptyList())
        assertTrue(empty.accept(Page(emptyList(), null, 0)).isEmpty())
        assertTrue(empty.finished)
        val all = songs(100, Platform.QQ)
        val cursor = PlaylistQueuePageCursor(all)
        assertTrue(cursor.accept(Page(all, null, 100)).isEmpty())
        assertTrue(cursor.finished)
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("accepted inconsistent page") }
        catch (error: LibraryPageFailure) { assertEquals("incomplete", error.category) }
    }
}
