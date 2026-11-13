package tech.musxi.player.lockscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.localaudio.LocalAudioItem
import tech.musxi.core.localaudio.LocalAudioMetadata
import tech.musxi.core.localaudio.LocalImportMode
import tech.musxi.core.lyrics.LyricLine
import tech.musxi.core.lyrics.LyricsState
import tech.musxi.core.lyrics.LyricsStatus
import tech.musxi.core.wallpaper.SavedWallpaper
import tech.musxi.core.wallpaper.WallpaperAsset
import tech.musxi.core.wallpaper.WallpaperFormat
import tech.musxi.core.wallpaper.WallpaperFrame
import tech.musxi.core.wallpaper.WallpaperImageInfo
import tech.musxi.core.wallpaper.WallpaperPreferences
import tech.musxi.playback.PlaybackState

class LockScreenLyricsContentTest {
    private val queue = MediaKey(Platform.QQ, "queue-id")
    private val source = MediaKey(Platform.NETEASE, "source-id")
    private val otherSource = MediaKey(Platform.KUGOU, "other-source")
    private val nextQueue = MediaKey(Platform.QQ, "next-queue-id")

    private val playback = PlaybackState(
        connected = true, playing = true, buffering = false, mediaId = queue.stableId, playWhenReady = true,
        positionMs = 42_000, durationMs = 269_000, hasNext = true, hasPrevious = true,
        title = "晴天", artist = "周杰伦", coverUrl = "https://y.gtimg.cn/a.jpg",
        sourceKey = source, positionSampledAtElapsedMs = 5_000, playbackSpeed = 1.25f,
    )
    private val lyrics = timedFor(queue, source)

    private fun timedFor(queueKey: MediaKey, sourceKey: MediaKey) = LyricsState(
        sourceKey = sourceKey, status = LyricsStatus.TIMED,
        lines = listOf(LyricLine(0, "第一行"), LyricLine(5_000, "第二行")),
        activeIndex = 0, offsetMs = 120, queueKey = queueKey,
    )

    private fun content(
        playback: PlaybackState = this.playback,
        lyrics: LyricsState = this.lyrics,
        waiting: Boolean = false,
        wallpaper: SavedWallpaper = SavedWallpaper(),
    ) = LockScreenLyricsContent.from(playback, lyrics, waiting, wallpaper)

    @Test fun matchedDocumentCarriesSongFenceAndLivePosition() {
        val value = content()
        assertTrue(value.identified)
        assertEquals("晴天", value.title)
        assertEquals("周杰伦", value.artist)
        assertEquals("https://y.gtimg.cn/a.jpg", value.coverUrl)
        assertFalse(value.local)
        assertTrue(value.hasTimedLyrics)
        assertSame(lyrics, value.lyrics)
        assertEquals(120, value.lyrics.offsetMs)
        assertEquals(LockScreenPosition(42_000, 5_000, 1.25f, true), value.position)
        assertEquals(269_000, value.durationMs)
        assertTrue(value.playing)
        assertTrue(value.hasPrevious)
        assertTrue(value.hasNext)
    }

    @Test fun anotherSongClearsLyricsButKeepsPlaybackTruthful() {
        val switched = content(playback = playback.copy(mediaId = nextQueue.stableId, title = "七里香"))
        assertEquals("七里香", switched.title)
        assertEquals(LyricsState(), switched.lyrics)
        assertFalse(switched.hasTimedLyrics)
        assertTrue(switched.playing)
        assertTrue(switched.hasNext)
        // A stale document for the queue in view, say after an account change, is dropped too.
        assertEquals(LyricsState(), content(lyrics = lyrics.copy(queueKey = null)).lyrics)
    }

    @Test fun anotherSourceOrPendingSearchClearsLyrics() {
        assertEquals(LyricsState(), content(lyrics = lyrics.copy(sourceKey = otherSource)).lyrics)
        assertEquals(LyricsState(), content(playback = playback.copy(sourceKey = null)).lyrics)
        assertEquals(LyricsState(), content(waiting = true).lyrics)
    }

    @Test fun localRecordingAndUntimedDocumentsShowNoFollowableLyrics() {
        val local = LocalAudioItem(id = LOCAL_ID, metadata = LocalAudioMetadata(fileName = "book.m4a", title = "第一章"),
            mode = LocalImportMode.COPY, addedAtMs = 0)
        val localPlayback = playback.copy(mediaId = "local:$LOCAL_ID", localAudio = local,
            coverUrl = "file:///data/user/0/tech.musxi.player/cache/a.jpg", sourceKey = null)
        val value = content(playback = localPlayback)
        assertTrue(value.local)
        assertEquals("file:///data/user/0/tech.musxi.player/cache/a.jpg", value.coverUrl)
        assertEquals(LyricsState(), value.lyrics)
        assertFalse(value.hasTimedLyrics)
        // The play/pause and skip controls stay usable for a recording and for plain text alike.
        assertTrue(value.playing)
        assertTrue(value.hasNext)
        // Untimed or empty rows for this same song are kept as the follower's truth, but nothing is
        // followable, so the page shows the cover and the controls it always has.
        assertFalse(content(lyrics = lyrics.copy(status = LyricsStatus.PLAIN)).hasTimedLyrics)
        assertFalse(content(lyrics = lyrics.copy(status = LyricsStatus.EMPTY)).hasTimedLyrics)
        assertFalse(content(lyrics = lyrics.copy(status = LyricsStatus.LOADING)).hasTimedLyrics)
        // A timed document with no rows is nothing to follow.
        assertFalse(content(lyrics = lyrics.copy(lines = emptyList())).hasTimedLyrics)
    }

    @Test fun disconnectedPlayerDoesNotKeepTitleOrCover() {
        val value = content(playback = playback.copy(connected = false, playing = false))
        assertEquals("", value.title)
        assertEquals("", value.artist)
        assertNull(value.coverUrl)
        assertFalse(value.identified)
        assertEquals(LyricsState(), value.lyrics)
        assertFalse(value.playing)
        assertEquals(LockScreenPosition(42_000, 5_000, 1.25f, false), value.position)
        assertFalse(value.position.canExtrapolate)
        // Connected but with no media item is the same "nothing on screen" case.
        val idle = content(playback = playback.copy(mediaId = null))
        assertFalse(idle.identified)
        assertEquals("", idle.title)
        assertNull(idle.coverUrl)
        assertEquals(LyricsState(), idle.lyrics)
    }

    @Test fun positionSampleIsCopiedWithoutExtrapolatingOrResampling() {
        val paused = content(playback = playback.copy(playing = false, positionMs = 42_000,
            positionSampledAtElapsedMs = 5_000))
        assertEquals(LockScreenPosition(42_000, 5_000, 1.25f, false), paused.position)
        assertEquals(paused.position, content(playback = playback.copy(playing = false,
            positionMs = 42_000, positionSampledAtElapsedMs = 5_000)).position)
        val buffering = content(playback = playback.copy(buffering = true))
        assertTrue(buffering.buffering)
        assertFalse(buffering.position.canExtrapolate)
    }

    @Test fun savedWallpaperPassesThroughWithStrengthAndFrame() {
        val saved = SavedWallpaper(asset = WALLPAPER, preferences = WallpaperPreferences(
            enabled = true, strength = 150, frame = WallpaperFrame(centerX = 0.2f, centerY = 0.8f, zoom = 3.5f)))
        val value = content(wallpaper = saved)
        assertSame(saved, value.wallpaper)
        assertEquals(150, value.wallpaper.preferences.strength)
        assertEquals(WallpaperFrame(0.2f, 0.8f, 3.5f), value.wallpaper.preferences.frame)
        assertEquals(WALLPAPER, value.wallpaper.asset)
    }

    @Test fun switchedOffWallpaperKeepsItsImageForThePageToSkip() {
        val off = SavedWallpaper(asset = WALLPAPER,
            preferences = WallpaperPreferences(enabled = false, strength = 150, frame = WallpaperFrame(zoom = 2f)))
        val value = content(wallpaper = off)
        assertEquals(WALLPAPER, value.wallpaper.asset)
        assertFalse(value.wallpaper.preferences.enabled)
        assertEquals(150, value.wallpaper.preferences.strength)
        assertEquals(2f, value.wallpaper.preferences.frame.zoom)
        // Without a saved image the page reuses the cover's gradient; nothing is invented here.
        assertNull(content(wallpaper = SavedWallpaper()).wallpaper.asset)
    }

    private companion object {
        const val LOCAL_ID = "2f0d8f4e-1a9b-4c3d-8e7f-0a1b2c3d4e5f"
        val WALLPAPER = WallpaperAsset(
            id = "0123456789abcdef".repeat(4),
            info = WallpaperImageInfo(WallpaperFormat.JPEG, 1080, 2400),
            bytes = 4096,
        )
    }
}
