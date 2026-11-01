package tech.musxi.core.lyrics

import tech.musxi.core.library.MediaKey

/**
 * Minimal source binding metadata. Provider documents, credentials and URLs are intentionally excluded.
 */
data class LyricsCacheBinding(
    val origin: MediaKey? = null,
    val offsetMs: Long = 0L,
    val candidateKeys: List<MediaKey> = emptyList(),
)

/**
 * A disk hit. Expired content remains available as [stale] so a caller may render it while refreshing.
 */
data class LyricsCacheHit(
    val lyrics: ParsedLyrics,
    val binding: LyricsCacheBinding?,
    val cachedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val stale: Boolean,
)

/**
 * Synchronous, owner-scoped lyrics cache storage. A caller supplies owner digests from
 * [tech.musxi.core.library.LibraryCacheIdentity.owner].
 */
interface LyricsCacheStore {
    fun read(owner: String, key: MediaKey): LyricsCacheHit?

    fun write(
        owner: String,
        key: MediaKey,
        lyrics: ParsedLyrics,
        binding: LyricsCacheBinding? = null,
    )

    fun clearOwner(owner: String)

    fun clear()

    companion object {
        const val LYRICS_TTL_MS = 30L * 24L * 60L * 60L * 1_000L
        const val NO_LYRICS_TTL_MS = 24L * 60L * 60L * 1_000L
        const val MAX_ENTRIES = 256
        const val MAX_BYTES = 16L * 1024L * 1024L
    }
}
