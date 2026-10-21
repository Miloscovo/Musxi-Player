package tech.musxi.core.library

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.*

class SearchBrowserTest {
    private val accounts = Platform.entries.associateWith { AccountState(it, AccountPhase.CONNECTED,
        AccountProfile(it, "fixture", "Fixture"), generation = 1) }.toMutableMap()
    private data class Call(val query: SearchQuery, val current: () -> Boolean, val callback: (Result<Page<SongSummary>>) -> Unit)
    private val calls = mutableListOf<Call>()
    private val browser = SearchBrowser({ accounts.getValue(it) }, SearchPageFetch { query, current, callback ->
        calls.add(Call(query, current, callback))
    })
    private fun page(platform: Platform = Platform.NETEASE, next: Int? = null, title: String = "Song") =
        Page(listOf(SongSummary(MediaKey(platform, "1"), title, listOf("Artist"), "Album", 1000)), next, null)

    @Test fun newQueryAndPlatformRejectLateResultsAndCancelQueuedWork() {
        browser.search(Platform.NETEASE, "first")
        browser.search(Platform.QQ, "second")
        assertFalse(calls[0].current()); assertTrue(calls[1].current())
        calls[0].callback(Result.success(page()))
        assertTrue(browser.state.value.loading); assertNull(browser.state.value.page)
        calls[1].callback(Result.success(page(Platform.QQ)))
        assertEquals(Platform.QQ, browser.state.value.page!!.items.single().key.platform)
        browser.refresh(); browser.cancel()
        assertFalse(calls[2].current()); assertFalse(browser.state.value.loading)
        calls[2].callback(Result.failure(LibraryPageFailure("network")))
        assertNull(browser.state.value.error); assertNotNull(browser.state.value.page)
    }

    @Test fun accountChangeClearsResultsAndOldCallbacksCannotExposeThemAgain() {
        browser.search(Platform.NETEASE, "query")
        calls[0].callback(Result.success(page()))
        browser.refresh()
        accounts[Platform.NETEASE] = accounts.getValue(Platform.NETEASE).copy(generation = 2)
        browser.accountChanged()
        calls[1].callback(Result.success(page(title = "Stale")))
        assertNull(browser.state.value.page); assertEquals("account_changed", browser.state.value.error)
        browser.refresh()
        assertNull(browser.state.value.page)
        calls[2].callback(Result.success(page(title = "New")))
        assertEquals("New", browser.state.value.page!!.items.single().title)
    }

    @Test fun refreshFailureRetainsPageButChangingQueryClearsIt() {
        browser.search(Platform.NETEASE, "query")
        calls[0].callback(Result.success(page()))
        val original = browser.state.value.page
        browser.refresh()
        calls[1].callback(Result.failure(LibraryPageFailure("network")))
        assertEquals(original, browser.state.value.page); assertEquals("network", browser.state.value.error)
        browser.search(Platform.NETEASE, "other")
        assertNull(browser.state.value.page)
    }

    @Test fun paginationUsesExplicitBackendContinuationAndEmptyResultsAreSuccess() {
        browser.search(Platform.NETEASE, "query")
        calls[0].callback(Result.success(page(next = 30)))
        browser.next(); assertEquals(30, calls[1].query.offset)
        calls[1].callback(Result.success(Page(emptyList(), null, null)))
        assertTrue(browser.state.value.page!!.items.isEmpty()); assertNull(browser.state.value.error)
        browser.next(); assertEquals(2, calls.size)
    }

    @Test fun invalidRequestsAndUnverifiedAccountsDoNotReachFetch() {
        browser.search(Platform.NETEASE, " "); assertEquals("request", browser.state.value.error)
        browser.search(Platform.NETEASE, "query", 1); assertEquals("request", browser.state.value.error)
        accounts[Platform.NETEASE] = accounts.getValue(Platform.NETEASE).copy(phase = AccountPhase.REVALIDATING)
        browser.search(Platform.NETEASE, "query"); assertEquals("account_unavailable", browser.state.value.error)
        assertTrue(calls.isEmpty())
    }

    @Test fun malformedPlatformAndPaginationAreFailuresAndPrivateErrorsAreNotExposed() {
        browser.search(Platform.NETEASE, "query")
        calls[0].callback(Result.success(page(Platform.QQ))); assertEquals("protocol", browser.state.value.error)
        browser.refresh()
        calls[1].callback(Result.success(page(next = 1))); assertEquals("protocol", browser.state.value.error)
        browser.refresh()
        calls[2].callback(Result.failure(IllegalStateException("private upstream response")))
        assertEquals("unclassified", browser.state.value.error)
    }

    @Test fun resultsAreCopiedAndArtworkIsSanitizedBeforePublishing() {
        val artists = mutableListOf("Artist")
        val rows = mutableListOf(SongSummary(MediaKey(Platform.NETEASE, "1"), "Song", artists, "Album", 1,
            "https://p1.music.126.net/image?private=true"))
        browser.search(Platform.NETEASE, "query")
        calls[0].callback(Result.success(Page(rows, null, 1)))
        artists.clear(); rows.clear()
        val song = browser.state.value.page!!.items.single()
        assertEquals(listOf("Artist"), song.artists); assertNull(song.coverUrl)
    }
}
