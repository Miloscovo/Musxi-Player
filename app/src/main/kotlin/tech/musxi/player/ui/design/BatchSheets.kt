package tech.musxi.player.ui.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.PlaylistKind
import tech.musxi.core.library.PlaylistSummary
import tech.musxi.core.library.SongSummary
import tech.musxi.core.lyrics.LyricLine
import tech.musxi.core.lyrics.LyricsBinding
import tech.musxi.core.lyrics.LyricsMatchState
import tech.musxi.core.lyrics.ParsedLyrics
import tech.musxi.core.playback.FailureReason
import tech.musxi.core.playback.SourceSelectionPhase
import tech.musxi.core.playback.SourceSelectionState
import tech.musxi.player.ui.account.AboutSection
import tech.musxi.player.ui.account.AboutUpdateUi
import tech.musxi.player.ui.account.DownloadUi
import tech.musxi.player.ui.account.ReleaseUi
import tech.musxi.player.ui.account.UpdateActions
import tech.musxi.player.ui.account.noteLines
import tech.musxi.player.ui.library.LibraryFilter
import tech.musxi.player.ui.library.PlaylistUiState
import tech.musxi.player.ui.player.LyricsMatchSheet
import tech.musxi.player.ui.player.LyricsMatchUi
import tech.musxi.player.ui.player.PlayerStatus
import tech.musxi.player.ui.player.PlayerUi
import tech.musxi.player.ui.player.QueueRowUi
import tech.musxi.player.ui.player.QueueUi
import tech.musxi.player.ui.player.lyricsMatchUiOf
import tech.musxi.player.ui.player.sourcePanelUiOf
import tech.musxi.player.ui.shell.DockNoticeUi
import tech.musxi.player.ui.shell.ShellDock
import tech.musxi.player.ui.shell.ShellTab
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.ThemeMode

/*
 * Review sheets for the batch of new screens: about/update status, queue order vs. shuffle,
 * source-failure panel, player title line, dock notices, library grouping, lyrics matching,
 * and large font. Every string is a neutral placeholder.
 */

/** Account page with only the About section, on the page background. */
@Composable
fun AboutBatchSheet(accent: Accent, mode: ThemeMode, ui: AboutUpdateUi) {
    MusxiTheme(accent = accent, mode = mode) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 16.dp)) {
                AboutSection(version = "v0.2.0", ui = ui, actions = UpdateActions())
            }
        }
    }
}

/** Dock over the library page, with an optional notice above the mini player. */
@Composable
fun DockBatchSheet(accent: Accent, mode: ThemeMode, notice: DockNoticeUi?) {
    MusxiTheme(accent = accent, mode = mode) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.weight(1f))
                Column(Modifier.padding(horizontal = Space.page)) {
                    ShellDock(
                        mini = sampleMiniPlaying(),
                        selected = ShellTab.LIBRARY,
                        onSelect = {},
                        notice = notice,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/** Source panel over the player, with the original track on Kugou and the search failed. */
@Composable
fun SourceFailedBatchSheet(accent: Accent, mode: ThemeMode) {
    SourcePanelSheet(accent, mode, batchPlayer().copy(
        queuePlatform = Platform.KUGOU,
        status = PlayerStatus.FAILED,
        playing = false,
        swapSearching = false,
        failureReason = FailureReason.MEMBERSHIP_REQUIRED,
    ), batchSourceFailedPanel())
}

/** Lyrics matching sheet for a ready [LyricsMatchUi]. */
@Composable
fun LyricsBatchSheet(accent: Accent, mode: ThemeMode, ui: LyricsMatchUi) {
    MusxiTheme(accent = accent, mode = mode) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LyricsMatchSheet(ui = ui)
        }
    }
}

/** Runs [content] at font scale 2.0 on an xhdpi density, the same ratio the test qualifier uses. */
@Composable
fun LargeFontBatch(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalDensity provides Density(2f, fontScale = 2f),
        content = content,
    )
}

// --- samples ---------------------------------------------------------------------------------

private val batchOriginal = SongSummary(
    key = MediaKey(Platform.NETEASE, "b-1"),
    title = "星河回声",
    artists = listOf("林远"),
    album = "夜航",
    durationMs = 241_000,
)

/** Eight-track queue; the current row is index 2. */
private val batchTracks = listOf(
    "星河回声" to "林远",
    "潮汐" to "陈叙",
    "晚风" to "林远",
    "远山" to "许知",
    "灯火" to "陈叙",
    "北岸" to "许知",
    "雨巷" to "林远",
    "长亭" to "陈叙",
)

private fun batchRows() = batchTracks.mapIndexed { i, (title, artist) ->
    QueueRowUi(key = "b-$i", title = title, artist = artist, durationMs = 180_000L + i * 17_000L)
}

/** Plain list order: the cyclic timeline from the current row, so the panel shows it as a normal queue. */
fun batchQueueOrdered() = QueueUi(
    items = batchRows(),
    currentIndex = 2,
    upcoming = listOf(3, 4, 5, 6, 7, 0, 1),
)

/** Shuffled: the up-next rows come from a shuffled order, so the panel shows the shuffle header. */
fun batchQueueShuffled() = QueueUi(
    items = batchRows(),
    currentIndex = 2,
    upcoming = listOf(6, 3, 0, 7, 4),
)

/** The player behind the queue and source sheets. */
fun batchPlayer() = PlayerUi(
    title = "星河回声",
    artist = "林远",
    album = "夜航",
    coverSeed = "星河回声",
    queuePlatform = Platform.NETEASE,
    positionMs = 60_000,
    durationMs = 241_000,
    playing = true,
    status = PlayerStatus.NORMAL,
    queueIndex = 2,
    queueSize = 8,
    hasPrevious = true,
    hasNext = true,
)

/** Title line: the track plays from another platform, so the actual source line is shown. */
fun batchPlayerSourceLine() = batchPlayer().copy(
    queuePlatform = Platform.KUGOU,
    sourcePlatform = Platform.NETEASE,
    actualSourceLine = "实际播放：《星河回声》· 林远 · 网易云",
)

/** Title line when playback failed with a known reason and nothing is searching. */
fun batchPlayerFailed() = batchPlayer().copy(
    queuePlatform = Platform.KUGOU,
    status = PlayerStatus.FAILED,
    playing = false,
    failureReason = FailureReason.MEMBERSHIP_REQUIRED,
)

private const val BATCH_NOW = 1_000_000L

/** Kugou failed with membership, NetEase copyright, QQ returned nothing. */
fun batchSourceFailedPanel() = sourcePanelUiOf(
    SourceSelectionState(
        phase = SourceSelectionPhase.FAILED,
        original = batchOriginal.copy(key = MediaKey(Platform.KUGOU, "b-k1")),
        candidates = emptyList(),
        error = "no_candidates",
        failureReasons = mapOf(
            Platform.KUGOU to FailureReason.MEMBERSHIP_REQUIRED,
            Platform.NETEASE to FailureReason.COPYRIGHT_RESTRICTED,
        ),
    ),
    BATCH_NOW,
)

/** The About group of a release build: [checking] shows the arc, [note] the check row's text. */
fun batchAbout(checking: Boolean = false, note: String? = null, release: ReleaseUi? = null) = AboutUpdateUi(
    developmentBuild = false, checking = checking, checkNote = note, release = release,
    autoCheck = true, badge = release != null,
)

/** A newer release with four lines of notes; the About group shows it as one row. */
fun batchAvailable() = batchAbout(
    release = ReleaseUi(
        version = "0.2.1",
        title = "发现新版本 v0.2.1",
        meta = "2026年10月28日 · 18.1 MB",
        notes = noteLines("修复歌单缓存刷新后不更新的问题\n换源面板显示每个平台的失败原因\n队列在随机播放时不再显示拖动把手\n歌词匹配支持预览后再绑定"),
        releasePage = "https://github.com/qwt1011/Musxi-Player-Android/releases/tag/v0.2.1",
        ignored = false,
        metered = false,
        confirmText = "正在使用移动数据，约 18 MB，继续下载？",
        download = DownloadUi.Idle(canDownload = true),
    ),
)

/** Dock notice for a failed source, with a reason. */
fun batchDockFailed() = DockNoticeUi("没找到能播的版本 · 需要会员", "查看", busy = false)

/** Dock notice while another platform is being searched. */
fun batchDockSearching() = DockNoticeUi("当前音源不可用，正在其他平台找…", null, busy = true)

/** Library with created and collected playlists, so both group headings appear. */
fun batchLibraryGrouped() = PlaylistUiState(
    filter = LibraryFilter.NETEASE,
    items = listOf(
        batchPlaylist("夜读", 41, PlaylistKind.CREATED),
        batchPlaylist("通勤", 63, PlaylistKind.CREATED),
        batchPlaylist("雨天合集", 28, PlaylistKind.COLLECTED),
        batchPlaylist("海边慢歌", 120, PlaylistKind.COLLECTED),
    ),
    accountVerified = true,
    fromCache = true,
)

private fun batchPlaylist(title: String, count: Int, kind: PlaylistKind) = PlaylistSummary(
    key = MediaKey(Platform.NETEASE, "bp-${title.hashCode()}"),
    title = title,
    coverUrl = null,
    trackCount = count,
    kind = kind,
)

private val lyricsOriginal = batchOriginal.key

private val lyricsCandidates = listOf(
    SongSummary(MediaKey(Platform.NETEASE, "l-1"), "星河回声", listOf("林远"), "夜航", 241_000),
    SongSummary(MediaKey(Platform.QQ, "l-2"), "星河回声 (现场)", listOf("林远"), "现场", 268_000),
    SongSummary(MediaKey(Platform.KUGOU, "l-3"), "星河回声 (伴奏)", listOf("林远"), "夜航", 241_000),
)

private val sampleLines = listOf(
    LyricLine(0, "第一句歌词占位"),
    LyricLine(4_000, "第二句歌词占位"),
    LyricLine(9_000, "第三句歌词占位"),
)

private fun lyricsSample(state: LyricsMatchState) =
    lyricsMatchUiOf(state, title = "星河回声", artist = "林远")

/** Candidates, the first one bound and previewed with its lyrics. */
fun lyricsBound() = lyricsSample(
    LyricsMatchState(
        originalKey = lyricsOriginal,
        candidates = lyricsCandidates,
        previewKey = lyricsCandidates[0].key,
        preview = ParsedLyrics(lines = sampleLines, plainText = ""),
        binding = LyricsBinding(
            originalKey = lyricsOriginal,
            candidateKey = lyricsCandidates[0].key,
            offsetMs = 0,
            candidateOwner = "a".repeat(64),
            candidateGeneration = 1,
        ),
    ),
)

/** Second candidate is loading its lyrics. */
fun lyricsPreviewing() = lyricsSample(
    LyricsMatchState(
        originalKey = lyricsOriginal,
        candidates = lyricsCandidates,
        previewKey = lyricsCandidates[1].key,
        previewing = true,
    ),
)

/** Search returned nothing. */
fun lyricsNoResults() = lyricsSample(
    LyricsMatchState(originalKey = lyricsOriginal, candidates = emptyList()),
)

/** Search failed with a network error. */
fun lyricsError() = lyricsSample(
    LyricsMatchState(originalKey = lyricsOriginal, error = "network"),
)
