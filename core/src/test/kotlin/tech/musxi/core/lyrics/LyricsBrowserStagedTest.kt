package tech.musxi.core.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibraryCacheIdentity
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.MediaKey

class LyricsBrowserStagedTest {
    private class Requests {
        data class FetchCall(
            val key: MediaKey,
            val callback: (Result<ParsedLyrics>) -> Unit,
        )

        data class TranslationCall(
            val key: MediaKey,
            val original: ParsedLyrics,
            val refresh: Boolean,
            val callback: (Result<ParsedLyrics>) -> Unit,
        )

        val fetches = mutableListOf<FetchCall>()
        val translations = mutableListOf<TranslationCall>()

        fun browser(identities: Map<Platform, LibraryCacheIdentity>) = LyricsBrowser(
            { identities[it] },
            { key, callback -> fetches += FetchCall(key, callback) },
            { key, original, refresh, callback ->
                translations += TranslationCall(key, original, refresh, callback)
            },
        )
    }

    private val queue = MediaKey(Platform.QQ, "queue")
    private val sourceA = MediaKey(Platform.NETEASE, "source-a")
    private val sourceB = MediaKey(Platform.NETEASE, "source-b")
    private val identities = Platform.entries
        .associateWith { LibraryCacheIdentity("owner", 1, true) }
        .toMutableMap()
    private val requests = Requests()
    private val browser = requests.browser(identities)

    private fun lyrics(first: String = "first", second: String = "second") = ParsedLyrics(
        lines = listOf(LyricLine(1_000, first), LyricLine(4_000, second)),
        plainText = "$first\n$second",
    )

    private fun available(
        source: ParsedLyrics = lyrics(),
        first: String = "第一",
        second: String = "第二",
    ) = source.copy(
        lines = listOf(
            source.lines[0].copy(translation = first),
            source.lines[1].copy(translation = second),
        ),
        translation = LyricsTranslation(
            TranslationAvailability.AVAILABLE,
            TranslationLookup(TranslationLookupStatus.SUCCEEDED),
        ),
    )

    private fun verifiedNone(source: ParsedLyrics) = source.copy(
        translation = LyricsTranslation(
            TranslationAvailability.NONE,
            TranslationLookup(TranslationLookupStatus.SUCCEEDED, TranslationReason.NOT_PROVIDED),
        ),
    )

    @Test
    fun originalIsReadableWhileTranslationIsPendingAndRefreshPreservesPlaybackPosition() {
        browser.follow(queue, sourceA, 3_500)
        requests.fetches.single().callback(Result.success(lyrics()))

        val pending = browser.state.value
        assertEquals(LyricsStatus.TIMED, pending.status)
        assertEquals(lyrics().lines, pending.lines)
        assertEquals(0, pending.activeIndex)
        assertEquals(TranslationLookupStatus.PENDING, pending.translation.lookup.status)
        assertTrue(pending.canRefreshTranslation)
        assertEquals(sourceA, requests.translations.single().key)
        assertFalse(requests.translations.single().refresh)

        browser.refreshTranslation()

        assertEquals(2, requests.translations.size)
        assertTrue(requests.translations[1].refresh)
        assertEquals(lyrics(), requests.translations[1].original)
        assertEquals(lyrics().lines, browser.state.value.lines)
        assertEquals(0, browser.state.value.activeIndex)
        assertEquals(1, requests.fetches.size)

        requests.translations[1].callback(Result.success(available()))
        assertEquals(TranslationLookupStatus.SUCCEEDED, browser.state.value.translation.lookup.status)
        assertEquals("第一", browser.state.value.lines[0].translation)
    }

    @Test
    fun translationRefreshesAreIndependentAndLatestResponseWins() {
        browser.follow(queue, sourceA, 0)
        requests.fetches.single().callback(Result.success(lyrics()))

        browser.refreshTranslation()
        browser.refreshTranslation()
        assertEquals(3, requests.translations.size)
        assertTrue(requests.translations[1].refresh)
        assertTrue(requests.translations[2].refresh)
        assertEquals(1, requests.fetches.size)

        requests.translations[1].callback(Result.success(available(first = "中间", second = "中间二")))
        requests.translations[0].callback(Result.success(available(first = "最早", second = "最早二")))
        assertEquals(TranslationLookupStatus.PENDING, browser.state.value.translation.lookup.status)

        requests.translations[2].callback(Result.success(available(first = "最新", second = "最新二")))
        assertEquals(TranslationLookupStatus.SUCCEEDED, browser.state.value.translation.lookup.status)
        assertEquals("最新", browser.state.value.lines[0].translation)
        assertEquals("最新二", browser.state.value.lines[1].translation)
    }

    @Test
    fun failedRefreshKeepsAvailableTranslationAndOriginalTimeline() {
        browser.follow(queue, sourceA, 0)
        requests.fetches.single().callback(Result.success(lyrics()))
        requests.translations.single().callback(Result.success(available()))

        browser.refreshTranslation()
        requests.translations[1].callback(Result.failure(LibraryPageFailure("network")))

        val state = browser.state.value
        assertEquals(LyricsStatus.TIMED, state.status)
        assertEquals(lyrics().lines, state.lines.map { it.copy(translation = null) })
        assertEquals(TranslationAvailability.AVAILABLE, state.translation.availability)
        assertEquals(TranslationLookupStatus.FAILED, state.translation.lookup.status)
        assertEquals(TranslationReason.NETWORK, state.translation.lookup.reason)
        assertEquals("第一", state.lines[0].translation)
        assertTrue(state.canRefreshTranslation)
    }

    @Test
    fun successfulNoneResultClearsOldTranslationWithoutChangingOriginalWords() {
        browser.follow(queue, sourceA, 1_500)
        requests.fetches.single().callback(Result.success(lyrics()))
        requests.translations.single().callback(Result.success(available()))
        browser.refreshTranslation()

        val result = verifiedNone(lyrics())
        requests.translations[1].callback(Result.success(result))

        val state = browser.state.value
        assertEquals(LyricsStatus.TIMED, state.status)
        assertEquals(0, state.activeIndex)
        assertEquals(lyrics().lines, state.lines)
        assertEquals(TranslationAvailability.NONE, state.translation.availability)
        assertEquals(TranslationLookupStatus.SUCCEEDED, state.translation.lookup.status)
        assertNull(state.lines[0].translation)
        assertNull(state.lines[1].translation)
    }

    @Test
    fun refreshResultForDifferentOriginalRetainsOldTranslationAndReportsVersionMismatch() {
        browser.follow(queue, sourceA, 0)
        requests.fetches.single().callback(Result.success(lyrics()))
        requests.translations.single().callback(Result.success(available()))
        browser.refreshTranslation()

        val changed = lyrics("changed first", "second")
        requests.translations[1].callback(Result.success(available(changed, "错误一", "错误二")))

        val state = browser.state.value
        assertEquals(lyrics().lines, state.lines.map { it.copy(translation = null) })
        assertEquals("第一", state.lines[0].translation)
        assertEquals("第二", state.lines[1].translation)
        assertEquals(TranslationAvailability.AVAILABLE, state.translation.availability)
        assertEquals(TranslationLookupStatus.FAILED, state.translation.lookup.status)
        assertEquals(TranslationReason.VERSION_MISMATCH, state.translation.lookup.reason)
    }

    @Test
    fun switchingSourceRejectsOldTranslationAndPublishesOnlyNewSource() {
        val newSource = lyrics("B first", "B second")
        browser.follow(queue, sourceA, 0)
        requests.fetches[0].callback(Result.success(lyrics()))
        val oldTranslation = requests.translations.single().callback

        browser.follow(queue, sourceB, 0)
        assertEquals(LyricsStatus.LOADING, browser.state.value.status)
        oldTranslation(Result.success(available()))
        assertEquals(LyricsStatus.LOADING, browser.state.value.status)
        assertTrue(browser.state.value.lines.isEmpty())

        requests.fetches[1].callback(Result.success(newSource))
        assertEquals(sourceB, browser.state.value.sourceKey)
        assertEquals(sourceB, requests.translations.last().key)
        assertEquals(newSource.lines, browser.state.value.lines)

        requests.translations.last().callback(Result.success(available(newSource, "B 一", "B 二")))
        assertEquals("B 一", browser.state.value.lines[0].translation)
        assertFalse(browser.state.value.lines.any { it.text == "first" })
    }

    private fun rejectAfterOwnerGenerationChange(platform: Platform) {
        val localIdentities = Platform.entries
            .associateWith { LibraryCacheIdentity("owner", 1, true) }
            .toMutableMap()
        val localRequests = Requests()
        val localBrowser = localRequests.browser(localIdentities)

        localBrowser.follow(queue, sourceA, 0)
        localRequests.fetches.single().callback(Result.success(lyrics()))
        assertEquals(TranslationLookupStatus.PENDING, localBrowser.state.value.translation.lookup.status)

        localIdentities[platform] = localIdentities.getValue(platform).copy(generation = 2)
        localBrowser.follow(queue, sourceA, 0)
        assertEquals(LyricsStatus.LOADING, localBrowser.state.value.status)
        assertEquals(2, localRequests.fetches.size)

        localRequests.translations.single().callback(Result.success(available()))
        assertEquals(LyricsStatus.LOADING, localBrowser.state.value.status)
        assertTrue(localBrowser.state.value.lines.isEmpty())
    }

    @Test
    fun queueAndSourceOwnerGenerationChangesFencePendingTranslation() {
        rejectAfterOwnerGenerationChange(Platform.QQ)
        rejectAfterOwnerGenerationChange(Platform.NETEASE)
    }

    @Test
    fun fullRefreshRejectsOldOriginalAndAutomaticallyStartsFreshTranslation() {
        browser.follow(queue, sourceA, 0)
        assertEquals(1, requests.fetches.size)

        browser.refresh()
        assertEquals(LyricsStatus.LOADING, browser.state.value.status)
        assertEquals(2, requests.fetches.size)

        requests.fetches[0].callback(Result.success(available()))
        assertEquals(LyricsStatus.LOADING, browser.state.value.status)
        assertTrue(browser.state.value.lines.isEmpty())

        requests.fetches[1].callback(Result.success(lyrics()))
        assertEquals(LyricsStatus.TIMED, browser.state.value.status)
        assertEquals(lyrics().lines, browser.state.value.lines)
        assertFalse(requests.translations.single().refresh)
    }

    @Test
    fun pendingTranslationResumesWhenSwitchingAwayAndBackWithoutRefetchingOriginal() {
        browser.follow(queue, sourceA, 0)
        requests.fetches[0].callback(Result.success(lyrics()))
        val firstTranslation = requests.translations.single().callback

        browser.follow(queue, sourceB, 0)
        browser.follow(queue, sourceA, 0)

        assertEquals(2, requests.fetches.size)
        assertEquals(sourceA, browser.state.value.sourceKey)
        assertEquals(lyrics().lines, browser.state.value.lines)
        assertEquals(TranslationLookupStatus.PENDING, browser.state.value.translation.lookup.status)
        assertEquals(2, requests.translations.size)
        assertFalse(requests.translations[1].refresh)

        firstTranslation(Result.success(available(first = "旧", second = "旧二")))
        assertEquals(TranslationLookupStatus.PENDING, browser.state.value.translation.lookup.status)

        requests.translations[1].callback(Result.success(available(first = "恢复", second = "恢复二")))
        assertEquals("恢复", browser.state.value.lines[0].translation)
    }

    @Test
    fun duplicateAndCancelledTranslationCallbacksAreIgnored() {
        browser.follow(queue, sourceA, 0)
        requests.fetches.single().callback(Result.success(lyrics()))
        val callback = requests.translations.single().callback

        callback(Result.success(available()))
        callback(Result.failure(LibraryPageFailure("timeout")))

        assertEquals(TranslationLookupStatus.SUCCEEDED, browser.state.value.translation.lookup.status)
        assertEquals(TranslationAvailability.AVAILABLE, browser.state.value.translation.availability)
        assertEquals("第一", browser.state.value.lines[0].translation)

        val localRequests = Requests()
        val localBrowser = localRequests.browser(identities)
        localBrowser.follow(queue, sourceA, 0)
        localRequests.fetches.single().callback(Result.success(lyrics()))
        val cancelledCallback = localRequests.translations.single().callback
        localBrowser.cancel()
        cancelledCallback(Result.success(available()))
        assertEquals(LyricsState(), localBrowser.state.value)
    }

    @Test
    fun originalFailurePublishesErrorAndNeverStartsTranslation() {
        browser.follow(queue, sourceA, 0)
        requests.fetches.single().callback(Result.failure(LibraryPageFailure("network")))

        val state = browser.state.value
        assertEquals(LyricsStatus.ERROR, state.status)
        assertEquals("network", state.error)
        assertTrue(state.lines.isEmpty())
        assertEquals(TranslationLookupStatus.UNSUPPORTED, state.translation.lookup.status)
        assertFalse(state.canRefreshTranslation)
        assertTrue(requests.translations.isEmpty())
    }

    @Test
    fun pendingDocumentsAreBoundedToEightQueuedSources() {
        val localRequests = Requests()
        val localBrowser = localRequests.browser(identities)
        val sources = (0..8).map { MediaKey(Platform.NETEASE, "source-$it") }

        sources.forEachIndexed { index, source ->
            localBrowser.follow(queue, source, 0)
            val parsed = lyrics("track-$index", "second")
            localRequests.fetches.last().callback(Result.success(parsed))
        }
        assertEquals(9, localRequests.fetches.size)

        localBrowser.follow(queue, sources[7], 0)
        assertEquals(9, localRequests.fetches.size)
        assertEquals("track-7", localBrowser.state.value.lines[0].text)

        localBrowser.follow(queue, sources[0], 0)
        assertEquals(10, localRequests.fetches.size)
        assertEquals(sources[0], localBrowser.state.value.sourceKey)
    }

    @Test fun repositoryGuardExpiresBeforePublicationOnSwitchRefreshAndAccountLoss() {
        val guards = mutableListOf<() -> Boolean>()
        val callbacks = mutableListOf<(Result<ParsedLyrics>) -> Unit>()
        val local = LyricsBrowser({ identities[it] }, { _, _ -> error("legacy fetch used") }, null,
            { _, current, callback -> guards += current; callbacks += callback })
        local.follow(queue, sourceA, 0)
        assertTrue(guards[0]())
        local.follow(queue, sourceB, 0)
        assertFalse(guards[0]())
        assertTrue(guards[1]())
        local.refresh()
        assertFalse(guards[1]())
        assertTrue(guards[2]())
        identities[Platform.QQ] = identities.getValue(Platform.QQ).copy(generation = 2)
        assertFalse(guards[2]())
        local.follow(queue, sourceB, 0)
        callbacks[3](Result.success(lyrics()))
        assertFalse(guards[3]()) // Duplicate results cannot register another repository stage.
    }

    @Test fun documentContextsAreReleasedOnRefreshEvictionAndOwnerLossButRetainedOnCacheHit() {
        val calls = mutableListOf<(Result<ParsedLyrics>) -> Unit>()
        val released = mutableListOf<ParsedLyrics>()
        val local = LyricsBrowser({ identities[it] }, { _, callback -> calls += callback }, null,
            releaseDocument = { released += it })
        val first = lyrics()
        local.follow(queue, sourceA, 0); calls.last()(Result.success(first))
        local.cancel(); local.follow(queue, sourceA, 0)
        assertTrue(released.isEmpty())
        assertEquals(1, calls.size)
        local.refresh()
        assertTrue(released.single() === first)
        for (index in 0..8) {
            local.follow(queue, MediaKey(Platform.NETEASE, "entry-$index"), 0)
            calls.last()(Result.success(lyrics("entry-$index")))
        }
        assertEquals(2, released.size)
        identities[Platform.NETEASE] = identities.getValue(Platform.NETEASE).copy(verified = false)
        local.follow(queue, sourceA, 0)
        assertEquals(10, released.size)
        assertEquals(LyricsState(), local.state.value)
    }
}
