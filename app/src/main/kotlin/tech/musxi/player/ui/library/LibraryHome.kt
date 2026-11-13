package tech.musxi.player.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import tech.musxi.core.library.PlaylistKind
import tech.musxi.core.library.PlaylistSummary
import tech.musxi.core.library.SongSummary
import tech.musxi.core.playback.PlaybackJournalState
import tech.musxi.player.ui.components.ALL_LINES
import tech.musxi.player.ui.components.AnchoredMenu
import tech.musxi.player.ui.components.CardRow
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.components.MenuAlign
import tech.musxi.player.ui.components.MenuItem
import tech.musxi.player.ui.components.ProgressArc
import tech.musxi.player.ui.components.RowChevron
import tech.musxi.player.ui.components.RowSpec
import tech.musxi.player.ui.components.RowText
import tech.musxi.player.ui.components.listLines
import tech.musxi.player.ui.model.formatDuration
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.nowPlayingAccent
import tech.musxi.player.ui.theme.rememberHaptics
import tech.musxi.player.ui.theme.tabular

/** What the library tab shows above the playlist list for one platform. */
data class LibraryHomeUi(
    val accountName: String? = null,
    val resume: ResumeUi? = null,
    val recent: List<SongSummary> = emptyList(),
    /** 每日推荐 / 猜你喜欢, right after 继续播放; null keeps the module away. */
    val recommendation: RecommendationCardUi? = null,
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
 * The backend's [PlaylistKind] decides. Only [PlaylistKind.OTHER] falls back to the name, because
 * caches written before the backend sent `kind` have every playlist as OTHER. 网易云 calls it
 * "<nickname>喜欢的音乐", QQ音乐 and 酷狗 "我喜欢". A playlist of unknown kind that happens to be
 * named like this is still promoted; that is the cost of the fallback.
 */
internal fun isLikedPlaylist(p: PlaylistSummary): Boolean = when (p.kind) {
    PlaylistKind.LIKED -> true
    PlaylistKind.OTHER -> {
        val title = p.title.filterNot { it.isWhitespace() }
        title == "我喜欢" || title == "我喜欢的音乐" || title.endsWith("喜欢的音乐")
    }
    PlaylistKind.CREATED, PlaylistKind.COLLECTED -> false
}

/**
 * Row of the "继续播放" module: the song the saved queue stops at. The module's heading says what
 * it is, so the row carries only the song and where in the queue it stands. The whole row and the
 * round button on its right do the same thing. While the queue is being restored both are inert, so
 * a second tap cannot cancel and resubmit the restore that is already running.
 */
@Composable
fun ResumeRow(ui: ResumeUi, onResume: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardRow(onClick = onResume, enabled = !ui.restoring) {
        Cover(
            seed = ui.song.key.stableId,
            modifier = Modifier.size(RowSpec.coverFeature),
            shape = MaterialTheme.shapes.medium,
            url = ui.song.coverUrl,
        )
        Spacer(Modifier.width(RowSpec.leadGap))
        RowText(
            ui.song.title,
            subtitle = "${ui.index + 1} / ${ui.count} 首 · 上次听到 ${formatDuration(ui.positionMs)}",
            titleStyle = MaterialTheme.typography.titleMedium,
            subtitleStyle = MaterialTheme.typography.bodySmall.tabular,
            // The position is the point of this row: it wraps at large font sizes instead of being cut.
            subtitleLines = ALL_LINES,
        )
        Spacer(Modifier.width(RowSpec.trailGap))
        // Only a picture of the row's action: the row is the touch target, so there is one, not two.
        Box(
            Modifier.size(RowSpec.action).clip(CircleShape).background(cs.onSurface.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center,
        ) {
            if (ui.restoring) {
                ProgressArc(color = cs.onSurface, size = RowSpec.actionGlyph, strokeWidth = RowSpec.actionStroke)
            } else {
                Icon(
                    Icons.Filled.PlayArrow, contentDescription = null,
                    modifier = Modifier.size(RowSpec.actionGlyph), tint = cs.onSurface,
                )
            }
        }
    }
}

/**
 * Body of the "最近播放" module: a strip of covers that scrolls inside its card.
 *
 * The card has no side padding of its own (`SectionCard(padded = false)`); this strip's
 * `contentPadding` is the inset, so a cover slides under the card's rounded edge instead of stopping
 * short of it. That is [LazyRow]'s own padding, not a negative-margin layout trick: content placed
 * outside its parent's measured bounds is dropped by touch hit-testing, so a widened row would have
 * dead zones exactly where a swipe starts.
 */
@Composable
fun RecentStrip(
    songs: List<SongSummary>,
    nowPlayingId: String?,
    onPlay: (SongSummary) -> Unit,
    /** Long press on a cover offers to take the song off the strip. */
    onRemoveRecent: (SongSummary) -> Unit = {},
) {
    LazyRow(
        contentPadding = PaddingValues(Space.inner),
        horizontalArrangement = Arrangement.spacedBy(Space.sibling),
    ) {
        items(songs) { song ->
            RecentItem(
                song,
                playing = song.key.stableId == nowPlayingId,
                onClick = { onPlay(song) },
                onRemove = { onRemoveRecent(song) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentItem(song: SongSummary, playing: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    var menuOpen by remember { mutableStateOf(false) }
    // A tile is narrow: at large font sizes it reserves two lines for the title, so the tiles stay one height.
    val titleLines = listLines()
    // The menu anchors to this Box, which is exactly the cover and its title, so it opens over the item.
    Box(Modifier.width(RowSpec.coverTile)) {
        Column(
            Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .combinedClickable(
                    role = Role.Button,
                    // The long press plays Haptic.LONG_PRESS itself; the default buzz would double it.
                    hapticFeedbackEnabled = false,
                    onClick = onClick,
                    onLongClickLabel = "移除最近播放",
                    onLongClick = {
                        haptic(Haptic.LONG_PRESS)
                        menuOpen = true
                    },
                ),
        ) {
            Cover(
                seed = song.key.stableId,
                modifier = Modifier.size(RowSpec.coverTile),
                shape = MaterialTheme.shapes.medium,
                url = song.coverUrl,
            )
            Spacer(Modifier.height(RowSpec.tileGap))
            Text(
                song.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (playing) nowPlayingAccent() else cs.onSurface,
                minLines = titleLines, maxLines = titleLines, overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artists.joinToString(" / ").ifBlank { "未知歌手" },
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        AnchoredMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, align = MenuAlign.START) {
            MenuItem(
                "从最近播放中移除",
                onClick = {
                    menuOpen = false
                    onRemove()
                },
            )
        }
    }
}

/** Heading of the liked-songs module. The row below must not say it again, see [likedRowTitle]. */
internal const val LIKED_HEADING = "我喜欢"

/**
 * What the liked-songs row calls itself. The heading above it already says "我喜欢", and the
 * platforms name this list the same way (QQ音乐 and 酷狗 "我喜欢", 网易云 "<昵称>喜欢的音乐"), so
 * printing the playlist's own title there gave the same words twice. A title that adds something
 * (a nickname) is kept; one that only repeats the heading becomes "全部歌曲".
 */
internal fun likedRowTitle(playlistTitle: String): String {
    val plain = playlistTitle.filterNot { it.isWhitespace() }
    val repeatsHeading = plain == LIKED_HEADING || plain == "${LIKED_HEADING}的音乐"
    return if (repeatsHeading || plain.isEmpty()) "全部歌曲" else playlistTitle
}

/**
 * Row of the "我喜欢" module: the liked-songs playlist as one row in its card. The heading above
 * names it, so the row gives the count, which is what changes.
 */
@Composable
fun LikedPlaylistRow(playlist: PlaylistSummary, onOpen: () -> Unit) {
    CardRow(onClick = onOpen) {
        Cover(
            seed = playlist.key.stableId,
            modifier = Modifier.size(RowSpec.coverFeature),
            shape = MaterialTheme.shapes.medium,
            url = playlist.coverUrl,
        )
        Spacer(Modifier.width(RowSpec.leadGap))
        RowText(
            likedRowTitle(playlist.title),
            subtitle = "${playlist.trackCount} 首",
            titleStyle = MaterialTheme.typography.titleMedium,
            subtitleStyle = MaterialTheme.typography.bodySmall.tabular,
        )
        Spacer(Modifier.width(RowSpec.chevronGap))
        RowChevron()
    }
}
