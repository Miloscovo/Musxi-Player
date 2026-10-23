package tech.musxi.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tech.musxi.core.account.Platform

class RequestMetricsTest {
    @Test
    fun oneClockMeasuresQueueWorkAndDeliveryIndependently() {
        var now = 100L
        val metrics = RequestMetrics { now }
        val ticket = metrics.queued(Platform.QQ, RequestOperation.SEARCH)
        now = 125L
        assertTrue(metrics.started(ticket))
        now = 197L
        assertTrue(metrics.finished(ticket, RequestOutcome.SUCCESS))
        now = 212L
        assertTrue(metrics.delivered(ticket))

        val summary = metrics.snapshot().single()
        assertEquals(1L, summary.count)
        assertEquals(1L, summary.completed)
        assertEquals(25L, summary.queueTotalMs)
        assertEquals(25L, summary.queueMaxMs)
        assertEquals(72L, summary.workTotalMs)
        assertEquals(72L, summary.workMaxMs)
        assertEquals(15L, summary.deliveryTotalMs)
        assertEquals(15L, summary.deliveryMaxMs)
    }

    @Test
    fun repeatedLifecycleCallbacksCountOnlyOnce() {
        var now = 0L
        val metrics = RequestMetrics { now }
        val ticket = metrics.queued(Platform.NETEASE, RequestOperation.LYRICS)
        now = 10L
        assertTrue(metrics.started(ticket))
        now = 20L
        assertFalse(metrics.started(ticket))
        now = 30L
        assertTrue(metrics.finished(ticket, RequestOutcome.SUCCESS))
        now = 40L
        assertFalse(metrics.finished(ticket, RequestOutcome.FAILED))
        now = 50L
        assertTrue(metrics.delivered(ticket))
        now = 60L
        assertFalse(metrics.delivered(ticket))

        val summary = metrics.snapshot().single()
        assertEquals(1L, summary.count)
        assertEquals(1L, summary.completed)
        assertEquals(1L, summary.completed(RequestOutcome.SUCCESS))
        assertEquals(0L, summary.completed(RequestOutcome.FAILED))
        assertEquals(10L, summary.queueTotalMs)
        assertEquals(20L, summary.workTotalMs)
        assertEquals(20L, summary.deliveryTotalMs)
    }

    @Test
    fun clearRejectsOldInFlightTicketsAndTheNextEpochStartsFresh() {
        var now = 0L
        val metrics = RequestMetrics { now }
        val old = metrics.queued(Platform.KUGOU, RequestOperation.TRACKS)
        metrics.clear()

        now = 20L
        assertFalse(metrics.started(old))
        assertFalse(metrics.finished(old, RequestOutcome.STALE))
        assertFalse(metrics.delivered(old))
        assertTrue(metrics.snapshot().isEmpty())

        val current = metrics.queued(Platform.KUGOU, RequestOperation.TRACKS)
        now = 25L
        assertTrue(metrics.started(current))
        now = 35L
        assertTrue(metrics.finished(current, RequestOutcome.REJECTED))
        now = 50L
        assertTrue(metrics.delivered(current))

        val summary = metrics.snapshot().single()
        assertEquals(1L, summary.count)
        assertEquals(1L, summary.completedRejected)
        assertEquals(5L, summary.queueTotalMs)
        assertEquals(10L, summary.workTotalMs)
        assertEquals(15L, summary.deliveryTotalMs)
    }

    @Test
    fun platformsAndOperationsHaveIndependentBuckets() {
        var now = 0L
        val metrics = RequestMetrics { now }
        val qq = metrics.queued(Platform.QQ, RequestOperation.PLAYLISTS)
        val netease = metrics.queued(Platform.NETEASE, RequestOperation.PLAYLISTS)
        val kugou = metrics.queued(Platform.KUGOU, RequestOperation.AUDIO)

        now = 5L
        assertTrue(metrics.started(qq))
        now = 15L
        assertTrue(metrics.finished(qq, RequestOutcome.FAILED))
        now = 25L
        assertTrue(metrics.started(netease))
        now = 30L
        assertTrue(metrics.finished(netease, RequestOutcome.STALE))
        now = 40L
        assertTrue(metrics.started(kugou))
        now = 50L
        assertTrue(metrics.finished(kugou, RequestOutcome.SUCCESS))

        val byKey = metrics.snapshot().associateBy { it.platform to it.operation }
        assertEquals(3, byKey.size)
        assertEquals(1L, byKey.getValue(Platform.QQ to RequestOperation.PLAYLISTS).completedFailed)
        assertEquals(1L, byKey.getValue(Platform.NETEASE to RequestOperation.PLAYLISTS).completedStale)
        assertEquals(1L, byKey.getValue(Platform.KUGOU to RequestOperation.AUDIO).completedSuccess)
    }

    @Test
    fun snapshotIsDetachedAndCannotBeMutated() {
        var now = 0L
        val metrics = RequestMetrics { now }
        val first = metrics.queued(Platform.QQ, RequestOperation.QUEUE)
        now = 3L
        metrics.started(first)
        now = 7L
        metrics.finished(first, RequestOutcome.SUCCESS)
        val before = metrics.snapshot()
        assertEquals(1L, before.single().count)

        @Suppress("UNCHECKED_CAST")
        try {
            (before as MutableList<RequestMetricSummary>).clear()
            fail("snapshot must be immutable")
        } catch (_: UnsupportedOperationException) {
        }

        metrics.queued(Platform.QQ, RequestOperation.QUEUE)
        val after = metrics.snapshot()
        assertEquals(1, before.size)
        assertEquals(1L, before.single().count)
        assertEquals(2L, after.single().count)
    }

    @Test
    fun clockRollbackClampsEveryDurationToZero() {
        var now = 100L
        val metrics = RequestMetrics { now }
        val ticket = metrics.queued(Platform.NETEASE, RequestOperation.SONG)
        now = 70L
        assertTrue(metrics.started(ticket))
        now = 40L
        assertTrue(metrics.finished(ticket, RequestOutcome.FAILED))
        now = 10L
        assertTrue(metrics.delivered(ticket))

        val summary = metrics.snapshot().single()
        assertEquals(0L, summary.queueTotalMs)
        assertEquals(0L, summary.queueMaxMs)
        assertEquals(0L, summary.workTotalMs)
        assertEquals(0L, summary.workMaxMs)
        assertEquals(0L, summary.deliveryTotalMs)
        assertEquals(0L, summary.deliveryMaxMs)
    }

    @Test
    fun durationTotalsSaturateInsteadOfOverflowing() {
        var now = 0L
        val metrics = RequestMetrics { now }
        val first = metrics.queued(Platform.QQ, RequestOperation.SEARCH)
        now = Long.MAX_VALUE
        assertTrue(metrics.started(first))
        now = Long.MIN_VALUE
        assertTrue(metrics.finished(first, RequestOutcome.SUCCESS))
        now = 0L
        val second = metrics.queued(Platform.QQ, RequestOperation.SEARCH)
        now = Long.MAX_VALUE
        assertTrue(metrics.started(second))

        val summary = metrics.snapshot().single()
        assertEquals(Long.MAX_VALUE, summary.queueTotalMs)
        assertEquals(Long.MAX_VALUE, summary.queueMaxMs)
        assertEquals(0L, summary.workTotalMs)
    }

    @Test
    fun allThirtyBucketsAreBoundedAndTicketTextIsRedacted() {
        val metrics = RequestMetrics { 0L }
        for (platform in Platform.entries) {
            for (operation in RequestOperation.entries) {
                val ticket = metrics.queued(platform, operation)
                assertEquals("RequestMetrics.Ticket(redacted)", ticket.toString())
            }
        }

        val snapshot = metrics.snapshot()
        assertEquals(30, snapshot.size)
        assertEquals(30, snapshot.map { it.platform to it.operation }.distinct().size)
    }
}
