package tech.musxi.playback

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Job
import tech.musxi.core.library.SongSummary
import tech.musxi.core.playback.PlaybackFailure
import tech.musxi.core.playback.SourceSelection
import tech.musxi.core.playback.AlternativeSources

/** Supplied by Application; authentication and platform IO remain outside the player module. */
interface PlaybackHost {
    val playbackSources: PlaybackSourceProvider
    val sourceSelection: SourceSelection
    fun findAlternativeSources(original: SongSummary, result: (Result<AlternativeSources>) -> Unit): Job
    /** Ordinary MediaSession STOP cancels a pending playlist read, without issuing another STOP. */
    fun onPlaybackStopRequested()
    /** Original account-owned metadata only; capabilities and candidate tickets never persist. */
    fun onPlaybackCheckpoint(generation: Long, snapshot: tech.musxi.core.playback.SavedPlaybackQueue,
        recent: tech.musxi.core.playback.RecentPlay?) = Unit
    fun onPlaybackQueueCleared(platform: tech.musxi.core.account.Platform, generation: Long) = Unit
}

interface PlaybackSourceProvider {
    val authorization: Flow<Long?>
    fun currentGeneration(): Long?
    /** Account generations are scoped to the platform of the requested media. */
    fun generationFor(mediaId: String): Long? = currentGeneration()
    /** Called on an ExoPlayer loader thread, never the main thread. */
    fun resolve(mediaId: String, generation: Long): StreamLease
}

class PlaybackSourceException(val failure: PlaybackFailure) : IOException("Playback source unavailable")

/** In-memory capability. Its reader must check both expiry and account generation on every use. */
class StreamLease(val metadata: SongSummary?, private val reader: () -> String) {
    fun readUrl(): String = reader()
    override fun toString() = "StreamLease(redacted)"
}

/** Opaque queue URIs are never sent to the network. Only the loader receives signed URLs. */
class QueueSources(private val provider: PlaybackSourceProvider) {
    private class Ticket(val mediaId: String, val generation: Long,
        val sourceId: String = mediaId, val sourceGeneration: Long = generation)
    class Resolved internal constructor(val url: String, val metadata: SongSummary?, private val current: () -> Boolean) {
        /** Metadata posted to the owner thread must still belong to this source ticket. */
        fun isCurrent() = current()
        override fun toString() = "Resolved(redacted)"
    }
    private val gate = Any()
    private var tickets: Map<String, Ticket> = emptyMap()
    private val leases = LinkedHashMap<String, StreamLease>(4, 0.75f, true)

    fun replace(mediaIds: List<String>, generation: Long): List<String> {
        require(mediaIds.isNotEmpty() && mediaIds.size <= MAX_QUEUE_SIZE && mediaIds.all(PlaybackSourcePolicy::acceptsMediaId))
        require(mediaIds.map { it.substringBefore(':') }.distinct().size == 1)
        if (provider.generationFor(mediaIds.first()) != generation) throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        val nonce = UUID.randomUUID().toString()
        val next = mediaIds.mapIndexed { index, id -> "musxi://queue/$nonce/$index" to Ticket(id, generation) }.toMap()
        synchronized(gate) { tickets = next; leases.clear() }
        return next.keys.toList()
    }

    fun clear() = synchronized(gate) { tickets = emptyMap(); leases.clear() }
    /** Keep surviving slots and their selected-source capabilities; revoke removed slots immediately. */
    fun retain(uris: Set<String>) = synchronized(gate) {
        require(uris.all(tickets::containsKey))
        tickets = tickets.filterKeys { it in uris }
        leases.keys.retainAll(uris)
    }
    fun append(mediaId: String, generation: Long): String = synchronized(gate) {
        require(tickets.size < MAX_QUEUE_SIZE && PlaybackSourcePolicy.acceptsMediaId(mediaId))
        require(tickets.values.all { it.mediaId.substringBefore(':') == mediaId.substringBefore(':') && it.generation == generation })
        if (provider.generationFor(mediaId) != generation) throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        val uri = "musxi://queue/${UUID.randomUUID()}/0"
        tickets = tickets + (uri to Ticket(mediaId, generation))
        uri
    }
    fun invalidate(uri: String) { synchronized(gate) { leases.remove(uri) } }
    fun selectSource(uri: String, mediaId: String, generation: Long) = synchronized(gate) {
        require(PlaybackSourcePolicy.acceptsMediaId(mediaId))
        val original = tickets[uri] ?: throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        checkCurrent(uri, original)
        if (provider.generationFor(mediaId) != generation) throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        tickets = tickets + (uri to Ticket(original.mediaId, original.generation, mediaId, generation))
        leases.remove(uri)
    }
    fun sourceAuthorized(uri: String): Boolean = synchronized(gate) {
        tickets[uri]?.let { provider.generationFor(it.sourceId) == it.sourceGeneration } == true
    }
    fun resetSource(uri: String) = synchronized(gate) {
        val ticket = tickets[uri] ?: return@synchronized
        tickets = tickets + (uri to Ticket(ticket.mediaId, ticket.generation))
        leases.remove(uri)
    }

    fun resolve(uri: String): Resolved {
        val ticket = synchronized(gate) { tickets[uri] } ?: throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        // A ticket lock deduplicates concurrent range opens without blocking queue replacement.
        return synchronized(ticket) {
            checkCurrent(uri, ticket)
            var lease = synchronized(gate) { leases[uri] }
            var url: String? = null
            if (lease != null) {
                try { url = lease.readUrl() }
                catch (error: PlaybackSourceException) {
                    if (error.failure != PlaybackFailure.SOURCE_EXPIRED) throw error
                    invalidate(uri); lease = null
                }
            }
            if (lease == null) {
                lease = provider.resolve(ticket.sourceId, ticket.sourceGeneration)
                url = lease.readUrl()
            }
            val officialUrl = url ?: throw PlaybackSourceException(PlaybackFailure.PROTOCOL)
            if (!PlaybackSourcePolicy.accepts(ticket.sourceId, officialUrl)) throw PlaybackSourceException(PlaybackFailure.PROTOCOL)
            if (lease.metadata?.key?.stableId?.let { it != ticket.sourceId } == true)
                throw PlaybackSourceException(PlaybackFailure.PROTOCOL)
            checkCurrent(uri, ticket)
            synchronized(gate) {
                if (tickets[uri] !== ticket) throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
                leases[uri] = lease
                while (leases.size > 3) leases.remove(leases.keys.first())
            }
            Resolved(officialUrl, lease.metadata) {
                try { checkCurrent(uri, ticket); true } catch (_: PlaybackSourceException) { false }
            }
        }
    }

    private fun checkCurrent(uri: String, ticket: Ticket) {
        if (provider.generationFor(ticket.mediaId) != ticket.generation ||
            provider.generationFor(ticket.sourceId) != ticket.sourceGeneration || synchronized(gate) { tickets[uri] !== ticket })
            throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
    }

    companion object { const val MAX_QUEUE_SIZE = 20_000 }
}
