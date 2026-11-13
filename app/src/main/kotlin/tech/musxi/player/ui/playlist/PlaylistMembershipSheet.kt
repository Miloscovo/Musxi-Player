package tech.musxi.player.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.musxi.core.library.MediaKey
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.components.MusxiSheet
import tech.musxi.player.ui.components.PlainRow
import tech.musxi.player.ui.components.PlatformTag
import tech.musxi.player.ui.components.ProgressArc
import tech.musxi.player.ui.components.RowCheckbox
import tech.musxi.player.ui.components.RowDivider
import tech.musxi.player.ui.components.RowSpec
import tech.musxi.player.ui.components.RowText
import tech.musxi.player.ui.components.songSubtitle
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.displayName
import tech.musxi.player.ui.theme.rememberHaptics

/** Test handle of the save button. */
internal const val MembershipSaveTag = "membership-save"

/** Test handle of the "新建歌单" row. */
internal const val MembershipCreateTag = "membership-create"

/**
 * The "加入歌单" sheet: which of the account's ordinary playlists hold this song, as a checklist with
 * one save. "我喜欢" is not listed; the heart is its own control.
 *
 * Place it once in a full-window overlay and leave it in the tree; a null [ui] slides it out. The
 * save button is in the header, not at the bottom: at the medium height the bottom of the sheet is
 * below the screen, and a save the user has to drag up to find is a save they miss.
 */
@Composable
fun PlaylistMembershipSheet(
    ui: MembershipSheetUi?,
    dark: Boolean,
    onToggle: (MediaKey) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onStartCreate: () -> Unit = {},
    onCancelCreate: () -> Unit = {},
    onCreate: (String) -> Unit = {},
) {
    // Kept so the sheet slides out showing what it showed, instead of going blank first.
    val last = remember { arrayOfNulls<MembershipSheetUi>(1) }
    if (ui != null) last[0] = ui
    val shown = ui ?: last[0]
    MusxiSheet(visible = ui != null, onDismiss = onDismiss) {
        if (shown != null) SheetBody(shown, dark, onToggle, onSave, onRetry, onStartCreate)
    }
    if (ui != null && ui.naming) {
        NewPlaylistDialog(ui, onCancel = onCancelCreate, onCreate = onCreate)
    }
}

@Composable
private fun ColumnScope.SheetBody(
    ui: MembershipSheetUi,
    dark: Boolean,
    onToggle: (MediaKey) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onStartCreate: () -> Unit,
) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Header(ui, dark, onSave)
    when {
        ui.phase == MembershipPhase.LOADING -> Centered {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressArc(size = 14.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在读取歌单…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ui.phase == MembershipPhase.FAILED -> Centered {
            Button(onClick = onRetry) { Text("重新读取", style = MaterialTheme.typography.labelLarge) }
        }
        else -> {
            // "我喜欢" (only in the heart's sheet) leads, then the way to make a new list, then the rest.
            val liked = ui.rows.takeWhile { it.target.favorite }
            val lists = ui.rows.drop(liked.size)
            LazyColumn(
                // While a save or its check runs, the rows stay readable but quieter: they are not the
                // state to act on yet.
                Modifier.weight(1f).alpha(if (ui.editable) 1f else 0.55f),
                contentPadding = PaddingValues(start = Space.page, end = Space.page, bottom = navBottom + 8.dp),
            ) {
                itemsIndexed(liked, key = { _, row -> "liked:" + row.target.playlist.key.stableId }) { _, row ->
                    PlaylistRow(row, enabled = ui.editable, onToggle = { onToggle(row.target.playlist.key) })
                    RowDivider(start = RowSpec.textStart(RowSpec.cover))
                }
                item(key = "create") {
                    CreateRow(ui, onStartCreate)
                    if (lists.isNotEmpty()) RowDivider(start = RowSpec.textStart(RowSpec.cover))
                }
                itemsIndexed(lists, key = { _, row -> row.target.playlist.key.stableId }) { index, row ->
                    PlaylistRow(row, enabled = ui.editable, onToggle = { onToggle(row.target.playlist.key) })
                    if (index < lists.lastIndex) RowDivider(start = RowSpec.textStart(RowSpec.cover))
                }
                if (lists.isEmpty()) item(key = "empty") {
                    Text(
                        if (ui.canCreate) "还没有自建歌单，可以在上面新建一个"
                        else "还没有自建歌单。可以先在${ui.song.key.platform.displayName}官方客户端创建，再回来添加",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}

/**
 * "新建歌单": a dashed-free plus tile in the cover's place, so it reads as a row like the others but
 * not as a list. On a platform that cannot create from here it stays, quiet, and says so.
 */
@Composable
private fun CreateRow(ui: MembershipSheetUi, onStartCreate: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val enabled = ui.editable && ui.canCreate
    PlainRow(
        onClick = {
            haptic(Haptic.TICK)
            onStartCreate()
        },
        enabled = enabled,
        role = Role.Button,
        modifier = Modifier.testTag(MembershipCreateTag),
    ) {
        Box(
            Modifier.size(RowSpec.cover).clip(MaterialTheme.shapes.small)
                .background(cs.onSurface.copy(alpha = if (ui.canCreate) 0.07f else 0.04f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(22.dp),
                tint = if (ui.canCreate) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.width(RowSpec.leadGap))
        RowText(
            "新建歌单",
            subtitle = if (ui.canCreate) "新建后直接把这首歌加进去"
            else "${ui.song.key.platform.displayName}暂不支持在 Musxi 里新建",
            subtitleColor = cs.onSurfaceVariant,
            titleColor = if (ui.canCreate) cs.onSurface else cs.onSurfaceVariant,
        )
    }
}

/**
 * Asks for the new list's name. The rule is checked as the user types (QQ counts 20 UTF-16 units,
 * the others 80 characters), and both buttons are off while the request runs so a second tap cannot
 * make a second list.
 */
@Composable
private fun NewPlaylistDialog(ui: MembershipSheetUi, onCancel: () -> Unit, onCreate: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    val problem = playlistNameProblem(value.text, ui.song.key.platform)
    val shownProblem = ui.nameError ?: problem?.takeIf { value.text.isNotEmpty() }
    AlertDialog(
        onDismissRequest = { if (!ui.creating) onCancel() },
        title = { Text("新建歌单") },
        text = {
            NewPlaylistField(value, { value = it }, shownProblem, ui.creating)
        },
        confirmButton = {
            TextButton(onClick = { onCreate(value.text) }, enabled = !ui.creating && problem == null) {
                Text(if (ui.creating) "正在新建…" else "新建并加入")
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !ui.creating) { Text("取消") } },
    )
}

/** The field on its own: under Robolectric a field inside a Dialog window never lets Compose idle. */
@Composable
internal fun NewPlaylistField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, problem: String?, busy: Boolean) {
    val focus = remember { FocusRequester() }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        enabled = !busy,
        placeholder = { Text("歌单名称") },
        isError = problem != null,
        supportingText = problem?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** The song, its platform, the save button, and one line of status when there is something to say. */
@Composable
private fun Header(ui: MembershipSheetUi, dark: Boolean, onSave: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.page).padding(bottom = 8.dp)) {
        Text(ui.mode.title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(
                seed = ui.song.album.ifBlank { ui.song.key.stableId },
                modifier = Modifier.size(RowSpec.coverCompact),
                shape = MaterialTheme.shapes.small,
                url = ui.song.coverUrl,
            )
            Spacer(Modifier.width(RowSpec.leadGap))
            Column(Modifier.weight(1f)) {
                Text(
                    ui.song.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The platform is named because a swapped source plays from elsewhere: the lists
                    // here are always the original song's platform.
                    PlatformTag(ui.song.key.platform, dark, showName = true)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        songSubtitle(ui.song), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            SaveButton(ui, onSave)
        }
        ui.message?.let { message ->
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (ui.messageWarning) cs.error else cs.onSurface,
            )
        }
    }
}

/**
 * Ink capsule, like the library's "播放全部". It names how many changes it will send, so the user
 * sees that untouched lists are not part of the save.
 */
@Composable
private fun SaveButton(ui: MembershipSheetUi, onSave: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val busy = ui.phase == MembershipPhase.SAVING || ui.phase == MembershipPhase.CHECKING
    val label = when {
        ui.phase == MembershipPhase.SAVING -> "保存中"
        ui.phase == MembershipPhase.CHECKING -> "核对中"
        ui.changeCount > 0 -> "保存 ${ui.changeCount} 项"
        else -> "保存"
    }
    Button(
        onClick = onSave,
        enabled = ui.canSave,
        modifier = Modifier.heightIn(min = 36.dp).testTag(MembershipSaveTag)
            .semantics { if (busy) stateDescription = label },
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = cs.onSurface,
            contentColor = cs.surface,
            disabledContainerColor = cs.onSurface.copy(alpha = 0.10f),
            disabledContentColor = cs.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        if (busy) {
            ProgressArc(color = cs.onSurfaceVariant, size = 12.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * One playlist. The whole row toggles; the mark only shows state. A pending change says so in the
 * second line, so a checklist with one row flipped is not mistaken for the saved state.
 */
@Composable
private fun PlaylistRow(row: MembershipRowUi, enabled: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val count = "${row.target.playlist.trackCount} 首"
    val (second, warn) = when {
        row.outcome != null -> row.outcome.text to row.outcome.warning
        row.changed -> (if (row.checked) "保存后加入" else "保存后移出") to false
        row.pinned -> "当前歌单 · $count" to false
        else -> count to false
    }
    PlainRow(
        onClick = {
            haptic(Haptic.TICK)
            onToggle()
        },
        enabled = enabled,
        role = Role.Checkbox,
        modifier = Modifier.semantics {
            toggleableState = if (row.checked) ToggleableState.On else ToggleableState.Off
        },
    ) {
        Cover(
            seed = row.target.playlist.key.stableId,
            modifier = Modifier.size(RowSpec.cover),
            shape = MaterialTheme.shapes.small,
            url = row.target.playlist.coverUrl,
        )
        Spacer(Modifier.width(RowSpec.leadGap))
        RowText(
            row.target.playlist.title,
            subtitle = second,
            subtitleColor = when {
                warn -> cs.error
                row.changed || row.outcome != null -> cs.onSurface
                else -> cs.onSurfaceVariant
            },
            subtitleLines = if (row.outcome != null) 2 else 1,
        )
        Spacer(Modifier.width(12.dp))
        RowCheckbox(row.checked)
    }
}

@Composable
private fun ColumnScope.Centered(content: @Composable () -> Unit) {
    Box(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 40.dp),
        contentAlignment = Alignment.TopCenter,
    ) { content() }
}
