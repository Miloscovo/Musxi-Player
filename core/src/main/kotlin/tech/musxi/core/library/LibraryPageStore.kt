package tech.musxi.core.library

/** A page is complete only for its own offset; it is not a persisted playback queue. */
data class SavedLibraryPage<T>(val page: Page<T>, val fetchedAtEpochMs: Long)

/** Owner is a SHA-256 digest of platform + verified/saved account identity, never a credential. */
interface LibraryPageStore {
    fun readPlaylists(owner: String, offset: Int): SavedLibraryPage<PlaylistSummary>?
    fun writePlaylists(owner: String, offset: Int, value: SavedLibraryPage<PlaylistSummary>)
    fun readTracks(owner: String, playlist: MediaKey, offset: Int): SavedLibraryPage<SongSummary>?
    fun writeTracks(owner: String, playlist: MediaKey, offset: Int, value: SavedLibraryPage<SongSummary>)
    /** Erase all metadata, including other owners. Failure must be observable to disconnect. */
    fun clear()
}
