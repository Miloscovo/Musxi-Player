package tech.musxi.core.library

/** Capability is about the implemented protocol; server permission is rechecked for every write. */
data class PlaylistManagementCapabilities(
    val create: Boolean,
    val rename: Boolean,
    val follow: Boolean,
    val reason: String? = null,
    val delete: Boolean = false,
)
data class PlaylistManagementInfo(
    val target: EditablePlaylist,
    val canRename: Boolean,
    val canDelete: Boolean,
    val canUnfollow: Boolean,
    val reason: String? = null,
)
data class CreatedPlaylistSongResult(val target: EditablePlaylist, val songResult: Result<Unit>)
data class PlaylistSongChangeResult(val song: MediaKey, val change: PlaylistChangeResult)
data class PlaylistSongsResult(val playlist: MediaKey, val results: List<PlaylistSongChangeResult>) {
    val successful: Boolean get() = results.all { it.change.status == PlaylistChangeStatus.APPLIED ||
        it.change.status == PlaylistChangeStatus.UNCHANGED }
}
