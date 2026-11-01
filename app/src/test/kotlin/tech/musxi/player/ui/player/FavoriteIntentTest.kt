package tech.musxi.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.FavoritesState
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary

/**
 * The heart's tap is an intent that waits for the backend. These cases pin the order of events the
 * shell relies on: queue while syncing, submit exactly once, then confirm or fail with a reason.
 */
class FavoriteIntentTest {

    private val song = SongSummary(MediaKey(Platform.NETEASE, "1"), "晴天", listOf("周杰伦"), "叶惠美", 269_000)
    private val other = MediaKey(Platform.NETEASE, "2")

    private fun state(
        keys: Set<MediaKey> = emptySet(),
        synced: Boolean = true,
        loading: Boolean = false,
        pending: MediaKey? = null,
        canWrite: Boolean = true,
        error: String? = null,
        queued: List<MediaKey> = emptyList(),
    ) = FavoritesState(
        keys = keys,
        synced = synced,
        loading = loading,
        pending = pending,
        canWrite = canWrite,
        error = error,
        queuedKeys = queued,
    )

    private fun intent(
        liked: Boolean = true,
        submitted: Boolean = false,
        attempts: Int = 0,
        startedAtMs: Long = 0,
    ) = FavoriteIntent(song, liked, submitted, attempts, startedAtMs)

    @Test fun waitsWhileTheCollectionIsStillSyncing() {
        val syncing = state(synced = false, loading = true)
        assertEquals(FavoriteStep.Wait, nextFavoriteStep(syncing, intent(), 1_000))
    }

    @Test fun waitsWhileAnotherSongIsBeingWritten() {
        val busy = state(loading = true, pending = other)
        assertEquals(FavoriteStep.Wait, nextFavoriteStep(busy, intent(), 1_000))
    }

    @Test fun submitsOnceAfterTheSyncFinishes() {
        val queued = intent()
        assertEquals(FavoriteStep.Wait, nextFavoriteStep(state(synced = false, loading = true), queued, 1_000))

        val step = nextFavoriteStep(state(), queued, 2_000)
        val submit = step as FavoriteStep.Submit
        assertTrue(submit.intent.submitted)
        assertEquals(1, submit.intent.attempts)
        assertEquals(song, submit.intent.song)
        assertTrue(submit.intent.liked)

        // The backend now reports the write in flight; the submitted copy must not be sent again.
        val writing = state(loading = true, pending = song.key)
        assertEquals(FavoriteStep.Wait, nextFavoriteStep(writing, submit.intent, 3_000))
    }

    @Test fun waitsWhileOurWriteIsQueuedBehindAnother() {
        // The backend runs writes in order: ours sits in queuedKeys while another song is pending.
        // Neither "pending" nor "loading" names us, yet the write is not over and must not be resent.
        val submitted = intent(submitted = true, attempts = 1)
        val queued = state(pending = other, queued = listOf(song.key))
        assertEquals(FavoriteStep.Wait, nextFavoriteStep(queued, submitted, 5_000))
        assertTrue(favoriteUiOf(queued, song, null)!!.syncing)
    }

    @Test fun waitsWhileOurOwnWriteIsPending() {
        val submitted = intent(submitted = true, attempts = 1)
        val writing = state(loading = true, pending = song.key)
        assertEquals(FavoriteStep.Wait, nextFavoriteStep(writing, submitted, 5_000))
    }

    @Test fun doneWhenTheVerifiedCollectionMatches() {
        val submitted = intent(liked = true, submitted = true, attempts = 1)
        assertEquals(FavoriteStep.Done, nextFavoriteStep(state(keys = setOf(song.key)), submitted, 5_000))

        val unlike = intent(liked = false, submitted = true, attempts = 1)
        assertEquals(FavoriteStep.Done, nextFavoriteStep(state(keys = emptySet()), unlike, 5_000))
    }

    @Test fun failsWithTheCategoryWhenTheWriteLeavesTheCollectionUnsynced() {
        val submitted = intent(submitted = true, attempts = 1)
        val failed = state(synced = false, error = "network")
        assertEquals(
            FavoriteStep.Fail("网络异常，收藏未生效"),
            nextFavoriteStep(failed, submitted, 5_000),
        )
    }

    @Test fun failsWhenThePlatformDidNotConfirmTheWrite() {
        val submitted = intent(submitted = true, attempts = 1)
        val unverified = state(synced = false, error = "write_unverified")
        assertEquals(
            FavoriteStep.Fail("平台没有确认这次收藏，请稍后再试"),
            nextFavoriteStep(unverified, submitted, 5_000),
        )
    }

    @Test fun doneAtOnceWhenTheSongAlreadyHasTheRequestedState() {
        assertEquals(
            FavoriteStep.Done,
            nextFavoriteStep(state(keys = setOf(song.key)), intent(liked = true), 1_000),
        )
        assertEquals(
            FavoriteStep.Done,
            nextFavoriteStep(state(keys = emptySet()), intent(liked = false), 1_000),
        )
    }

    @Test fun failsWhenTheAccountCannotWrite() {
        assertEquals(
            FavoriteStep.Fail("当前账号不能修改收藏"),
            nextFavoriteStep(state(canWrite = false), intent(), 1_000),
        )
    }

    @Test fun submitsAgainWhenTheUserChangedTheirMindDuringVerification() {
        // The write for "like" was verified, but the user has since asked for "unlike".
        val reconsidered = intent(liked = false, submitted = true, attempts = 1)
        val verified = state(keys = setOf(song.key))
        val again = nextFavoriteStep(verified, reconsidered, 5_000) as FavoriteStep.Submit
        assertEquals(2, again.intent.attempts)
        assertTrue(again.intent.submitted)
        assertFalse(again.intent.liked)

        val third = nextFavoriteStep(verified, again.intent, 6_000) as FavoriteStep.Submit
        assertEquals(3, third.intent.attempts)

        assertEquals(
            FavoriteStep.Fail("没能确认收藏状态，请稍后再试"),
            nextFavoriteStep(verified, third.intent, 7_000),
        )
    }

    @Test fun failsAfterTwentySeconds() {
        val old = intent(startedAtMs = 1_000)
        assertEquals(FavoriteStep.Wait, nextFavoriteStep(state(loading = true), old, 21_000))
        assertEquals(
            FavoriteStep.Fail("收藏同步太久，请稍后再试"),
            nextFavoriteStep(state(loading = true), old, 21_001),
        )
        // The limit applies to a submitted intent too.
        val submitted = intent(submitted = true, attempts = 1, startedAtMs = 1_000)
        assertEquals(
            FavoriteStep.Fail("收藏同步太久，请稍后再试"),
            nextFavoriteStep(state(loading = true, pending = song.key), submitted, 30_000),
        )
    }

    @Test fun failureTextNamesTheCause() {
        assertEquals("当前账号不能修改收藏", favoriteFailureText("permission"))
        listOf("account_unverified", "expired", "account_unavailable", "account_changed").forEach {
            assertEquals("账号需要重新验证，请到「我的」检查", favoriteFailureText(it))
        }
        listOf("network", "timeout", "http").forEach {
            assertEquals("网络异常，收藏未生效", favoriteFailureText(it))
        }
        listOf("rate", "risk", "limit").forEach {
            assertEquals("操作太频繁，请稍后再试", favoriteFailureText(it))
        }
        assertEquals("平台没有确认这次收藏，请稍后再试", favoriteFailureText("write_unverified"))
        assertEquals("收藏未生效，请稍后重试", favoriteFailureText("protocol"))
        assertEquals("收藏未生效，请稍后重试", favoriteFailureText(null))
    }

    @Test fun uiShowsTheRequestedStateAtOnceAndMarksItAsSyncing() {
        val confirmedLiked = state(keys = setOf(song.key))
        val unlike = intent(liked = false)
        val ui = favoriteUiOf(confirmedLiked, song, unlike)!!
        assertFalse(ui.liked)
        assertTrue(ui.syncing)
        assertTrue(ui.enabled)

        val like = favoriteUiOf(state(), song, intent(liked = true))!!
        assertTrue(like.liked)
        assertTrue(like.syncing)
    }

    @Test fun uiShowsTheConfirmedStateWithoutAnIntent() {
        val liked = favoriteUiOf(state(keys = setOf(song.key)), song, null)!!
        assertTrue(liked.liked)
        assertFalse(liked.syncing)

        val writingOther = favoriteUiOf(state(loading = true, pending = other), song, null)!!
        assertFalse(writingOther.liked)
        assertFalse(writingOther.syncing)

        val writingThis = favoriteUiOf(state(loading = true, pending = song.key), song, null)!!
        assertTrue(writingThis.syncing)
    }

    @Test fun uiIgnoresAnIntentForAnotherSong() {
        val ofOther = FavoriteIntent(
            song.copy(key = other), liked = true, startedAtMs = 0,
        )
        val ui = favoriteUiOf(state(), song, ofOther)!!
        assertFalse(ui.liked)
        assertFalse(ui.syncing)
    }

    @Test fun uiStaysTappableUntilTheCollectionIsKnownToBeReadOnly() {
        assertTrue(favoriteUiOf(state(synced = false, canWrite = false), song, null)!!.enabled)
        assertTrue(favoriteUiOf(state(synced = true, canWrite = true), song, null)!!.enabled)
        assertFalse(favoriteUiOf(state(synced = true, canWrite = false), song, null)!!.enabled)
    }

    @Test fun noUiWithoutASongOrACollection() {
        assertNull(favoriteUiOf(null, song, null))
        assertNull(favoriteUiOf(state(), null, null))
    }
}
