package tech.musxi.core.lyrics

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*

class LyricsBrowserTest {
    private val original = MediaKey(Platform.QQ, "AbCdEf12345678")
    private val source = MediaKey(Platform.NETEASE, "1")
    private val identities = Platform.entries.associateWith { LibraryCacheIdentity("owner", 1, true) }.toMutableMap()
    private val calls = mutableListOf<Pair<MediaKey, (Result<ParsedLyrics>) -> Unit>>()
    private val browser = LyricsBrowser({ identities[it] }) { key, callback -> calls.add(key to callback) }
    private fun lyrics() = LrcParser.parse("[00:01.00]first\n[00:03.00]second")

    @Test fun followsActualSourceAndSeeksBothDirectionsWithoutRefetching() {
        browser.follow(original, source, 0)
        assertEquals(source, calls.single().first)
        calls.single().second(Result.success(lyrics()))
        assertEquals(LyricsStatus.TIMED, browser.state.value.status)
        assertEquals(-1, browser.state.value.activeIndex)
        browser.follow(original, source, 3500)
        assertEquals(1, browser.state.value.activeIndex)
        browser.follow(original, source, 1500)
        assertEquals(0, browser.state.value.activeIndex)
        assertEquals(1, calls.size)
    }

    @Test fun replacementCancelAndAccountChangeRejectOldResponsesAndInvalidateCache() {
        browser.follow(original, source, 0)
        browser.follow(original, original, 0)
        calls[0].second(Result.success(lyrics()))
        assertEquals(original, browser.state.value.sourceKey)
        assertEquals(LyricsStatus.LOADING, browser.state.value.status)
        identities[Platform.QQ] = identities.getValue(Platform.QQ).copy(generation = 2)
        browser.follow(original, original, 0)
        calls[1].second(Result.success(lyrics()))
        assertEquals(LyricsStatus.LOADING, browser.state.value.status)
        calls[2].second(Result.success(lyrics()))
        browser.cancel()
        calls[2].second(Result.failure(Exception("private upstream")))
        assertEquals(LyricsState(), browser.state.value)
        browser.follow(original, original, 0)
        assertEquals(3, calls.size) // Authorized in-memory document is reused.
        identities[Platform.QQ] = identities.getValue(Platform.QQ).copy(verified = false)
        browser.follow(original, original, 0)
        assertEquals(LyricsState(), browser.state.value)
        identities[Platform.QQ] = identities.getValue(Platform.QQ).copy(verified = true)
        browser.follow(original, original, 0)
        assertEquals(4, calls.size)
    }

    @Test fun sourceAccountLossBlocksSubstitutedLyricsEvenWhenQueueAccountStillConnected() {
        browser.follow(original, source, 0)
        identities[Platform.NETEASE] = identities.getValue(Platform.NETEASE).copy(verified = false)
        calls.single().second(Result.success(lyrics()))
        assertEquals(LyricsStatus.LOADING, browser.state.value.status)
        browser.follow(original, source, 0)
        assertEquals(LyricsState(), browser.state.value)
    }

    @Test fun emptyPlainErrorAreDistinctAndOnlyExplicitRefreshRetriesErrors() {
        browser.follow(original, source, 0)
        calls.last().second(Result.success(LrcParser.parse("")))
        assertEquals(LyricsStatus.EMPTY, browser.state.value.status)
        browser.refresh()
        calls.last().second(Result.success(LrcParser.parse("plain text")))
        assertEquals(LyricsStatus.PLAIN, browser.state.value.status)
        browser.refresh()
        calls.last().second(Result.failure(Exception("private upstream")))
        assertEquals(LyricsStatus.ERROR, browser.state.value.status)
        assertEquals("unavailable", browser.state.value.error)
        browser.follow(original, source, 2000)
        assertEquals(3, calls.size)
        browser.refresh()
        calls.last().second(Result.failure(LibraryPageFailure("network")))
        assertEquals("network", browser.state.value.error)
    }
}
