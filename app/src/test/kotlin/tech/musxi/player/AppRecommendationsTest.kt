package tech.musxi.player

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*
import tech.musxi.core.recommendation.*

class AppRecommendationsTest {
    @Test fun disabledPlayIsRejectedBeforeSubmissionAndDoesNotRevokeAcceptedQueue() {
        val platform = Platform.NETEASE
        val identity = LibraryCacheIdentity("a".repeat(64), 9, true)
        val song = SongSummary(MediaKey(platform, "1"), "Title", listOf("Artist"), "Album", 1000)
        var enabled = true
        var callback: ((Result<List<SongSummary>>) -> Unit)? = null
        val store = object : RecommendationStore {
            var saved: RecommendationSnapshot? = null
            override fun read(owner: String) = saved
            override fun write(owner: String, snapshot: RecommendationSnapshot) { saved = snapshot }
            override fun clear() { saved = null }
        }
        val browser = RecommendationsBrowser({ identity }, { _, _, action -> action(store) },
            RecommendationFetch { _, _, result -> callback = result }, { it() }, { it() },
            isEnabled = { enabled }, clearCache = { store.clear() })
        val submitted = mutableListOf<PlaybackQueue>()
        val facade = AppRecommendations(browser) { queue, accepted -> submitted += queue; accepted(Result.success(Unit)) }
        facade.open(platform)
        callback!!(Result.success(listOf(song)))
        val revision = facade.state.value.getValue(platform).revision
        var result: Result<Unit>? = null
        facade.play(platform, expectedRevision = revision) { result = it }
        assertTrue(result!!.isSuccess)

        // The live setting also protects the interval before its synchronous notification.
        enabled = false
        facade.play(platform, expectedRevision = revision) { result = it }
        assertEquals("recommendations_disabled", (result!!.exceptionOrNull() as LibraryPageFailure).category)
        browser.setEnabled(false)
        facade.open(platform)
        facade.refresh(platform)
        facade.play(platform, expectedRevision = revision) { result = it }
        assertEquals("recommendations_disabled", (result!!.exceptionOrNull() as LibraryPageFailure).category)
        assertEquals(listOf(song), submitted.single().songs)
        assertNull(facade.state.value.getValue(platform).snapshot)
    }

    @Test fun playSubmitsFullSnapshotAtSelectedIndexAndRejectsRefreshRace() {
        val platform = Platform.NETEASE
        val song = SongSummary(MediaKey(platform, "1"), "Title", listOf("Artist"), "Album", 1000)
        val identity = LibraryCacheIdentity("a".repeat(64), 9, true)
        var callback: ((Result<List<SongSummary>>) -> Unit)? = null
        val store = object : RecommendationStore {
            var saved: RecommendationSnapshot? = null
            override fun read(owner: String) = saved
            override fun write(owner: String, snapshot: RecommendationSnapshot) { saved = snapshot }
            override fun clear() { saved = null }
        }
        val browser = RecommendationsBrowser({ identity }, { _, _, action -> action(store) },
            RecommendationFetch { _, _, result -> callback = result }, { it() }, { it() })
        val submitted = mutableListOf<PlaybackQueue>()
        val facade = AppRecommendations(browser) { queue, accepted -> submitted += queue; accepted(Result.success(Unit)) }
        facade.open(platform)
        callback!!(Result.success(listOf(song, song.copy(key = MediaKey(platform, "2")))))
        val revision = facade.state.value.getValue(platform).revision
        var accepted: Result<Unit>? = null
        facade.play(platform, 1, revision) { accepted = it }
        assertTrue(accepted!!.isSuccess)
        assertEquals(2, submitted.single().songs.size)
        assertEquals(1, submitted.single().startIndex)
        assertEquals(9L, submitted.single().accountGeneration)
        facade.clearCachedResults()
        facade.play(platform, 1, revision) { accepted = it }
        assertTrue(accepted!!.isFailure)
        assertEquals(1, submitted.size)
    }
}
