package tech.musxi.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibraryCacheIdentity
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary

class PlaybackJournalBrowserTest {
    @Test fun checkpointsDuringRestoreCannotHideItsAcknowledgementOrEndRestoringEarly() {
        val harness = Harness(identity())
        harness.browser.refresh(Platform.NETEASE)
        harness.succeed(harness.lastAccess(), PlaybackJournal(saved()))
        harness.browser.restore(Platform.NETEASE)
        val ack = harness.submissions.single().callback
        harness.browser.save(Platform.NETEASE, 1, saved(positionMs = 123))
        harness.succeed(harness.lastAccess())
        assertTrue(harness.state().restoring)
        harness.browser.refresh(Platform.NETEASE)
        harness.succeed(harness.lastAccess(), PlaybackJournal(saved()))
        assertTrue(harness.state().restoring)
        harness.browser.clearRecent(Platform.NETEASE)
        harness.succeed(harness.lastAccess(), PlaybackJournal(saved()))
        assertTrue(harness.state().restoring)
        ack(Result.failure(Exception("service rejected")))
        assertFalse(harness.state().restoring)
        assertEquals("player", harness.state().error)

        harness.browser.restore(Platform.NETEASE)
        harness.browser.save(Platform.NETEASE, 1, saved())
        harness.fail(harness.lastAccess(), Exception("disk failed"))
        harness.submissions.last().callback(Result.success(Unit))
        assertFalse(harness.state().restoring)
        assertEquals("storage", harness.state().error)
    }

    @Test fun verificationLossRejectsPendingSaveEvenWithoutGenerationChange() {
        val harness = Harness(identity())
        harness.browser.save(Platform.NETEASE, 1, saved())
        val write = harness.lastAccess()
        harness.identities[Platform.NETEASE] = identity(verified = false)
        harness.succeed(write)
        assertFalse(harness.state().accountVerified)
        assertNull(harness.state().queue)
    }

    private data class AccessCall(
        val platform: Platform,
        val expected: LibraryCacheIdentity,
        val transform: ((PlaybackJournal) -> PlaybackJournal)?,
        val callback: (Result<PlaybackJournal>) -> Unit,
    )

    private data class Submission(
        val queue: SavedPlaybackQueue,
        val callback: (Result<Unit>) -> Unit,
    )

    private class Harness(initialIdentity: LibraryCacheIdentity?) {
        val identities = Platform.entries
            .associateWith { initialIdentity }
            .toMutableMap()
        val accesses = mutableListOf<AccessCall>()
        val submissions = mutableListOf<Submission>()
        val browser = PlaybackJournalBrowser(
            { identities[it] },
            { platform, expected, transform, callback ->
                accesses += AccessCall(platform, expected, transform, callback)
            },
            { queue, callback -> submissions += Submission(queue, callback) },
        )

        fun succeed(
            call: AccessCall,
            stored: PlaybackJournal = PlaybackJournal(),
        ) {
            call.callback(Result.success(call.transform?.invoke(stored) ?: stored))
        }

        fun fail(call: AccessCall, error: Throwable) {
            call.callback(Result.failure(error))
        }

        fun lastAccess(platform: Platform = Platform.NETEASE) =
            accesses.last { it.platform == platform }

        fun state(platform: Platform = Platform.NETEASE) =
            browser.state.value.getValue(platform)
    }

    @Test
    fun unverifiedIdentityDisplaysSavedStateButCannotRestore() {
        val harness = Harness(identity(verified = false))
        val saved = saved()

        harness.browser.refresh(Platform.NETEASE)
        harness.succeed(harness.lastAccess(), PlaybackJournal(queue = saved))

        assertEquals(saved, harness.state().queue)
        assertFalse(harness.state().accountVerified)

        harness.browser.restore(Platform.NETEASE)

        assertEquals("account_unavailable", harness.state().error)
        assertTrue(harness.submissions.isEmpty())
    }

    @Test
    fun ownerOrGenerationChangeClearsStateAndRejectsOldReadCallback() {
        val changes = listOf<Pair<String, (Harness) -> Unit>>(
            "owner" to { it.identities[Platform.NETEASE] = identity(owner = "b") },
            "generation" to { it.identities[Platform.NETEASE] = identity(generation = 2) },
        )

        changes.forEach { (label, change) ->
            val harness = Harness(identity())
            harness.browser.refresh(Platform.NETEASE)
            val previousRead = harness.lastAccess()
            harness.succeed(previousRead, PlaybackJournal(queue = saved(song("shown"))))

            change(harness)
            harness.browser.accountChanged()
            val currentRead = harness.lastAccess()

            assertNull("$label change must clear the queue", harness.state().queue)
            assertTrue("$label change must issue a new read", harness.state().loading)

            harness.browser.restore(Platform.NETEASE)
            assertEquals("$label change must reject restore", "account_unavailable", harness.state().error)

            harness.succeed(previousRead, PlaybackJournal(queue = saved(song("stale"))))
            assertNull("$label old callback must be ignored", harness.state().queue)
            assertTrue(harness.state().loading)

            harness.succeed(currentRead, PlaybackJournal(queue = saved(song("current"))))
            assertEquals("current", harness.state().queue!!.songs.single().title)
            assertFalse(harness.state().loading)
        }
    }

    @Test
    fun restoreSubmitsCompleteSavedQueueAndCancelRejectsOldAcknowledgement() {
        val harness = Harness(identity())
        val saved = saved(
            songs = listOf(song("first"), song("second")),
            index = 1,
            positionMs = 42_123,
            repeatMode = "ALL",
            shuffleEnabled = true,
            savedAtEpochMs = 9_876,
        )
        harness.browser.refresh(Platform.NETEASE)
        harness.succeed(harness.lastAccess(), PlaybackJournal(queue = saved))

        harness.browser.restore(Platform.NETEASE)
        val oldSubmission = harness.submissions.single()

        assertEquals(saved, oldSubmission.queue)
        assertTrue(harness.state().restoring)

        harness.browser.cancelRestore()
        oldSubmission.callback(Result.failure(IllegalStateException("stale ack")))
        assertFalse(harness.state().restoring)
        assertNull(harness.state().error)

        harness.browser.restore(Platform.NETEASE)
        val currentSubmission = harness.submissions.last()
        assertTrue(harness.state().restoring)
        assertNull(harness.state().error)

        currentSubmission.callback(Result.success(Unit))
        assertFalse(harness.state().restoring)
        assertNull(harness.state().error)
        assertEquals(saved, currentSubmission.queue)
    }

    @Test
    fun saveRejectsPreviousReadCallback() {
        val harness = Harness(identity())
        val previous = saved(song("previous"))
        val next = saved(song("next"), positionMs = 8_000)
        harness.browser.refresh(Platform.NETEASE)
        val previousRead = harness.lastAccess()
        harness.succeed(previousRead, PlaybackJournal(queue = previous))

        harness.browser.save(Platform.NETEASE, 1, next)
        val saveRead = harness.lastAccess()
        assertEquals(harness.identities.getValue(Platform.NETEASE), saveRead.expected)
        assertNotNull(saveRead.transform)

        harness.succeed(previousRead, PlaybackJournal(queue = previous))
        assertEquals(previous, harness.state().queue)

        harness.succeed(saveRead, PlaybackJournal(queue = previous))
        assertEquals(next, harness.state().queue)
    }

    @Test
    fun recentUsesOriginalKeyForDeduplicationCapsAtOneHundredAndKeepsSource() {
        val harness = Harness(identity())
        harness.browser.refresh(Platform.NETEASE)
        val initial = (0 until 100).map { index ->
            recent(
                original = song("original-$index", Platform.NETEASE),
                source = song("old-source-$index", Platform.NETEASE),
            )
        }
        harness.succeed(harness.lastAccess(), PlaybackJournal(queue = saved(), recent = initial))

        val newest = recent(
            original = song("original-0", Platform.NETEASE).copy(title = "Updated title"),
            source = song("qq-source", Platform.QQ),
        )
        harness.browser.save(Platform.NETEASE, 1, saved(), newest)
        harness.succeed(harness.lastAccess(), PlaybackJournal(queue = saved(), recent = initial))

        val result = harness.state().recent
        assertEquals(100, result.size)
        assertEquals(newest.original, result.first().original)
        assertEquals(Platform.QQ, result.first().source.key.platform)
        assertEquals("qq-source", result.first().source.title)
        assertEquals(result.size, result.map { it.original.key }.distinct().size)
        assertFalse(result.drop(1).any { it.original.key == newest.original.key })
    }

    @Test
    fun clearRecentPreservesSavedQueue() {
        val harness = Harness(identity())
        val saved = saved(song("saved"))
        harness.browser.refresh(Platform.NETEASE)
        val journal = PlaybackJournal(
            queue = saved,
            recent = listOf(recent(song("original"), song("source"))),
        )
        harness.succeed(
            harness.lastAccess(),
            journal,
        )

        harness.browser.clearRecent(Platform.NETEASE)
        val clearCall = harness.lastAccess()
        assertNotNull(clearCall.transform)
        harness.succeed(clearCall, journal)

        assertEquals(saved, harness.state().queue)
        assertTrue(harness.state().recent.isEmpty())
    }

    @Test
    fun storageFailuresUseFixedCategories() {
        val harness = Harness(identity())
        harness.browser.refresh(Platform.NETEASE)

        harness.fail(harness.lastAccess(), IllegalStateException("must not leak"))
        assertEquals("storage", harness.state().error)

        harness.browser.refresh(Platform.NETEASE)
        harness.fail(harness.lastAccess(), LibraryPageFailure("network"))
        assertEquals("storage", harness.state().error)

        harness.browser.refresh(Platform.NETEASE)
        harness.fail(harness.lastAccess(), LibraryPageFailure("account_changed"))
        assertEquals("account_changed", harness.state().error)
    }

    private fun identity(
        owner: String = "a",
        generation: Long = 1,
        verified: Boolean = true,
    ) = LibraryCacheIdentity(owner, generation, verified)

    private fun saved(
        song: SongSummary = song("saved"),
        songs: List<SongSummary> = listOf(song),
        index: Int = 0,
        positionMs: Long = 1_000,
        repeatMode: String = "OFF",
        shuffleEnabled: Boolean = false,
        savedAtEpochMs: Long = 10_000,
    ) = SavedPlaybackQueue(
        songs = songs,
        index = index,
        positionMs = positionMs,
        repeatMode = repeatMode,
        shuffleEnabled = shuffleEnabled,
        savedAtEpochMs = savedAtEpochMs,
    )

    private fun recent(
        original: SongSummary,
        source: SongSummary,
    ) = RecentPlay(original, source, playedAtEpochMs = 1)

    private fun song(
        id: String,
        platform: Platform = Platform.NETEASE,
    ) = SongSummary(
        key = MediaKey(platform, id),
        title = id,
        artists = listOf("Artist"),
        album = "Album",
        durationMs = 1_000,
        coverUrl = null,
    )
}
