package tech.musxi.core.library

/** A page is complete only for its own offset; it is not a persisted playback queue. */
data class SavedLibraryPage<T>(val page: Page<T>, val fetchedAtEpochMs: Long)

data class CachedTrackPage(val offset: Int, val saved: SavedLibraryPage<SongSummary>)

/** Owner is a SHA-256 digest of platform + verified/saved account identity, never a credential. */
interface LibraryPageStore {
    fun readPlaylists(owner: String, offset: Int): SavedLibraryPage<PlaylistSummary>?
    fun writePlaylists(owner: String, offset: Int, value: SavedLibraryPage<PlaylistSummary>)
    fun readTracks(owner: String, playlist: MediaKey, offset: Int): SavedLibraryPage<SongSummary>?
    /**
     * Reads the contiguous cached pages available from offset zero. The default keeps existing
     * stores source-compatible; stores that retain multiple pages should override it.
     */
    fun readTrackPages(owner: String, playlist: MediaKey): List<CachedTrackPage> {
        val pages = mutableListOf<CachedTrackPage>()
        val seen = mutableSetOf<Int>()
        var offset = 0
        while (offset !in seen) {
            seen += offset
            val saved = readTracks(owner, playlist, offset) ?: break
            pages += CachedTrackPage(offset, saved)
            offset = saved.page.nextOffset ?: break
        }
        return pages
    }
    fun writeTracks(owner: String, playlist: MediaKey, offset: Int, value: SavedLibraryPage<SongSummary>)
    /** Filling must not evict earlier pages just to fit a new page. False leaves this collection intact. */
    fun writeTracksRetainingPages(owner: String, playlist: MediaKey, offset: Int,
        value: SavedLibraryPage<SongSummary>): Boolean {
        writeTracks(owner, playlist, offset, value)
        return true
    }
    /** Erase all metadata, including other owners. Failure must be observable to disconnect. */
    fun clear()
}
