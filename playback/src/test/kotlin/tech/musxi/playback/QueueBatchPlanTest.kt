package tech.musxi.playback

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary

class QueueBatchPlanTest {
    private fun song(id: String) = SongSummary(MediaKey(Platform.NETEASE, id), id, emptyList(), "", 0)

    @Test fun headAndTailPlansPreserveOrderAndCurrentSlotIdentity() {
        val current = listOf(song("current"), song("next"))
        val added = listOf(song("before-1"), song("before-2"))
        val head = planQueueBatch(current, added, QueueInsertAt.HEAD)!!
        assertEquals(0, head.insertionIndex)
        assertEquals(listOf("before-1", "before-2", "current", "next"), head.songs.map { it.key.id })
        assertEquals("current", head.songs[head.insertionIndex + added.size].key.id)
        val tail = planQueueBatch(current, added, QueueInsertAt.TAIL)!!
        assertEquals(2, tail.insertionIndex)
        assertEquals(listOf("current", "next", "before-1", "before-2"), tail.songs.map { it.key.id })
    }

    @Test fun emptyOrOversizedBatchIsRejectedAsOneUnit() {
        val current = listOf(song("current"))
        assertNull(planQueueBatch(current, emptyList(), QueueInsertAt.TAIL))
        assertNull(planQueueBatch(current, List(QueueSources.MAX_QUEUE_SIZE) { song("$it") }, QueueInsertAt.HEAD))
        assertEquals(listOf("current"), current.map { it.key.id })
    }
}
