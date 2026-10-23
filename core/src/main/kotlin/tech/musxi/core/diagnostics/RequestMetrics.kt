package tech.musxi.core.diagnostics

import java.util.Collections
import tech.musxi.core.account.Platform

enum class RequestOperation {
    OTHER,
    PLAYLISTS,
    TRACKS,
    SONG,
    SEARCH,
    QUEUE,
    AUDIO,
    FAVORITES_READ,
    FAVORITES_WRITE,
    LYRICS,
}

enum class RequestOutcome {
    SUCCESS,
    FAILED,
    REJECTED,
    STALE,
}

/**
 * An immutable view of one platform/operation bucket.
 *
 * Work time covers the whole logical operation, from [RequestMetrics.started]
 * until [RequestMetrics.finished], rather than individual HTTP attempts.
 */
data class RequestMetricSummary(
    val platform: Platform,
    val operation: RequestOperation,
    val count: Long,
    val completed: Long,
    val completedSuccess: Long,
    val completedFailed: Long,
    val completedRejected: Long,
    val completedStale: Long,
    val queueTotalMs: Long,
    val queueMaxMs: Long,
    val workTotalMs: Long,
    val workMaxMs: Long,
    val deliveryTotalMs: Long,
    val deliveryMaxMs: Long,
) {
    fun completed(outcome: RequestOutcome): Long = when (outcome) {
        RequestOutcome.SUCCESS -> completedSuccess
        RequestOutcome.FAILED -> completedFailed
        RequestOutcome.REJECTED -> completedRejected
        RequestOutcome.STALE -> completedStale
    }
}

/**
 * Thread-safe, fixed-size request timing aggregation for the three platforms
 * and ten request operations known by the core module.
 *
 * The aggregate owns no ticket map. A ticket keeps only its own lifecycle
 * state, while the aggregate has at most 30 buckets.
 */
class RequestMetrics(private val nowMs: () -> Long) {
    private val gate = Any()
    private var epoch = Any()
    private val buckets = arrayOfNulls<Bucket>(BUCKET_COUNT)

    /** Starts queue timing. The returned handle is opaque to callers. */
    fun queued(platform: Platform, operation: RequestOperation): Ticket = synchronized(gate) {
        val queuedAtMs = nowMs()
        val index = indexOf(platform, operation)
        val bucket = buckets[index] ?: Bucket(platform, operation).also { buckets[index] = it }
        bucket.count = saturatedIncrement(bucket.count)
        Ticket(this, epoch, platform, operation, queuedAtMs)
    }

    /** Records the end of queueing. Repeated calls are ignored. */
    fun started(ticket: Ticket): Boolean = synchronized(gate) {
        if (!ticket.belongsTo(this, epoch) ||
            ticket.startedAtMs != null ||
            ticket.finishedAtMs != null
        ) {
            return@synchronized false
        }
        val startedAtMs = nowMs()
        val bucket = bucketFor(ticket) ?: return@synchronized false
        ticket.startedAtMs = startedAtMs
        bucket.addQueue(elapsed(ticket.queuedAtMs, startedAtMs))
        true
    }

    /** Records the end of the logical operation. Repeated calls are ignored. */
    fun finished(ticket: Ticket, outcome: RequestOutcome): Boolean = synchronized(gate) {
        val startedAtMs = ticket.startedAtMs
        if (!ticket.belongsTo(this, epoch) ||
            startedAtMs == null ||
            ticket.finishedAtMs != null
        ) {
            return@synchronized false
        }
        val finishedAtMs = nowMs()
        val bucket = bucketFor(ticket) ?: return@synchronized false
        ticket.finishedAtMs = finishedAtMs
        bucket.addWork(elapsed(startedAtMs, finishedAtMs))
        bucket.addOutcome(outcome)
        true
    }

    /**
     * Records delivery to the main thread. The ticket deliberately remains
     * usable until delivery; finishing work does not remove or invalidate it.
     */
    fun delivered(ticket: Ticket): Boolean = synchronized(gate) {
        val finishedAtMs = ticket.finishedAtMs
        if (!ticket.belongsTo(this, epoch) ||
            finishedAtMs == null ||
            ticket.deliveredAtMs != null
        ) {
            return@synchronized false
        }
        val deliveredAtMs = nowMs()
        val bucket = bucketFor(ticket) ?: return@synchronized false
        ticket.deliveredAtMs = deliveredAtMs
        bucket.addDelivery(elapsed(finishedAtMs, deliveredAtMs))
        true
    }

    /** Returns a copied, unmodifiable list. It can never expose internal buckets. */
    fun snapshot(): List<RequestMetricSummary> = synchronized(gate) {
        val result = mutableListOf<RequestMetricSummary>()
        for (bucket in buckets) {
            if (bucket != null) result += bucket.snapshot()
        }
        Collections.unmodifiableList(result)
    }

    /** Clears all metrics and invalidates every ticket created before this call. */
    fun clear() {
        synchronized(gate) {
            epoch = Any()
            buckets.fill(null)
        }
    }

    private fun bucketFor(ticket: Ticket): Bucket? =
        buckets[indexOf(ticket.platform, ticket.operation)]

    private class Bucket(
        private val platform: Platform,
        private val operation: RequestOperation,
    ) {
        var count = 0L
        private var completed = 0L
        private var completedSuccess = 0L
        private var completedFailed = 0L
        private var completedRejected = 0L
        private var completedStale = 0L
        private var queueTotalMs = 0L
        private var queueMaxMs = 0L
        private var workTotalMs = 0L
        private var workMaxMs = 0L
        private var deliveryTotalMs = 0L
        private var deliveryMaxMs = 0L

        fun addQueue(durationMs: Long) {
            queueTotalMs = saturatedAdd(queueTotalMs, durationMs)
            queueMaxMs = maxOf(queueMaxMs, durationMs)
        }

        fun addWork(durationMs: Long) {
            workTotalMs = saturatedAdd(workTotalMs, durationMs)
            workMaxMs = maxOf(workMaxMs, durationMs)
        }

        fun addDelivery(durationMs: Long) {
            deliveryTotalMs = saturatedAdd(deliveryTotalMs, durationMs)
            deliveryMaxMs = maxOf(deliveryMaxMs, durationMs)
        }

        fun addOutcome(outcome: RequestOutcome) {
            completed = saturatedIncrement(completed)
            when (outcome) {
                RequestOutcome.SUCCESS -> completedSuccess = saturatedIncrement(completedSuccess)
                RequestOutcome.FAILED -> completedFailed = saturatedIncrement(completedFailed)
                RequestOutcome.REJECTED -> completedRejected = saturatedIncrement(completedRejected)
                RequestOutcome.STALE -> completedStale = saturatedIncrement(completedStale)
            }
        }

        fun snapshot() = RequestMetricSummary(
            platform = platform,
            operation = operation,
            count = count,
            completed = completed,
            completedSuccess = completedSuccess,
            completedFailed = completedFailed,
            completedRejected = completedRejected,
            completedStale = completedStale,
            queueTotalMs = queueTotalMs,
            queueMaxMs = queueMaxMs,
            workTotalMs = workTotalMs,
            workMaxMs = workMaxMs,
            deliveryTotalMs = deliveryTotalMs,
            deliveryMaxMs = deliveryMaxMs,
        )
    }

    /**
     * Opaque lifecycle handle. Its default representation intentionally omits
     * platform and operation so logs cannot accidentally carry account labels
     * or user-derived request metadata.
     */
    class Ticket internal constructor(
        private val owner: RequestMetrics,
        private val epoch: Any,
        internal val platform: Platform,
        internal val operation: RequestOperation,
        internal val queuedAtMs: Long,
    ) {
        internal var startedAtMs: Long? = null
        internal var finishedAtMs: Long? = null
        internal var deliveredAtMs: Long? = null

        internal fun belongsTo(metrics: RequestMetrics, currentEpoch: Any): Boolean =
            owner === metrics && epoch === currentEpoch

        override fun toString(): String = "RequestMetrics.Ticket(redacted)"
    }

    companion object {
        private const val BUCKET_COUNT = 30

        private fun indexOf(platform: Platform, operation: RequestOperation): Int =
            platform.ordinal * RequestOperation.entries.size + operation.ordinal

        private fun elapsed(fromMs: Long, toMs: Long): Long =
            if (toMs < fromMs) 0L else saturatedSubtract(toMs, fromMs)

        private fun saturatedIncrement(value: Long): Long =
            if (value == Long.MAX_VALUE) Long.MAX_VALUE else value + 1L

        private fun saturatedAdd(left: Long, right: Long): Long {
            if (right <= 0L) return left
            return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
        }

        private fun saturatedSubtract(left: Long, right: Long): Long =
            if (right < 0L && left > Long.MAX_VALUE + right) Long.MAX_VALUE else left - right
    }
}
