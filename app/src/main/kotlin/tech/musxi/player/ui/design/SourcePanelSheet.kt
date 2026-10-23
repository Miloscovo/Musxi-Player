package tech.musxi.player.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary
import tech.musxi.core.playback.SourceCandidate
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
 * Source panel sheet, composed the way it really appears: the player behind a scrim, the panel on
 * top. Rendering the panel alone would hide the one thing the design leans on — that the user can
 * still see which track is in trouble.
 */
@Composable
fun SourcePanelSheet(accent: Accent, mode: ThemeMode, player: PlayerUi, panel: SourcePanelUi) {
    MusxiTheme(accent = accent, mode = mode) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            FullPlayer(ui = player, dark = mode != ThemeMode.LIGHT)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                SourcePanel(ui = panel, dark = mode != ThemeMode.LIGHT)
            }
        }
    }
}

// --- samples ---------------------------------------------------------------------------------

private val original = SongSummary(
    key = MediaKey(Platform.NETEASE, "1173100"),
    title = "晴天",
    artists = listOf("周杰伦"),
    album = "叶惠美",
    durationMs = 269_000,
)

private fun candidate(id: String, platform: Platform, album: String, durationMs: Long) =
    SourceCandidate(
        song = SongSummary(
            key = MediaKey(platform, id),
            title = "晴天",
            artists = listOf("周杰伦"),
            album = album,
            durationMs = durationMs,
        ),
        accountGeneration = 1,
    )

/** Two plausible candidates, the first of which is an exact-length match. */
private val candidates = listOf(
    candidate("k1", Platform.KUGOU, "叶惠美", 269_400),
    candidate("q1", Platform.QQ, "叶惠美", 271_000),
    // A live take: same title, clearly longer. This is the case the duration note exists for.
    candidate("k2", Platform.KUGOU, "魔天伦 世界巡回演唱会", 302_000),
)

private fun state(
    phase: SourceSelectionPhase,
    list: List<SourceCandidate> = emptyList(),
    automaticIndex: Int? = null,
    deadline: Long? = null,
    partial: Boolean = false,
    error: String? = null,
) = SourceSelectionState(
    phase = phase,
    original = original,
    candidates = list,
    automaticIndex = automaticIndex,
    deadlineElapsedMs = deadline,
    partial = partial,
    error = error,
)

/** The player behind the sheet: this track is the one that failed. */
fun playerForPanel() = PlayerUi(
    title = "晴天",
    artist = "周杰伦",
    album = "叶惠美",
    coverSeed = "晴天",
    queuePlatform = Platform.NETEASE,
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

/** Searching: placeholders, no counts yet. */
fun panelSearching() = sourcePanelUiOf(state(SourceSelectionPhase.SEARCHING), NOW)

/** Choosing with a live countdown: two seconds into the five-second wait. */
fun panelChoosing() = sourcePanelUiOf(
    state(
        SourceSelectionPhase.CHOOSING,
        list = candidates,
        automaticIndex = 0,
        deadline = NOW + 3_000L,
    ),
    NOW,
)

/** Choosing, but a platform did not answer: the list may be incomplete. */
fun panelChoosingPartial() = sourcePanelUiOf(
    state(
        SourceSelectionPhase.CHOOSING,
        list = candidates.take(2),
        automaticIndex = 0,
        deadline = NOW + 3_000L,
        partial = true,
    ),
    NOW,
)

/** Nothing found at all. */
fun panelFailed() = sourcePanelUiOf(
    state(SourceSelectionPhase.FAILED, error = "no_candidates"),
    NOW,
)

/** Nothing found, but not every platform answered. */
fun panelFailedPartial() = sourcePanelUiOf(
    state(SourceSelectionPhase.FAILED, partial = true, error = "no_candidates"),
    NOW,
)
