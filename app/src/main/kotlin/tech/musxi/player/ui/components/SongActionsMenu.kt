package tech.musxi.player.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.musxi.core.library.EditablePlaylist
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

/** The playlist page scrolls beyond this height, so a long list of playlists cannot fill the screen. */
internal val PlaylistPickerMaxHeight = 360.dp

/**
 * Playlists the picker offers. The platform's own "我喜欢" is left out: liking a song is the "收藏"
 * row's job, and listing it here would give two ways to do the same thing. Order is kept.
 */
internal fun writableTargets(list: List<EditablePlaylist>): List<EditablePlaylist> =
    list.filterNot { it.favorite }

/**
 * The song menu behind a row's "⋯": play next, queue, favourite and add to a playlist. An action
 * is offered only when its callback is given, so each screen shows exactly what it can do.
 *
 * Two pages. The first lists the actions; "加入歌单" opens the second, which reads the account's
 * writable playlists once when it is entered. Every other choice closes the menu.
 */
@Composable
fun SongActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    ui: SongActionsUi,
    onPlayNext: (() -> Unit)?,
    onAddToQueue: (() -> Unit)?,
    onToggleFavorite: (() -> Unit)?,
    loadPlaylists: (((Result<List<EditablePlaylist>>) -> Unit) -> Unit)?,
    onAddToPlaylist: ((EditablePlaylist) -> Unit)?,
    align: MenuAlign = MenuAlign.END,
    preferAbove: Boolean = false,
) {
    // The playlist row needs both halves: somewhere to read the list from and somewhere to send the pick.
    val pickerLoad = if (ui.canAddToPlaylist && onAddToPlaylist != null) loadPlaylists else null
    val hasRows = onPlayNext != null || onAddToQueue != null || onToggleFavorite != null || pickerLoad != null
    // Page two is showing. It is cleared once the popup has left the screen, not when it starts to
    // close, so the card does not change size while it fades out.
    var picking by remember { mutableStateOf(false) }
    AnchoredMenu(
        expanded = expanded && hasRows,
        onDismiss = onDismiss,
        align = align,
        preferAbove = preferAbove,
        maxHeight = if (picking && pickerLoad != null) PlaylistPickerMaxHeight else Dp.Unspecified,
    ) {
        DisposableEffect(Unit) { onDispose { picking = false } }
        if (picking && pickerLoad != null && onAddToPlaylist != null) {
            PlaylistPickerPage(
                load = pickerLoad,
                onPick = { target ->
                    onDismiss()
                    onAddToPlaylist(target)
                },
                onBack = { picking = false },
            )
        } else {
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
            if ((onPlayNext != null || onAddToQueue != null) && (onToggleFavorite != null || pickerLoad != null)) {
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
            if (pickerLoad != null) {
                MenuItem(
                    "加入歌单",
                    icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
                    trailing = { MenuChevron() },
                    // Stays open: the choice of playlist is the second page.
                    onClick = { picking = true },
                )
            }
        }
    }
}

/** What the playlist page has so far. */
private sealed interface PickerState {
    data object Loading : PickerState
    data object Failed : PickerState
    data class Loaded(val targets: List<EditablePlaylist>) : PickerState
}

/**
 * The playlist page of a song menu: a way back, then the playlists the song can be added to. Shared
 * by the song menu and the player's overflow menu.
 *
 * The list is read once when the page is entered, and again only when the user taps the retry row.
 * A reply that arrives after the page was left, or after a newer read began, is ignored.
 */
@Composable
internal fun ColumnScope.PlaylistPickerPage(
    load: ((Result<List<EditablePlaylist>>) -> Unit) -> Unit,
    onPick: (EditablePlaylist) -> Unit,
    onBack: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<PickerState>(PickerState.Loading) }
    DisposableEffect(attempt) {
        var live = true
        state = PickerState.Loading
        load { result ->
            if (live) {
                state = result.fold(
                    onSuccess = { PickerState.Loaded(writableTargets(it)) },
                    onFailure = { PickerState.Failed },
                )
            }
        }
        onDispose { live = false }
    }
    MenuItem("返回", onClick = onBack, icon = Icons.AutoMirrored.Outlined.ArrowBack)
    MenuHeader("加入歌单")
    when (val current = state) {
        PickerState.Loading -> MenuItem(
            "正在读取歌单…",
            onClick = {},
            enabled = false,
            trailing = { ProgressArc(size = 14.dp) },
        )
        PickerState.Failed -> MenuItem("读取失败，点按重试", onClick = { attempt++ })
        is PickerState.Loaded -> {
            if (current.targets.isEmpty()) {
                MenuItem("没有可添加的歌单", onClick = {}, enabled = false)
            } else {
                current.targets.forEach { target ->
                    MenuItem(
                        target.playlist.title,
                        supporting = "${target.playlist.trackCount} 首",
                        leading = {
                            Cover(
                                seed = target.playlist.key.stableId,
                                modifier = Modifier.fillMaxSize(),
                                shape = RoundedCornerShape(5.dp),
                                url = target.playlist.coverUrl,
                            )
                        },
                        onClick = { onPick(target) },
                    )
                }
            }
        }
    }
}
