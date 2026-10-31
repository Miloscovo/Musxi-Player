package tech.musxi.player.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.musxi.core.library.SongSummary
import tech.musxi.player.ui.model.formatDuration
import tech.musxi.player.ui.search.highlighted
import tech.musxi.player.ui.theme.nowPlayingAccent

/**
 * One song, wherever songs are listed.
 *
 * This row used to live inside the search screen as a private composable. The playlist detail page
 * needs the same row, and the cheap way out — a second copy — is exactly how the design sheets
 * drifted away from the real pages in earlier rounds. So it is one component used by both.
 *
 * Kept identical to the playlist row in the library on purpose: 64dp high, 48dp cover as the lead,
 * title over subtitle, duration on the trailing edge.
 */
@Composable
fun SongRow(
    song: SongSummary,
    modifier: Modifier = Modifier,
    /** Search words picked out in the title and subtitle; blank leaves both plain. */
    highlight: String = "",
    /** This is the track that is playing: accent title, bars where the duration would be. */
    playing: Boolean = false,
    /** Shows a "more" button after the duration. The tap never reaches [onClick]. */
    onMore: (() -> Unit)? = null,
    /** Popup anchored to the "more" button; composed beside it, so it opens next to the button. */
    menu: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val accent = nowPlayingAccent()
    // Matches are set in bold only. An unspecified colour leaves each word in the colour of the line
    // it sits in (including the accent of the playing title); a coloured word in every row of a full
    // page is louder than the results themselves.
    val title = remember(song.title, highlight) { highlighted(song.title, highlight, Color.Unspecified) }
    val subtitleText = songSubtitle(song)
    val subtitle = remember(subtitleText, highlight) { highlighted(subtitleText, highlight, Color.Unspecified) }
    Row(
        modifier.fillMaxWidth().height(64.dp).pressableRow(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 48dp, the same lead as every other list row in the app.
        Cover(
            seed = song.album.ifBlank { song.key.stableId },
            modifier = Modifier.size(48.dp),
            shape = MaterialTheme.shapes.small,
            url = song.coverUrl,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.titleSmall,
                color = if (playing) accent else cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (playing) {
            PlayingBars(accent)
        } else {
            Text(
                formatDuration(song.durationMs),
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            )
        }
        if (onMore != null) {
            // The menu is the button's sibling inside one Box, which is what AnchoredMenu anchors to.
            Box {
                IconButton(onClick = onMore, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.MoreVert, contentDescription = "更多操作",
                        tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp),
                    )
                }
                menu?.invoke()
            }
        }
    }
}

/** Artist list first, album only when it adds information. */
fun songSubtitle(song: SongSummary): String {
    val artists = song.artists.joinToString(" / ").ifBlank { "未知歌手" }
    return if (song.album.isBlank() || song.album.equals("<unknown>", ignoreCase = true)) artists
    else "$artists · ${song.album}"
}
