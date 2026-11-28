package tech.musxi.core.localaudio

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalAudioSearchTest {
    @Test fun lastProgressUsesSaveTimeAndKeepsUnknownLast() {
        val older = track(1, title = "旧进度")
        val recent = track(2, title = "最近")
        val unknown = track(3)
        val state = library(listOf(older, recent, unknown), progress = listOf(
            LocalAudioProgress(older.id, 99_000, false, 100),
            LocalAudioProgress(recent.id, 1_000, false, 200),
        ))
        assertEquals(listOf(recent, older, unknown), LocalAudioSearch.ordered(state, null,
            LocalAudioSort.LAST_PROGRESS, descending = true))
        assertEquals(listOf(older, recent, unknown), LocalAudioSearch.ordered(state, null,
            LocalAudioSort.LAST_PROGRESS, descending = false))
    }

    @Test fun equalKeysKeepFolderOrderInBothDirections() {
        val first = track(1, title = "相同", duration = 100)
        val second = track(2, title = "相同", duration = 100)
        val mix = folder(1, "排序")
        val state = library(listOf(first, second), folders = listOf(mix), members = listOf(
            LocalAudioMember(mix.id, second.id, 0), LocalAudioMember(mix.id, first.id, 1)))
        for (descending in listOf(false, true)) for (sort in listOf(LocalAudioSort.TITLE, LocalAudioSort.DURATION)) {
            assertEquals(listOf(second, first), LocalAudioSearch.ordered(state, mix.id, sort, descending))
        }
    }

    @Test
    fun normalizesFullWidthCharactersBeforeMatching() {
        val song = track(1, title = "ＭＵＳＸＩ 第２集")
        val state = library(listOf(song))

        assertEquals(listOf(song), search(state, "ｍｕｓｘｉ").items)
        assertEquals(listOf(song), search(state, "第2集").items)
        // The ideographic space is whitespace after NFKC and splits tokens like any other space.
        assertEquals(listOf(song), search(state, "第２集　ｍｕｓｘｉ").items)
    }

    @Test
    fun lowercasesWithRootLocaleAndMatchesEachTokenInAnyField() {
        val song = track(1, title = "Yesterday", creator = "The BEATLES")
        val other = track(2, title = "Yesterday Once More", creator = "Carpenters")
        val state = library(listOf(song, other))

        assertEquals(listOf(song), search(state, "  beAtles   yester  ").items)
        assertEquals(listOf(song), search(state, "THE beatles").items)
        // Tokens match fields independently but never straddle a field boundary.
        assertEquals(emptyList<LocalAudioItem>(), search(state, "yesterdaythe").items)
        assertEquals(listOf(song), search(state, "track-1").items)
    }

    @Test
    fun treatsSqlAndRegexMetacharactersAsLiterals() {
        val percent = track(1, title = "50% Faster")
        val plain = track(2, title = "50 Percent")
        val bracketed = track(3, title = "Mix [a-z]_ok")
        val state = library(listOf(percent, plain, bracketed))

        // "%", "_" and "[]" are ordinary characters, never SQL LIKE or regex syntax.
        assertEquals(listOf(percent), search(state, "50%").items)
        assertEquals(listOf(percent), search(state, "%").items)
        assertEquals(listOf(bracketed), search(state, "[a-z]_").items)
        assertEquals(listOf(bracketed), search(state, "_").items)
        assertEquals(emptyList<LocalAudioItem>(), search(state, "50.Percent").items)
        assertEquals(listOf(plain), search(state, "50 percent").items)
    }

    @Test
    fun scopesResultsToAFolderOrToUnfiledAudio() {
        val filed = track(1, title = "Filed")
        val loose = track(2, title = "Loose")
        val mix = folder(1, "Mix")
        val state = library(
            items = listOf(filed, loose),
            folders = listOf(mix),
            members = listOf(LocalAudioMember(mix.id, filed.id, 0)),
        )

        assertEquals(listOf(filed), search(state, "", folderId = mix.id).items)
        assertEquals(listOf(loose), search(state, "", unfiledOnly = true).items)
        assertEquals(2, search(state, "").items.size)
    }

    @Test
    fun filtersByModeAvailabilityBookmarkAndProgress() {
        val unplayed = track(1, title = "Unplayed")
        val started = track(2, title = "Started", mode = LocalImportMode.REFERENCE,
            availability = LocalAudioAvailability.MISSING)
        val finished = track(3, title = "Finished", mode = LocalImportMode.REFERENCE)
        val marked = track(4, title = "Marked", mode = LocalImportMode.REFERENCE)
        val touchedNotStarted = track(5, title = "Touched", mode = LocalImportMode.REFERENCE)
        val state = library(
            items = listOf(unplayed, started, finished, marked, touchedNotStarted),
            progress = listOf(
                LocalAudioProgress(started.id, 4_000, completed = false, updatedAtMs = 2),
                LocalAudioProgress(finished.id, 0, completed = true, updatedAtMs = 3),
                LocalAudioProgress(touchedNotStarted.id, 0, completed = false, updatedAtMs = 4),
            ),
            bookmarks = listOf(LocalAudioBookmark(id(99), marked.id, 1_000, "Intro")),
        )

        // No progress row at all counts as unplayed, as does a zero position that was never completed.
        assertEquals(listOf(unplayed, marked, touchedNotStarted),
            search(state, "", progress = LocalAudioProgressFilter.UNPLAYED).items)
        assertEquals(listOf(started),
            search(state, "", progress = LocalAudioProgressFilter.IN_PROGRESS).items)
        assertEquals(listOf(finished),
            search(state, "", progress = LocalAudioProgressFilter.COMPLETED).items)
        assertEquals(listOf(marked), search(state, "", bookmarkedOnly = true).items)
        assertEquals(listOf(started, finished, marked, touchedNotStarted),
            search(state, "", mode = LocalImportMode.REFERENCE).items)
        assertEquals(listOf(started),
            search(state, "", availability = LocalAudioAvailability.MISSING).items)
        assertEquals(listOf(unplayed), search(state, "", mode = LocalImportMode.COPY).items)
    }

    @Test
    fun sortsNamesNaturallyKeepsTiesStableAndPutsUnknownValuesLast() {
        val ten = track(1, title = "Track 10", duration = 2_000)
        val two = track(2, title = "Track 2", duration = 1_000)
        val three = track(3, title = "Track 3")
        val duplicateLow = track(4, title = "Track 7", duration = 3_000)
        val duplicateHigh = track(5, title = "Track 7", duration = 3_000)
        val state = library(listOf(ten, two, three, duplicateLow, duplicateHigh))

        assertEquals(listOf(two, three, duplicateLow, duplicateHigh, ten),
            LocalAudioSearch.ordered(state, null, LocalAudioSort.TITLE, descending = false))
        assertEquals(listOf(ten, duplicateLow, duplicateHigh, three, two),
            LocalAudioSearch.ordered(state, null, LocalAudioSort.TITLE, descending = true))

        assertEquals(listOf(two, ten, duplicateLow, duplicateHigh, three),
            LocalAudioSearch.ordered(state, null, LocalAudioSort.DURATION, descending = false))
        // Unknown duration stays last when descending; the tie keeps the scope order.
        assertEquals(listOf(duplicateLow, duplicateHigh, ten, two, three),
            LocalAudioSearch.ordered(state, null, LocalAudioSort.DURATION, descending = true))
    }

    @Test
    fun manualOrderFollowsMembershipInsideAFolderAndItemOrderGlobally() {
        val first = track(1, title = "First")
        val second = track(2, title = "Second")
        val third = track(3, title = "Third")
        val mix = folder(1, "Mix")
        val state = library(
            items = listOf(third, first, second),
            folders = listOf(mix),
            members = listOf(
                LocalAudioMember(mix.id, second.id, 0),
                LocalAudioMember(mix.id, third.id, 1),
                LocalAudioMember(mix.id, first.id, 2),
            ),
        )

        assertEquals(listOf(third, first, second),
            LocalAudioSearch.ordered(state, null, LocalAudioSort.MANUAL, descending = false))
        assertEquals(listOf(second, third, first),
            LocalAudioSearch.ordered(state, mix.id, LocalAudioSort.MANUAL, descending = false))
        assertEquals(listOf(first, third, second),
            LocalAudioSearch.ordered(state, mix.id, LocalAudioSort.MANUAL, descending = true))
        // Querying a folder reuses the folder's manual order rather than the library order.
        assertEquals(listOf(second, third, first), search(state, "", folderId = mix.id).items)
    }

    @Test
    fun pagesResultsAndRejectsAStaleRevision() {
        val songs = (1..5).map { track(it, title = "Song $it") }
        val state = library(songs, revision = 7)

        val first = page(state, LocalAudioQuery(limit = 2))
        assertEquals(7L, first.revision)
        assertEquals(5, first.total)
        assertEquals(0, first.offset)
        assertEquals(listOf(songs[0], songs[1]), first.items)
        assertEquals(2, first.nextOffset)
        assertNotNull(first.token)

        val middle = page(state, LocalAudioQuery(offset = 2, limit = 2, expectedToken = first.token))
        assertEquals(listOf(songs[2], songs[3]), middle.items)
        assertEquals(4, middle.nextOffset)

        val last = page(state, LocalAudioQuery(offset = 4, limit = 2, expectedToken = middle.token))
        assertEquals(listOf(songs[4]), last.items)
        assertNull(last.nextOffset)

        val past = page(state, LocalAudioQuery(offset = 5, limit = 2, expectedToken = last.token))
        assertTrue(past.items.isEmpty())
        assertNull(past.nextOffset)

        assertEquals(5, page(state, LocalAudioQuery(limit = 2, expectedRevision = 7)).total)
        assertFailure(LocalAudioError.STALE_STATE) {
            page(state, LocalAudioQuery(expectedRevision = 6))
        }
    }

    @Test
    fun progressIndependentPagesContinueAcrossProgressChanges() {
        val songs = (1..4).map { track(it, title = "Song $it") }
        val firstState = library(songs, revision = 10, organizationRevision = 20, progressRevision = 30)
        val first = page(firstState, LocalAudioQuery(limit = 2))
        assertNull(first.token?.progressRevision)

        val changed = firstState.copy(
            revision = 11,
            progressRevision = 31,
            progress = listOf(LocalAudioProgress(songs[0].id, 1_000, false, 1)),
        )
        val second = page(changed, LocalAudioQuery(offset = 2, limit = 2, expectedToken = first.token))

        assertEquals(listOf(songs[2], songs[3]), second.items)
        assertTrue(first.isCurrent(changed))
        assertTrue(LocalAudioQuery(limit = 2).isCurrent(changed, first))
    }

    @Test
    fun progressDependentPagesRejectProgressChanges() {
        val first = track(1, title = "Started")
        val second = track(2, title = "Unplayed")
        val firstState = library(
            listOf(first, second),
            revision = 10,
            organizationRevision = 20,
            progressRevision = 30,
            progress = listOf(LocalAudioProgress(first.id, 1_000, false, 1)),
        )
        val request = LocalAudioQuery(progress = LocalAudioProgressFilter.IN_PROGRESS, limit = 1)
        val page = page(firstState, request)
        assertEquals(30L, page.token?.progressRevision)

        val changed = firstState.copy(
            revision = 11,
            progressRevision = 31,
            progress = listOf(LocalAudioProgress(first.id, 2_000, false, 2)),
        )
        assertFailure(LocalAudioError.STALE_STATE) {
            page(changed, request.copy(offset = 1, expectedToken = page.token))
        }
        assertFalse(page.isCurrent(changed))
        assertFalse(request.isCurrent(changed, page))
    }

    @Test
    fun lastProgressSortRejectsProgressChanges() {
        val first = track(1)
        val second = track(2)
        val firstState = library(
            listOf(first, second),
            revision = 10,
            organizationRevision = 20,
            progressRevision = 30,
            progress = listOf(LocalAudioProgress(first.id, 1_000, false, 1)),
        )
        val request = LocalAudioQuery(sort = LocalAudioSort.LAST_PROGRESS, limit = 1)
        val page = page(firstState, request)

        assertFailure(LocalAudioError.STALE_STATE) {
            page(firstState.copy(revision = 11, progressRevision = 31),
                request.copy(offset = 1, expectedToken = page.token))
        }
    }

    @Test
    fun organizationChangesRejectEveryToken() {
        val songs = (1..2).map { track(it) }
        val firstState = library(songs, revision = 10, organizationRevision = 20, progressRevision = 30)
        val request = LocalAudioQuery(limit = 1)
        val page = page(firstState, request)
        val changed = firstState.copy(revision = 11, organizationRevision = 21)

        assertFailure(LocalAudioError.STALE_STATE) {
            page(changed, request.copy(offset = 1, expectedToken = page.token))
        }
        assertFalse(page.isCurrent(changed))
        assertFalse(request.isCurrent(changed, page))
    }

    @Test
    fun tokensCannotBeMovedToAnotherQuery() {
        val firstState = library((1..3).map { track(it, title = "Song $it") },
            revision = 10, organizationRevision = 20)
        val original = LocalAudioQuery(text = "Song 1")
        val page = page(firstState, original)
        val other = original.copy(text = "Song 2", descending = true)

        assertFailure(LocalAudioError.INVALID_TARGET) {
            page(firstState, other.copy(offset = 1, expectedToken = page.token))
        }
        assertFalse(other.isCurrent(firstState, page))
    }

    @Test
    fun tokenIdentityExcludesPagingAndLegacyRevisionChecks() {
        val songs = (1..4).map { track(it, title = "Song $it") }
        val firstState = library(songs, revision = 10, organizationRevision = 20, progressRevision = 30)
        val first = page(firstState, LocalAudioQuery(text = "Song", limit = 2))
        val continuation = LocalAudioQuery(
            text = "Song",
            offset = 2,
            limit = 1,
            expectedRevision = firstState.revision,
            expectedToken = first.token,
        )

        assertEquals(listOf(songs[2]), page(firstState, continuation).items)
        assertTrue(continuation.isCurrent(firstState, page(firstState, continuation)))
    }

    @Test
    fun requestTokenCannotValidateALegacyPage() {
        val state = library((1..2).map { track(it) })
        val tokenPage = page(state, LocalAudioQuery(limit = 1))
        val legacyPage = tokenPage.copy(token = null)

        assertFalse(
            LocalAudioQuery(limit = 1, expectedToken = tokenPage.token).isCurrent(state, legacyPage),
        )
        assertTrue(LocalAudioQuery(limit = 1).isCurrent(state, legacyPage))
    }

    @Test
    fun legacyRevisionStillRejectsProgressChanges() {
        val songs = (1..3).map { track(it) }
        val firstState = library(songs, revision = 10, organizationRevision = 20, progressRevision = 30)

        assertFailure(LocalAudioError.STALE_STATE) {
            page(
                firstState.copy(revision = 11, progressRevision = 31),
                LocalAudioQuery(offset = 1, expectedRevision = firstState.revision),
            )
        }
    }

    @Test
    fun legacyProtectedPagesRemainStrictAtPublication() {
        val state = library((1..3).map { track(it) }, revision = 10,
            organizationRevision = 20, progressRevision = 30)
        val request = LocalAudioQuery(limit = 1, expectedRevision = state.revision)
        val page = page(state, request)

        assertNull(page.token?.query?.expectedRevision)
        assertEquals(10L, page.token?.legacyRevision)
        assertTrue(request.isCurrent(state, page))
        val next = page(
            state,
            LocalAudioQuery(offset = 1, limit = 1, expectedToken = page.token),
        )
        assertEquals(10L, next.token?.legacyRevision)
        assertFalse(request.isCurrent(state.copy(revision = 11, progressRevision = 31), page))
        assertFalse(page.isCurrent(state.copy(revision = 11, progressRevision = 31)))
        assertFalse(next.isCurrent(state.copy(revision = 11, progressRevision = 31)))
    }

    @Test
    fun requestRevisionCannotBeRelaxedByATokenPage() {
        val state = library((1..2).map { track(it) })
        val page = page(state, LocalAudioQuery(limit = 1))

        assertFalse(
            LocalAudioQuery(limit = 1, expectedRevision = state.revision - 1).isCurrent(state, page),
        )
    }

    @Test
    fun unreadyStateInvalidatesNewAndLegacyPages() {
        val state = library((1..2).map { track(it) }, revision = 10)
        val request = LocalAudioQuery(limit = 1)
        val page = page(state, request)
        val legacyPage = page.copy(token = null)
        val unready = state.copy(ready = false)

        assertFalse(request.isCurrent(unready, page))
        assertFalse(page.isCurrent(unready))
        assertFalse(request.isCurrent(unready, legacyPage))
        assertFalse(legacyPage.isCurrent(unready))
    }

    @Test
    fun continuationRequiresATokenOrLegacyRevision() {
        val state = library((1..3).map { track(it) })

        assertFailure(LocalAudioError.INVALID_TARGET) {
            page(state, LocalAudioQuery(offset = 1))
        }
        assertEquals(
            1,
            page(state, LocalAudioQuery(offset = 1, limit = 1, expectedRevision = state.revision)).items.size,
        )
    }

    @Test
    fun firstPageMayStartWithoutATokenOrLegacyRevision() {
        val state = library((1..3).map { track(it) })

        assertEquals(1, page(state, LocalAudioQuery(limit = 1)).items.size)
    }

    @Test
    fun rejectsAnUnreadyLibraryAndAMissingFolder() {
        val unready = library(listOf(track(1, title = "Hidden")), ready = false)
        assertFailure(LocalAudioError.STORAGE_FAILED) { page(unready, LocalAudioQuery()) }
        assertFailure(LocalAudioError.STORAGE_FAILED) {
            LocalAudioSearch.ordered(unready, null, LocalAudioSort.TITLE, descending = false)
        }

        val ready = library(listOf(track(1, title = "Song")))
        assertFailure(LocalAudioError.NOT_FOUND) {
            page(ready, LocalAudioQuery(folderId = id(42)))
        }
        assertFailure(LocalAudioError.NOT_FOUND) {
            LocalAudioSearch.ordered(ready, id(42), LocalAudioSort.MANUAL, descending = false)
        }
    }

    @Test
    fun stopsWhenCancelled() {
        val state = library((1..3).map { track(it, title = "Song $it") })
        var checks = 0
        try {
            page(state, LocalAudioQuery()) { if (++checks > 1) throw Cancelled() }
            fail("query must observe cancellation")
        } catch (expected: Cancelled) {
            assertTrue(checks > 1)
        }
    }

    private class Cancelled : RuntimeException()

    private fun page(
        state: LocalAudioLibraryState,
        request: LocalAudioQuery,
        checkCancelled: () -> Unit = {},
    ): LocalAudioSearchPage = LocalAudioSearch.query(state, request, checkCancelled)

    private fun search(
        state: LocalAudioLibraryState,
        text: String,
        folderId: String? = null,
        unfiledOnly: Boolean = false,
        mode: LocalImportMode? = null,
        availability: LocalAudioAvailability? = null,
        progress: LocalAudioProgressFilter = LocalAudioProgressFilter.ANY,
        bookmarkedOnly: Boolean = false,
    ): LocalAudioSearchPage = page(
        state,
        LocalAudioQuery(
            text = text,
            folderId = folderId,
            unfiledOnly = unfiledOnly,
            mode = mode,
            availability = availability,
            progress = progress,
            bookmarkedOnly = bookmarkedOnly,
        ),
    )

    private fun assertFailure(expected: LocalAudioError, block: () -> Unit) {
        try {
            block()
            fail("Expected $expected")
        } catch (failure: LocalAudioFailure) {
            assertEquals(expected, failure.error)
        }
    }

    private fun id(seed: Int): String = String.format(Locale.ROOT, "%08d-0000-0000-0000-000000000000", seed)

    /** Folder ids use a distinct tail so they can never collide with audio ids. */
    private fun folder(seed: Int, name: String): LocalAudioFolder =
        LocalAudioFolder(String.format(Locale.ROOT, "%08x-0000-0000-0000-0000000000f0", seed), name)

    private fun track(
        seed: Int,
        title: String? = null,
        fileName: String = "track-$seed.mp3",
        creator: String? = null,
        duration: Long? = null,
        mode: LocalImportMode = LocalImportMode.COPY,
        availability: LocalAudioAvailability = LocalAudioAvailability.AVAILABLE,
    ): LocalAudioItem = LocalAudioItem(
        id = id(seed),
        metadata = LocalAudioMetadata(
            fileName = fileName,
            title = title,
            creator = creator,
            durationMs = duration,
        ),
        mode = mode,
        availability = availability,
        addedAtMs = seed.toLong(),
    )

    private fun library(
        items: List<LocalAudioItem>,
        folders: List<LocalAudioFolder> = emptyList(),
        members: List<LocalAudioMember> = emptyList(),
        progress: List<LocalAudioProgress> = emptyList(),
        bookmarks: List<LocalAudioBookmark> = emptyList(),
        ready: Boolean = true,
        revision: Long = 1,
        organizationRevision: Long = revision,
        progressRevision: Long = revision,
    ): LocalAudioLibraryState = LocalAudioLibraryState(
        ready = ready,
        revision = revision,
        organizationRevision = organizationRevision,
        progressRevision = progressRevision,
        folders = folders,
        items = items,
        members = members,
        progress = progress,
        bookmarks = bookmarks,
    )
}
