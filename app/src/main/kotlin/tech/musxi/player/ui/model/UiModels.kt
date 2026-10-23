package tech.musxi.player.ui.model

import tech.musxi.core.account.Platform
import tech.musxi.core.library.SongSummary

/**
 * Presentation state for the player chrome. It is derived from PlaybackConnection.state and
 * SourceSelection.state by a ViewModel; screens never hold a second copy of playback truth.
 */
data class NowPlayingUi(
    val song: SongSummary,
    val playlistTitle: String,
    /** Platform that owns the queue item. */
    val queuePlatform: Platform,
    /** Platform actually streaming; differs only after a cross-platform source was chosen. */
    val sourcePlatform: Platform,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val hasPrevious: Boolean = true,
    val hasNext: Boolean = true,
    val status: PlayerStatus? = null,
)

enum class PlayerStatus(val text: String) {
    INTERRUPTED("播放被其他音频暂时打断"),
    ENDED("已播放完"),
    FAILED("播放失败，请检查网络"),
    SEARCHING("原曲暂不可播，正在寻找其他音源"),
    CHOOSING("已找到其他音源，即将自动尝试"),
}

data class CandidateUi(val song: SongSummary, val sameName: Boolean, val tried: Boolean = false)

enum class MainTab(val label: String) { LIBRARY("曲库"), SEARCH("搜索"), ME("我的") }

/**
 * Clock-style duration. Anything past an hour gains an hour field: a 90-minute track must read
 * `1:30:12`, not `90:12`, which looks like a one-minute-forty-eight track.
 */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
