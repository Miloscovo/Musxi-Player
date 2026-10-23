package tech.musxi.player.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.musxi.core.account.Platform
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.components.ProgressArc
import tech.musxi.player.ui.theme.displayName
import tech.musxi.player.ui.theme.nowPlayingAccent

/**
 * Everything the bar shows. `subtitle` is one line with a strict priority order, because the bar
 * is short and a second line would push the whole chrome around.
 */
data class MiniPlayerUi(
    val visible: Boolean = false,
    val title: String = "",
    val subtitle: String = "",
    val playing: Boolean = false,
    val buffering: Boolean = false,
    /** 0..1, already clamped; drives whichever progress style the bar uses. */
    val progress: Float = 0f,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    /** Set only when the queue platform and the streaming platform differ, i.e. after a source swap. */
    val sourcePlatform: Platform? = null,
)

/**
 * Collapses the playback state into the bar.
 *
 * The subtitle answers "why is nothing happening" before it answers "who is this": an interruption
 * or a failure is the reason the play button does not look pressed. The artist name is the
 * fallback, and a cross-platform swap appends which platform is actually streaming.
 */
fun miniPlayerUi(state: tech.musxi.playback.PlaybackState): MiniPlayerUi {
    val hasTrack = state.mediaId != null || state.title.isNotBlank()
    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    return MiniPlayerUi(
        visible = hasTrack,
        title = state.title,
        subtitle = subtitleOf(state),
        playing = state.playing && !state.buffering,
        buffering = state.buffering,
        progress = progress,
        hasPrevious = state.hasPrevious,
        hasNext = state.hasNext,
        sourcePlatform = swappedSourceOf(state),
    )
}

/**
 * One line, and the reason beats the name.
 *
 * The title line above already says which song this is, so when something is wrong the subtitle
 * spends its whole width on the reason. Prefixing the artist (the first version did) pushed the
 * reason past the ellipsis — "周杰伦 · 被其他音频暂…" explained nothing. The artist only appears
 * when there is nothing to explain.
 */
internal fun subtitleOf(state: tech.musxi.playback.PlaybackState): String {
    val artist = state.artist.ifBlank { "未知歌手" }
    val reason = when {
        state.suppression == tech.musxi.playback.PlaybackSuppression.AUDIO_FOCUS -> "被其他音频暂时打断"
        state.suppression == tech.musxi.playback.PlaybackSuppression.OUTPUT -> "没有可用的音频输出"
        state.suppression == tech.musxi.playback.PlaybackSuppression.OTHER -> "播放暂时被暂停"
        state.buffering -> "缓冲中"
        state.failure != null || state.error != null -> "播放失败"
        state.ended -> "已播完"
        else -> null
    }
    if (reason != null) return reason
    val source = swappedSourceOf(state)
    return if (source == null) artist else "$artist · ${source.displayName}音源"
}

/**
 * Only after a cross-platform swap are there two platforms to name; before that the note would
 * just repeat what the library already said. The track rows use the same "X 歌单 · Y 音源" wording.
 */
internal fun swappedSourceOf(state: tech.musxi.playback.PlaybackState): Platform? {
    val queue = state.queuePlatform ?: return null
    val source = state.sourcePlatform ?: return null
    return source.takeIf { it != queue }
}

/**
 * How progress is shown inside the card.
 *
 * The linear band was the first attempt and it is the reason the card got fat: reserving 12dp for a
 * 2dp rule forced the height up to 72dp, and the rule itself read as a divider drawn across the
 * content rather than as progress.
 */
enum class ProgressStyle {
    /** 2dp rule in its own reserved band at the bottom. Needs 72dp; the most conventional. */
    BAND,

    /** Ring wrapped around the play button. No rule at all, so the card can stay at 64dp. */
    RING,

    /** 2dp rule flush with the card's bottom edge, clipped by the corner; reads as "filling up". */
    FLUSH,
}

/**
 * Mini player. Sits above the tab bar and is the only playback feedback on the library, search and
 * account tabs, which is why it carries progress and can be tapped to open the player.
 */
@Composable
fun MiniPlayer(
    ui: MiniPlayerUi,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    style: ProgressStyle = ProgressStyle.RING,
    shape: Shape = MaterialTheme.shapes.large,
    /**
     * Tonal, not solid accent. With a ring around the button, a solid accent fill put two accent
     * marks in the same spot and out-shouted the cover; the accent belongs to the ring alone.
     */
    tonalButton: Boolean = true,
    /** True when a dock already provides the container: no clip, no background, no radius. */
    embedded: Boolean = false,
) {
    if (!ui.visible) return
    val cs = MaterialTheme.colorScheme
    val band = style == ProgressStyle.BAND
    Box(
        Modifier.fillMaxWidth().height(if (band) 72.dp else 64.dp)
            .then(if (embedded) Modifier else Modifier.clip(shape).background(cs.surfaceContainerHigh))
            .clickable(onClick = onExpand),
    ) {
        Row(
            // The band style reserves its own strip; without that the cover used to end exactly on
            // the rule (measured: cover bottom y≈1411.5, rule 1412–1415).
            Modifier.fillMaxSize().padding(
                start = 8.dp, end = 8.dp,
                bottom = if (band) 12.dp else 0.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 48dp in a 64dp card: the cover is the protagonist, so it gets the room, and the play
            // control gives some of its own up.
            Cover(
                seed = ui.title,
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ui.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    ui.subtitle, style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(4.dp))
            MiniControls(ui, style, tonalButton, onPlayPause, onPrevious, onNext)
        }
        when (style) {
            ProgressStyle.BAND -> MiniProgress(
                ui.progress,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
            )
            // Flush with the edge: the corner clip shapes the line into the card itself, so it
            // reads as the card filling rather than as a rule sitting on top of it.
            ProgressStyle.FLUSH -> MiniProgress(
                ui.progress,
                modifier = Modifier.align(Alignment.BottomCenter),
                inset = 0.dp,
            )
            ProgressStyle.RING -> Unit
        }
    }
}

@Composable
private fun MiniControls(
    ui: MiniPlayerUi,
    style: ProgressStyle,
    tonalButton: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // With a ring around it, a solid accent button would put two accent marks in the same spot and
    // out-shout the cover. The tonal variant leaves the accent to the ring alone.
    val buttonColor = if (tonalButton) cs.primaryContainer else cs.primary
    val iconColor = if (tonalButton) cs.onPrimaryContainer else cs.onPrimary
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, enabled = ui.hasPrevious, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Outlined.SkipPrevious, "上一首",
                tint = if (ui.hasPrevious) cs.onSurfaceVariant else cs.onSurfaceVariant.copy(alpha = 0.38f),
                modifier = Modifier.size(20.dp),
            )
        }
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            // The ring lives outside the button so the accent never fights the button's own fill.
            if (style == ProgressStyle.RING) RingProgress(ui.progress)
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(buttonColor)
                    .clickable(onClick = onPlayPause)
                    .semantics { contentDescription = if (ui.playing) "暂停" else "播放" },
                contentAlignment = Alignment.Center,
            ) {
                // A static arc, never a spinner: an indeterminate indicator never idles and hangs
                // the screenshot tests (see ProgressArc's doc).
                if (ui.buffering) {
                    ProgressArc(modifier = Modifier.size(15.dp), color = iconColor)
                } else {
                    Icon(
                        if (ui.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        null, tint = iconColor, modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        IconButton(onClick = onNext, enabled = ui.hasNext, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Outlined.SkipNext, "下一首",
                tint = if (ui.hasNext) cs.onSurfaceVariant else cs.onSurfaceVariant.copy(alpha = 0.38f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Progress as a ring around the play button: 2.5dp, starting at twelve o'clock, drawn in the gap
 * between the 36dp button and its 40dp slot. It costs no extra height and cannot be mistaken for a
 * divider — which is what the linear rule was being read as.
 *
 * The unplayed track sits at 20% onSurface. At 10% it measured 1.2:1 against the card, so the ring
 * looked like a stray arc with no track at all.
 */
@Composable
private fun RingProgress(progress: Float) {
    val cs = MaterialTheme.colorScheme
    // Hoisted: a @Composable call is not allowed inside the draw scope below.
    val accent = nowPlayingAccent()
    Canvas(Modifier.size(40.dp)) {
        val stroke = 2.5.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = cs.onSurface.copy(alpha = 0.20f),
            startAngle = -90f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(inset, inset), size = arcSize,
            style = Stroke(width = stroke),
        )
        if (progress > 0f) {
            drawArc(
                color = accent,
                startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f), useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * Linear progress. Two layers so the unplayed part still reads as a track rather than as empty card.
 */
@Composable
private fun MiniProgress(progress: Float, modifier: Modifier = Modifier, inset: Dp = 16.dp) {
    val cs = MaterialTheme.colorScheme
    val accent = nowPlayingAccent()
    Box(modifier.fillMaxWidth().padding(horizontal = inset).height(2.dp)) {
        Box(
            Modifier.fillMaxWidth().height(2.dp).clip(CircleShape)
                .background(cs.onSurface.copy(alpha = 0.10f)),
        )
        Box(
            Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(2.dp).clip(CircleShape)
                .background(accent),
        )
    }
}
