package tech.musxi.playback

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Job
import tech.musxi.core.library.SongSummary
import tech.musxi.core.playback.PlaybackFailure
import tech.musxi.core.playback.SourceSelection
import tech.musxi.core.playback.SourceCandidate
import tech.musxi.core.playback.AlternativeSources
import tech.musxi.core.playback.QualityOptions

/** Supplied by Application; authentication and platform IO remain outside the player module. */
interface PlaybackHost {
    /** Already-fetched, identity-fenced lyrics; the service owns the playback clock and timers. */
    val mediaCardLyrics: Flow<MediaCardLyricsState>
        get() = kotlinx.coroutines.flow.flowOf(MediaCardLyricsState())
    val localAudioPlayback: LocalAudioPlaybackHost? get() = null
    /** A validated platform takeover invalidates an older asynchronous local preparation. */
    fun onPlatformPlaybackRequested() = Unit
    /** Local takeover cancels platform fetches, without invalidating a newer local preparation. */
    fun onLocalPlaybackRequested() = onPlaybackStopRequested()
    val playbackSources: PlaybackSourceProvider
    val sourceSelection: SourceSelection
    val sourcePreferences: kotlinx.coroutines.flow.Flow<tech.musxi.core.playback.SourceSwitchPreferences>
        get() = kotlinx.coroutines.flow.flowOf(tech.musxi.core.playback.SourceSwitchPreferences())
    fun currentSourcePreferences() = tech.musxi.core.playback.SourceSwitchPreferences()
    fun findAlternativeSources(original: SongSummary, result: (Result<AlternativeSources>) -> Unit): Job
    fun findAlternativeSources(original: SongSummary, excluded: Set<String>, result: (Result<AlternativeSources>) -> Unit): Job =
        findAlternativeSources(original) { response -> result(response.map { found ->
            found.copy(candidates = found.candidates.filter { it.song.key.stableId !in excluded })
        }) }
    fun findAlternativeSources(original: SongSummary, excluded: Set<String>,
        mode: tech.musxi.core.playback.SourceSelectionMode,
        progress: tech.musxi.core.playback.SourceSearchProgressListener,
        result: (Result<AlternativeSources>) -> Unit): Job = findAlternativeSources(original, excluded, result)
    /** Ordinary MediaSession STOP cancels a pending playlist read, without issuing another STOP. */
    fun onPlaybackStopRequested()
    /** Original account-owned metadata only; capabilities and candidate tickets never persist. */
    fun onPlaybackCheckpoint(generation: Long, snapshot: tech.musxi.core.playback.SavedPlaybackQueue,
        recent: tech.musxi.core.playback.RecentPlay?) = Unit
    fun onPlaybackCheckpointSaved(generation: Long, snapshot: tech.musxi.core.playback.SavedPlaybackQueue,
        recent: tech.musxi.core.playback.RecentPlay?, completed: (Result<Unit>) -> Unit) {
        onPlaybackCheckpoint(generation, snapshot, recent)
        completed(Result.success(Unit))
    }
    fun onPlaybackQueueCleared(platform: tech.musxi.core.account.Platform, generation: Long) = Unit
    fun onPlaybackQualityChanged(platform: tech.musxi.core.account.Platform, qualityId: String) = Unit
    /** Called only after a selected alternative actually reaches playback. Metadata only. */
    fun onAlternativeSourcePlayed(original: SongSummary, generation: Long, source: SourceCandidate) = Unit
    fun onAlternativeSourceFailed(original: tech.musxi.core.library.MediaKey, source: tech.musxi.core.library.MediaKey) = Unit
}

interface PlaybackSourceProvider {
    val authorization: Flow<Long?>
    fun currentGeneration(): Long?
    /** Account generations are scoped to the platform of the requested media. */
    fun generationFor(mediaId: String): Long? = currentGeneration()
    /** Called on an ExoPlayer loader thread, never the main thread. */
    fun resolve(mediaId: String, generation: Long): StreamLease
    fun resolve(mediaId: String, generation: Long, qualityId: String?): StreamLease = resolve(mediaId, generation)
    fun qualityOptions(mediaId: String, generation: Long): QualityOptions = QualityOptions(emptyList(), null)
}

class PlaybackSourceException(val failure: PlaybackFailure,
    val reason: tech.musxi.core.playback.FailureReason = tech.musxi.core.playback.FailureReason.fromFailure(failure)
) : IOException("Playback source unavailable")

/** In-memory capability. Its reader must check both expiry and account generation on every use. */
class StreamLease(val metadata: SongSummary?, val qualityId: String? = null, val qualityName: String? = null,
    private val reader: () -> String) {
    fun readUrl(): String = reader()
    override fun toString() = "StreamLease(redacted)"
}

/** Opaque queue URIs are never sent to the network. Only the loader receives signed URLs. */
class QueueSources(private val provider: PlaybackSourceProvider) {
    private class Ticket(val mediaId: String, val generation: Long,
        val sourceId: String = mediaId, val sourceGeneration: Long = generation, val qualityId: String? = null,
        val expectedSource: SongSummary? = null)
    class Resolved internal constructor(val url: String, val metadata: SongSummary?, private val current: () -> Boolean) {
        /** Metadata posted to the owner thread must still belong to this source ticket. */
        fun isCurrent() = current()
        override fun toString() = "Resolved(redacted)"
    }
    private val gate = Any()
    private var tickets: Map<String, Ticket> = emptyMap()
    private val leases = LinkedHashMap<String, StreamLease>(4, 0.75f, true)
    private val qualityNames = mutableMapOf<String, String>()
    class QualityChange internal constructor(internal val uri: String, internal val identity: Any,
        internal val previousLease: StreamLease?, val lease: StreamLease)

    fun replace(mediaIds: List<String>, generation: Long): List<String> {
        require(mediaIds.isNotEmpty() && mediaIds.size <= MAX_QUEUE_SIZE && mediaIds.all(PlaybackSourcePolicy::acceptsMediaId))
        require(mediaIds.map { it.substringBefore(':') }.distinct().size == 1)
        if (provider.generationFor(mediaIds.first()) != generation) throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        val nonce = UUID.randomUUID().toString()
        val next = mediaIds.mapIndexed { index, id -> "musxi://queue/$nonce/$index" to Ticket(id, generation) }.toMap()
        synchronized(gate) { tickets = next; leases.clear(); qualityNames.clear() }
        return next.keys.toList()
    }

    fun clear() = synchronized(gate) { tickets = emptyMap(); leases.clear(); qualityNames.clear() }
    /** Keep surviving slots and their selected-source capabilities; revoke removed slots immediately. */
    fun retain(uris: Set<String>) = synchronized(gate) {
        require(uris.all(tickets::containsKey))
        tickets = tickets.filterKeys { it in uris }
        leases.keys.retainAll(uris)
        qualityNames.keys.retainAll(uris)
    }
    fun append(mediaId: String, generation: Long): String = appendAll(listOf(mediaId), generation).single()

    fun appendAll(mediaIds: List<String>, generation: Long): List<String> = synchronized(gate) {
        require(mediaIds.isNotEmpty() && mediaIds.size <= MAX_QUEUE_SIZE - tickets.size)
        require(mediaIds.all(PlaybackSourcePolicy::acceptsMediaId))
        val platform = tickets.values.firstOrNull()?.mediaId?.substringBefore(':')
            ?: mediaIds.first().substringBefore(':')
        require(mediaIds.all { it.substringBefore(':') == platform })
        require(tickets.values.all { it.mediaId.substringBefore(':') == platform && it.generation == generation })
        if (provider.generationFor(mediaIds.first()) != generation ||
            mediaIds.any { provider.generationFor(it) != generation })
            throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        val added = mediaIds.map { mediaId ->
            val uri = "musxi://queue/${UUID.randomUUID()}/0"
            uri to Ticket(mediaId, generation)
        }
        tickets = tickets + added.toMap()
        added.map { it.first }
    }
    fun invalidate(uri: String) { synchronized(gate) { leases.remove(uri) } }
    fun selectSource(uri: String, mediaId: String, generation: Long, expectedSource: SongSummary? = null) = synchronized(gate) {
        require(PlaybackSourcePolicy.acceptsMediaId(mediaId))
        val original = tickets[uri] ?: throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        checkCurrent(uri, original)
        if (provider.generationFor(mediaId) != generation) throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        require(expectedSource == null || expectedSource.key.stableId == mediaId)
        tickets = tickets + (uri to Ticket(original.mediaId, original.generation, mediaId, generation,
            expectedSource = expectedSource))
        leases.remove(uri)
        qualityNames.remove(uri)
    }
    /** Resolve a candidate without changing the active ticket. Only commit publishes its capability. */
    class PreparedSource internal constructor(internal val uri: String, internal val original: Any,
        internal val candidate: Any, internal val lease: StreamLease)

    fun prepareSource(uri: String, mediaId: String, generation: Long, expectedSource: SongSummary): PreparedSource {
        require(PlaybackSourcePolicy.acceptsMediaId(mediaId) && expectedSource.key.stableId == mediaId)
        val original = synchronized(gate) { tickets[uri] } ?: throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        checkCurrent(uri, original)
        if (provider.generationFor(mediaId) != generation) throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        val candidate = Ticket(original.mediaId, original.generation, mediaId, generation, expectedSource = expectedSource)
        val lease = provider.resolve(mediaId, generation)
        validateResolved(candidate, lease, lease.readUrl())
        checkCurrent(uri, original)
        if (provider.generationFor(mediaId) != generation) throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        return PreparedSource(uri, original, candidate, lease)
    }

    fun commitSource(change: PreparedSource) = synchronized(gate) {
        val original = change.original as Ticket
        val candidate = change.candidate as Ticket
        checkCurrent(change.uri, original)
        if (provider.generationFor(candidate.sourceId) != candidate.sourceGeneration)
            throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        tickets = tickets + (change.uri to candidate)
        leases[change.uri] = change.lease
        qualityNames.remove(change.uri)
        change.lease.qualityName?.let { qualityNames[change.uri] = it }
        while (leases.size > 3) leases.remove(leases.keys.first())
    }
    fun sourceAuthorized(uri: String): Boolean = synchronized(gate) {
        tickets[uri]?.let { provider.generationFor(it.sourceId) == it.sourceGeneration } == true
    }
    fun resetSource(uri: String) = synchronized(gate) {
        val ticket = tickets[uri] ?: return@synchronized
        tickets = tickets + (uri to Ticket(ticket.mediaId, ticket.generation))
        leases.remove(uri)
        qualityNames.remove(uri)
    }

    fun currentSource(uri: String): Pair<String, Long>? = synchronized(gate) {
        tickets[uri]?.let { it.sourceId to it.sourceGeneration }
    }

    fun currentQualityName(uri: String): String? = synchronized(gate) { qualityNames[uri] }
    fun currentQualityId(uri: String): String? = synchronized(gate) { leases[uri]?.qualityId ?: tickets[uri]?.qualityId }

    fun qualities(uri: String): QualityOptions {
        val ticket = synchronized(gate) { tickets[uri] } ?: throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        checkCurrent(uri, ticket)
        val options = provider.qualityOptions(ticket.sourceId, ticket.sourceGeneration)
        checkCurrent(uri, ticket)
        return options.copy(current = currentQualityId(uri))
    }

    fun prepareQuality(uri: String, qualityId: String): QualityChange {
        val ticket = synchronized(gate) { tickets[uri] } ?: throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
        checkCurrent(uri, ticket)
        val lease = provider.resolve(ticket.sourceId, ticket.sourceGeneration, qualityId)
        if (lease.qualityId != qualityId || lease.qualityName.isNullOrBlank())
            throw PlaybackSourceException(PlaybackFailure.UNAVAILABLE)
        validateLease(uri, ticket, lease)
        checkCurrent(uri, ticket)
        return QualityChange(uri, ticket, synchronized(gate) { leases[uri] }, lease)
    }

    fun commitQuality(change: QualityChange) = synchronized(gate) {
        val uri = change.uri
        val ticket = change.identity as Ticket
        checkCurrent(uri, ticket)
        val lease = change.lease
        validateLease(uri, ticket, lease)
        tickets = tickets + (uri to Ticket(ticket.mediaId, ticket.generation, ticket.sourceId, ticket.sourceGeneration, lease.qualityId,
            ticket.expectedSource))
        leases[uri] = lease
        qualityNames[uri] = requireNotNull(lease.qualityName)
    }

    fun rollbackQuality(change: QualityChange) = synchronized(gate) {
        val ticket = change.identity as Ticket
        val current = tickets[change.uri] ?: return@synchronized
        if (current.mediaId != ticket.mediaId || current.sourceId != ticket.sourceId ||
            current.sourceGeneration != ticket.sourceGeneration || leases[change.uri] !== change.lease) return@synchronized
        tickets = tickets + (change.uri to ticket)
        leases.remove(change.uri)
        qualityNames.remove(change.uri)
        change.previousLease?.let { previous ->
            leases[change.uri] = previous
            previous.qualityName?.let { qualityNames[change.uri] = it }
        }
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
                lease = if (ticket.qualityId == null) provider.resolve(ticket.sourceId, ticket.sourceGeneration)
                    else provider.resolve(ticket.sourceId, ticket.sourceGeneration, ticket.qualityId)
                url = lease.readUrl()
            }
            val officialUrl = url ?: throw PlaybackSourceException(PlaybackFailure.PROTOCOL)
            validateResolved(ticket, lease, officialUrl)
            checkCurrent(uri, ticket)
            synchronized(gate) {
                if (tickets[uri] !== ticket) throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
                leases[uri] = lease
                lease.qualityName?.let { qualityNames[uri] = it }
                while (leases.size > 3) leases.remove(leases.keys.first())
            }
            Resolved(officialUrl, lease.metadata) {
                try { checkCurrent(uri, ticket); true } catch (_: PlaybackSourceException) { false }
            }
        }
    }

    private fun validateLease(uri: String, ticket: Ticket, lease: StreamLease) {
        val url = try { lease.readUrl() } catch (error: PlaybackSourceException) { throw error }
        validateResolved(ticket, lease, url)
        if (synchronized(gate) { tickets[uri] !== ticket }) throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
    }

    private fun validateResolved(ticket: Ticket, lease: StreamLease, url: String) {
        if (!PlaybackSourcePolicy.accepts(ticket.sourceId, url)) throw PlaybackSourceException(PlaybackFailure.PROTOCOL)
        if (lease.metadata?.key?.stableId?.let { it != ticket.sourceId } == true)
            throw PlaybackSourceException(PlaybackFailure.PROTOCOL)
        ticket.expectedSource?.let { expected -> lease.metadata?.let { resolved ->
            // Search metadata is not authoritative for the later stream lookup. Reject a
            // different singer/recording before opening its audio, even if the ID is unchanged.
            if (!tech.musxi.core.playback.SourceMatchPolicy.confirmsSelected(expected, resolved))
                throw PlaybackSourceException(PlaybackFailure.UNAVAILABLE)
        } }
    }

    private fun checkCurrent(uri: String, ticket: Ticket) {
        if (provider.generationFor(ticket.mediaId) != ticket.generation ||
            provider.generationFor(ticket.sourceId) != ticket.sourceGeneration || synchronized(gate) { tickets[uri] !== ticket })
            throw PlaybackSourceException(PlaybackFailure.AUTH_REQUIRED)
    }

    companion object { const val MAX_QUEUE_SIZE = 20_000 }
}
