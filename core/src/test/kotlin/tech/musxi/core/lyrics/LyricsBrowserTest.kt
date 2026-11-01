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

    @Test fun clearingCacheReleasesDocumentsAndRejectsPendingLyrics() {
        val released = mutableListOf<ParsedLyrics>()
        val cache = LyricsBrowser({ identities[it] }, { key, done -> calls.add(key to done) },
            null, releaseDocument = { released += it })
        cache.follow(original, source, 0)
        val parsed = lyrics()
        calls.single().second(Result.success(parsed))
        cache.follow(original, original, 0)
        val pending = calls.last().second
        cache.clearCache()
        assertEquals(listOf(parsed), released)
        pending(Result.success(lyrics()))
        assertEquals(LyricsStatus.IDLE, cache.state.value.status)
        cache.follow(original, source, 0)
        assertEquals(3, calls.size)
        assertEquals(LyricsStatus.LOADING, cache.state.value.status)
    }

    @Test fun manualBindingUsesOriginalQueueAndOffsetDelaysHighlighting() {
        var requestedOriginal: MediaKey? = null
        var requestedSource: MediaKey? = null
        val matched = LyricsBrowser({ identities[it] }, { _, _ -> fail("Unexpected legacy fetch") }, null,
            fetchForOriginal = { queue, automatic, _, done ->
                requestedOriginal = queue
                requestedSource = automatic
                done(Result.success(lyrics().copy(origin = LyricsOrigin.MATCHED, offsetMs = 2000)))
            })
        matched.follow(original, source, 1500)
        assertEquals(original, requestedOriginal)
        assertEquals(source, requestedSource)
        assertEquals(LyricsOrigin.MATCHED, matched.state.value.origin)
        assertEquals(-1, matched.state.value.activeIndex)
        matched.follow(original, source, 3500)
        assertEquals(0, matched.state.value.activeIndex)
        matched.follow(original, source, 5500)
        assertEquals(1, matched.state.value.activeIndex)
    }

    @Test fun explicitDiskReaderCanDisplayOfflineOwnerButCannotAcceptFormerVerifiedRequest() {
        val pending = mutableListOf<(Result<ParsedLyrics>) -> Unit>()
        val cached = LyricsBrowser({ identities[it] }, { _, done -> pending.add(done) }, null,
            allowCachedUnverified = true)
        cached.follow(original, source, 2000)
        identities[Platform.NETEASE] = identities.getValue(Platform.NETEASE).copy(verified = false)
        pending.single()(Result.success(lyrics()))
        assertEquals(LyricsStatus.LOADING, cached.state.value.status)
        cached.follow(original, source, 2000)
        pending.last()(Result.success(lyrics()))
        assertEquals(LyricsStatus.TIMED, cached.state.value.status)
        assertEquals(0, cached.state.value.activeIndex)
        identities[Platform.NETEASE] = identities.getValue(Platform.NETEASE).copy(owner = "different", generation = 2)
        cached.follow(original, source, 2000)
        assertEquals(LyricsStatus.LOADING, cached.state.value.status)
    }

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
