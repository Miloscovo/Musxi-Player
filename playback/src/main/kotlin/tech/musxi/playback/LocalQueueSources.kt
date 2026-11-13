package tech.musxi.playback

import java.net.URI
import java.net.URISyntaxException
import java.util.UUID
import tech.musxi.core.localaudio.LocalAudioError
import tech.musxi.core.localaudio.LocalAudioFailure

/**
 * Opaque playback tickets for the local library queue. A ticket names one queue slot and never
 * an audio ID, a document URI, or a file path. Every [resolve] asks the host again, so a changed
 * grant, import mode, or file version is never masked by a cached URI.
 */
class LocalQueueSources(private val host: LocalAudioPlaybackHost) {
    /** Slot identity is per replace; a loader result from an earlier queue can never match it. */
    private class Slot(val entry: LocalAudioQueueEntry)

    private val gate = Any()
    private var slots: Map<String, Slot> = emptyMap()

    fun replace(entries: List<LocalAudioQueueEntry>): List<String> {
        require(entries.isNotEmpty() && entries.size <= QueueSources.MAX_QUEUE_SIZE)
        require(entries.map { it.audio.id }.toSet().size == entries.size)
        val nonce = UUID.randomUUID().toString()
        val next = entries.mapIndexed { index, entry -> "musxi-local://queue/$nonce/$index" to Slot(entry) }
        synchronized(gate) { slots = next.toMap() }
        return next.map { it.first }
    }

    /** Loader-thread IO. Never called with the ticket lock held, so replacement stays responsive. */
    fun resolve(ticket: String): String {
        val slot = synchronized(gate) { slots[ticket] } ?: throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
        val uri = try {
            host.resolve(slot.entry)
        } catch (failure: LocalAudioFailure) {
            throw failure
        } catch (_: Exception) {
            throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        }
        // Identity fence: a result that arrives after replace/clear/retain keeps the old capability.
        if (synchronized(gate) { slots[ticket] !== slot }) throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
        return playableUri(uri)
    }

    fun retain(tickets: Set<String>) = synchronized(gate) {
        require(tickets.all(slots::containsKey))
        slots = slots.filterKeys { it in tickets }
    }

    fun clear() = synchronized(gate) { slots = emptyMap() }

    fun contains(ticket: String): Boolean = synchronized(gate) { slots.containsKey(ticket) }

    /** Local files only. Provider-specific content queries stay backend-only; file queries are invalid. */
    private fun playableUri(value: String): String {
        if (value.isBlank() || value.length > MAX_URI_LENGTH || value.any { it.isISOControl() })
            throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        val uri = try { URI(value) } catch (_: URISyntaxException) {
            throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        }
        if (uri.isOpaque || uri.rawUserInfo != null || uri.rawFragment != null)
            throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        val authority = uri.rawAuthority
        val playable = when (uri.scheme?.lowercase(java.util.Locale.ROOT)) {
            "content" -> !authority.isNullOrEmpty()
            "file" -> authority.isNullOrEmpty() && uri.rawQuery == null && uri.path?.startsWith('/') == true && uri.path.length > 1
            else -> false
        }
        if (!playable) throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        return value
    }

    override fun toString() = "LocalQueueSources(redacted)"

    private companion object { const val MAX_URI_LENGTH = 8192 }
}
