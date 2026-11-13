package tech.musxi.player.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.LibrarySource
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.PlaylistSummary
import tech.musxi.core.library.SongSummary
import tech.musxi.core.recommendation.RecommendationKind
import tech.musxi.core.recommendation.RecommendationState
import tech.musxi.player.ui.components.ALL_LINES
import tech.musxi.player.ui.components.CardRow
import tech.musxi.player.ui.components.Cover
import tech.musxi.player.ui.components.ProgressArc
import tech.musxi.player.ui.components.RowSpec
import tech.musxi.player.ui.components.RowText
import tech.musxi.player.ui.components.sectionCardColor
import tech.musxi.player.ui.theme.tabular

/** What the recommendation module on the library home shows for one platform. */
data class RecommendationCardUi(
    /** 每日推荐 or 猜你喜欢: the module heading, never repeated inside the card. */
    val heading: String,
    val phase: RecommendationPhase,
    val songs: List<SongSummary> = emptyList(),
    /** An old list kept on screen: a cache from earlier, or an account waiting to be checked. */
    val stale: Boolean = false,
    /** A network read is running over a list that is already there. */
    val refreshing: Boolean = false,
    /** Why a first read failed; only for [RecommendationPhase.FAILED]. */
    val failure: String? = null,
)

enum class RecommendationPhase { LOADING, READY, EMPTY, FAILED }

/** The platform's own word for it: 网易云 and 酷狗 have a daily list, QQ a personal radio (contract, 11-12). */
internal fun recommendationHeading(kind: RecommendationKind): String = when (kind) {
    RecommendationKind.DAILY_SONGS -> "每日推荐"
    RecommendationKind.PERSONAL_RADIO -> "猜你喜欢"
}

/**
 * Folds the backend state into the card, or null when the module stays away: no verified account
 * and nothing cached means the platform's connect card is the next step, not a recommendation.
 */
internal fun recommendationCardOf(state: RecommendationState?): RecommendationCardUi? {
    if (state == null) return null
    val heading = recommendationHeading(state.kind)
    val snapshot = state.snapshot
    if (snapshot == null) {
        if (!state.accountVerified) return null
        val phase = when {
            state.loading -> RecommendationPhase.LOADING
            state.error != null && state.error != "cancelled" -> RecommendationPhase.FAILED
            // Opened but not yet answered (a cancel left it idle): the skeleton keeps the layout.
            else -> RecommendationPhase.LOADING
        }
        return RecommendationCardUi(heading, phase, failure = state.error?.let(::recommendationFailureText))
    }
    return RecommendationCardUi(
        heading = heading,
        phase = if (snapshot.songs.isEmpty()) RecommendationPhase.EMPTY else RecommendationPhase.READY,
        songs = snapshot.songs,
        stale = state.stale,
        refreshing = state.refreshing,
    )
}

/**
 * The list page behind the card, shaped as the playlist page's state so the same page draws it.
 * The summary is for display only: its key names no real playlist and must never reach
 * `playPlaylist` (contract, 11-12); playing goes through `recommendations.play` with the revision.
 */
internal fun recommendationTracksOf(platform: Platform, state: RecommendationState?): TrackListUiState {
    val snapshot = state?.snapshot
    val songs = snapshot?.songs.orEmpty()
    return TrackListUiState(
        playlist = PlaylistSummary(
            key = MediaKey(platform, "recommendations"),
            title = state?.let { recommendationHeading(it.kind) } ?: "每日推荐",
            coverUrl = songs.firstOrNull()?.coverUrl,
            trackCount = songs.size,
        ),
        items = songs,
        loading = state?.loading == true && snapshot == null,
        // Only a first read that failed is a failure here; a failed refresh keeps the list and says
        // so in a notice instead.
        error = state?.error?.takeIf { snapshot == null && it != "cancelled" },
        fromCache = state?.source == LibrarySource.CACHE,
        fetchedAtEpochMs = snapshot?.fetchedAtEpochMs,
        accountVerified = state?.accountVerified == true,
        storageError = state?.storageError == true,
        refreshing = state?.refreshing == true,
    )
}

/**
 * What a refresh the user asked for ended with, once it has settled: null when it worked or was
 * only cancelled. A local rate limit says how long to wait, rounded up to whole seconds.
 */
internal fun recommendationRefreshNotice(state: RecommendationState): String? {
    val error = state.error ?: return null
    if (error == "cancelled") return null
    if (error == "rate" && state.retryAfterMs > 0) {
        return "刚刷新过，${(state.retryAfterMs + 999) / 1000} 秒后再试"
    }
    return recommendationFailureText(error)
}

/** Why a play from the recommendation list did not start. */
internal fun recommendationPlayFailureText(error: Throwable): String =
    when ((error as? LibraryPageFailure)?.category) {
        "stale_revision" -> "推荐已更新，请重新选择"
        "recommendations_disabled" -> "每日推荐已关闭"
        "account_unavailable", "expired", "identity", "account_changed" -> "账号需要重新验证"
        else -> "没能开始播放，请稍后重试"
    }

/** One sentence per backend category; never the raw code. */
internal fun recommendationFailureText(error: String): String = when (error) {
    "network", "timeout" -> "网络不太稳，稍后再试"
    "expired", "account_unavailable", "identity", "account_changed" -> "账号需要重新验证"
    "risk" -> "平台暂时拦截了请求，稍后再试"
    "rate", "limit" -> "刚刷新过，请稍后再试"
    "permission" -> "这个账号暂时看不到推荐"
    else -> "平台暂时没有响应"
}

/**
 * "YOASOBI、周杰伦 等": who is in the list, in the order the platform gave. Two names, so the
 * "等" that says there are more still fits on one line at 360dp instead of being cut off.
 */
internal fun recommendationArtists(songs: List<SongSummary>, limit: Int = 2): String {
    val names = songs.asSequence().flatMap { it.artists.asSequence() }
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(limit + 1).toList()
    if (names.isEmpty()) return "多位歌手"
    return names.take(limit).joinToString("、") + if (names.size > limit) " 等" else ""
}

/**
 * The recommendation module's one row, shaped like the 我喜欢 row below it (the owner's choice A,
 * 11-12): a stack of two covers, who is in the list, how many songs, and a round play button.
 * The row opens the list; the button plays it all. Every state stays one row high, so the home page
 * does not jump while a list arrives or fails.
 */
@Composable
fun RecommendationRow(
    ui: RecommendationCardUi,
    onOpen: () -> Unit,
    onPlayAll: () -> Unit,
    onRetry: () -> Unit,
) {
    when (ui.phase) {
        RecommendationPhase.LOADING -> CardRow(onClick = null) {
            SkeletonStack()
            Spacer(Modifier.width(RowSpec.leadGap))
            Column(Modifier.weight(1f).semantics { contentDescription = "正在读取${ui.heading}" }) {
                SkeletonBar(0.62f, 14)
                Spacer(Modifier.height(8.dp))
                SkeletonBar(0.34f, 10)
            }
        }
        RecommendationPhase.EMPTY -> CardRow(onClick = null) {
            SkeletonStack(quiet = true)
            Spacer(Modifier.width(RowSpec.leadGap))
            RowText(
                if (ui.heading == "每日推荐") "今天没有推荐" else "暂时没有推荐",
                subtitle = "平台这次没有给出歌曲",
                titleStyle = MaterialTheme.typography.titleMedium,
                subtitleLines = ALL_LINES,
            )
        }
        RecommendationPhase.FAILED -> CardRow(onClick = null) {
            SkeletonStack(quiet = true)
            Spacer(Modifier.width(RowSpec.leadGap))
            RowText(
                "推荐没能加载",
                subtitle = ui.failure ?: "平台暂时没有响应",
                titleStyle = MaterialTheme.typography.titleMedium,
                subtitleLines = ALL_LINES,
            )
            Spacer(Modifier.width(RowSpec.chevronGap))
            TextButton(onClick = onRetry) {
                Text("重试", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        RecommendationPhase.READY -> CardRow(onClick = onOpen) {
            CoverStack(ui.songs)
            Spacer(Modifier.width(RowSpec.leadGap))
            RowText(
                recommendationArtists(ui.songs),
                subtitle = "${ui.songs.size} 首" + if (ui.stale) " · 可能不是最新" else "",
                titleStyle = MaterialTheme.typography.titleMedium,
                subtitleStyle = MaterialTheme.typography.bodySmall.tabular,
                subtitleLines = ALL_LINES,
            )
            Spacer(Modifier.width(RowSpec.trailGap))
            PlayAllButton(ui.refreshing, ui.heading, onPlayAll)
        }
    }
}

/**
 * Two covers, the second peeking out behind the first, in the same 56dp a single cover takes, so
 * the text lines up with the 继续播放 and 我喜欢 rows above and below.
 */
@Composable
private fun CoverStack(songs: List<SongSummary>) {
    val front = songs.firstOrNull() ?: return
    val back = songs.getOrNull(1)
    val shape = MaterialTheme.shapes.medium
    Box(Modifier.size(RowSpec.coverFeature)) {
        if (back != null) {
            Cover(
                seed = back.key.stableId, url = back.coverUrl, shape = shape,
                modifier = Modifier.size(STACK_COVER).align(Alignment.TopEnd),
            )
            // Pushes the back cover one step behind without a shadow: a veil of the card colour.
            Box(Modifier.size(STACK_COVER).align(Alignment.TopEnd).clip(shape).background(sectionCardColor().copy(alpha = 0.45f)))
        }
        Cover(
            seed = front.key.stableId, url = front.coverUrl, shape = shape,
            modifier = Modifier.size(STACK_COVER).align(Alignment.BottomStart)
                .border(1.5.dp, sectionCardColor(), shape),
        )
    }
}

/** The cover stack's place, empty: grey tiles while loading, an outline when there is nothing. */
@Composable
private fun SkeletonStack(quiet: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val fill = cs.onSurface.copy(alpha = if (quiet) 0.04f else 0.07f)
    Box(Modifier.size(RowSpec.coverFeature)) {
        Box(Modifier.size(STACK_COVER).align(Alignment.TopEnd).clip(shape).background(fill.copy(alpha = fill.alpha * 0.6f)))
        Box(
            Modifier.size(STACK_COVER).align(Alignment.BottomStart).clip(shape)
                .background(sectionCardColor()).background(fill)
                .border(1.5.dp, sectionCardColor(), shape),
        )
    }
}

@Composable
private fun SkeletonBar(fraction: Float, heightDp: Int) {
    Box(
        Modifier.fillMaxWidth(fraction).height(heightDp.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
    )
}

/**
 * Plays the whole list from the top. Unlike 继续播放, the row here opens the list, so the button
 * is a target of its own: 48dp to touch, 40dp to see.
 */
@Composable
private fun PlayAllButton(refreshing: Boolean, heading: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(48.dp).offset(x = 4.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "播放全部", onClick = onClick)
            .semantics { contentDescription = "播放全部$heading" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(RowSpec.action).clip(CircleShape).background(cs.onSurface.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center,
        ) {
            if (refreshing) {
                ProgressArc(color = cs.onSurface, size = RowSpec.actionGlyph, strokeWidth = RowSpec.actionStroke)
            } else {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(RowSpec.actionGlyph), tint = cs.onSurface)
            }
        }
    }
}

private val STACK_COVER = 48.dp
