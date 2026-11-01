package tech.musxi.player.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.model.formatDuration

/** Switching the queue form also leaves whichever form was open. */
internal fun PlayerLabState.chooseQueueStyle(style: QueueStyle) {
    queueStyle = style
    sheetVisible = false
    if (mode == PlayerMode.QUEUE) toggleMode(PlayerMode.QUEUE)
}

/** The page behind the player: a fake playlist. Tapping a row only changes the current song. */
@Composable
internal fun PlayerLabPage(env: LabEnv, s: PlayerLabState, nowColor: Color) {
    val cs = MaterialTheme.colorScheme
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 150.dp
    Column(Modifier.fillMaxSize().background(cs.background)) {
        LabTopBar(env, LabExperiment.PLAYER.title)
        PlayerOptionRows(
            s, cs.onSurface, cs.primary, cs.onPrimary, cs.surfaceContainerHigh,
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = bottom)) {
            itemsIndexed(env.songs, key = { _, song -> song.id }) { i, song ->
                val playing = i == s.index
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { s.jumpTo(i) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(song.seed, Modifier.size(48.dp), MaterialTheme.shapes.small, song.coverUrl)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            song.title, style = MaterialTheme.typography.titleSmall,
                            color = if (playing) nowColor else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            song.artist + " · " + song.album, style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(formatDuration(song.durationMs), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** Background and queue-form choices; the same rows appear on the page and inside the player. */
@Composable
internal fun PlayerOptionRows(
    s: PlayerLabState,
    ink: Color,
    accent: Color,
    onAccent: Color,
    idle: Color,
    modifier: Modifier = Modifier,
) {
    val backdrops = remember { PlayerBackdrop.entries.filter { it != PlayerBackdrop.BLUR || BlurSupported } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow("背景", backdrops.map { it.label }, backdrops.indexOf(s.backdrop), ink, accent, onAccent, idle) { s.backdrop = backdrops[it] }
        ChipRow("队列", QueueStyle.entries.map { it.label }, s.queueStyle.ordinal, ink, accent, onAccent, idle) {
            s.chooseQueueStyle(QueueStyle.entries[it])
        }
    }
}

@Composable
private fun ChipRow(
    title: String,
    labels: List<String>,
    selected: Int,
    ink: Color,
    accent: Color,
    onAccent: Color,
    idle: Color,
    onSelect: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = 0.6f), modifier = Modifier.width(32.dp))
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label, style = MaterialTheme.typography.labelMedium, color = if (on) onAccent else ink,
                modifier = Modifier.clip(CircleShape).background(if (on) accent else idle)
                    .clickable { onSelect(i) }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** The "方案" panel inside the full-screen player, so the options can be changed while looking at the result. */
@Composable
internal fun PlayerOptionsPopup(s: PlayerLabState) {
    val l = s.layout
    val density = LocalDensity.current
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            s.optionsOpen = false
        },
    )
    val width = with(density) { 300.dp.toPx() }
    val panel = PlayerRect(
        l.width - with(density) { 20.dp.toPx() } - width, l.options.bottom + with(density) { 8.dp.toPx() },
        width, with(density) { 112.dp.toPx() },
    )
    Column(
        Modifier.placeAt(panel).clip(shape).background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.2f), shape).padding(14.dp),
    ) {
        PlayerOptionRows(s, Color.White, Color.White, Color.Black, Color.White.copy(alpha = 0.16f))
    }
}

/** The playing-song colour for the page: the cover colour, lifted toward white on a dark page so it stays readable. */
internal fun nowPlayingColor(palette: CoverPalette, dark: Boolean): Color =
    if (dark) lerp(palette.top, Color.White, 0.55f) else palette.top
