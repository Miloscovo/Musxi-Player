package tech.musxi.player.ui.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.components.AnchoredMenu
import tech.musxi.player.ui.components.MenuDivider
import tech.musxi.player.ui.components.MenuHeader
import tech.musxi.player.ui.components.MenuItem
import tech.musxi.player.ui.components.MusxiConfirmDialog
import tech.musxi.player.ui.components.MusxiTextFieldDialog
import tech.musxi.player.ui.components.ProgressArc
import tech.musxi.player.ui.theme.displayName
import tech.musxi.player.ui.theme.identityColor
import tech.musxi.player.ui.theme.isDarkSurface

@Composable
fun PlaylistManagementMenu(
    ui: PlaylistMenuUi,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveToStart: () -> Unit,
    onMoveToEnd: () -> Unit,
    onResetOrder: () -> Unit,
    canMove: Boolean,
    canMoveToEnd: Boolean,
    customOrder: Boolean,
    enabled: Boolean,
) {
    AnchoredMenu(expanded = true, onDismiss = onDismiss, preferAbove = false) {
        MenuHeader(ui.playlist.title, "排序仅保存在 Musxi")
        MenuItem("移到最前", onMoveToStart, enabled = enabled && canMove)
        MenuItem("移到最后", onMoveToEnd, enabled = enabled && canMoveToEnd,
            supporting = if (canMoveToEnd) null else "请先加载完整列表")
        if (customOrder) MenuItem("恢复默认顺序", onResetOrder, enabled = enabled)
        if (ui.loading) MenuItem("正在读取操作权限…", {}, enabled = false,
            leading = { ProgressArc(color = MaterialTheme.colorScheme.onSurfaceVariant, size = 16.dp, strokeWidth = 1.5.dp) })
        ui.info?.let { info ->
            if (info.canRename || info.canDelete) MenuDivider()
            if (info.canRename) MenuItem("重命名", onRename, Icons.Outlined.DriveFileRenameOutline, enabled = enabled)
            if (info.canDelete) MenuItem("删除歌单", onDelete, Icons.Outlined.DeleteOutline,
                iconTint = MaterialTheme.colorScheme.error, enabled = enabled)
        }
        if (ui.error != null) MenuItem("操作权限读取失败", {}, enabled = false, supporting = ui.error)
    }
}

/** Keeps the last shown naming UI across the exit animation so the card fades out cleanly. */
@Composable
fun PlaylistNameDialogHost(ui: PlaylistNameUi?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val last = remember { arrayOfNulls<PlaylistNameUi>(1) }
    if (ui != null) last[0] = ui
    val shown = ui ?: last[0] ?: return
    PlaylistNameDialogContent(ui = shown, visible = ui != null, onDismiss = onDismiss, onConfirm = onConfirm)
}

@Composable
fun PlaylistNameDialog(
    ui: PlaylistNameUi,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    PlaylistNameDialogContent(ui = ui, visible = true, onDismiss = onDismiss, onConfirm = onConfirm)
}

@Composable
private fun PlaylistNameDialogContent(
    ui: PlaylistNameUi,
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.isDarkSurface()
    val resetKey = remember(ui.title, ui.platform, ui.target?.playlist?.key, ui.initial) {
        "${ui.title}:${ui.platform}:${ui.target?.playlist?.key?.stableId}:${ui.initial}"
    }
    var value by remember(resetKey) { mutableStateOf(TextFieldValue(ui.draft, TextRange(0, ui.draft.length))) }
    var editedAfterError by remember(resetKey, ui.error) { mutableStateOf(false) }
    val seenOpen = remember { booleanArrayOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            if (seenOpen[0]) {
                value = TextFieldValue(ui.draft, TextRange(0, ui.draft.length))
                editedAfterError = false
            }
            seenOpen[0] = true
        }
    }
    val candidate = value.text.trim()
    val problem = playlistNameProblem(candidate, ui.platform)
    val remoteError = ui.error?.takeUnless { editedAfterError }
    val shownError = remoteError ?: problem?.takeIf { value.text.isNotEmpty() }
    val maxCount = if (ui.platform == Platform.QQ) 20 else 80
    val currentCount = if (ui.platform == Platform.QQ) candidate.length else candidate.codePointCount(0, candidate.length)

    MusxiTextFieldDialog(
        visible = visible,
        title = ui.title,
        value = value,
        onValueChange = {
            if (it.text != value.text) editedAfterError = true
            value = it
        },
        confirmLabel = if (ui.target == null) "创建" else "保存",
        onDismiss = onDismiss,
        onConfirm = { onConfirm(candidate) },
        confirmEnabled = problem == null,
        placeholder = "输入歌单名称",
        helperText = if (ui.target == null) "新建后将同步到${ui.platform.displayName}账号" else "修改将同步到${ui.platform.displayName}账号",
        errorText = shownError,
        counterText = "$currentCount / $maxCount",
        busy = ui.submitting,
        busyLabel = "正在提交…",
        eyebrow = ui.platform.displayName,
        eyebrowDot = ui.platform.identityColor(dark),
    )
}

/** Keeps the last shown delete UI across the exit animation so the card fades out cleanly. */
@Composable
fun PlaylistDeleteDialogHost(ui: PlaylistDeleteUi?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val last = remember { arrayOfNulls<PlaylistDeleteUi>(1) }
    if (ui != null) last[0] = ui
    val shown = ui ?: last[0] ?: return
    PlaylistDeleteDialogContent(ui = shown, visible = ui != null, onDismiss = onDismiss, onConfirm = onConfirm)
}

@Composable
fun PlaylistDeleteDialog(
    ui: PlaylistDeleteUi,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    PlaylistDeleteDialogContent(ui = ui, visible = true, onDismiss = onDismiss, onConfirm = onConfirm)
}

@Composable
private fun PlaylistDeleteDialogContent(
    ui: PlaylistDeleteUi,
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.isDarkSurface()
    val playlist = ui.target.playlist
    val platform = playlist.key.platform
    MusxiConfirmDialog(
        visible = visible,
        title = "删除${platform.displayName}歌单？",
        body = "将删除整个“${playlist.title}”，包含其中 ${playlist.trackCount} 首歌曲的歌单关系。",
        note = "歌曲本身和其他歌单不会删除，已经开始播放的队列继续保留。此操作会修改平台账号中的歌单。",
        confirmLabel = "删除整个歌单",
        busyLabel = "正在删除…",
        danger = true,
        busy = ui.submitting,
        eyebrow = "${platform.displayName} · 远端操作",
        eyebrowDot = platform.identityColor(dark),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}
