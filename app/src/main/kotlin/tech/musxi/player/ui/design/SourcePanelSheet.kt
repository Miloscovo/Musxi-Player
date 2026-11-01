package tech.musxi.player.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary
import tech.musxi.core.playback.FailureReason
import tech.musxi.core.playback.SourceCandidate
import tech.musxi.core.playback.SourceSearchProgress
import tech.musxi.core.playback.SourceSearchStatus
import tech.musxi.core.playback.SourceSelectionMode
import tech.musxi.core.playback.SourceSelectionPhase
import tech.musxi.core.playback.SourceSelectionState
import tech.musxi.player.ui.player.FullPlayer
import tech.musxi.player.ui.player.PlayerStatus
import tech.musxi.player.ui.player.PlayerUi
import tech.musxi.player.ui.player.SourcePanel
import tech.musxi.player.ui.player.SourcePanelUi
import tech.musxi.player.ui.player.sourcePanelUiOf
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/**
 * Source panel sheet, composed the way it really appears: the player behind, the sheet on top with
 * its own scrim, resting at the middle detent. Rendering the panel alone would hide the one thing
 * the design leans on, that the user can still see which track is in trouble.
 *
 * Like the shell today there is no glass host here, so the sheet draws its solid fallback.
 */
@Composable
fun SourcePanelSheet(accent: Accent, mode: ThemeMode, player: PlayerUi, panel: SourcePanelUi) {
    MusxiTheme(accent = accent, mode = mode) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            FullPlayer(ui = player, dark = mode != ThemeMode.LIGHT)
            SourcePanel(ui = panel, dark = mode != ThemeMode.LIGHT)
        }
    }
}

// --- samples ---------------------------------------------------------------------------------

/** The song the Kugou account cannot play without membership. */
private val original = SongSummary(
    key = MediaKey(Platform.KUGOU, "k-1173100"),
    title = "晴天",
    artists = listOf("周杰伦"),
    album = "叶惠美",
    durationMs = 269_000,
)

private fun candidate(id: String, platform: Platform, title: String, album: String, durationMs: Long) =
    SourceCandidate(
        song = SongSummary(
            key = MediaKey(platform, id),
            title = title,
            artists = listOf("周杰伦"),
            album = album,
            durationMs = durationMs,
        ),
        accountGeneration = 1,
    )

/** Four versions around the original's length: a studio match, a live take (+32s), a short cut (-8s). */
private val candidates = listOf(
    candidate("n1", Platform.NETEASE, "晴天", "叶惠美", 270_000),
    candidate("n2", Platform.NETEASE, "晴天（Live）", "魔天伦 世界巡回演唱会", 301_000),
    candidate("q1", Platform.QQ, "晴天", "叶惠美", 261_000),
    candidate("q2", Platform.QQ, "晴天", "周杰伦的床边故事", 272_000),
)

private fun progress(platform: Platform, status: SourceSearchStatus, count: Int = 0) =
    SourceSearchProgress(platform, status, completedPlatforms = 1, totalPlatforms = 2, candidateCount = count)

private fun state(
    phase: SourceSelectionPhase,
    mode: SourceSelectionMode = SourceSelectionMode.AUTO,
    list: List<SourceCandidate> = emptyList(),
    progress: List<SourceSearchProgress> = emptyList(),
    reasons: Map<Platform, FailureReason> = mapOf(Platform.KUGOU to FailureReason.MEMBERSHIP_REQUIRED),
    partial: Boolean = false,
    error: String? = null,
) = SourceSelectionState(
    revision = 1,
    phase = phase,
    mode = mode,
    original = original,
    candidates = list,
    partial = partial,
    error = error,
    platformProgress = progress,
    failureReasons = reasons,
)

/** The player behind the sheet: this track is the one that failed. */
fun playerForPanel() = PlayerUi(
    title = "晴天",
    artist = "周杰伦",
    album = "叶惠美",
    coverSeed = "晴天",
    queuePlatform = Platform.KUGOU,
    positionMs = 42_000,
    durationMs = 269_000,
    playing = false,
    status = PlayerStatus.FAILED,
    swapSearching = true,
    queueIndex = 2,
    queueSize = 12,
    hasPrevious = true,
    hasNext = true,
)

/**
 * The same page once the search is over. The background used to keep saying "正在换源" while the
 * panel announced "没找到" — two contradicting statements on one screen.
 */
fun playerForPanelFailed() = playerForPanel().copy(swapSearching = false)

private const val NOW = 1_000_000L

/** Searching: NetEase has answered, QQ is still looking, Kugou is the original. */
fun panelSearching() = sourcePanelUiOf(
    state(
        SourceSelectionPhase.SEARCHING,
        progress = listOf(
            progress(Platform.NETEASE, SourceSearchStatus.COMPLETE, count = 2),
            progress(Platform.QQ, SourceSearchStatus.SEARCHING),
        ),
    ),
    NOW,
)

/** Choosing between four versions of the song. */
fun panelChoosing() = sourcePanelUiOf(
    state(
        SourceSelectionPhase.CHOOSING,
        list = candidates,
        progress = listOf(
            progress(Platform.NETEASE, SourceSearchStatus.COMPLETE, count = 2),
            progress(Platform.QQ, SourceSearchStatus.COMPLETE, count = 2),
        ),
    ),
    NOW,
)

/** Choosing, but a platform did not answer: the list may be incomplete. */
fun panelChoosingPartial() = sourcePanelUiOf(
    state(
        SourceSelectionPhase.CHOOSING,
        list = candidates.take(2),
        progress = listOf(
            progress(Platform.NETEASE, SourceSearchStatus.COMPLETE, count = 2),
            progress(Platform.QQ, SourceSearchStatus.FAILURE),
        ),
        partial = true,
    ),
    NOW,
)

/** The user asked for another source: the headline is "换个音源", nothing has failed. */
fun panelSearchingManual() = sourcePanelUiOf(
    state(
        SourceSelectionPhase.SEARCHING,
        mode = SourceSelectionMode.MANUAL,
        progress = listOf(progress(Platform.NETEASE, SourceSearchStatus.SEARCHING)),
        reasons = emptyMap(),
    ),
    NOW,
)

/** Nothing found: NetEase has no rights, QQ returned nothing, Kugou needs membership. */
fun panelFailed() = sourcePanelUiOf(
    state(
        SourceSelectionPhase.FAILED,
        progress = listOf(
            progress(Platform.NETEASE, SourceSearchStatus.COMPLETE),
            progress(Platform.QQ, SourceSearchStatus.COMPLETE),
        ),
        reasons = mapOf(
            Platform.KUGOU to FailureReason.MEMBERSHIP_REQUIRED,
            Platform.NETEASE to FailureReason.COPYRIGHT_RESTRICTED,
        ),
        error = "no_matching_candidates",
    ),
    NOW,
)

/** Nothing found, but not every platform answered. */
fun panelFailedPartial() = sourcePanelUiOf(
    state(
        SourceSelectionPhase.FAILED,
        progress = listOf(
            progress(Platform.NETEASE, SourceSearchStatus.COMPLETE),
            progress(Platform.QQ, SourceSearchStatus.FAILURE),
        ),
        partial = true,
        error = "no_matching_candidates",
    ),
    NOW,
)
