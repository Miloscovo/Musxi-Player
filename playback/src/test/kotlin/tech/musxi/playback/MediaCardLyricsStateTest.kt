package tech.musxi.playback

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.lyrics.*

class MediaCardLyricsStateTest {
    private val queue = MediaKey(Platform.QQ, "queue")
    private val source = MediaKey(Platform.NETEASE, "source")
    private val playback = PlaybackState(connected = true, mediaId = queue.stableId, sourceKey = source)
    private val lyrics = LyricsState(source, LyricsStatus.TIMED, listOf(LyricLine(0, "first")),
        queueKey = queue, offsetMs = 50)

    @Test fun bridgeAcceptsOnlyReadyDocumentForCurrentQueueAndActualSource() {
        assertEquals(MediaCardLyricsState(true, queue.stableId, source.stableId, lyrics.lines, 50),
            MediaCardLyricsState.from(true, playback, lyrics, false))
        for (invalid in listOf(lyrics.copy(queueKey = source), lyrics.copy(sourceKey = queue),
            lyrics.copy(status = LyricsStatus.LOADING), lyrics.copy(status = LyricsStatus.PLAIN))) {
            assertTrue(MediaCardLyricsState.from(true, playback, invalid, false).lines.isEmpty())
        }
        assertTrue(MediaCardLyricsState.from(true, playback, lyrics, true).lines.isEmpty())
        assertTrue(MediaCardLyricsState.from(true, playback.copy(connected = false), lyrics, false).lines.isEmpty())
        assertFalse(MediaCardLyricsState.from(false, playback, lyrics, false).enabled)
    }

    @Test fun progressTicksAndTranslationChangesDoNotRepublishTheDocument() {
        assertEquals(MediaCardLyricsState.from(true, playback, lyrics, false),
            MediaCardLyricsState.from(true, playback.copy(positionMs = 1_000),
                lyrics.copy(activeIndex = 0, activeWordIndex = 1, activeWordProgress = 0.9f), false))
    }
}
