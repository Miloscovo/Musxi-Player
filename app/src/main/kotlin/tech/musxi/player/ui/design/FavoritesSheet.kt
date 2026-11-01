package tech.musxi.player.ui.design

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tech.musxi.player.ui.player.FavoriteUi
import tech.musxi.player.ui.player.FullPlayer
import tech.musxi.player.ui.player.LyricsUi
import tech.musxi.player.ui.player.PlayerUi
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/**
 * Favourites sheet: the player page with the heart in the title block.
 *
 * In lyrics mode the compact translation badge and heart form one group beside the title block.
 * Both controls share a centre line and disjoint 48dp targets; text stays aligned with the timeline.
 *
 * One state per image, on purpose — the difference between "not liked", "saving" and "liked" is the
 * thing being judged. The state is named by the file, as with every other sheet.
 */
@Composable
fun FavoritesSheet(
    accent: Accent,
    mode: ThemeMode,
    favorite: FavoriteUi,
    player: PlayerUi = playerNormal(),
    lyrics: LyricsUi? = null,
) {
    MusxiTheme(accent = accent, mode = mode) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            FullPlayer(
                ui = player,
                dark = mode != ThemeMode.LIGHT,
                favorite = favorite,
                lyrics = lyrics,
            )
        }
    }
}

/** Not liked, and the account can write. */
fun favoriteNone() = FavoriteUi(liked = false)

/** Saving a like: the heart already shows the requested state, plus the small syncing dot. */
fun favoriteSavingLike() = FavoriteUi(liked = true, syncing = true)

/** Saving an un-like: the outline is shown at once, plus the small syncing dot. */
fun favoriteSavingUnlike() = FavoriteUi(liked = false, syncing = true)

fun favoriteLiked() = FavoriteUi(liked = true)

/** Read-only: the heart is drawn but inert, so the user learns why nothing happens. */
fun favoriteReadOnly() = FavoriteUi(liked = false, enabled = false)
