package tech.musxi.player.ui.localaudio

import tech.musxi.player.localaudio.LocalArtworkCrop
import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PlaylistRemove
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.musxi.core.localaudio.LocalAudioBatchAction
import tech.musxi.core.localaudio.LocalAudioBatchResult
import tech.musxi.core.localaudio.LocalAudioError
import tech.musxi.core.localaudio.LocalAudioFolder
import tech.musxi.core.localaudio.LocalImportMode
import tech.musxi.player.localaudio.LocalAudioPickers
import tech.musxi.player.ui.components.AnchoredMenu
import tech.musxi.player.ui.components.MenuAlign
import tech.musxi.player.ui.components.MenuChevron
import tech.musxi.player.ui.components.MenuDivider
import tech.musxi.player.ui.components.MenuHeader
import tech.musxi.player.ui.components.MenuItem
import tech.musxi.player.ui.theme.Space

/*
 * The 本地音频 pages wired to the library. The host (MainShell) calls only [LocalAudioHomeRoute] under
 * the library tab's fourth segment and [LocalAudioFolderRoute] as the tab's second level; everything
 * else, menus, dialogs and the system pickers, lives here and talks to a [LocalAudioController].
 *
 * Notices never carry a URI, a path or an exception message: every failure goes through
 * [localErrorText] or one of the scene-specific sentences below.
 */

private const val NOT_CONNECTED = "播放服务还没准备好，请稍后再试"
private const val NO_PICKER = "这台手机上没有可用的文件选择器"

/** The library's status line for the 本地音频 segment, for `LibraryContent(localStatus = …)`. */
@Composable
fun rememberLocalLibraryStatus(controller: LocalAudioController): String {
    val state by controller.library.collectAsStateWithLifecycle()
    return remember(state.ready, state.error, state.folders.size, state.items.size) { localLibraryStatus(state) }
}

/** The 本地音频 home: continue the last local list, a running import, and the folders. */
@Composable
fun LocalAudioHomeRoute(
    controller: LocalAudioController,
    bottomInset: Dp,
    nowPlayingFolderId: String?,
    onOpenFolder: (String) -> Unit,
    onOpenUnfiled: () -> Unit,
    onNotice: (String) -> Unit,
    /** A local list is playing now: "继续播放" would only reload it, so its card is hidden. */
    localPlaying: Boolean = false,
    /** Hoisted by the shell so reselecting the library tab can scroll this page to the top. */
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    /** The library page's header, handed on to [LocalAudioHome]. */
    header: @Composable () -> Unit = {},
) {
    val state by controller.library.collectAsStateWithLifecycle()
    val imports by controller.imports.collectAsStateWithLifecycle()
    val restore by controller.restoreState.collectAsStateWithLifecycle()
    val autoArtwork by controller.autoArtwork.collectAsStateWithLifecycle()
    val index = remember(state, autoArtwork) { LocalLibraryIndex(state, autoArtwork) }
    // Only a restore this page started shows its spinner; one the service runs at cold start hides
    // the card, because "继续" would race it.
    var restoringHere by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val ui = LocalHomeUi(
        loading = !state.ready && state.error == null,
        failed = !state.ready && state.error != null,
        folders = remember(index) { index.folders.map(index::folderUi) },
        unfiled = index.unfiled.size,
        resume = when {
            restoringHere -> LocalResumeUi(RESUME_TITLE, RESUME_DETAIL, restoring = true)
            !localPlaying && restore.ready && restore.available && !restore.restoring ->
                LocalResumeUi(RESUME_TITLE, RESUME_DETAIL, restoring = false)
            else -> null
        },
        importing = if (imports.running) importProgressText(imports) else null,
    )
    LocalAudioHome(
        ui = ui,
        listState = listState,
        header = header,
        onOpenFolder = onOpenFolder,
        onOpenUnfiled = onOpenUnfiled,
        onNewFolder = { creating = true },
        onResume = {
            when {
                restoringHere -> Unit
                !controller.connected.value -> onNotice(NOT_CONNECTED)
                else -> {
                    restoringHere = true
                    controller.restoreLastQueue { result ->
                        restoringHere = false
                        when (result.localError()) {
                            null -> if (result.isFailure) onNotice(localErrorText(null))
                            LocalAudioError.NOT_FOUND -> onNotice("上次列表里的音频都已删除")
                            LocalAudioError.SOURCE_CHANGED -> onNotice("上次的列表已经变了，请从文件夹重新播放")
                            else -> onNotice(localErrorText(result.localError()))
                        }
                    }
                }
            }
        },
        onCancelImport = controller::cancelImport,
        bottomInset = bottomInset,
        nowPlayingFolderId = nowPlayingFolderId,
    )
    if (creating) {
        LocalNameDialog(
            title = "新建文件夹",
            initial = "",
            confirmLabel = "创建",
            saving = saving,
            onDismiss = { creating = false },
            onConfirm = { name ->
                saving = true
                createFolderAndOpen(controller, name, onOpenFolder, onNotice) {
                    saving = false
                    creating = false
                }
            },
        )
    }
}

/**
 * Creates a folder from the home's "新建" and opens it: the next step after a new folder is always to
 * fill it. [done] runs first either way, so the dialog closes before the page changes.
 */
internal fun createFolderAndOpen(
    controller: LocalAudioController,
    name: String,
    onOpenFolder: (String) -> Unit,
    onNotice: (String) -> Unit,
    done: () -> Unit,
) {
    controller.createFolder(name) { result ->
        done()
        result.onSuccess { onOpenFolder(it.id) }.onFailure { onNotice(localErrorText(result.localError())) }
    }
}

private const val RESUME_TITLE = "上次的本地播放列表"
private const val RESUME_DETAIL = "恢复后先暂停，从上次停下的地方接着听"

/** What a system picker was opened for; kept across activity recreation, so ids only, never URIs. */
internal enum class PickKind { IMPORT_FILES, RELINK, LINK_DIRECTORY, IMPORT_DIRECTORY, REAUTHORIZE, ARTWORK }

/** [id] is the folder for imports and links, the recording for a relink or a cover, the link for reauthorizing. */
internal data class PickTarget(val kind: PickKind, val id: String, val mode: LocalImportMode)

private val PickTargetSaver = Saver<PickTarget?, ArrayList<String>>(
    save = { target -> target?.let { arrayListOf(it.kind.name, it.id, it.mode.name) } },
    restore = { PickTarget(PickKind.valueOf(it[0]), it[1], LocalImportMode.valueOf(it[2])) },
)

/**
 * One launcher for every picker. It must be StartActivityForResult: the typed contracts hand back
 * only URIs, and the backend needs the result Intent's real flags to take persistable read access.
 * The URIs go straight to the backend and are never stored in state.
 */
@Composable
private fun rememberLocalPicker(
    onPicked: (PickTarget, List<Uri>, Int) -> Unit,
    onNoPicker: () -> Unit,
): (PickTarget) -> Unit {
    var pending by rememberSaveable(stateSaver = PickTargetSaver) { mutableStateOf<PickTarget?>(null) }
    val latest by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = pending
        pending = null
        // Cancelling the picker is not an error and starts nothing.
        if (target == null || result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = result.data ?: return@rememberLauncherForActivityResult
        val uris = buildList {
            data.data?.let(::add)
            data.clipData?.let { clip -> repeat(clip.itemCount) { i -> clip.getItemAt(i).uri?.let(::add) } }
        }.distinct()
        if (uris.isNotEmpty()) latest(target, uris, data.flags)
    }
    return { target ->
        val intent = when (target.kind) {
            PickKind.IMPORT_FILES -> LocalAudioPickers.files()
            PickKind.RELINK -> LocalAudioPickers.files(allowMultiple = false)
            PickKind.ARTWORK -> LocalAudioPickers.image()
            else -> LocalAudioPickers.directory()
        }
        pending = target
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            pending = null
            onNoPicker()
        }
    }
}

/** A dialog the folder page can show; one at a time. */
private sealed interface FolderDialog {
    data object RenameFolder : FolderDialog
    data object DeleteFolder : FolderDialog
    data class Unlink(val linkId: String, val name: String) : FolderDialog
    data class RenameAudio(val id: String) : FolderDialog
    data class DeleteAudios(val ids: List<String>) : FolderDialog
    data class RemoveAudios(val ids: List<String>) : FolderDialog
    /** "新建文件夹" from an add-to list: create it, then put the recordings in. */
    class NewFolderThen(val then: (LocalAudioFolder) -> Unit) : FolderDialog
}

/** Which page of a row's or the folder's menu is showing. */
private sealed interface MenuPage {
    data object Root : MenuPage
    data object Folders : MenuPage
    data class Link(val id: String) : MenuPage
}

private val SelectionSaver = listSaver<MutableState<Set<String>>, String>(
    save = { it.value.toList() },
    restore = { mutableStateOf(it.toSet()) },
)

/**
 * One folder, or with a null [folderId] the 未归入文件夹 list. Long press picks several; while
 * picking, [onSelectionChange] tells the host to hide its dock, back leaves picking first, and the
 * action bar sits at the bottom of the page above the navigation bar.
 */
@Composable
fun LocalAudioFolderRoute(
    controller: LocalAudioController,
    folderId: String?,
    bottomInset: Dp,
    nowPlayingAudioId: String?,
    onBack: () -> Unit,
    onNotice: (String) -> Unit,
    onSelectionChange: (Boolean) -> Unit,
) {
    val state by controller.library.collectAsStateWithLifecycle()
    val imports by controller.imports.collectAsStateWithLifecycle()
    val autoArtwork by controller.autoArtwork.collectAsStateWithLifecycle()
    val index = remember(state, autoArtwork) { LocalLibraryIndex(state, autoArtwork) }
    val folder = folderId?.let(index::folder)
    val unfiled = folderId == null
    fun busyNow() = controller.imports.value.running || controller.sourceChecks.value.running

    // A folder deleted here or elsewhere leaves the page once; popping twice would leave the tab.
    var left by remember { mutableStateOf(false) }
    fun leave() { if (!left) { left = true; onBack() } }
    LaunchedEffect(state.ready, folder, folderId) {
        if (state.ready && folderId != null && folder == null) leave()
    }

    var importMode by rememberSaveable { mutableStateOf(LocalImportMode.COPY) }
    var selected by rememberSaveable(saver = SelectionSaver) { mutableStateOf<Set<String>>(emptySet()) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    var folderPage by remember { mutableStateOf<MenuPage>(MenuPage.Root) }
    var rowMenu by remember { mutableStateOf<String?>(null) }
    var rowPage by remember { mutableStateOf<MenuPage>(MenuPage.Root) }
    var batchMenu by remember { mutableStateOf<LocalBatchAction?>(null) }
    var dialog by remember { mutableStateOf<FolderDialog?>(null) }
    var saving by remember { mutableStateOf(false) }
    // A chosen cover is framed by the shell's sheet (see CoverCropHost for why not here).
    val coverCrop = LocalCoverCropHost.current

    val rowIds = remember(index, folderId) { if (folderId != null) index.memberIds(folderId) else index.unfiled }
    val rows = remember(index, rowIds) { rowIds.mapNotNull(index::row) }
    // Rows that disappear (deleted, moved away) drop out of the picked set.
    val picked = remember(rows, selected) { rows.map { it.id }.filter { it in selected } }

    fun endSelection() { selecting = false; selected = emptySet() }
    LaunchedEffect(selecting) { onSelectionChange(selecting) }
    DisposableEffect(Unit) { onDispose { onSelectionChange(false) } }
    BackHandler(enabled = selecting) { endSelection() }

    fun fail(result: Result<*>, busy: Boolean = false) = onNotice(localErrorText(result.localError(), busy))

    fun play(audioId: String?, fromStart: Boolean) {
        if (!controller.connected.value) { onNotice(NOT_CONNECTED); return }
        controller.play(folderId, audioId, fromStart) { result ->
            // SOURCE_CHANGED from play only means a newer play request took over.
            val error = result.localError()
            if (result.isFailure && error != LocalAudioError.SOURCE_CHANGED) onNotice(localErrorText(error))
        }
    }

    val pick = rememberLocalPicker(
        onNoPicker = { onNotice(NO_PICKER) },
        onPicked = { target, uris, flags ->
            val busy = busyNow()
            when (target.kind) {
                PickKind.IMPORT_FILES -> controller.importFiles(target.id, uris, target.mode, flags) { result ->
                    result.onSuccess { onNotice(importNotice(it, target.mode)) }.onFailure {
                        val error = result.localError()
                        onNotice(
                            if (error == LocalAudioError.LIMIT_REACHED || error == null) localErrorText(error, busy)
                            else localImportErrorText(error, target.mode),
                        )
                    }
                }
                PickKind.IMPORT_DIRECTORY -> controller.importDirectory(target.id, uris.first(), target.mode, flags) { result ->
                    result.onSuccess { onNotice(importNotice(it, target.mode)) }.onFailure {
                        val error = result.localError()
                        onNotice(
                            when {
                                error == LocalAudioError.LIMIT_REACHED && !busy -> "文件夹里的音频超过 500 个，请选择其中的子文件夹"
                                error == LocalAudioError.LIMIT_REACHED || error == null -> localErrorText(error, busy)
                                else -> localImportErrorText(error, target.mode)
                            },
                        )
                    }
                }
                PickKind.LINK_DIRECTORY -> {
                    // The backend cannot name a picked tree yet (3.3), so the link takes the app
                    // folder's name; reading the provider's name here would mean parsing the URI in UI.
                    val name = controller.library.value.folders.firstOrNull { it.id == target.id }?.name ?: "手机文件夹"
                    controller.linkDirectory(target.id, uris.first(), name, target.mode, flags) { result ->
                        result.onSuccess { link ->
                            // Linking only remembers the folder; the user picked it to get its audio now.
                            controller.refreshDirectory(link.id) { refresh ->
                                refresh.onSuccess { onNotice("已关联。" + refreshNotice(it.results, it.missingCount, link.mode)) }
                                    .onFailure { onNotice("已关联，但刷新没有完成：" + localErrorText(refresh.localError(), busyNow())) }
                            }
                        }.onFailure {
                            onNotice(
                                if (result.localError() == LocalAudioError.SOURCE_CHANGED) "这个手机文件夹已用另一种保存方式关联到此文件夹"
                                else if (result.localError() == LocalAudioError.LIMIT_REACHED) "关联的手机文件夹已达上限"
                                else localImportErrorText(result.localError() ?: LocalAudioError.STORAGE_FAILED, target.mode),
                            )
                        }
                    }
                }
                PickKind.REAUTHORIZE -> controller.reauthorizeDirectory(target.id, uris.first(), flags) { result ->
                    result.onSuccess { onNotice("已重新授权，可以刷新这个手机文件夹了") }.onFailure {
                        onNotice(
                            if (result.localError() == LocalAudioError.SOURCE_CHANGED) "选的不是原来的手机文件夹；要用新文件夹，请重新关联"
                            else localErrorText(result.localError()),
                        )
                    }
                }
                // The picture is not the cover yet: the user frames the part of it to keep.
                // Covers go through the shell's host (its own picker); nothing arrives here.
                PickKind.ARTWORK -> Unit
                PickKind.RELINK -> {
                    if (uris.size != 1) {
                        onNotice("只能选择一个文件")
                    } else controller.relink(target.id, uris.single(), flags) { result ->
                        result.onSuccess { onNotice("已重新关联") }.onFailure {
                            onNotice(
                                if (result.localError() == LocalAudioError.SOURCE_CHANGED) "该文件已关联到另一条音频"
                                else localErrorText(result.localError()),
                            )
                        }
                    }
                }
            }
        },
    )

    /** Organize [ids] into [destination] (ADD or MOVE) with the fresh-revision retry. */
    fun organize(action: LocalBatchAction, ids: List<String>, destination: LocalAudioFolder) {
        if (ids.size > MAX_LOCAL_BATCH) { onNotice("一次最多整理 $MAX_LOCAL_BATCH 条"); return }
        val source = folderId
        val move = action == LocalBatchAction.MOVE && source != null
        controller.withFreshRevision<LocalAudioBatchResult>(
            stillValid = { selectionStillValid(it, ids, memberOf = if (move) source else null, folders = listOf(destination.id)) },
            command = { revision, done ->
                if (move) controller.moveToFolderBatch(source, destination.id, ids, revision, done)
                else controller.addToFolderBatch(destination.id, ids, revision, done)
            },
        ) { result ->
            result.onSuccess { batch ->
                endSelection()
                onNotice(batchNotice(batch, destination.name))
            }.onFailure { onNotice(organizeFailureText(result, busy = false, limitText = "本地库数量已达上限")) }
        }
    }

    fun removeFromHere(ids: List<String>) {
        val source = folderId ?: return
        if (ids.size > MAX_LOCAL_BATCH) { onNotice("一次最多整理 $MAX_LOCAL_BATCH 条"); return }
        saving = true
        controller.withFreshRevision<LocalAudioBatchResult>(
            stillValid = { selectionStillValid(it, ids, memberOf = source) },
            command = { revision, done -> controller.removeFromFolderBatch(source, ids, revision, done) },
        ) { result ->
            saving = false
            dialog = null
            result.onSuccess {
                endSelection()
                onNotice("已移出 ${it.removedMemberships} 条，音频仍在本地库")
            }.onFailure { onNotice(organizeFailureText(result, busy = false)) }
        }
    }

    fun checkSources(ids: List<String>) {
        if (ids.size > MAX_LOCAL_BATCH) { onNotice("一次最多检查 $MAX_LOCAL_BATCH 条"); return }
        controller.withFreshRevision<List<tech.musxi.core.localaudio.LocalAudioSourceCheckResult>>(
            stillValid = { selectionStillValid(it, ids) },
            command = { revision, done -> controller.checkSources(ids, revision, done) },
            retry = postToMain,
        ) { result ->
            result.onSuccess {
                endSelection()
                onNotice(sourceCheckSummary(it))
            }.onFailure { onNotice(organizeFailureText(result, busyNow())) }
        }
    }

    /** No batch delete exists; the recordings go one by one and the notice counts the outcome. */
    fun deleteAudios(ids: List<String>) {
        saving = true
        var deleted = 0
        var firstError: LocalAudioError? = null
        fun next(i: Int) {
            if (i == ids.size) {
                saving = false
                dialog = null
                endSelection()
                onNotice(
                    when {
                        firstError == null -> "已从本地库删除 $deleted 条"
                        deleted == 0 -> localErrorText(firstError)
                        else -> "已删除 $deleted 条，${ids.size - deleted} 条没有删除"
                    },
                )
                return
            }
            controller.deleteAudio(ids[i]) { result ->
                if (result.isSuccess) deleted++ else if (firstError == null) firstError = result.localError() ?: LocalAudioError.STORAGE_FAILED
                next(i + 1)
            }
        }
        next(0)
    }

    val folderUi = folder?.let(index::folderUi) ?: LocalFolderUi(
        id = UNFILED_ID, name = "未归入文件夹", count = rows.size, subtitle = "", continuePlayback = true,
        linkedDirectories = 0, unavailable = rows.count { it.unavailable },
    )
    val pageUi = LocalFolderPageUi(
        folder = folderUi,
        rows = rows,
        resumeLine = if (folderId != null) resumeLine(index.resumePoint(folderId), rows.size) else "",
        selected = picked.toSet(),
        selecting = selecting,
        playingId = nowPlayingAudioId,
        importing = if (imports.running) importProgressText(imports) else null,
    )
    val otherFolders = remember(index, folderId) { index.folders.filter { it.id != folderId } }

    Box(Modifier.fillMaxSize()) {
        // Rows resolve their artwork through the library; design sheets and tests leave it unset.
        androidx.compose.runtime.CompositionLocalProvider(LocalArtworkFile provides controller::artworkFile) {
            LocalFolderPage(
                ui = pageUi,
                onBack = onBack,
                onContinue = { play(null, fromStart = false) },
                onFromStart = { play(null, fromStart = true) },
                onAdd = { addMenu = true },
                onPlay = { id -> play(id, fromStart = false) },
                onMore = { id -> rowPage = MenuPage.Root; rowMenu = id },
                onToggleSelect = { id -> selected = if (id in selected) selected - id else selected + id },
                onStartSelect = { id -> selecting = true; selected = setOf(id) },
                onEndSelect = ::endSelection,
                onSelectAll = { selected = if (picked.size == rows.size) emptySet() else rows.map { it.id }.toSet() },
                // While picking, the bar replaces the dock; the list must clear the bar instead.
                bottomInset = if (selecting) maxOf(bottomInset, SelectionBarRoom) else bottomInset,
                unfiled = unfiled,
                onFolderMore = if (folder != null) ({ folderPage = MenuPage.Root; folderMenu = true }) else null,
                onCancelImport = controller::cancelImport,
                addMenu = {
                    if (folder != null) {
                        ImportMenu(
                            expanded = addMenu,
                            busy = imports.running,
                            mode = importMode,
                            onToggleMode = {
                                importMode = if (importMode == LocalImportMode.COPY) LocalImportMode.REFERENCE else LocalImportMode.COPY
                            },
                            onDismiss = { addMenu = false },
                            onPick = { kind -> addMenu = false; pick(PickTarget(kind, folder.id, importMode)) },
                        )
                    }
                },
                folderMenu = {
                    if (folder != null) {
                        FolderMenu(
                            expanded = folderMenu,
                            folder = folder,
                            links = index.directories.filter { it.folderId == folder.id },
                            page = folderPage,
                            busy = imports.running,
                            onPage = { folderPage = it },
                            onDismiss = { folderMenu = false },
                            onRename = { folderMenu = false; dialog = FolderDialog.RenameFolder },
                            onToggleFromStart = {
                                controller.setContinuePlayback(folder.id, !folder.continuePlayback) { r -> if (r.isFailure) fail(r) }
                            },
                            onRefresh = { link ->
                                folderMenu = false
                                val busy = busyNow()
                                controller.refreshDirectory(link.id) { result ->
                                    result.onSuccess { onNotice(refreshNotice(it.results, it.missingCount, link.mode)) }
                                        .onFailure { fail(result, busy) }
                                }
                            },
                            onReauthorize = { link ->
                                folderMenu = false
                                pick(PickTarget(PickKind.REAUTHORIZE, link.id, link.mode))
                            },
                            onUnlink = { link -> folderMenu = false; dialog = FolderDialog.Unlink(link.id, link.name) },
                            onDelete = { folderMenu = false; dialog = FolderDialog.DeleteFolder },
                        )
                    }
                },
                rowMenu = { id ->
                    val row = rows.firstOrNull { it.id == id }
                    val item = index.item(id)
                    if (row != null && item != null) {
                        RowMenu(
                            expanded = rowMenu == id,
                            row = row,
                            inFolder = folderId != null,
                            page = rowPage,
                            folders = otherFolders,
                            isMember = { f -> id in index.memberIds(f) },
                            onPage = { rowPage = it },
                            onDismiss = { rowMenu = null },
                            onFromStart = { rowMenu = null; play(id, fromStart = true) },
                            onRename = { rowMenu = null; dialog = FolderDialog.RenameAudio(id) },
                            onAddTo = { target ->
                                rowMenu = null
                                controller.addToFolder(target.id, id) { r ->
                                    r.onSuccess { onNotice("已加入「${target.name}」") }.onFailure { fail(r) }
                                }
                            },
                            onNewFolder = {
                                rowMenu = null
                                dialog = FolderDialog.NewFolderThen { created ->
                                    controller.addToFolder(created.id, id) { r ->
                                        r.onSuccess { onNotice("已加入「${created.name}」") }.onFailure { fail(r) }
                                    }
                                }
                            },
                            // Asks first, like the batch action, then goes through the same revision-checked path.
                            onRemove = { rowMenu = null; dialog = FolderDialog.RemoveAudios(listOf(id)) },
                            onRelink = {
                                rowMenu = null
                                pick(PickTarget(PickKind.RELINK, id, LocalImportMode.REFERENCE))
                            },
                            onCheck = {
                                rowMenu = null
                                controller.withFreshRevision<List<tech.musxi.core.localaudio.LocalAudioSourceCheckResult>>(
                                    stillValid = { selectionStillValid(it, listOf(id)) },
                                    command = { revision, done -> controller.checkSources(listOf(id), revision, done) },
                                    retry = postToMain,
                                ) { result ->
                                    result.onSuccess { onNotice(singleCheckNotice(it.firstOrNull())) }
                                        .onFailure { onNotice(organizeFailureText(result, busyNow())) }
                                }
                            },
                            onPickArtwork = if (coverCrop == null) null else {
                                { rowMenu = null; coverCrop.pick(id) }
                            },
                            onClearArtwork = {
                                rowMenu = null
                                coverCrop?.restore(id)
                            },
                            onDelete = { rowMenu = null; dialog = FolderDialog.DeleteAudios(listOf(id)) },
                        )
                    }
                },
            )
        }

        if (selecting) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = Space.page)
                    .padding(bottom = 12.dp),
            ) {
                LocalSelectionBar(
                    count = picked.size,
                    inFolder = folderId != null,
                    onAction = { action ->
                        when {
                            picked.size > MAX_LOCAL_BATCH -> onNotice("一次最多整理 $MAX_LOCAL_BATCH 条")
                            action == LocalBatchAction.ADD || action == LocalBatchAction.MOVE -> batchMenu = action
                            action == LocalBatchAction.REMOVE -> dialog = FolderDialog.RemoveAudios(picked)
                            action == LocalBatchAction.CHECK -> checkSources(picked)
                            action == LocalBatchAction.DELETE -> dialog = FolderDialog.DeleteAudios(picked)
                        }
                    },
                )
                // Anchored to the bar: it opens above it, where the thumb already is.
                val target = batchMenu
                AnchoredMenu(
                    expanded = target != null,
                    onDismiss = { batchMenu = null },
                    align = MenuAlign.START,
                    preferAbove = true,
                    maxHeight = 360.dp,
                ) {
                    MenuHeader(if (target == LocalBatchAction.MOVE) "移到文件夹" else "加入文件夹", "已选 ${picked.size} 条")
                    otherFolders.forEach { f ->
                        MenuItem(f.name, icon = Icons.Outlined.Folder, onClick = {
                            batchMenu = null
                            organize(target ?: LocalBatchAction.ADD, picked, f)
                        })
                    }
                    if (otherFolders.isNotEmpty()) MenuDivider()
                    MenuItem("新建文件夹", icon = Icons.Outlined.CreateNewFolder, onClick = {
                        val action = target ?: LocalBatchAction.ADD
                        val ids = picked
                        batchMenu = null
                        dialog = FolderDialog.NewFolderThen { created -> organize(action, ids, created) }
                    })
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        FolderDialog.RenameFolder -> folder?.let { f ->
            LocalNameDialog(
                title = "重命名文件夹", initial = f.name, confirmLabel = "保存", saving = saving,
                onDismiss = { dialog = null },
                onConfirm = { name ->
                    if (name.trim() == f.name) dialog = null
                    else { saving = true
                    controller.renameFolder(f.id, name) { r -> saving = false; dialog = null; if (r.isFailure) fail(r) } }
                },
            )
        }
        FolderDialog.DeleteFolder -> folder?.let { f ->
            LocalConfirmDialog(
                title = "删除文件夹「${f.name}」？",
                text = "音频保留在本地库，此文件夹的手机文件夹关联一并移除。",
                confirmLabel = "删除", danger = true, saving = saving,
                onDismiss = { dialog = null },
                onConfirm = {
                    saving = true
                    controller.deleteFolder(f.id) { r ->
                        saving = false
                        dialog = null
                        if (r.isSuccess) { onNotice("已删除文件夹，音频仍在本地库"); leave() } else fail(r)
                    }
                },
            )
        }
        is FolderDialog.Unlink -> LocalConfirmDialog(
            title = "取消关联「${d.name}」？",
            text = "已导入的音频会保留在这个文件夹里，只是以后不能再从这个手机文件夹刷新。",
            confirmLabel = "取消关联", danger = true, saving = saving,
            onDismiss = { dialog = null },
            onConfirm = {
                saving = true
                controller.unlinkDirectory(d.linkId) { r ->
                    saving = false
                    dialog = null
                    if (r.isSuccess) onNotice("已取消关联，导入的音频都还在") else fail(r)
                }
            },
        )
        is FolderDialog.RenameAudio -> index.item(d.id)?.let { item ->
            LocalNameDialog(
                title = "重命名", initial = item.title, confirmLabel = "保存", saving = saving,
                onDismiss = { dialog = null },
                onConfirm = { name ->
                    // Saving the shown title unchanged would pin it as a custom name.
                    if (name.trim() == item.title) dialog = null
                    else { saving = true
                    controller.renameAudio(d.id, name) { r -> saving = false; dialog = null; if (r.isFailure) fail(r) } }
                },
                // Only a renamed recording has an original name to go back to.
                onRestore = if (item.displayName != null) ({
                    saving = true
                    controller.renameAudio(d.id, null) { r -> saving = false; dialog = null; if (r.isFailure) fail(r) }
                }) else null,
            )
        }
        is FolderDialog.DeleteAudios -> LocalConfirmDialog(
            title = if (d.ids.size == 1) "从本地库删除这一条？" else "从本地库删除 ${d.ids.size} 条？",
            text = (if (d.ids.size == 1) "会删除这条的" else "会删除这些音频的") +
                "进度、书签和自定义名称。不会删除手机里的原文件；Musxi 中的副本会被清理。",
            confirmLabel = "删除", danger = true, saving = saving,
            onDismiss = { dialog = null },
            onConfirm = { deleteAudios(d.ids) },
        )
        is FolderDialog.RemoveAudios -> LocalConfirmDialog(
            title = "从此文件夹移出 ${d.ids.size} 条？",
            text = "只是移出这个文件夹，音频、进度和书签都还在本地库里。",
            confirmLabel = "移出", danger = false, saving = saving,
            onDismiss = { dialog = null },
            onConfirm = { removeFromHere(d.ids) },
        )
        is FolderDialog.NewFolderThen -> LocalNameDialog(
            title = "新建文件夹", initial = "", confirmLabel = "创建", saving = saving,
            onDismiss = { dialog = null },
            onConfirm = { name ->
                saving = true
                controller.createFolder(name) { r ->
                    saving = false
                    dialog = null
                    r.onSuccess(d.then).onFailure { fail(r) }
                }
            },
        )
    }
}

/** Placeholder id for the 未归入文件夹 list's header; it is never sent to the backend. */
private const val UNFILED_ID = "unfiled"

/** Room the list leaves at its bottom while the selection bar shows: bar, its margin, nav bar. */
private val SelectionBarRoom = 120.dp

internal fun batchNotice(batch: LocalAudioBatchResult, folderName: String): String = when (batch.action) {
    LocalAudioBatchAction.MOVE -> "已移到「$folderName」"
    LocalAudioBatchAction.REMOVE -> "已移出 ${batch.removedMemberships} 条"
    LocalAudioBatchAction.ADD -> when {
        batch.addedMemberships == 0 -> "都已在「$folderName」里"
        batch.addedMemberships < batch.selected -> "已加入「$folderName」${batch.addedMemberships} 条，其余已在其中"
        else -> "已加入「$folderName」"
    }
}

internal fun singleCheckNotice(result: tech.musxi.core.localaudio.LocalAudioSourceCheckResult?): String = when {
    result == null -> localErrorText(null)
    !result.applied -> "检查期间来源有变化，请重新检查"
    result.error == null && result.availability == tech.musxi.core.localaudio.LocalAudioAvailability.AVAILABLE -> "来源可用"
    else -> result.availability?.let { availabilityText(it) } ?: localErrorText(result.error)
}

/** The "+" menu: three ways in, then how to keep what comes in. */
@Composable
private fun ImportMenu(
    expanded: Boolean,
    busy: Boolean,
    mode: LocalImportMode,
    onToggleMode: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (PickKind) -> Unit,
) {
    AnchoredMenu(expanded = expanded, onDismiss = onDismiss, align = MenuAlign.END, preferAbove = false) {
        val wait = if (busy) "已有导入在进行" else null
        MenuItem("选择文件", icon = Icons.Outlined.AudioFile, supporting = wait ?: "可以一次选多个",
            enabled = !busy, onClick = { onPick(PickKind.IMPORT_FILES) })
        MenuItem("关联手机文件夹", icon = Icons.Outlined.Link, supporting = wait ?: "以后可手动刷新",
            enabled = !busy, onClick = { onPick(PickKind.LINK_DIRECTORY) })
        MenuItem("一次性导入整个文件夹", icon = Icons.Outlined.DriveFolderUpload, supporting = wait,
            enabled = !busy, onClick = { onPick(PickKind.IMPORT_DIRECTORY) })
        MenuDivider()
        // Tapping switches the mode and leaves the menu open, so the choice is seen before picking.
        ImportModeRow(mode, onToggleMode)
    }
}

@Composable
private fun ImportModeRow(mode: LocalImportMode, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // Same 6dp inset and rounded press tile as MenuItem, so the row reads as part of the menu.
    Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button, onClickLabel = "切换保存方式", onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "保存方式", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    importModeLabel(mode), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.SwapHoriz, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
            Text(
                importModeHint(mode), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** The folder's "⋯": rename, the from-start switch, linked phone folders, delete. */
@Composable
private fun FolderMenu(
    expanded: Boolean,
    folder: LocalAudioFolder,
    links: List<tech.musxi.core.localaudio.LocalAudioDirectoryLink>,
    page: MenuPage,
    busy: Boolean,
    onPage: (MenuPage) -> Unit,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onToggleFromStart: () -> Unit,
    onRefresh: (tech.musxi.core.localaudio.LocalAudioDirectoryLink) -> Unit,
    onReauthorize: (tech.musxi.core.localaudio.LocalAudioDirectoryLink) -> Unit,
    onUnlink: (tech.musxi.core.localaudio.LocalAudioDirectoryLink) -> Unit,
    onDelete: () -> Unit,
) {
    AnchoredMenu(expanded = expanded, onDismiss = onDismiss, align = MenuAlign.END, preferAbove = false, maxHeight = 480.dp) {
        val link = (page as? MenuPage.Link)?.let { p -> links.firstOrNull { it.id == p.id } }
        if (link != null) {
            MenuHeader(link.name, directoryStatus(link))
            MenuItem("刷新", icon = Icons.Outlined.Sync, enabled = !busy,
                supporting = if (busy) "已有导入在进行" else "导入新增的音频", onClick = { onRefresh(link) })
            if (link.error == LocalAudioError.PERMISSION_REQUIRED) {
                MenuItem("重新授权", icon = Icons.Outlined.LockOpen, supporting = "重新选择同一个手机文件夹",
                    onClick = { onReauthorize(link) })
            }
            MenuItem("取消关联", icon = Icons.Outlined.LinkOff, onClick = { onUnlink(link) })
            MenuDivider()
            MenuItem("返回", icon = Icons.AutoMirrored.Outlined.ArrowBack, onClick = { onPage(MenuPage.Root) })
        } else {
        MenuItem("重命名文件夹", icon = Icons.Outlined.DriveFileRenameOutline, onClick = onRename)
        MenuItem(
            "每次从头播放",
            icon = Icons.Outlined.Replay,
            supporting = "点一条时不接着上次的位置",
            onClick = onToggleFromStart,
            trailing = { Switch(checked = !folder.continuePlayback, onCheckedChange = null, modifier = Modifier.scale(0.7f)) },
        )
        if (links.isNotEmpty()) {
            MenuDivider()
            MenuHeader("已关联的手机文件夹")
            links.forEach { l ->
                MenuItem(l.name, icon = Icons.Outlined.Link, supporting = directoryStatus(l),
                    trailing = { MenuChevron() }, onClick = { onPage(MenuPage.Link(l.id)) })
            }
        }
        MenuDivider()
        MenuItem("删除文件夹", icon = Icons.Outlined.DeleteOutline, iconTint = MaterialTheme.colorScheme.error, onClick = onDelete)
        }
    }
}

/** A row's "⋯": play it from the start, rename, file it, fix its source, or delete it. */
@Composable
private fun RowMenu(
    expanded: Boolean,
    row: LocalAudioRowUi,
    inFolder: Boolean,
    page: MenuPage,
    folders: List<LocalAudioFolder>,
    isMember: (String) -> Boolean,
    onPage: (MenuPage) -> Unit,
    onDismiss: () -> Unit,
    onFromStart: () -> Unit,
    onRename: () -> Unit,
    onAddTo: (LocalAudioFolder) -> Unit,
    onNewFolder: () -> Unit,
    onRemove: () -> Unit,
    onRelink: () -> Unit,
    onCheck: () -> Unit,
    onDelete: () -> Unit,
    onPickArtwork: (() -> Unit)? = null,
    onClearArtwork: () -> Unit = {},
) {
    AnchoredMenu(expanded = expanded, onDismiss = onDismiss, align = MenuAlign.END, preferAbove = false, maxHeight = 420.dp) {
        if (page == MenuPage.Folders) {
            MenuHeader("加入文件夹", row.title)
            folders.forEach { f ->
                val already = isMember(f.id)
                MenuItem(f.name, icon = Icons.Outlined.Folder, selected = already, enabled = !already,
                    onClick = { onAddTo(f) })
            }
            if (folders.isNotEmpty()) MenuDivider()
            MenuItem("新建文件夹", icon = Icons.Outlined.CreateNewFolder, onClick = onNewFolder)
            MenuItem("返回", icon = Icons.AutoMirrored.Outlined.ArrowBack, onClick = { onPage(MenuPage.Root) })
        } else {
        MenuItem("从头播放这一条", icon = Icons.Outlined.Replay, onClick = onFromStart)
        MenuItem("重命名", icon = Icons.Outlined.DriveFileRenameOutline, onClick = onRename)
        MenuItem(if (inFolder) "加入其他文件夹" else "加入文件夹", icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
            trailing = { MenuChevron() }, onClick = { onPage(MenuPage.Folders) })
        if (inFolder) MenuItem("移出此文件夹", icon = Icons.Outlined.PlaylistRemove, onClick = onRemove)
        // A picked cover can be changed again or dropped; otherwise only changed.
        if (onPickArtwork != null) MenuItem("更换封面", icon = Icons.Outlined.Image, onClick = onPickArtwork)
        if (onPickArtwork != null && row.artworkCustom) MenuItem("恢复默认封面", icon = Icons.Outlined.HideImage, onClick = onClearArtwork)
        MenuDivider()
        // Only a 使用原文件 recording has a source to point elsewhere; a copy is its own source.
        if (row.canRelink) {
            MenuItem("重新关联", icon = Icons.Outlined.FindReplace,
                supporting = if (row.unavailable) "原文件找不到时，选它现在的位置" else "改用另一个文件", onClick = onRelink)
        }
        MenuItem("检查来源", icon = Icons.AutoMirrored.Outlined.FactCheck, onClick = onCheck)
        MenuItem("从本地库删除", icon = Icons.Outlined.DeleteOutline, iconTint = MaterialTheme.colorScheme.error, onClick = onDelete)
        }
    }
}
