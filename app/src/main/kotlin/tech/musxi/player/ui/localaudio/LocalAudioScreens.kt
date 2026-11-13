@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package tech.musxi.player.ui.localaudio

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.PlaylistRemove
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.components.PlainRow
import tech.musxi.player.ui.components.PlayingBars
import tech.musxi.player.ui.components.ProgressArc
import tech.musxi.player.ui.components.RowCheckbox
import tech.musxi.player.ui.components.RowDivider
import tech.musxi.player.ui.components.RowSpec
import tech.musxi.player.ui.components.RowText
import tech.musxi.player.ui.components.SectionCard
import tech.musxi.player.ui.components.SectionTitle
import tech.musxi.player.ui.components.CardRow
import tech.musxi.player.ui.components.listLines
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.nowPlayingAccent
import tech.musxi.player.ui.theme.tabular

/*
 * The 本地音频 pages. Stateless: they take what LocalAudioUi.kt derives and report taps. The library
 * tab hosts the home list under its segmented control; a folder is the tab's second level, like a
 * playlist, so the dock stays visible when a recording starts.
 */

/** What the 本地音频 home shows above and around its folders. */
data class LocalHomeUi(
    val loading: Boolean = false,
    /** The library could not be opened; its data is kept, nothing can be done until it can. */
    val failed: Boolean = false,
    val folders: List<LocalFolderUi> = emptyList(),
    /** Recordings in no folder; a row of its own when there are any. */
    val unfiled: Int = 0,
    /** "继续上次本地播放": the last local list can be put back, paused. */
    val resume: LocalResumeUi? = null,
    /** A running import or refresh, shown as a card that can be cancelled from here. */
    val importing: String? = null,
)

data class LocalResumeUi(val title: String, val detail: String, val restoring: Boolean)

/**
 * The home list: a way back into the last local list, a running import, then the folders. The
 * folders are a long list, so they lie on the page as rows; the two modules above them sit in cards.
 */
@Composable
fun LocalAudioHome(
    ui: LocalHomeUi,
    onOpenFolder: (String) -> Unit,
    onOpenUnfiled: () -> Unit,
    onNewFolder: () -> Unit,
    onResume: () -> Unit,
    onCancelImport: () -> Unit,
    bottomInset: Dp = 0.dp,
    nowPlayingFolderId: String? = null,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    /** The library page's title and segments, first in this page's list so they scroll away with it. */
    header: @Composable () -> Unit = {},
) {
    val pageInset = Modifier.padding(horizontal = Space.page)
    val note = @Composable { content: @Composable () -> Unit ->
        Column(Modifier.fillMaxSize()) {
            header()
            Box(Modifier.fillMaxWidth().weight(1f)) { content() }
        }
    }
    when {
        ui.loading -> note { CenterNote("正在打开本地音频…", bottomInset) }
        ui.failed -> note { CenterNote("本地音频库暂时打不开，原有数据都还在", bottomInset) }
        ui.folders.isEmpty() && ui.unfiled == 0 && ui.importing == null ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                header()
                LocalEmpty(onNewFolder, bottomInset, Modifier)
            }
        else -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = bottomInset)) {
            item(key = "header") { header() }
            item(key = "modules") {
                Column(pageInset) {
                    ui.importing?.let { line ->
                        SectionTitle("正在导入")
                        SectionCard { ImportingRow(line, onCancelImport) }
                        Spacer(Modifier.height(Space.section))
                    }
                    ui.resume?.let { resume ->
                        SectionTitle("继续播放")
                        SectionCard { LocalResumeRow(resume, onResume) }
                        Spacer(Modifier.height(Space.section))
                    }
                    SectionTitle("文件夹", trailing = {
                        TextButton(onClick = onNewFolder, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Icon(Icons.Outlined.CreateNewFolder, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("新建", style = MaterialTheme.typography.labelLarge)
                        }
                    })
                }
            }
            itemsIndexed(ui.folders, key = { _, f -> f.id }) { index, folder ->
                Column(pageInset) {
                    FolderRow(folder, playing = folder.id == nowPlayingFolderId) { onOpenFolder(folder.id) }
                    if (index < ui.folders.lastIndex || ui.unfiled > 0) RowDivider(start = RowSpec.textStart(RowSpec.cover))
                }
            }
            if (ui.unfiled > 0) {
                item(key = "unfiled") {
                    Column(pageInset) { UnfiledRow(ui.unfiled, onOpenUnfiled) }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(folder: LocalFolderUi, playing: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    PlainRow(onClick = onClick) {
        Cover(seed = folder.id, modifier = Modifier.size(RowSpec.cover), shape = MaterialTheme.shapes.small)
        Spacer(Modifier.width(RowSpec.leadGap))
        RowText(
            folder.name,
            subtitle = folder.subtitle,
            titleColor = if (playing) nowPlayingAccent() else cs.onSurface,
            subtitleStyle = MaterialTheme.typography.bodySmall.tabular,
        )
        if (folder.unavailable > 0) {
            Spacer(Modifier.width(RowSpec.trailGap))
            Text("${folder.unavailable} 条不可用", style = MaterialTheme.typography.labelSmall, color = cs.error)
        }
    }
}

@Composable
private fun UnfiledRow(count: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    PlainRow(onClick = onClick) {
        Box(
            Modifier.size(RowSpec.cover).clip(MaterialTheme.shapes.small).background(cs.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.FolderOpen, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(RowSpec.leadGap))
        RowText("未归入文件夹", subtitle = "共 $count 条", subtitleStyle = MaterialTheme.typography.bodySmall.tabular)
    }
}

@Composable
private fun LocalResumeRow(ui: LocalResumeUi, onResume: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardRow(onClick = onResume, enabled = !ui.restoring) {
        RowText(ui.title, subtitle = ui.detail, titleStyle = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(RowSpec.trailGap))
        Box(
            Modifier.size(RowSpec.action).clip(CircleShape).background(cs.onSurface.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center,
        ) {
            if (ui.restoring) {
                ProgressArc(color = cs.onSurface, size = RowSpec.actionGlyph, strokeWidth = RowSpec.actionStroke)
            } else {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(RowSpec.actionGlyph), tint = cs.onSurface)
            }
        }
    }
}

@Composable
private fun ImportingRow(line: String, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardRow(onClick = null) {
        ProgressArc(color = cs.onSurfaceVariant, size = 16.dp, strokeWidth = 2.dp)
        Spacer(Modifier.width(RowSpec.leadGap))
        RowText(line, titleStyle = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onCancel) { Text("取消", style = MaterialTheme.typography.labelLarge) }
    }
}

/** First visit: what this is for, and the one step that starts it. */
@Composable
private fun LocalEmpty(onNewFolder: () -> Unit, bottomInset: Dp, modifier: Modifier = Modifier.fillMaxSize()) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.page).padding(top = 56.dp, bottom = bottomInset),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("还没有本地音频", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(
            // Three short lines that each fit a phone's width: a long line wrapped its last
            // character ("式。") onto a row of its own, which read as a mistake.
            "建一个文件夹，把手机里的音频或视频放进来\n视频只播放声音，封面会自动截取\n导入后会记住每条听到哪里",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        InkButton("新建文件夹", Icons.Outlined.CreateNewFolder, onClick = onNewFolder)
    }
}

@Composable
private fun CenterNote(text: String, bottomInset: Dp) {
    Box(Modifier.fillMaxSize().padding(bottom = bottomInset), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Turns a row's artwork key into the image file Coil loads. The route provides the library's real
 * resolver; design sheets and tests leave it unset, so rows draw their placeholder.
 */
val LocalArtworkFile = androidx.compose.runtime.staticCompositionLocalOf<(String) -> java.io.File?> { { null } }

/** The folder page's own state, beyond its rows. */
data class LocalFolderPageUi(
    val folder: LocalFolderUi,
    val rows: List<LocalAudioRowUi>,
    /** "第 3 条 · 23:18" under 继续播放. */
    val resumeLine: String,
    /** Ids the user has picked; empty when not selecting. */
    val selected: Set<String> = emptySet(),
    val selecting: Boolean = false,
    /** The recording playing now, if it is in this list. */
    val playingId: String? = null,
    /** A running import or refresh, so the page the user imported from shows it is under way. */
    val importing: String? = null,
)

/**
 * One folder: its name and what 继续播放 will do, then its recordings. A long press starts picking
 * several; the bar that replaces the dock's job then offers what can be done to the picked ones.
 */
@Composable
fun LocalFolderPage(
    ui: LocalFolderPageUi,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onFromStart: () -> Unit,
    onAdd: () -> Unit,
    onPlay: (String) -> Unit,
    onMore: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onStartSelect: (String) -> Unit,
    onEndSelect: () -> Unit,
    onSelectAll: () -> Unit,
    bottomInset: Dp = 0.dp,
    /**
     * The 未归入文件夹 list: no folder to continue, add to or edit, so its play buttons, "+" and
     * folder menu are left out and a tap plays one recording.
     */
    unfiled: Boolean = false,
    /** Opens the folder menu (the second top-right button). Null leaves the button out. */
    onFolderMore: (() -> Unit)? = null,
    /**
     * Floating menus, each composed in the same Box as the button that opens it so the menu
     * anchors to that button: the import menu under "+", the folder menu under "⋯", and each row's
     * own menu under its "⋯" (called with the row id).
     */
    addMenu: @Composable () -> Unit = {},
    folderMenu: @Composable () -> Unit = {},
    rowMenu: @Composable (String) -> Unit = {},
    onCancelImport: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(horizontal = Space.page)) {
        if (ui.selecting) {
            SelectionTopBar(ui.selected.size, ui.rows.size, onEndSelect, onSelectAll)
        } else {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.weight(1f))
                if (!unfiled) {
                    Box {
                        IconButton(onClick = onAdd) {
                            Icon(Icons.Outlined.Add, "添加音频", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        addMenu()
                    }
                }
                if (onFolderMore != null) {
                    Box {
                        IconButton(onClick = onFolderMore) {
                            Icon(Icons.Filled.MoreHoriz, "文件夹操作", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        folderMenu()
                    }
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset)) {
            item(key = "header") { FolderHeader(ui, onContinue, onFromStart, onAdd, unfiled, onCancelImport) }
            itemsIndexed(ui.rows, key = { _, row -> row.id }) { index, row ->
                AudioRow(
                    row = row,
                    index = index,
                    playing = row.id == ui.playingId,
                    selecting = ui.selecting,
                    selected = row.id in ui.selected,
                    onClick = { if (ui.selecting) onToggleSelect(row.id) else onPlay(row.id) },
                    onLongClick = { if (!ui.selecting) onStartSelect(row.id) else onToggleSelect(row.id) },
                    onMore = { onMore(row.id) },
                    menu = { rowMenu(row.id) },
                )
                if (index < ui.rows.lastIndex) RowDivider(start = 36.dp)
            }
        }
    }
}

@Composable
private fun FolderHeader(
    ui: LocalFolderPageUi,
    onContinue: () -> Unit,
    onFromStart: () -> Unit,
    onAdd: () -> Unit,
    unfiled: Boolean = false,
    onCancelImport: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)) {
        Text(
            ui.folder.name, style = MaterialTheme.typography.headlineSmall, color = cs.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            listOfNotNull(
                "${ui.folder.count} 条",
                if (ui.folder.linkedDirectories > 0) "关联了 ${ui.folder.linkedDirectories} 个手机文件夹" else null,
                if (ui.folder.continuePlayback) null else "每次从头播放",
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium.tabular,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.section))
        if (unfiled) {
            // Loose recordings have no order to continue in; each row plays on its own.
            Text(
                if (ui.rows.isEmpty()) "每条音频都已归入文件夹" else "点一条单独播放；长按可以把它们放进文件夹",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        } else if (ui.rows.isEmpty()) {
            InkButton("添加音频", Icons.Outlined.Add, onClick = onAdd)
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Space.sibling),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                InkButton("继续播放", Icons.Filled.PlayArrow, onClick = onContinue, enabled = !ui.selecting)
                QuietButton("从头播放", Icons.Outlined.Replay, onClick = onFromStart, enabled = !ui.selecting)
            }
            Spacer(Modifier.height(8.dp))
            Text(ui.resumeLine, style = MaterialTheme.typography.bodySmall.tabular, color = cs.onSurfaceVariant)
        }
        ui.importing?.let { line ->
            Spacer(Modifier.height(Space.sibling))
            SectionCard { ImportingRow(line, onCancelImport) }
        }
    }
}

/**
 * One recording: its place in the folder, title, who and how far, and the length. A recording in
 * progress gets a hairline bar under its text: the one thing a listener of long audio scans for.
 */
@Composable
private fun AudioRow(
    row: LocalAudioRowUi,
    index: Int,
    playing: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
    menu: @Composable () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val accent = nowPlayingAccent()
    val lines = listLines()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = if (selecting) Role.Checkbox else Role.Button)
            .semantics { if (selecting) this.selected = selected }
            .heightIn(min = RowSpec.minHeight)
            .padding(vertical = RowSpec.vertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
            when {
                selecting -> RowCheckbox(selected)
                playing -> PlayingBars(accent)
                else -> Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelMedium.tabular,
                    color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
        // The recording's own picture when it has one (its embedded art, or a frame of a video);
        // nothing at all otherwise, so a list of plain recordings does not fill up with placeholders.
        val artwork = row.artworkKey?.let { LocalArtworkFile.current(it) }
        if (artwork != null) {
            coil3.compose.AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(RowSpec.coverCompact).clip(MaterialTheme.shapes.small),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.video) {
                    // A quiet tag, not a colour: the row is still just something to listen to.
                    Text(
                        "视频",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(cs.surfaceContainerHighest)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = when {
                        row.unavailable -> cs.onSurfaceVariant
                        playing -> accent
                        else -> cs.onSurface
                    },
                    maxLines = lines, overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.subtitle.isNotEmpty()) {
                Text(
                    row.subtitle,
                    style = MaterialTheme.typography.bodySmall.tabular,
                    color = if (row.unavailable) cs.error else cs.onSurfaceVariant,
                    maxLines = lines, overflow = TextOverflow.Ellipsis,
                )
            }
            val fraction = (row.listen as? ListenState.InProgress)?.fraction
            if (fraction != null && !row.unavailable) {
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth(0.62f).height(2.dp).clip(CircleShape).background(cs.onSurface.copy(alpha = 0.10f))) {
                    Box(Modifier.fillMaxWidth(fraction).height(2.dp).clip(CircleShape).background(cs.onSurface.copy(alpha = 0.55f)))
                }
            }
        }
        if (!selecting) {
            Box {
                IconButton(onClick = onMore, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.MoreVert, contentDescription = "更多操作",
                        tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp),
                    )
                }
                menu()
            }
        }
    }
}

@Composable
private fun SelectionTopBar(count: Int, total: Int, onDone: () -> Unit, onSelectAll: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onDone) { Text("完成", style = MaterialTheme.typography.labelLarge) }
        Text(
            if (count == 0) "选择音频" else "已选 $count 条",
            style = MaterialTheme.typography.titleSmall.tabular,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        TextButton(onClick = onSelectAll) {
            Text(if (count == total && total > 0) "全不选" else "全选", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** What can be done to the picked recordings. [inFolder] is false on the 未归入文件夹 list. */
enum class LocalBatchAction(val label: String, val icon: ImageVector) {
    ADD("加入", Icons.AutoMirrored.Outlined.PlaylistAdd),
    MOVE("移到", Icons.AutoMirrored.Outlined.DriveFileMove),
    REMOVE("移出", Icons.Outlined.PlaylistRemove),
    CHECK("检查", Icons.AutoMirrored.Outlined.FactCheck),
    DELETE("删除", Icons.Outlined.DeleteOutline),
}

/**
 * The bar shown while picking: five plain actions, the destructive one last and red. It sits where
 * the dock sits, so the caller hides the dock while it shows.
 */
@Composable
fun LocalSelectionBar(
    count: Int,
    inFolder: Boolean,
    onAction: (LocalBatchAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val actions = LocalBatchAction.entries.filter { inFolder || (it != LocalBatchAction.MOVE && it != LocalBatchAction.REMOVE) }
    SectionCard(modifier, padded = false) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            actions.forEach { action ->
                val danger = action == LocalBatchAction.DELETE
                val enabled = count > 0
                Column(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(enabled = enabled, role = Role.Button, onClick = { onAction(action) })
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val tint = when {
                        !enabled -> cs.onSurfaceVariant.copy(alpha = 0.38f)
                        danger -> cs.error
                        else -> cs.onSurface
                    }
                    Icon(action.icon, null, tint = tint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(action.label, style = MaterialTheme.typography.labelSmall, color = tint)
                }
            }
        }
    }
}

/** Ink capsule, the page's one strong action. Same shape as the library's 播放全部. */
@Composable
internal fun InkButton(label: String, icon: ImageVector?, onClick: () -> Unit, enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = cs.onSurface, contentColor = cs.surface),
        contentPadding = PaddingValues(horizontal = 18.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** The quieter neighbour of [InkButton]. */
@Composable
internal fun QuietButton(label: String, icon: ImageVector?, onClick: () -> Unit, enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = cs.surfaceContainerHigh, contentColor = cs.onSurface),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
