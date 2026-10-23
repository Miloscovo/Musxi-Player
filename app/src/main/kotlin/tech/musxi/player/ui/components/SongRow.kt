package tech.musxi.player.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.musxi.core.library.SongSummary
import tech.musxi.player.ui.model.formatDuration

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
fun SongRow(song: SongSummary, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().height(64.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 48dp, the same lead as every other list row in the app.
        Cover(
            seed = song.album.ifBlank { song.key.stableId },
            modifier = Modifier.size(48.dp),
            shape = MaterialTheme.shapes.small,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                songSubtitle(song), style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            formatDuration(song.durationMs),
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
        )
    }
}

/** Artist list first, album only when it adds information. */
fun songSubtitle(song: SongSummary): String {
    val artists = song.artists.joinToString(" / ").ifBlank { "未知歌手" }
    return if (song.album.isBlank() || song.album.equals("<unknown>", ignoreCase = true)) artists
    else "$artists · ${song.album}"
}
