package tech.musxi.core.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibraryCacheIdentity
import tech.musxi.core.library.MediaKey

class LyricsTranslationContractTest {
    private val queue = MediaKey(Platform.QQ, "queue")
    private val source = MediaKey(Platform.NETEASE, "source-a")
    private val otherSource = MediaKey(Platform.NETEASE, "source-b")
    private val identities = Platform.entries
        .associateWith { LibraryCacheIdentity("owner", 1, true) }
        .toMutableMap()
    private val calls = mutableListOf<Pair<MediaKey, (Result<ParsedLyrics>) -> Unit>>()
    private val browser = LyricsBrowser({ identities[it] }) { key, callback ->
        calls.add(key to callback)
    }

    @Test
    fun legacyParseAndTwoArgumentLineKeepUnknownTranslationDefaults() {
        val twoArgumentLine = LyricLine(1_000, "original")
        val parsed = LrcParser.parse("[00:01.00]original")

        assertNull(twoArgumentLine.translation)
        assertEquals(listOf(LyricLine(1_000, "original")), parsed.lines)
        assertEquals(TranslationAvailability.UNKNOWN, parsed.translation.availability)
        assertNotEquals(TranslationAvailability.NONE, parsed.translation.availability)
        assertEquals(TranslationLookupStatus.UNSUPPORTED, parsed.translation.lookup.status)
        assertEquals(TranslationReason.NOT_IMPLEMENTED, parsed.translation.lookup.reason)
    }

    @Test
    fun partialTranslationsSurviveBidirectionalSeeksWithOneFetch() {
        browser.follow(queue, source, 1_500)
        val parsed = ParsedLyrics(
            lines = listOf(
                LyricLine(1_000, "one", "一"),
                LyricLine(3_000, "two"),
                LyricLine(5_000, "three", "三"),
            ),
            plainText = "one\ntwo\nthree",
            translation = LyricsTranslation(
                TranslationAvailability.AVAILABLE,
                TranslationLookup(TranslationLookupStatus.SUCCEEDED),
            ),
        )
        calls.single().second(Result.success(parsed))

        assertEquals(0, browser.state.value.activeIndex)
        assertEquals("一", browser.state.value.lines[0].translation)
        assertNull(browser.state.value.lines[1].translation)
        assertNull(browser.state.value.translation.lookup.reason)

        browser.follow(queue, source, 3_500)
        assertEquals(1, browser.state.value.activeIndex)
        assertEquals(LyricsStatus.TIMED, browser.state.value.status)

        browser.follow(queue, source, 5_500)
        assertEquals(2, browser.state.value.activeIndex)
        assertEquals("三", browser.state.value.lines[2].translation)

        browser.follow(queue, source, 1_500)
        assertEquals(0, browser.state.value.activeIndex)
        assertEquals(parsed.translation, browser.state.value.translation)
        assertEquals(1, calls.size)
    }

    @Test
    fun availableTranslationIsKeptWhenLookupFailsWithNetworkOrTimeout() {
        listOf(TranslationReason.NETWORK, TranslationReason.TIMEOUT).forEach { reason ->
            val localCalls = mutableListOf<(Result<ParsedLyrics>) -> Unit>()
            val localBrowser = LyricsBrowser({ identities[it] }) { _, callback ->
                localCalls.add(callback)
            }
            localBrowser.follow(queue, source, 0)
            localCalls.single()(
                Result.success(
                    ParsedLyrics(
                        lines = listOf(LyricLine(1_000, "original", "译文")),
                        plainText = "original",
                        translation = LyricsTranslation(
                            TranslationAvailability.AVAILABLE,
                            TranslationLookup(TranslationLookupStatus.FAILED, reason),
                        ),
                    )
                )
            )

            val state = localBrowser.state.value
            assertEquals(LyricsStatus.TIMED, state.status)
            assertNull(state.error)
            assertEquals(TranslationAvailability.AVAILABLE, state.translation.availability)
            assertEquals(TranslationLookupStatus.FAILED, state.translation.lookup.status)
            assertEquals(reason, state.translation.lookup.reason)
            assertEquals("original", state.lines.single().text)
            assertEquals("译文", state.lines.single().translation)
        }
    }

    @Test
    fun unknownFailureAndVerifiedNoneSucceedRemainDistinctWithOriginalLyrics() {
        browser.follow(queue, source, 0)
        calls.single().second(
            Result.success(
                ParsedLyrics(
                    lines = listOf(LyricLine(1_000, "original")),
                    plainText = "original",
                    translation = LyricsTranslation(
                        TranslationAvailability.UNKNOWN,
                        TranslationLookup(TranslationLookupStatus.FAILED, TranslationReason.NETWORK),
                    ),
                )
            )
        )
        val unknown = browser.state.value

        browser.refresh()
        calls.last().second(
            Result.success(
                ParsedLyrics(
                    lines = listOf(LyricLine(1_000, "original")),
                    plainText = "original",
                    translation = LyricsTranslation(
                        TranslationAvailability.NONE,
                        TranslationLookup(TranslationLookupStatus.SUCCEEDED),
                    ),
                )
            )
        )
        val none = browser.state.value

        assertEquals(TranslationAvailability.UNKNOWN, unknown.translation.availability)
        assertEquals(TranslationLookupStatus.FAILED, unknown.translation.lookup.status)
        assertEquals(TranslationAvailability.NONE, none.translation.availability)
        assertEquals(TranslationLookupStatus.SUCCEEDED, none.translation.lookup.status)
        assertNotEquals(unknown.translation, none.translation)
        assertEquals("original", unknown.lines.single().text)
        assertEquals("original", none.lines.single().text)
        assertEquals(LyricsStatus.TIMED, none.status)
        assertNull(none.error)
    }

    @Test
    fun sourceCancelAndAccountGenerationClearTranslationsAndRejectLateCallbacks() {
        browser.follow(queue, source, 0)
        browser.follow(queue, queue, 0)
        assertEquals(LyricsTranslation(), browser.state.value.translation)

        calls[0].second(
            Result.success(
                ParsedLyrics(
                    lines = listOf(LyricLine(1_000, "stale source", "旧译文")),
                    plainText = "stale source",
                    translation = LyricsTranslation(TranslationAvailability.AVAILABLE),
                )
            )
        )
        assertEquals(LyricsStatus.LOADING, browser.state.value.status)
        assertEquals(LyricsTranslation(), browser.state.value.translation)

        calls[1].second(
            Result.success(
                ParsedLyrics(
                    lines = listOf(LyricLine(1_000, "queue source", "队列译文")),
                    plainText = "queue source",
                    translation = LyricsTranslation(
                        TranslationAvailability.AVAILABLE,
                        TranslationLookup(TranslationLookupStatus.SUCCEEDED),
                    ),
                )
            )
        )
        assertEquals("队列译文", browser.state.value.lines.single().translation)

        identities[Platform.QQ] = identities.getValue(Platform.QQ).copy(generation = 2)
        browser.follow(queue, queue, 0)
        assertEquals(LyricsStatus.LOADING, browser.state.value.status)
        assertEquals(LyricsTranslation(), browser.state.value.translation)

        calls[1].second(
            Result.success(
                ParsedLyrics(
                    listOf(LyricLine(2_000, "late generation", "迟到的译文")),
                    "late generation",
                    LyricsTranslation(TranslationAvailability.AVAILABLE),
                )
            )
        )
        assertEquals(LyricsStatus.LOADING, browser.state.value.status)
        assertEquals(LyricsTranslation(), browser.state.value.translation)

        calls[2].second(
            Result.success(
                ParsedLyrics(
                    listOf(LyricLine(3_000, "current", "当前译文")),
                    "current",
                    LyricsTranslation(
                        TranslationAvailability.AVAILABLE,
                        TranslationLookup(TranslationLookupStatus.SUCCEEDED),
                    ),
                )
            )
        )
        assertEquals("当前译文", browser.state.value.lines.single().translation)

        browser.cancel()
        calls[2].second(
            Result.success(
                ParsedLyrics(
                    listOf(LyricLine(4_000, "late cancel", "取消后的译文")),
                    "late cancel",
                    LyricsTranslation(TranslationAvailability.AVAILABLE),
                )
            )
        )
        assertEquals(LyricsState(), browser.state.value)
    }

    @Test
    fun cacheReusesTranslationStateWithoutMixingSources() {
        browser.follow(queue, source, 0)
        calls[0].second(
            Result.success(
                ParsedLyrics(
                    listOf(LyricLine(1_000, "source a", "译文 A")),
                    "source a",
                    LyricsTranslation(
                        TranslationAvailability.AVAILABLE,
                        TranslationLookup(TranslationLookupStatus.SUCCEEDED),
                    ),
                )
            )
        )

        browser.follow(queue, otherSource, 0)
        calls[1].second(
            Result.success(
                ParsedLyrics(
                    listOf(LyricLine(2_000, "source b")),
                    "source b",
                    LyricsTranslation(
                        TranslationAvailability.NONE,
                        TranslationLookup(TranslationLookupStatus.SUCCEEDED),
                    ),
                )
            )
        )

        browser.follow(queue, source, 0)
        assertEquals(2, calls.size)
        assertEquals(source, browser.state.value.sourceKey)
        assertEquals("source a", browser.state.value.lines.single().text)
        assertEquals("译文 A", browser.state.value.lines.single().translation)
        assertEquals(TranslationAvailability.AVAILABLE, browser.state.value.translation.availability)
        assertEquals(TranslationLookupStatus.SUCCEEDED, browser.state.value.translation.lookup.status)
    }
}
