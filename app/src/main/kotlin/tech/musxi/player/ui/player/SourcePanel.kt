package tech.musxi.player.ui.player

import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.components.MusxiSheet
import tech.musxi.player.ui.components.PlatformTag
import tech.musxi.player.ui.components.ProgressArc
import tech.musxi.player.ui.components.pressableRow
import tech.musxi.player.ui.model.formatDuration
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.tabular

/**
 * What was last on screen, kept so the sheet can slide out showing it instead of going blank, and so
 * a face that is cross-fading away keeps the data it had. Plain fields on purpose: they are written
 * during composition with the value being composed, which makes the writes idempotent.
 */
private class ShownFace {
    var ui: SourcePanelUi = SourcePanelUi()
    val byPhase = HashMap<SourcePhase, SourcePanelUi>()
}

/**
 * Source panel: a [MusxiSheet] with three faces (searching, choosing, nothing found), not a page.
 *
 * Place it once above the player and leave it in the tree: [ui] going hidden makes the sheet slide
 * out, and the sheet draws its own scrim and takes the back key. Pulling it down, tapping the scrim
 * or pressing back calls [onCancel]; a failed search has nothing left to cancel in the service, so
 * the sheet also remembers locally that this search was dismissed and stays closed until a new one
 * (a new revision) starts.
 *
 * The ring on a platform that is still searching is the repo's static arc: spec section 4 wants a
 * spinning one on the device, but an endless animation never lets a screenshot test go idle, and
 * the switch that tells the two apart does not exist yet.
 */
@Composable
fun SourcePanel(
    ui: SourcePanelUi,
    dark: Boolean,
    onPick: (Int) -> Unit = {},
    onCancel: () -> Unit = {},
    onSkip: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val shown = remember { ShownFace() }
    if (ui.phase != SourcePhase.HIDDEN) {
        shown.ui = ui
        shown.byPhase[ui.phase] = ui
    }
    // Read here, not inside the lambda, so a new face makes the lambda (and the sheet) recompose.
    val face = shown.ui
    var dismissedRevision by remember { mutableLongStateOf(-1L) }
    MusxiSheet(
        visible = ui.phase != SourcePhase.HIDDEN && ui.revision != dismissedRevision,
        onDismiss = {
            if (ui.phase != SourcePhase.HIDDEN) dismissedRevision = ui.revision
            onCancel()
        },
    ) {
        SourceFaces(face, shown.byPhase, dark, onPick, onSkip, onRetry)
    }
}

/**
 * The body under the grab handle. Keyed by phase: a progress update inside one face recomposes it
 * in place, only a change of face cross-fades. The leaving face is drawn from [lastByPhase], the data
 * it had, instead of the new face's.
 */
@Composable
private fun ColumnScope.SourceFaces(
    face: SourcePanelUi,
    lastByPhase: Map<SourcePhase, SourcePanelUi>,
    dark: Boolean,
    onPick: (Int) -> Unit,
    onSkip: () -> Unit,
    onRetry: () -> Unit,
) {
    Crossfade(
        targetState = face.phase,
        modifier = Modifier.weight(1f).fillMaxWidth(),
        animationSpec = Motion.enter(),
    ) { phase ->
        val ui = if (phase == face.phase) face else lastByPhase[phase] ?: face
        when (phase) {
            SourcePhase.SEARCHING -> SearchingBody(ui, dark)
            SourcePhase.CHOOSING -> ChooseBody(ui, dark, onPick)
            SourcePhase.FAILED -> FailedBody(ui, dark, onSkip, onRetry)
            SourcePhase.HIDDEN -> Unit
        }
    }
}

@Composable
private fun navBottomPadding() = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

@Composable
private fun SheetHeader(ui: SourcePanelUi) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(
            ui.title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        if (ui.subtitle.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                ui.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (ui.note.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(ui.note, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

/**
 * One platform line: its dot, a sentence and, while it is still working, a ring. The ring is the
 * repo's static arc on purpose, see [SourcePanel]. The row grows with the text size instead of
 * clipping at 200% font scale.
 */
@Composable
private fun PlatformLine(line: PlatformLineUi, dark: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlatformTag(line.platform, dark, showName = false)
        Spacer(Modifier.width(12.dp))
        Text(
            line.text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (line.muted) cs.onSurfaceVariant else cs.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (line.busy) ProgressArc(color = cs.onSurfaceVariant, size = 16.dp, strokeWidth = 2.dp)
    }
}

/**
 * The two faces without a list scroll their column anyway: when it fits nothing moves, and a drag
 * on it then goes to the sheet (nested scroll), so the whole body drags the sheet like the list does.
 */
@Composable
private fun SearchingBody(ui: SourcePanelUi, dark: Boolean) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = navBottomPadding() + 8.dp),
    ) {
        SheetHeader(ui)
        Spacer(Modifier.height(8.dp))
        ui.platformLines.forEach { PlatformLine(it, dark) }
    }
}

@Composable
private fun ChooseBody(ui: SourcePanelUi, dark: Boolean, onPick: (Int) -> Unit) {
    val navBottom = navBottomPadding()
    Column(Modifier.fillMaxSize()) {
        SheetHeader(ui)
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = navBottom + 8.dp),
        ) {
            itemsIndexed(ui.candidates) { index, candidate -> CandidateRow(candidate, dark) { onPick(index) } }
        }
    }
}

/**
 * A candidate: cover, title, artist and album, the platform, the length, and how far that is from
 * the original's. The album and the signed difference are both printed on purpose: a matching title
 * is not the same recording, and a live take is usually the one that runs longer. The length is
 * never displaced, it is the number the user needs to judge the recording.
 */
@Composable
private fun CandidateRow(candidate: CandidateRowUi, dark: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().pressableRow(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(
            seed = candidate.title + candidate.platform.name,
            modifier = Modifier.size(48.dp),
            shape = MaterialTheme.shapes.small,
            url = candidate.coverUrl,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                candidate.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (candidate.subtitle.isNotEmpty()) {
                Text(
                    candidate.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            PlatformTag(candidate.platform, dark)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (candidate.durationMs > 0) {
                Text(
                    formatDuration(candidate.durationMs),
                    style = MaterialTheme.typography.labelSmall.tabular, color = cs.onSurfaceVariant,
                )
            }
            // Under the length, outside the truncating subtitle: it is the single most reliable
            // tell between a studio take and a live one.
            candidate.durationNote?.let { note ->
                Text(note, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant.copy(alpha = 0.75f))
            }
        }
    }
}

/**
 * Why each platform has nothing, then the two ways on. Retrying is the accent action; moving to the
 * next song is the quiet one. The sheet has no close button: the handle, the scrim and back close it.
 */
@Composable
private fun FailedBody(ui: SourcePanelUi, dark: Boolean, onSkip: () -> Unit, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = navBottomPadding() + 8.dp),
    ) {
        SheetHeader(ui)
        Spacer(Modifier.height(8.dp))
        if (ui.platformLines.isEmpty()) {
            // No platform reported anything: one summary sentence instead of invented lines.
            Text(
                ui.failureDetail, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else {
            ui.platformLines.forEach { PlatformLine(it, dark) }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onRetry) {
                Text("重试", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onSkip,
                colors = ButtonDefaults.textButtonColors(contentColor = cs.onSurfaceVariant),
            ) {
                Text("播下一首", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
