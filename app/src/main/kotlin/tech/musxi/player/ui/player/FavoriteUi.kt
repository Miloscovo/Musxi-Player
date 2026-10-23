package tech.musxi.player.ui.player

/**
 * The favourite button's state.
 *
 * The backend verifies a write by re-reading the collection, so it never updates optimistically:
 * the heart must be able to say "working on it" without pretending the song is already liked. That
 * is what [pending] is for — `FavoritesState.pending` names the song whose write is in flight.
 */
data class FavoriteUi(
    val liked: Boolean = false,
    /** A write is in flight; keep the confirmed state and show a waiting ring. */
    val pending: Boolean = false,
    /** False when the account cannot write (read-only session, expired login). */
    val enabled: Boolean = true,
)
