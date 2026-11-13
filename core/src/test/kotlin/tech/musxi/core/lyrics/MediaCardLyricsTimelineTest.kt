package tech.musxi.core.lyrics

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibraryCacheIdentity
import tech.musxi.core.library.MediaKey

class MediaCardLyricsTimelineTest {
    private val lines = listOf(LyricLine(1_000, "first"), LyricLine(3_000, "second"), LyricLine(5_000, ""))

    @Test fun beforeFirstExactBoundaryBlankLineAndEndUseAbsolutePlayerPosition() {
        assertEquals(MediaCardLyricLine(null, 1_000), MediaCardLyricsTimeline.sample(lines, 0, 0))
        assertEquals(MediaCardLyricLine("first", 3_000), MediaCardLyricsTimeline.sample(lines, 1_000, 0))
        assertEquals(MediaCardLyricLine("second", 5_000), MediaCardLyricsTimeline.sample(lines, 4_999, 0))
        assertEquals(MediaCardLyricLine(null, null), MediaCardLyricsTimeline.sample(lines, 5_000, 0))
        assertEquals(MediaCardLyricLine(null, null), MediaCardLyricsTimeline.sample(emptyList(), 0, 0))
    }

    @Test fun signedManualOffsetMovesBothCurrentLineAndNextWakeup() {
        assertEquals(MediaCardLyricLine(null, 3_000), MediaCardLyricsTimeline.sample(lines, 2_999, 2_000))
        assertEquals(MediaCardLyricLine("first", 5_000), MediaCardLyricsTimeline.sample(lines, 3_000, 2_000))
        assertEquals(MediaCardLyricLine("second", 3_000), MediaCardLyricsTimeline.sample(lines, 1_000, -2_000))
    }

    @Test fun equalTimestampsSelectLastLineAndTitlesAreBoundedWithoutBrokenSurrogates() {
        val duplicate = listOf(LyricLine(0, "old"), LyricLine(0, "new"), LyricLine(2_000, "next"))
        assertEquals(MediaCardLyricLine("new", 2_000), MediaCardLyricsTimeline.sample(duplicate, 0, 0))
        assertEquals("first second", MediaCardLyricsTimeline.displayText(" first\n\tsecond\r "))
        val title = MediaCardLyricsTimeline.displayText("A".repeat(511) + "🎵")
        assertEquals(511, title.length)
        assertFalse(Character.isHighSurrogate(title.last()))
    }

    @Test fun browserPublishesQueueIdentitySeparatelyAndRejectsOldTargetCallbacks() {
        val queue = MediaKey(Platform.QQ, "queue")
        val nextQueue = MediaKey(Platform.QQ, "next")
        val source = MediaKey(Platform.NETEASE, "source")
        val pending = mutableListOf<(Result<ParsedLyrics>) -> Unit>()
        val browser = LyricsBrowser({ LibraryCacheIdentity("owner", 1, true) }) { _, done -> pending += done }
        browser.follow(queue, source, 0)
        assertEquals(queue, browser.state.value.queueKey)
        browser.follow(nextQueue, source, 0)
        pending.first()(Result.success(ParsedLyrics(listOf(LyricLine(0, "old")), "")))
        assertEquals(LyricsStatus.LOADING, browser.state.value.status)
        pending.last()(Result.success(ParsedLyrics(lines, "")))
        assertEquals(nextQueue, browser.state.value.queueKey)
        assertEquals(source, browser.state.value.sourceKey)
        browser.cancel()
        assertNull(browser.state.value.queueKey)
    }
}
