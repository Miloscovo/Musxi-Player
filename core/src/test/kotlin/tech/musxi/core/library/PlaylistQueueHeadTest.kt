package tech.musxi.core.library

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform

class PlaylistQueueHeadTest {
    private fun songs(count: Int) = (0 until count).map {
        SongSummary(MediaKey(Platform.KUGOU, "${"%032x".format(it)}_0_${it + 1}"), "song-$it", emptyList(), "", 0)
    }

    @Test fun selectedPageSplitsBothSidesInDisplayOrderWithoutDeduping() {
        val all = songs(30)
        val prefix = all.take(25) + all[10]
        val split = playlistQueueWindow(prefix, all[12].key, 10)!!
        assertEquals(all.take(10), split.before)
        assertEquals(all.subList(10, 20), split.head)
        assertEquals(all.subList(20, 25) + all[10], split.after)
        assertEquals(2, split.startIndex)
        assertEquals(all[12], split.head[split.startIndex])
    }

    @Test fun queueWindowRequiresSelectedSongAndPositivePageSize() {
        val all = songs(3)
        assertNull(playlistQueueWindow(all, MediaKey(Platform.KUGOU, "${"%032x".format(9)}_0_10"), 30))
        try {
            playlistQueueWindow(all, all.first().key, 0)
            fail("Zero page size accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun remainderReturnsPagesSeriallyAndDropsLateResultAfterCancellation() {
        val all = songs(2)
        var pending: ((Result<List<SongSummary>?>) -> Unit)? = null
        var cancelled = 0
        var delivered = 0
        val remainder = QueueRemainder(emptyList(), emptyList(), { pending = it }, { cancelled++ })
        remainder.loadNext { delivered++ }
        pending!!(Result.success(listOf(all.first())))
        remainder.loadNext { delivered++ }
        remainder.cancel()
        pending!!(Result.success(listOf(all.last())))
        assertEquals(1, delivered)
        assertEquals(1, cancelled)
    }

    @Test fun completionAcrossDifferentPageSizesMatchesFullDisplayOrderIncludingDuplicates() {
        val all = songs(253).toMutableList().apply { this[117] = this[5] }
        for (prefixSize in listOf(1, 30, 60, 99, 100, 125, 253)) {
            val known = all.take(prefixSize)
            val selected = known[prefixSize / 2]
            val window = playlistQueueWindow(known, selected.key, 30)!!
            val queue = (window.before + window.head + window.after).toMutableList()
            var loaded = known.size
            do {
                val request = playlistQueuePage(loaded)
                assertEquals(0, request.offset % request.limit)
                val page = all.drop(request.offset).take(request.limit)
                val added = request.unseen(page)
                queue.addAll(added)
                loaded += added.size
            } while (loaded < all.size)
            assertEquals(all, queue)
            assertEquals(selected, window.head[window.startIndex])
        }
    }
}
