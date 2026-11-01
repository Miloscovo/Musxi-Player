package tech.musxi.core.library

/** A menu target belongs to one verified account attempt; it is not a write credential. */
data class EditablePlaylist(val playlist: PlaylistSummary, val accountGeneration: Long, val favorite: Boolean)
