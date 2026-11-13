package tech.musxi.player.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.Composable
import tech.musxi.player.ui.theme.LocalMusxi
import tech.musxi.player.ui.theme.likedHeartColor

/** What the song's action menu can offer for one song. Built by the shell from backend state. */
data class SongActionsUi(
    val liked: Boolean = false,
    /** False while the platform's favorites are not synced or the account is read-only. */
    val canFavorite: Boolean = false,
    /** Why the favourite row is disabled, in the user's words. The two causes need different words. */
    val favoriteNote: String? = null,
    val canAddToPlaylist: Boolean = true,
)

/**
 * The song menu behind a row's "⋯": play next, queue, favourite and the playlists the song is in.
 * An action is offered only when its callback is given, so each screen shows exactly what it can do.
 *
 * Every choice closes the menu. "加入歌单" hands over to the shell's playlist sheet: a checklist with
 * per-list results needs more room than a small card anchored to a button.
 */
@Composable
fun SongActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    ui: SongActionsUi,
    onPlayNext: (() -> Unit)?,
    onAddToQueue: (() -> Unit)?,
    onToggleFavorite: (() -> Unit)?,
    onManagePlaylists: (() -> Unit)?,
    align: MenuAlign = MenuAlign.END,
    preferAbove: Boolean = false,
) {
    val manage = if (ui.canAddToPlaylist) onManagePlaylists else null
    val hasRows = onPlayNext != null || onAddToQueue != null || onToggleFavorite != null || manage != null
    AnchoredMenu(
        expanded = expanded && hasRows,
        onDismiss = onDismiss,
        align = align,
        preferAbove = preferAbove,
    ) {
        if (onPlayNext != null) {
            MenuItem(
                "下一首播放",
                icon = Icons.AutoMirrored.Outlined.PlaylistPlay,
                onClick = {
                    onDismiss()
                    onPlayNext()
                },
            )
        }
        if (onAddToQueue != null) {
            MenuItem(
                "加入播放队列",
                icon = Icons.AutoMirrored.Outlined.QueueMusic,
                onClick = {
                    onDismiss()
                    onAddToQueue()
                },
            )
        }
        // A hairline only between the two groups, and only when both exist.
        if ((onPlayNext != null || onAddToQueue != null) && (onToggleFavorite != null || manage != null)) {
            MenuDivider()
        }
        if (onToggleFavorite != null) {
            MenuItem(
                if (ui.liked) "取消收藏" else "收藏",
                icon = if (ui.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                // A liked heart keeps the red the player's heart uses; an unliked one stays grey.
                iconTint = if (ui.liked) likedHeartColor(LocalMusxi.current.dark) else null,
                enabled = ui.canFavorite,
                supporting = if (!ui.canFavorite) ui.favoriteNote ?: "收藏同步中" else null,
                onClick = {
                    onDismiss()
                    onToggleFavorite()
                },
            )
        }
        if (manage != null) {
            MenuItem(
                "加入歌单",
                icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
                onClick = {
                    onDismiss()
                    manage()
                },
            )
        }
    }
}
