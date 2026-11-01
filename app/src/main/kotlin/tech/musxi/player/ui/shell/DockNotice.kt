package tech.musxi.player.ui.shell

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.musxi.core.playback.FailureReason
import tech.musxi.core.playback.SourceSelectionMode
import tech.musxi.core.playback.SourceSelectionPhase
import tech.musxi.player.ui.components.ProgressArc
import tech.musxi.player.ui.player.withFailureReason

/** One line shown above the mini player when the player page is closed. */
data class DockNoticeUi(
    val text: String,
    val action: String?,
    val busy: Boolean,
    /** The action searches other platforms for the current song instead of opening the player. */
    val findsSources: Boolean = false,
)

/**
 * Maps the source selection phase to the dock notice. The full player page already shows the
 * complete source panel, so nothing is shown while it is open.
 */
internal fun dockNoticeOf(
    phase: SourceSelectionPhase,
    mode: SourceSelectionMode,
    playerOpen: Boolean,
    /** Why the original did not play; only the failed notice shows it. */
    reason: FailureReason? = null,
    /**
     * The song failed and no automatic search started (the failure was not one that searches by
     * itself). The mini player already says why; the notice offers the way out.
     */
    playbackFailed: Boolean = false,
): DockNoticeUi? {
    if (playerOpen) return null
    return when (phase) {
        SourceSelectionPhase.SEARCHING -> when (mode) {
            SourceSelectionMode.AUTO -> DockNoticeUi("当前音源不可用，正在其他平台找…", null, busy = true)
            SourceSelectionMode.MANUAL -> DockNoticeUi("正在其他平台查找音源…", null, busy = true)
        }
        SourceSelectionPhase.CHOOSING -> DockNoticeUi("找到可替换的音源，需要你确认", "选择", busy = false)
        SourceSelectionPhase.FAILED -> DockNoticeUi(withFailureReason("没找到能播的版本", reason), "查看", busy = false)
        SourceSelectionPhase.NONE ->
            if (playbackFailed) DockNoticeUi("其他平台可能有能播的版本", "换个音源", busy = false, findsSources = true) else null
        SourceSelectionPhase.SELECTED -> null
    }
}

@Composable
fun DockNotice(ui: DockNoticeUi, onAction: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            // A minimum, not a fixed height: at large font sizes the row grows instead of clipping.
            .heightIn(min = 40.dp)
            .padding(horizontal = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ui.busy) {
            ProgressArc(color = cs.onSurfaceVariant, size = 14.dp)
        } else {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = ui.text,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ui.action?.let { label ->
            TextButton(onClick = onAction) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.primary,
                )
            }
        }
    }
}
