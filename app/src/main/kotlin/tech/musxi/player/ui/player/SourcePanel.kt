package tech.musxi.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.components.OpticallyCentredText
import tech.musxi.player.ui.model.formatDuration
import tech.musxi.player.ui.theme.displayName
import tech.musxi.player.ui.theme.identityColor
import tech.musxi.player.ui.theme.isDarkSurface
import tech.musxi.player.ui.theme.tabular

/**
 * Source panel: a bottom sheet, not a page.
 *
 * A swap interrupts playback for five seconds and then gets out of the way; a full screen would
 * erase the context the user needs to decide. The sheet keeps the player visible behind it.
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
    if (ui.phase == SourcePhase.HIDDEN) return
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(cs.surfaceContainerHigh)
            .padding(bottom = 16.dp),
    ) {
        // Drag handle: the only affordance that says "this can be dismissed".
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(width = 32.dp, height = 4.dp).clip(CircleShape)
                    .background(cs.onSurfaceVariant.copy(alpha = 0.30f)),
            )
        }
        Header(ui)
        Spacer(Modifier.height(12.dp))
        when (ui.phase) {
            SourcePhase.SEARCHING -> SearchingRows()
            SourcePhase.CHOOSING -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                ui.candidates.forEachIndexed { index, candidate ->
                    if (index > 0) Spacer(Modifier.height(4.dp))
                    CandidateRow(candidate, dark) { onPick(index) }
                }
            }
            SourcePhase.FAILED -> Failure(ui, onSkip, onRetry, onCancel)
            SourcePhase.HIDDEN -> Unit
        }
        Spacer(Modifier.height(8.dp))
        Footer(ui, onCancel, onSkip)
    }
}

@Composable
private fun Header(ui: SourcePanelUi) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(
            when (ui.phase) {
                SourcePhase.SEARCHING -> "正在找替代音源"
                SourcePhase.CHOOSING -> "换个音源"
                // The headline lives here and nowhere else; printing it again below made the
                // failure state say the same sentence twice in two type sizes.
                SourcePhase.FAILED -> ui.failureHeadline
                SourcePhase.HIDDEN -> ""
            },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = cs.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            ui.reason, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (ui.partial && ui.phase == SourcePhase.CHOOSING) {
            Spacer(Modifier.height(2.dp))
            Text(
                "有平台没回应，候选可能不全",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            )
        }
    }
}

/** Three placeholders shaped like real rows: cover, two text bars, a trailing block. */
@Composable
private fun SearchingRows() {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        repeat(3) { index ->
            if (index > 0) Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().height(CANDIDATE_HEIGHT).clip(RoundedCornerShape(16.dp))
                    .background(cs.onSurface.copy(alpha = 0.04f)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(48.dp).clip(MaterialTheme.shapes.small)
                        .background(cs.onSurface.copy(alpha = 0.10f)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Box(
                        Modifier.fillMaxWidth(0.55f).height(12.dp).clip(CircleShape)
                            .background(cs.onSurface.copy(alpha = 0.10f)),
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.fillMaxWidth(0.35f).height(10.dp).clip(CircleShape)
                            .background(cs.onSurface.copy(alpha = 0.07f)),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.width(28.dp).height(10.dp).clip(CircleShape)
                        .background(cs.onSurface.copy(alpha = 0.07f)),
                )
            }
        }
    }
}

/**
 * A candidate. The album and the signed duration difference are both printed on purpose: a matching
 * title is not the same recording, and a live take is usually the one that runs longer.
 *
 * The duration is never displaced. The five-second countdown used to sit where the duration goes,
 * which removed exactly the number the user needs in those five seconds to judge the recording.
 * It now leads the caption line, with its unit spelled out.
 */
@Composable
private fun CandidateRow(candidate: CandidateRowUi, dark: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().height(CANDIDATE_HEIGHT)
            .clip(RoundedCornerShape(16.dp))
            // Rows sit directly on the panel. Card-inside-a-panel-inside-a-scrim was three greys
            // stacked; only the countdown row earns a tint, and that tint is its progress.
            .clickable(onClick = onClick),
    ) {
        // The countdown IS the row's background rather than a ring beside it: the five seconds are
        // about to choose this row, so the fill says "this one" without any extra explanation.
        if (candidate.autoTarget && candidate.countdownFraction > 0f) {
            Box(
                Modifier.fillMaxWidth(candidate.countdownFraction).fillMaxHeight(),
            ) {
                val fill = cs.primary.copy(alpha = if (dark) 0.20f else 0.12f)
                // The trailing edge fades instead of cutting: a hard right angle in the middle of
                // a line reads as a half-rendered highlight rather than as elapsed time.
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(0f to fill, 0.82f to fill, 1f to Color.Transparent),
                        ),
                    ),
                )
            }
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(
                seed = candidate.title,
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.small,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    candidate.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    candidateCaption(candidate),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (candidate.secondsLeft != null) cs.primary else cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatDuration(candidate.durationMs),
                    style = MaterialTheme.typography.labelSmall.tabular, color = cs.onSurfaceVariant,
                )
                // The delta lives under the duration, outside the truncating caption, because it is
                // the single most reliable tell between a studio take and a live one.
                candidate.durationDeltaMs?.let { delta ->
                    val seconds = kotlin.math.abs(delta) / 1000
                    Text(
                        if (delta > 0) "长 $seconds 秒" else "短 $seconds 秒",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

/**
 * `3 秒后播放 · 酷狗音乐 · 叶惠美`
 *
 * The countdown leads so a long album name is what gets ellipsised, never the time; and it carries
 * the word 秒, because a bare "3" in a strip of times reads as a serial number.
 */
internal fun candidateCaption(candidate: CandidateRowUi): String {
    val parts = mutableListOf<String>()
    candidate.secondsLeft?.let { parts += "$it 秒后播放" }
    parts += candidate.platform.displayName
    if (candidate.album.isNotBlank() && !candidate.album.equals("<unknown>", ignoreCase = true)) {
        parts += candidate.album
    }
    return parts.joinToString(" · ")
}

@Composable
private fun Failure(ui: SourcePanelUi, onSkip: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        // The headline is already the sheet's title; repeating it here was the last duplication.
        Text(
            ui.failureDetail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The one accent action on this panel: moving on is the only useful thing here.
            Button(onClick = onSkip) {
                Text("播下一首", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onRetry) {
                Text("重试", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.weight(1f))
            // The candidate state can be dismissed; this state must be dismissible too, otherwise
            // the only way out is the drag handle.
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = cs.onSurfaceVariant),
            ) {
                Text("关闭", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun Footer(ui: SourcePanelUi, onCancel: () -> Unit, onSkip: () -> Unit) {
    if (ui.phase == SourcePhase.FAILED) return
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Both are secondary: the real primary action in this state is tapping a candidate, so
        // neither of these should carry the accent.
        TextButton(
            onClick = onSkip,
            colors = ButtonDefaults.textButtonColors(contentColor = cs.onSurfaceVariant),
        ) {
            Text("跳过这首", style = MaterialTheme.typography.labelLarge)
        }
        TextButton(
            onClick = onCancel,
            colors = ButtonDefaults.textButtonColors(contentColor = cs.onSurfaceVariant),
        ) {
            Text("取消", style = MaterialTheme.typography.labelLarge)
        }
    }
}

private val CANDIDATE_HEIGHT = 72.dp
