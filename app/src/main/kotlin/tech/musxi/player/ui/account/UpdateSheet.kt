package tech.musxi.player.ui.account

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import tech.musxi.player.ui.components.MusxiSheet
import tech.musxi.player.ui.components.ProgressArc
import tech.musxi.player.ui.components.RowDivider
import tech.musxi.player.ui.components.SheetDetent
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.tabular

/** Test handle of the sheet's one ink button, whichever step it belongs to. */
internal const val UpdatePrimaryTag = "update-primary"

/**
 * A newer release in a sheet: its title, date and size, the notes, and the download steps fixed at
 * the bottom. The "新版本" row of the "关于" card opens it and only reports how far the steps are, so
 * every step is acted on here and nowhere else.
 *
 * Place it once in a full-window overlay and keep it in the tree (see [MusxiSheet]): [visible]
 * shows it, and [onDismiss] must set [visible] to false. When [release] turns null while shown (the
 * release went away, or a check found nothing newer), it slides out with what it last showed and
 * reports [onDismiss], so the host's flag cannot reopen it later by itself.
 *
 * It has one open height, the full one: at the medium height the bottom of a [MusxiSheet] lies below
 * the screen, and long notes push the steps there. A drag that would settle there closes it instead.
 * The steps follow the notes rather than sitting on the sheet's floor, so a short release reads
 * top to bottom with its button right under it instead of across an empty page.
 */
@Composable
fun UpdateSheet(
    visible: Boolean,
    release: ReleaseUi?,
    actions: UpdateActions,
    onDismiss: () -> Unit,
) {
    // Kept so the sheet slides out showing what it showed, instead of going blank first.
    val last = remember { arrayOfNulls<ReleaseUi>(1) }
    if (release != null) last[0] = release
    val shown = release ?: last[0]
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(visible, release == null) {
        if (visible && release == null) dismiss()
    }
    MusxiSheet(
        visible = visible && release != null,
        onDismiss = onDismiss,
        initialDetent = SheetDetent.FULL,
        onDetentChange = { if (it == SheetDetent.MEDIUM) onDismiss() },
    ) {
        if (shown != null) SheetBody(shown, actions)
    }
}

@Composable
private fun ColumnScope.SheetBody(release: ReleaseUi, actions: UpdateActions) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.page).padding(top = 4.dp, bottom = 12.dp)) {
        Text(release.title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
        if (release.meta.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(release.meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
    val notesScroll = rememberScrollState()
    // Takes only the height the notes need, up to what is left, and scrolls beyond that; a drag at
    // its top hands over to the sheet (nested scroll). The steps sit right under it.
    Column(
        Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(notesScroll)
            .padding(horizontal = Space.page).padding(bottom = 16.dp),
    ) {
        if (release.notes.isEmpty()) {
            Text("这个版本没有附更新说明", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        } else {
            ReleaseNotes(release.notes)
        }
    }
    StepArea(release, actions, divided = notesScroll.canScrollForward)
}

/** Plain-text notes; headings in ink, items with a bullet hanging in the margin of their wrap. */
@Composable
private fun ReleaseNotes(lines: List<NoteLine>) {
    val cs = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyMedium
    Column(Modifier.fillMaxWidth()) {
        lines.forEachIndexed { index, line ->
            when (line.kind) {
                NoteKind.GAP -> Spacer(Modifier.height(8.dp))
                NoteKind.HEADING -> Text(
                    line.text, style = style, color = cs.onSurface, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(
                        top = if (index > 0 && lines[index - 1].kind != NoteKind.GAP) 8.dp else 0.dp,
                        bottom = 2.dp,
                    ),
                )
                NoteKind.ITEM -> Row {
                    Text("•", style = style, color = cs.onSurfaceVariant, modifier = Modifier.width(16.dp))
                    Text(line.text, style = style, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
                NoteKind.TEXT -> Text(line.text, style = style, color = cs.onSurfaceVariant)
            }
        }
    }
}

/**
 * The fixed bottom of the sheet: the step the download is in. Each state replaces the one before it.
 * The hairline above it shows only while notes continue under it, so short notes do not get a rule
 * floating in the empty sheet.
 */
@Composable
private fun StepArea(release: ReleaseUi, actions: UpdateActions, divided: Boolean) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxWidth()) {
        RowDivider(Modifier.alpha(if (divided) 1f else 0f))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.page)
                .padding(top = 16.dp, bottom = navBottom + 8.dp),
        ) {
            when (val download = release.download) {
                is DownloadUi.Idle -> IdleStep(release, download, actions)
                is DownloadUi.Downloading -> DownloadingStep(download, actions.onCancelDownload)
                DownloadUi.Verifying -> BusyStep("正在校验…")
                DownloadUi.Ready -> ReadyStep(actions.onInstall, actions.onCancelDownload)
                DownloadUi.NeedsPermission -> PermissionStep(actions)
                DownloadUi.Installing -> BusyStep("正在打开系统安装…")
            }
        }
    }
}

@Composable
private fun IdleStep(release: ReleaseUi, idle: DownloadUi.Idle, actions: UpdateActions) {
    val cs = MaterialTheme.colorScheme
    // Asked after each tap, never on its own; dropped when the connection stops being metered.
    var confirming by remember(release.version, release.metered) { mutableStateOf(false) }
    if (confirming && release.metered && idle.canDownload) {
        // The question replaces the whole step, so "继续下载" is the only ink on screen.
        StepNote(release.confirmText, cs.onSurface)
        InkButton("继续下载") {
            confirming = false
            actions.onDownload(release.version)
        }
        QuietRow { QuietAction("取消") { confirming = false } }
    } else {
        idle.failure?.let { StepNote(it, cs.error) }
        idle.unavailableNote?.let { StepNote(it) }
        if (idle.canDownload) {
            InkButton(if (idle.failure != null) "重试" else "下载并安装") {
                if (release.metered) confirming = true else actions.onDownload(release.version)
            }
        }
        QuietRow {
            if (release.ignored) QuietAction("取消忽略") { actions.onClearIgnore() }
            else QuietAction("忽略此版本") { actions.onIgnore(release.version) }
            release.releasePage?.let { page -> QuietAction("在 GitHub 查看") { actions.onOpenRelease(page) } }
        }
    }
}

/** Thin bar, "已下载 x / y MB" with the percentage, and cancel. The bar eases between the backend's 256 KB steps. */
@Composable
private fun DownloadingStep(download: DownloadUi.Downloading, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val fraction by animateFloatAsState(download.fraction.coerceIn(0f, 1f), Motion.progress(), label = "updateProgress")
    Box(
        Modifier.fillMaxWidth().height(3.dp).clip(CircleShape)
            .background(cs.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(cs.primary))
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            download.text, style = MaterialTheme.typography.bodySmall.tabular, color = cs.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        download.percent?.let {
            Text("$it%", style = MaterialTheme.typography.bodySmall.tabular, color = cs.onSurfaceVariant)
        }
    }
    QuietRow { QuietAction("取消下载", onClick = onCancel) }
}

/** One quiet centred line with the static arc, for the steps the user only waits through. */
@Composable
private fun BusyStep(text: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProgressArc(size = 14.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReadyStep(onInstall: () -> Unit, onDiscard: () -> Unit) {
    StepNote("安装时应用会关闭，播放会停止")
    InkButton("安装", onClick = onInstall)
    // A way out for someone who downloaded and changed their mind: the contract lets cancel drop a
    // finished file, so it does not sit in storage until the next start.
    QuietRow { QuietAction("删除安装包", onClick = onDiscard) }
}

/**
 * Says why the permission is needed before sending the user to settings. When they come back the
 * install is requested again, which re-checks the permission (docs/android-app-updates.md).
 */
@Composable
private fun PermissionStep(actions: UpdateActions) {
    var openedSettings by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (openedSettings) {
            openedSettings = false
            actions.onInstall()
        }
    }
    StepNote("系统要求先开启“允许安装未知应用”，才能安装更新。")
    InkButton("去设置") {
        openedSettings = true
        actions.onOpenInstallSettings()
    }
}

/** A sentence above the step's button, centred like the button and the quiet actions under it. */
@Composable
private fun StepNote(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = color, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    )
}

/** The one action of a step: an ink capsule across the sheet, the colours of the playlist sheet's save. */
@Composable
private fun InkButton(text: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(UpdatePrimaryTag),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = cs.onSurface, contentColor = cs.surface),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun QuietRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun QuietAction(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}
