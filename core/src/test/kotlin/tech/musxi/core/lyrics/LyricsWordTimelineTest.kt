package tech.musxi.core.lyrics

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibraryCacheIdentity
import tech.musxi.core.library.MediaKey

class LyricsWordTimelineTest {
    private val words = listOf(LyricWord(0, 3, 1_200, 1_600), LyricWord(3, 5, 1_600, 2_200))
    private fun document() = ParsedLyrics(listOf(LyricLine(1_200, "Hello", words = words)), "Hello",
        LyricsTranslation(TranslationAvailability.NONE, TranslationLookup(TranslationLookupStatus.SUCCEEDED)))

    @Test fun samplesProviderTimesAtBoundariesGapsAndZeroDuration() {
        assertEquals(LyricWordPosition(), LyricsWordTimeline.sample(words, 1_199))
        assertEquals(LyricWordPosition(0, 0f), LyricsWordTimeline.sample(words, 1_200))
        assertEquals(LyricWordPosition(0, .5f), LyricsWordTimeline.sample(words, 1_400))
        assertEquals(LyricWordPosition(1, 0f), LyricsWordTimeline.sample(words, 1_600))
        assertEquals(LyricWordPosition(1, 1f), LyricsWordTimeline.sample(words, 9_000))
        val gap = listOf(words[0], words[1].copy(startTimeMs = 2_000))
        assertEquals(LyricWordPosition(0, 1f), LyricsWordTimeline.sample(gap, 1_800))
        assertEquals(LyricWordPosition(0, 1f), LyricsWordTimeline.sample(listOf(words[0].copy(endTimeMs = 1_200)), 1_200))
    }

    @Test fun validatesFullUtf16CoverageAndRejectsBrokenUnicodeAndTimes() {
        assertTrue(LyricsWordTimeline.valid("Hello", words))
        assertTrue(LyricsWordTimeline.valid("😀好", listOf(LyricWord(0, 2, 0, 1), LyricWord(2, 3, 1, 2))))
        assertFalse(LyricsWordTimeline.valid("😀好", listOf(LyricWord(0, 1, 0, 1), LyricWord(1, 3, 1, 2))))
        assertFalse(LyricsWordTimeline.valid("Hello", words.drop(1)))
        assertFalse(LyricsWordTimeline.valid("Hello", listOf(words[0], words[1].copy(textEnd = 6))))
        assertFalse(LyricsWordTimeline.valid("Hello", words.map { it.copy(startTimeMs = -1) }))
        assertFalse(LyricsWordTimeline.valid("Hello", listOf(words[0], words[1].copy(startTimeMs = 1_000))))
    }

    @Test fun browserSamplesPauseAndSeekUsingSameClockAndManualOffsetWithoutNetwork() {
        val key = MediaKey(Platform.KUGOU, "source")
        var requests = 0
        val browser = LyricsBrowser({ LibraryCacheIdentity("owner", 1, true) }) { _, done ->
            requests++
            done(Result.success(document().copy(origin = LyricsOrigin.MATCHED, offsetMs = 300)))
        }
        browser.follow(key, key, 1_700)
        assertEquals(0, browser.state.value.activeIndex)
        assertEquals(0, browser.state.value.activeWordIndex)
        assertEquals(.5f, browser.state.value.activeWordProgress)
        val paused = browser.state.value
        browser.follow(key, key, 1_700)
        assertEquals(paused, browser.state.value)
        browser.follow(key, key, 2_200)
        assertEquals(1, browser.state.value.activeWordIndex)
        assertEquals(.5f, browser.state.value.activeWordProgress)
        browser.follow(key, key, 1_400)
        assertEquals(-1, browser.state.value.activeIndex)
        assertEquals(-1, browser.state.value.activeWordIndex)
        assertEquals(1, requests)
    }

    @Test fun optionalFailurePreservesWordsEvenWithoutTranslationAndSourceChangeFencesLateResults() {
        val key = MediaKey(Platform.KUGOU, "source")
        val other = MediaKey(Platform.QQ, "other")
        val callbacks = mutableListOf<(Result<ParsedLyrics>) -> Unit>()
        val browser = LyricsBrowser({ LibraryCacheIdentity("owner", 1, true) },
            { _, done -> done(Result.success(document().copy(lines = document().lines.map { it.copy(words = emptyList()) }))) },
            { _, _, _, done -> callbacks += done })
        browser.follow(key, key, 1_400)
        callbacks.last()(Result.success(document()))
        assertEquals(words, browser.state.value.lines.single().words)
        browser.refreshTranslation()
        callbacks.last()(Result.success(LyricsTranslationAligner.failed(
            LrcParser.parse("[00:01.20]Hello"), TranslationReason.NETWORK)))
        assertEquals(words, browser.state.value.lines.single().words)
        assertEquals(TranslationLookupStatus.FAILED, browser.state.value.translation.lookup.status)
        assertEquals(.5f, browser.state.value.activeWordProgress)
        browser.refreshTranslation()
        val oldCallback = callbacks.last()
        browser.follow(key, other, 1_400)
        val current = browser.state.value
        oldCallback(Result.success(document()))
        assertEquals(current, browser.state.value)
        assertTrue(browser.state.value.lines.single().words.isEmpty())
    }

    @Test fun cachedWordRetentionRequiresIdenticalOriginalAndARealFailure() {
        val old = document()
        val failed = LyricsTranslationAligner.failed(old.copy(lines = old.lines.map { it.copy(words = emptyList()) }), TranslationReason.NETWORK)
        assertEquals(words, LyricsWordTimeline.retainOnFailure(old, failed).lines.single().words)
        val other = failed.copy(lines = listOf(LyricLine(1_200, "other")), plainText = "other")
        assertTrue(LyricsWordTimeline.retainOnFailure(old, other).lines.single().words.isEmpty())
        val successful = failed.copy(translation = old.translation)
        assertTrue(LyricsWordTimeline.retainOnFailure(old, successful).lines.single().words.isEmpty())
    }

    @Test fun independentWordFailureRetainsWordsAlongsideSuccessfulNewTranslation() {
        val old = document()
        val incoming = old.copy(lines = old.lines.map { it.copy(words = emptyList(), translation = "你好") })
        val retained = LyricsWordTimeline.retainVerified(old, incoming)
        assertEquals(words, retained.lines.single().words)
        assertEquals("你好", retained.lines.single().translation)
        val changed = incoming.copy(lines = incoming.lines.map { it.copy(timeMs = it.timeMs + 1) })
        assertTrue(LyricsWordTimeline.retainVerified(old, changed).lines.single().words.isEmpty())
    }

    @Test fun successfulWordsSurviveWhenBrowserRetainsTranslationAfterTranslationFailure() {
        val key = MediaKey(Platform.QQ, "source")
        val old = document().copy(lines = document().lines.map { it.copy(translation = "你好", words = emptyList()) },
            translation = LyricsTranslation(TranslationAvailability.AVAILABLE, TranslationLookup(TranslationLookupStatus.SUCCEEDED)))
        var callback: ((Result<ParsedLyrics>) -> Unit)? = null
        val browser = LyricsBrowser({ LibraryCacheIdentity("owner", 1, true) },
            { _, done -> done(Result.success(old)) }, { _, _, _, done -> callback = done })
        browser.follow(key, key, 1400)
        callback!!(Result.success(LyricsTranslationAligner.failed(document(), TranslationReason.INVALID_DATA)))
        assertEquals(words, browser.state.value.lines.single().words)
        assertEquals("你好", browser.state.value.lines.single().translation)
        assertEquals(TranslationLookupStatus.FAILED, browser.state.value.translation.lookup.status)
    }
}
