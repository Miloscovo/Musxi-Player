package tech.musxi.player.ui.player

/**
 * The favourite button's state.
 *
 * The backend verifies a write by re-reading the whole collection, which takes seconds, and it
 * never updates optimistically. The player therefore shows the state the user asked for at once:
 * [liked] is the target, not the last confirmed value. [syncing] only says that the backend is
 * still confirming it in the background; it must not change what the heart looks like.
 */
data class FavoriteUi(
    /** The state to show: the user's requested state while a request is open, else the confirmed one. */
    val liked: Boolean = false,
    /** The backend is still confirming [liked]; shown as a small quiet marker, never as a ring. */
    val syncing: Boolean = false,
    /** False when the account is known to be read-only; the tap then explains why instead of writing. */
    val enabled: Boolean = true,
    /** Counts refused requests; each increase shakes the heart once. */
    val failures: Int = 0,
)
