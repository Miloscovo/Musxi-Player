package tech.musxi.player.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.musxi.core.library.EditablePlaylist
import tech.musxi.player.ui.components.AnchoredMenu
import tech.musxi.player.ui.components.MenuAlign
import tech.musxi.player.ui.components.MenuChevron
import tech.musxi.player.ui.components.MenuDivider
import tech.musxi.player.ui.components.MenuHeader
import tech.musxi.player.ui.components.MenuItem
import tech.musxi.player.ui.components.PlaylistPickerMaxHeight
import tech.musxi.player.ui.components.PlaylistPickerPage
import tech.musxi.player.ui.components.ProgressArc
import tech.musxi.playback.OutputType

/** One selectable row of the quality and output menus. */
data class MenuChoice(
    val id: String,
    val label: String,
    val selected: Boolean,
    val icon: ImageVector? = null,
)

/**
 * The quality menu's content. [message] is a status line shown as a disabled row (loading, failed,
 * nothing available); [loading] puts a small arc on it.
 */
data class QualityMenuUi(
    val choices: List<MenuChoice>,
    val message: String?,
    val loading: Boolean = false,
)

data class OutputMenuUi(val choices: List<MenuChoice>, val message: String?)

/** What the overflow menu asks the shell to do. */
enum class MoreAction {
    SLEEP_OFF, SLEEP_15, SLEEP_30, SLEEP_45, SLEEP_60, SLEEP_90,
    SEARCH_ARTIST, SEARCH_ALBUM, COPY, SHARE, INFO,
}

/**
 * [sleepRemaining] is the formatted time left on the sleep timer, null when none is running. It is
 * only needed while the menu is open; the dot on the button comes from the separate `sleepTimerActive`.
 */
data class MoreMenuUi(
    val artist: String,
    val album: String,
    val sleepRemaining: String?,
)

/** Glyph for an output device, by what kind of route it is. */
internal fun OutputType.menuIcon(): ImageVector = when (this) {
    OutputType.SPEAKER -> Icons.Outlined.Speaker
    OutputType.WIRED, OutputType.USB -> Icons.Outlined.Headphones
    OutputType.BLUETOOTH -> Icons.Outlined.Bluetooth
    OutputType.REMOTE, OutputType.OTHER, OutputType.UNKNOWN -> Icons.Outlined.DevicesOther
}

/**
 * Keeps the last non-null value so a menu whose state has just become null can still draw its
 * content while the exit animation plays, instead of emptying out as it fades.
 */
private class Latest<T : Any> { var value: T? = null }

@Composable
private fun <T : Any> rememberLatest(value: T?): T? {
    val holder = remember { Latest<T>() }
    if (value != null) holder.value = value
    return holder.value
}

private val QuietArc: @Composable () -> Unit = { ProgressArc(size = 14.dp) }

@Composable
internal fun PlayModeMenu(
    expanded: Boolean,
    current: PlayMode,
    onDismiss: () -> Unit,
    onSelect: (PlayMode) -> Unit,
) {
    AnchoredMenu(expanded = expanded, onDismiss = onDismiss, align = MenuAlign.START) {
        MenuHeader("播放顺序")
        PlayMode.entries.forEach { mode ->
            MenuItem(
                label = mode.label,
                onClick = { onSelect(mode) },
                icon = mode.icon(),
                selected = mode == current,
            )
        }
    }
}

@Composable
internal fun QualityMenu(
    menu: QualityMenuUi?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val shown = rememberLatest(menu)
    AnchoredMenu(expanded = menu != null, onDismiss = onDismiss, align = MenuAlign.END) {
        MenuHeader("音质")
        if (shown != null) {
            shown.choices.forEach { choice ->
                MenuItem(
                    label = choice.label,
                    onClick = { onSelect(choice.id) },
                    icon = choice.icon,
                    selected = choice.selected,
                )
            }
            shown.message?.let { message ->
                MenuItem(
                    label = message,
                    onClick = {},
                    enabled = false,
                    trailing = if (shown.loading) QuietArc else null,
                )
            }
        }
    }
}

@Composable
internal fun OutputMenu(
    menu: OutputMenuUi?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenSystemOutput: () -> Unit,
) {
    val shown = rememberLatest(menu)
    AnchoredMenu(expanded = menu != null, onDismiss = onDismiss, align = MenuAlign.START) {
        MenuHeader("音频输出")
        if (shown != null) {
            shown.choices.forEach { choice ->
                MenuItem(
                    label = choice.label,
                    onClick = { onSelect(choice.id) },
                    icon = choice.icon,
                    selected = choice.selected,
                )
            }
            shown.message?.let { message ->
                MenuItem(label = message, onClick = {}, enabled = false)
            }
        }
        MenuDivider()
        MenuItem(
            label = "系统媒体输出",
            onClick = onOpenSystemOutput,
            icon = Icons.AutoMirrored.Outlined.OpenInNew,
        )
    }
}

/** Which page of the overflow menu is showing. */
internal enum class MorePage { MAIN, SLEEP, PLAYLIST }

/**
 * The overflow menu under the top bar's "更多" button. [page] picks between its pages; the caller
 * owns it so that reopening the menu can always start on the first one.
 *
 * "加入歌单" is on the first page when both [loadPlaylists] and [onAddToPlaylist] are given. It opens
 * the playlist page, the same one the song menu uses.
 *
 * "匹配歌词" is shown when [onMatchLyrics] is given. It closes the menu first, then calls it; the
 * caller opens the lyrics match sheet.
 */
@Composable
internal fun MoreMenu(
    menu: MoreMenuUi?,
    page: MorePage,
    onPage: (MorePage) -> Unit,
    onDismiss: () -> Unit,
    onAction: (MoreAction) -> Unit,
    loadPlaylists: (((Result<List<EditablePlaylist>>) -> Unit) -> Unit)? = null,
    onAddToPlaylist: ((EditablePlaylist) -> Unit)? = null,
    onMatchLyrics: (() -> Unit)? = null,
    /** "换个音源": a manual search of the other platforms. Null hides the item. */
    onFindSources: (() -> Unit)? = null,
) {
    val shown = rememberLatest(menu)
    val canPick = loadPlaylists != null && onAddToPlaylist != null
    // With nothing to read from or send to, the playlist page has no use: show the first page instead.
    val current = if (page == MorePage.PLAYLIST && !canPick) MorePage.MAIN else page
    AnchoredMenu(
        expanded = menu != null,
        onDismiss = onDismiss,
        align = MenuAlign.END,
        preferAbove = false,
        maxHeight = if (current == MorePage.PLAYLIST) PlaylistPickerMaxHeight else Dp.Unspecified,
    ) {
        if (shown == null) return@AnchoredMenu
        if (current == MorePage.SLEEP) {
            MenuItem(
                label = "返回",
                onClick = { onPage(MorePage.MAIN) },
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
            )
            MenuDivider()
            MenuItem(
                label = "关闭定时",
                onClick = { onAction(MoreAction.SLEEP_OFF) },
                selected = shown.sleepRemaining == null,
            )
            listOf(
                15 to MoreAction.SLEEP_15,
                30 to MoreAction.SLEEP_30,
                45 to MoreAction.SLEEP_45,
                60 to MoreAction.SLEEP_60,
                90 to MoreAction.SLEEP_90,
            ).forEach { (minutes, action) ->
                MenuItem(label = "$minutes 分钟", onClick = { onAction(action) })
            }
        } else if (current == MorePage.PLAYLIST && loadPlaylists != null && onAddToPlaylist != null) {
            PlaylistPickerPage(
                load = loadPlaylists,
                onPick = { target ->
                    onDismiss()
                    onAddToPlaylist(target)
                },
                onBack = { onPage(MorePage.MAIN) },
            )
        } else {
            // No song heading: the page behind already names the song, and "歌曲信息" has the rest.
            // The card's own inset is then the same above "定时停止" as below "歌曲信息".
            MenuItem(
                label = "定时停止",
                onClick = { onPage(MorePage.SLEEP) },
                icon = Icons.Outlined.Timer,
                supporting = shown.sleepRemaining?.let { "剩余 $it" },
                trailing = { MenuChevron() },
            )
            MenuDivider()
            MenuItem(
                label = "搜索该歌手",
                onClick = { onAction(MoreAction.SEARCH_ARTIST) },
                icon = Icons.Outlined.PersonSearch,
                enabled = shown.artist.isNotBlank(),
            )
            MenuItem(
                label = "搜索该专辑",
                onClick = { onAction(MoreAction.SEARCH_ALBUM) },
                icon = Icons.Outlined.Album,
                enabled = shown.album.isNotBlank(),
            )
            MenuDivider()
            if (canPick) {
                MenuItem(
                    label = "加入歌单",
                    onClick = { onPage(MorePage.PLAYLIST) },
                    icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
                    trailing = { MenuChevron() },
                )
            }
            // Also offered while the song plays: the user may want another platform's version.
            if (onFindSources != null) {
                MenuItem(
                    label = "换个音源",
                    onClick = {
                        onDismiss()
                        onFindSources()
                    },
                    icon = Icons.Outlined.SwapHoriz,
                )
            }
            if (onMatchLyrics != null) {
                MenuItem(
                    label = "匹配歌词",
                    onClick = {
                        onDismiss()
                        onMatchLyrics()
                    },
                    icon = Icons.Outlined.Lyrics,
                )
            }
            MenuItem("复制歌名", onClick = { onAction(MoreAction.COPY) }, icon = Icons.Outlined.ContentCopy)
            MenuItem("分享", onClick = { onAction(MoreAction.SHARE) }, icon = Icons.Outlined.Share)
            MenuItem("歌曲信息", onClick = { onAction(MoreAction.INFO) }, icon = Icons.Outlined.Info)
        }
    }
}

/** The "歌曲信息" dialog: one labelled line per known field. */
@Composable
internal fun SongInfoDialog(title: String, rows: List<Pair<String, String>>, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title.ifBlank { "歌曲信息" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { (label, value) ->
                    Row {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.width(72.dp),
                        )
                        Text(value, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("好") } },
    )
}
