package tech.musxi.player.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.musxi.core.library.PlaylistSummary
import tech.musxi.core.library.SongSummary
import tech.musxi.core.playback.PlaybackJournalState
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.components.ProgressArc
import tech.musxi.player.ui.components.pressableRow
import tech.musxi.player.ui.model.formatDuration
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.nowPlayingAccent
import tech.musxi.player.ui.theme.tabular

/** What the library tab shows above the playlist list for one platform. */
data class LibraryHomeUi(
    val accountName: String? = null,
    val resume: ResumeUi? = null,
    val recent: List<SongSummary> = emptyList(),
)

/** The saved queue's current song, offered as a one-tap way back into what was playing. */
data class ResumeUi(
    val song: SongSummary,
    val index: Int,
    val count: Int,
    val positionMs: Long,
    val restoring: Boolean,
)

/** Most recent plays kept on the strip. The journal holds up to 100; a screenful is plenty. */
internal const val RECENT_LIMIT = 12

/**
 * Folds one platform's journal into what the library tab can show.
 *
 * [playingQueue] is true while the player already holds a queue. Offering "continue" then would
 * restore a stale copy over what the user is listening to, so the card stays away.
 *
 * The card also needs a verified account: the backend refuses to restore for an unverified one, and a
 * tap that opens an empty player is worse than no card. [PlaybackJournalState.recent] is not gated;
 * it is display only, and each tap goes through the normal play path with its own checks.
 *
 * `recent` arrives newest first (the journal prepends each play), so only the repeats need removing:
 * the same song can sit in the journal once per source it was played from.
 */
internal fun libraryHomeUiOf(
    journal: PlaybackJournalState?,
    accountName: String?,
    playingQueue: Boolean,
): LibraryHomeUi {
    val name = accountName?.takeIf { it.isNotBlank() }
    if (journal == null) return LibraryHomeUi(accountName = name)
    val saved = journal.queue
    val song = saved?.songs?.getOrNull(saved.index)
    val resume = if (saved != null && song != null && journal.accountVerified && !playingQueue) {
        ResumeUi(
            song = song,
            index = saved.index,
            count = saved.songs.size,
            positionMs = saved.positionMs,
            restoring = journal.restoring,
        )
    } else null
    val recent = journal.recent
        .map { it.original }
        .distinctBy { it.key.stableId }
        .take(RECENT_LIMIT)
    return LibraryHomeUi(accountName = name, resume = resume, recent = recent)
}

/**
 * Whether [p] is the user's own "liked songs" list.
 *
 * Temporary rule: the backend does not yet say what kind of playlist this is, so it goes by name.
 * 网易云 calls it "<nickname>喜欢的音乐", QQ音乐 and 酷狗 "我喜欢". A user playlist that happens to be
 * named like this would be promoted too. Replace with the backend's `kind` field once it exists.
 */
internal fun isLikedPlaylist(p: PlaylistSummary): Boolean {
    val title = p.title.filterNot { it.isWhitespace() }
    return title == "我喜欢" || title == "我喜欢的音乐" || title.endsWith("喜欢的音乐")
}

/**
 * "Continue playing": one row, the same height as a song row's bigger sibling. The whole row and the
 * round button on its right do the same thing. While the queue is being restored both are inert, so a
 * second tap cannot cancel and resubmit the restore that is already running.
 */
@Composable
fun ResumeCard(ui: ResumeUi, onResume: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(72.dp)
            .pressableRow(onClick = onResume, enabled = !ui.restoring),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(
            seed = ui.song.key.stableId,
            modifier = Modifier.size(56.dp),
            shape = MaterialTheme.shapes.medium,
            url = ui.song.coverUrl,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "继续播放",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            )
            Text(
                ui.song.title,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${ui.index + 1} / ${ui.count} 首 · 上次听到 ${formatDuration(ui.positionMs)}",
                style = MaterialTheme.typography.bodySmall.tabular, color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(cs.surfaceContainerHigh)
                .clickable(enabled = !ui.restoring, role = Role.Button, onClickLabel = "继续播放", onClick = onResume),
            contentAlignment = Alignment.Center,
        ) {
            if (ui.restoring) {
                ProgressArc(color = cs.onSurface, size = 20.dp, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp), tint = cs.onSurface)
            }
        }
    }
}

/**
 * Recently played songs as a strip of covers.
 *
 * The strip runs to both screen edges so a cover slides off the glass rather than stopping at the page
 * margin. That is [LazyRow]'s own `contentPadding`, not a negative-margin layout trick: content
 * placed outside its parent's measured bounds is dropped by touch hit-testing, so a widened row would
 * have dead zones exactly where a swipe starts. The price is that the caller must not pad this
 * horizontally; [LibraryContent] pads each of its other items individually instead.
 */
@Composable
fun RecentRow(songs: List<SongSummary>, nowPlayingId: String?, onPlay: (SongSummary) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Text(
            "最近播放",
            style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
            modifier = Modifier.padding(horizontal = Space.page),
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Space.page),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(songs) { song ->
                RecentItem(song, playing = song.key.stableId == nowPlayingId) { onPlay(song) }
            }
        }
    }
}

@Composable
private fun RecentItem(song: SongSummary, playing: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.width(96.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Cover(
            seed = song.key.stableId,
            modifier = Modifier.size(96.dp),
            shape = MaterialTheme.shapes.medium,
            url = song.coverUrl,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            song.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (playing) nowPlayingAccent() else cs.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            song.artists.joinToString(" / ").ifBlank { "未知歌手" },
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The liked-songs playlist, given more room than the rest: a bigger cover and a bigger title are the
 * whole hierarchy. No colour block; size and white space do the work.
 */
@Composable
fun FeaturedPlaylistCard(playlist: PlaylistSummary, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(104.dp).pressableRow(onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(
            seed = playlist.key.stableId,
            modifier = Modifier.size(88.dp),
            shape = MaterialTheme.shapes.large,
            url = playlist.coverUrl,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "我喜欢的音乐",
                style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
            )
            Text(
                playlist.title,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${playlist.trackCount} 首",
                style = MaterialTheme.typography.bodyMedium.tabular, color = cs.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = cs.onSurfaceVariant,
        )
    }
}
