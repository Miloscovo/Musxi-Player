package tech.musxi.player.ui.design

import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.PlaylistSummary
import tech.musxi.core.library.SongSummary
import tech.musxi.player.ui.library.LibraryFilter
import tech.musxi.player.ui.library.PlaylistUiState
import tech.musxi.player.ui.library.TrackListUiState
import tech.musxi.player.ui.player.MiniPlayerUi

/**
 * Sample data shared by every sheet.
 *
 * Each sheet used to invent its own mini player and its own playlist rows, so the same product
 * appeared with different subtitles and different row heights depending on which file you opened.
 * One source, one appearance.
 */
fun sampleMiniPlaying() = MiniPlayerUi(
    visible = true,
    title = "晴天",
    subtitle = "周杰伦",
    playing = true,
    progress = 0.42f,
    hasPrevious = true,
    hasNext = true,
)

fun sampleMiniIdle() = MiniPlayerUi()

/** Playing, with a swapped source: the subtitle then carries the source note. */
fun miniPlayingSwapped() = sampleMiniPlaying().copy(
    subtitle = "周杰伦 · 网易云音源",
    sourcePlatform = Platform.NETEASE,
)

/** Interrupted by another app: the button must read as play, and the line must say why. */
fun miniInterrupted() = sampleMiniPlaying().copy(
    title = "夜的第七章",
    subtitle = "已被其他应用暂停",
    playing = false,
    progress = 0.68f,
)

/** Buffering: static arc in the button, one line of explanation. */
fun miniBuffering() = sampleMiniPlaying().copy(
    title = "枫",
    subtitle = "缓冲中",
    playing = false,
    buffering = true,
    progress = 0.12f,
    hasNext = false,
)

/** Nothing loaded: the dock collapses to the tab row. */
fun miniHidden() = MiniPlayerUi()

fun samplePlaylists(): List<PlaylistSummary> = listOf(
    playlist("我喜欢的音乐", 746, Platform.NETEASE),
    playlist("私人雷达", 28, Platform.NETEASE),
    playlist("周杰伦 · 全专辑精选合集（2000-2024 重制版）", 412, Platform.NETEASE),
    playlist("夜读", 41, Platform.NETEASE),
    playlist("通勤", 63, Platform.NETEASE),
    playlist("国语九十年代", 128, Platform.NETEASE),
)

fun sampleLibraryLoaded() = PlaylistUiState(
    filter = LibraryFilter.NETEASE,
    items = samplePlaylists(),
    accountVerified = true,
    fromCache = true,
)

private fun playlist(title: String, count: Int, platform: Platform) = PlaylistSummary(
    key = MediaKey(platform, "p-${title.hashCode()}"),
    title = title,
    coverUrl = null,
    trackCount = count,
)

/**
 * Tracks for the detail page.
 *
 * Titles and lengths are real ones on purpose: a row list of "歌曲 1 / 歌曲 2" hides exactly the
 * problems this sheet exists to find — a long title, a missing album, a track that runs over four
 * minutes next to one that runs under it.
 */
fun sampleSongs(): List<SongSummary> = listOf(
    song("晴天", "叶惠美", 4, 29),
    song("夜的第七章", "依然范特西", 4, 35),
    // No album: the subtitle must fall back to the artist alone instead of printing a separator.
    song("枫", "", 4, 37),
    song("稻香", "魔杰座", 3, 43),
    song("告白气球", "周杰伦的床边故事", 3, 35),
    song("七里香", "七里香", 4, 58),
    song("青花瓷", "我很忙", 3, 59),
    song("千里之外", "依然范特西", 4, 15),
    song("不能说的秘密", "不能说的秘密 电影原声带", 4, 56),
    song("以父之名", "叶惠美", 5, 42),
)

/** The playlist the detail page belongs to: one page read, more to come. */
fun trackListSample() = TrackListUiState(
    playlist = playlist("我喜欢的音乐", 746, Platform.NETEASE),
    items = sampleSongs(),
    nextOffset = 40,
    accountVerified = true,
    fromCache = true,
)

/**
 * Tracks for the search sheet. A different act from the playlist on purpose: search results and a
 * saved playlist are different scenes, and sharing one list made both sheets look like one page.
 */
fun searchSampleSongs(): List<SongSummary> = listOf(
    song("1173100", "夜に駆ける", listOf("YOASOBI"), "THE BOOK", 261_000),
    song("1815301", "群青", listOf("YOASOBI"), "THE BOOK", 251_000),
    song("1409445", "ハルジオン", listOf("YOASOBI"), "THE BOOK", 210_000),
    song("2058267", "あの夢をなぞって", listOf("YOASOBI"), "THE BOOK", 240_000),
    song("1409451", "怪物", listOf("YOASOBI"), "怪物", 206_000),
    song("1409453", "三原色", listOf("YOASOBI"), "THE BOOK 2", 197_000),
)

private fun song(id: String, title: String, artists: List<String>, album: String, durationMs: Long) =
    SongSummary(
        key = MediaKey(Platform.NETEASE, id),
        title = title,
        artists = artists,
        album = album,
        durationMs = durationMs,
    )

private fun song(title: String, album: String, minutes: Int, seconds: Int) = SongSummary(
    key = MediaKey(Platform.NETEASE, "s-${title.hashCode()}"),
    title = title,
    artists = listOf("周杰伦"),
    album = album,
    durationMs = (minutes * 60 + seconds) * 1000L,
)
