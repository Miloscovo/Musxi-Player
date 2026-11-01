package tech.musxi.player.ui.account

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import tech.musxi.core.update.UpdateCheckResult
import tech.musxi.player.ui.components.ALL_LINES
import tech.musxi.player.ui.components.CardRow
import tech.musxi.player.ui.components.ProgressArc
import tech.musxi.player.ui.components.RowDivider
import tech.musxi.player.ui.components.RowSpec
import tech.musxi.player.ui.components.RowText
import tech.musxi.player.ui.components.SwitchRow
import tech.musxi.player.ui.components.pressableRow

/** Update state shown by [AboutSection]. Produced from [UpdateCheckResult] by [updateStatusOf]. */
sealed interface UpdateStatusUi {
    data object Idle : UpdateStatusUi

    data object Checking : UpdateStatusUi

    data object UpToDate : UpdateStatusUi

    data class Available(
        val version: String,
        val notes: String,
        val releasePage: String,
    ) : UpdateStatusUi

    data class RateLimited(val retryAtEpochMillis: Long) : UpdateStatusUi

    data object Failed : UpdateStatusUi
}

fun updateStatusOf(result: UpdateCheckResult): UpdateStatusUi = when (result) {
    UpdateCheckResult.NoUpdate -> UpdateStatusUi.UpToDate
    is UpdateCheckResult.UpdateAvailable -> UpdateStatusUi.Available(
        version = result.version,
        notes = result.notes,
        releasePage = result.releasePage,
    )
    is UpdateCheckResult.RateLimited -> UpdateStatusUi.RateLimited(result.retryAtEpochMillis)
    UpdateCheckResult.Failed -> UpdateStatusUi.Failed
}

private const val RELEASE_PAGE_PREFIX = "https://github.com/qwt1011/Musxi-Player-Android/"
private val ReleasePagePath = Regex("""[A-Za-z0-9._/-]+""")

/**
 * Only release pages of this repository may be opened from the app. The rest of the URL is limited
 * to plain path characters, so "@", query, fragment, whitespace or ".." cannot point elsewhere.
 */
fun isTrustedReleasePage(url: String): Boolean {
    if (!url.startsWith(RELEASE_PAGE_PREFIX)) return false
    val rest = url.substring(RELEASE_PAGE_PREFIX.length)
    return ReleasePagePath.matches(rest) && !rest.contains("..")
}

/**
 * Earlier "About" group, driven by [UpdateStatusUi]: manual check only, no download. The account
 * page now uses the [AboutUpdateUi] overload below; this one stays for the review sheets in
 * BatchSheets.kt, which still build it, and can go when they do.
 */
@Composable
fun AboutSection(
    version: String,
    status: UpdateStatusUi,
    onCheck: () -> Unit,
    onOpenRelease: (String) -> Unit,
    onIgnore: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val checking = status is UpdateStatusUi.Checking
    Column {
        Text("关于", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("当前版本", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Spacer(Modifier.weight(1f))
            Text(version, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.45f))
        Row(
            Modifier.fillMaxWidth().height(64.dp)
                .pressableRow(onClick = onCheck, enabled = !checking),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("检查更新", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Spacer(Modifier.weight(1f))
            when (status) {
                UpdateStatusUi.Checking -> ProgressArc(size = 14.dp)
                UpdateStatusUi.UpToDate -> StatusText("已是最新")
                UpdateStatusUi.Failed -> StatusText("检查失败，点按重试")
                is UpdateStatusUi.RateLimited -> StatusText("请求太频繁，稍后再试")
                is UpdateStatusUi.Available, UpdateStatusUi.Idle -> Unit
            }
        }
        if (status is UpdateStatusUi.Available) {
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.45f))
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(
                    "发现新版本 ${displayVersion(status.version)}",
                    style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
                )
                if (status.notes.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        status.notes.trim(),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isTrustedReleasePage(status.releasePage)) {
                        TextButton(onClick = { onOpenRelease(status.releasePage) }) {
                            Text("查看", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    TextButton(onClick = { onIgnore(status.version) }) {
                        Text("忽略此版本", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusText(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = color,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/**
 * "关于" group driven by [AboutUpdateUi]: version, manual check, the newer release with its
 * download steps, and the daily auto-check switch. Rows keep the 64dp height, type sizes and
 * hairlines of the account rows above it.
 *
 * Expanded notes and the metered-data question are local to this group. The two `initially*`
 * parameters only seed them, so a review sheet can show those states without a tap.
 */
@Composable
fun AboutSection(
    version: String,
    ui: AboutUpdateUi,
    actions: UpdateActions,
    initiallyExpanded: Boolean = false,
    initiallyConfirming: Boolean = false,
    /** False when the caller draws the group heading itself (the "我的" page does, above its card). */
    showHeading: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    Column {
        if (showHeading) {
            Text("关于", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
        }
        CardRow(onClick = null) {
            RowText("当前版本", subtitle = if (ui.developmentBuild) "开发版本" else null)
            Spacer(Modifier.width(RowSpec.trailGap))
            Text(version, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        RowDivider()
        CardRow(onClick = actions.onCheck, enabled = !ui.checking) {
            RowText("检查更新", titleLines = ALL_LINES)
            Spacer(Modifier.width(RowSpec.trailGap))
            if (ui.checking) ProgressArc(size = 14.dp)
            else ui.checkNote?.let { StatusText(it) }
        }
        ui.release?.let { release ->
            RowDivider()
            ReleaseBlock(release, actions, initiallyExpanded, initiallyConfirming)
        }
        ui.autoCheck?.let { enabled ->
            RowDivider()
            SwitchRow(
                title = "自动检查更新",
                checked = enabled,
                onCheckedChange = actions.onSetAutoCheck,
                subtitle = "每天一次",
            )
        }
    }
}

/** A newer release: title, meta line, notes (three lines, then expandable), then the download step. */
@Composable
private fun ReleaseBlock(
    release: ReleaseUi,
    actions: UpdateActions,
    initiallyExpanded: Boolean,
    initiallyConfirming: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember(release.version) { mutableStateOf(initiallyExpanded) }
    var overflowing by remember(release.version) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp)) {
        Text(release.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
        if (release.meta.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(release.meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        if (release.notes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            if (expanded) {
                NotesFull(release.notes)
            } else {
                Text(
                    remember(release.notes) { notePreview(release.notes) },
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { overflowing = it.hasVisualOverflow },
                )
            }
            // "收起" must stay reachable once expanded, even though the full text no longer overflows.
            if (expanded || overflowing) {
                ToggleText(if (expanded) "收起" else "展开") { expanded = !expanded }
            }
        }
        Spacer(Modifier.height(4.dp))
        ReleaseActions(release, actions, initiallyConfirming)
    }
}

/** Full plain-text notes, shown in place on the page. */
@Composable
private fun NotesFull(lines: List<NoteLine>) {
    val cs = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodySmall
    Column(Modifier.fillMaxWidth()) {
        lines.forEachIndexed { index, line ->
            when (line.kind) {
                NoteKind.GAP -> Spacer(Modifier.height(6.dp))
                NoteKind.HEADING -> Text(
                    line.text, style = style, color = cs.onSurface, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(
                        top = if (index > 0 && lines[index - 1].kind != NoteKind.GAP) 6.dp else 0.dp,
                    ),
                )
                NoteKind.ITEM -> Row {
                    Text("•", style = style, color = cs.onSurfaceVariant, modifier = Modifier.width(14.dp))
                    Text(line.text, style = style, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
                NoteKind.TEXT -> Text(line.text, style = style, color = cs.onSurfaceVariant)
            }
        }
    }
}

/** Text-only toggle with the app's rounded press tile; its text starts on the page margin. */
@Composable
private fun ToggleText(text: String, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).pressableRow(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** The step the download is in; each state replaces the row of the one before it. */
@Composable
private fun ReleaseActions(release: ReleaseUi, actions: UpdateActions, initiallyConfirming: Boolean) {
    when (val download = release.download) {
        is DownloadUi.Idle -> IdleActions(release, download, actions, initiallyConfirming)
        is DownloadUi.Downloading -> DownloadingRow(download, actions.onCancelDownload)
        DownloadUi.Verifying -> BusyRow("正在校验…")
        DownloadUi.Ready -> ReadyRow(actions.onInstall)
        DownloadUi.NeedsPermission -> PermissionRow(actions)
        DownloadUi.Installing -> BusyRow("正在打开系统安装…")
    }
}

@Composable
private fun IdleActions(
    release: ReleaseUi,
    idle: DownloadUi.Idle,
    actions: UpdateActions,
    initiallyConfirming: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    // The question goes away when the connection stops being metered, and comes back unasked-for never.
    var confirming by remember(release.version, release.metered) { mutableStateOf(initiallyConfirming) }
    val asking = confirming && release.metered && idle.canDownload
    Column {
        idle.failure?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error)
            Spacer(Modifier.height(4.dp))
        }
        idle.unavailableNote?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        // Without the tonal button the row starts with a text button, whose own padding is pulled
        // back so its label still lines up with the text above.
        Row(
            Modifier.then(if (idle.canDownload) Modifier else Modifier.offset(x = (-12).dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (idle.canDownload) {
                TonalAction(if (idle.failure != null) "重试" else "下载并安装") {
                    if (release.metered) confirming = true else actions.onDownload(release.version)
                }
                Spacer(Modifier.width(4.dp))
            }
            if (release.ignored) TextAction("取消忽略") { actions.onClearIgnore() }
            else TextAction("忽略此版本") { actions.onIgnore(release.version) }
            release.releasePage?.let { page -> TextAction("查看") { actions.onOpenRelease(page) } }
        }
        if (asking) {
            Text(release.confirmText, style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
            // The 58dp minimum button width puts a two-character label 3dp further in than the padding.
            Row(Modifier.offset(x = (-15).dp), verticalAlignment = Alignment.CenterVertically) {
                TextAction("继续", cs.primary) {
                    confirming = false
                    actions.onDownload(release.version)
                }
                TextAction("取消") { confirming = false }
            }
        }
    }
}

/** Thin bar, "已下载 x / y MB" and a cancel button. The bar eases between the 256 KB steps the backend reports. */
@Composable
private fun DownloadingRow(download: DownloadUi.Downloading, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val fraction by animateFloatAsState(download.fraction.coerceIn(0f, 1f), tween(250), label = "updateProgress")
    Column {
        Box(
            Modifier.fillMaxWidth().height(3.dp).clip(CircleShape)
                .background(cs.outlineVariant.copy(alpha = 0.6f)),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(cs.primary))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(download.text, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            // Shifted right by the button's own 12dp padding, so the label ends on the bar's right edge.
            Box(Modifier.offset(x = 12.dp)) { TextAction("取消", onClick = onCancel) }
        }
    }
}

/** One quiet line with the static arc, for the steps the user only waits through. */
@Composable
private fun BusyRow(text: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        ProgressArc(size = 14.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun ReadyRow(onInstall: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TonalAction("安装", onClick = onInstall)
        Spacer(Modifier.width(12.dp))
        Text(
            "安装时应用会关闭，播放会停止",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Says why the permission is needed before sending the user to settings. When they come back the
 * install is requested again, which re-checks the permission (docs/android-app-updates.md).
 */
@Composable
private fun PermissionRow(actions: UpdateActions) {
    var openedSettings by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (openedSettings) {
            openedSettings = false
            actions.onInstall()
        }
    }
    Column {
        Text(
            "系统要求先开启“允许安装未知应用”，才能安装更新。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        TonalAction("去设置") {
            openedSettings = true
            actions.onOpenInstallSettings()
        }
    }
}

@Composable
private fun TextAction(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = color)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** The one emphasised action of a step: a faint accent tile, the same tone the selected tab uses. */
@Composable
private fun TonalAction(text: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    FilledTonalButton(
        onClick = onClick,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = cs.primary.copy(alpha = 0.12f), contentColor = cs.primary,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

