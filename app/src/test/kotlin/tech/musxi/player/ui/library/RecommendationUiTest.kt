package tech.musxi.player.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.LibrarySource
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary
import tech.musxi.core.recommendation.RecommendationKind
import tech.musxi.core.recommendation.RecommendationSnapshot
import tech.musxi.core.recommendation.RecommendationState

class RecommendationUiTest {
    private fun song(id: String, artist: String = "歌手$id") = SongSummary(
        key = MediaKey(Platform.NETEASE, id), title = "歌 $id", artists = listOf(artist),
        album = "专辑", durationMs = 200_000,
    )

    private fun snapshot(vararg ids: String) =
        RecommendationSnapshot(Platform.NETEASE, ids.map { song(it) }, fetchedAtEpochMs = 1_000)

    private val daily = RecommendationState(kind = RecommendationKind.DAILY_SONGS, accountVerified = true)

    @Test fun noAccountAndNoCacheHidesTheCard() {
        assertNull(recommendationCardOf(daily.copy(accountVerified = false)))
        assertNull(recommendationCardOf(null))
    }

    @Test fun phasesFollowTheState() {
        assertEquals(RecommendationPhase.LOADING, recommendationCardOf(daily.copy(loading = true))?.phase)
        val failed = recommendationCardOf(daily.copy(error = "network"))
        assertEquals(RecommendationPhase.FAILED, failed?.phase)
        assertEquals("网络不太稳，稍后再试", failed?.failure)
        // A cancel is not a failure: the skeleton stays until the next open.
        assertEquals(RecommendationPhase.LOADING, recommendationCardOf(daily.copy(error = "cancelled"))?.phase)
        assertEquals(RecommendationPhase.EMPTY, recommendationCardOf(daily.copy(snapshot = snapshot()))?.phase)
        val ready = recommendationCardOf(daily.copy(snapshot = snapshot("a", "b"), stale = true, refreshing = true))
        assertEquals(RecommendationPhase.READY, ready?.phase)
        assertEquals(2, ready?.songs?.size)
        assertTrue(ready!!.stale && ready.refreshing)
    }

    @Test fun qqIsCalledByItsOwnName() {
        val qq = RecommendationState(kind = RecommendationKind.PERSONAL_RADIO, accountVerified = true, loading = true)
        assertEquals("猜你喜欢", recommendationCardOf(qq)?.heading)
    }

    @Test fun aStaleCacheStaysVisibleWithoutAnAccount() {
        val card = recommendationCardOf(daily.copy(accountVerified = false, snapshot = snapshot("a"), stale = true))
        assertEquals(RecommendationPhase.READY, card?.phase)
        assertTrue(card!!.stale)
    }

    @Test fun listPageIsDisplayOnly() {
        val tracks = recommendationTracksOf(
            Platform.NETEASE,
            daily.copy(snapshot = snapshot("a", "b", "c"), source = LibrarySource.CACHE, refreshing = true),
        )
        assertEquals("每日推荐", tracks.playlist?.title)
        assertEquals(3, tracks.playlist?.trackCount)
        assertEquals(3, tracks.items.size)
        assertEquals(1_000L, tracks.fetchedAtEpochMs)
        assertTrue(tracks.fromCache && tracks.refreshing)
        // The whole list is there: nothing to page through.
        assertFalse(tracks.canLoadMore)
    }

    @Test fun listPageFailsOnlyWithoutAList() {
        assertEquals("network", recommendationTracksOf(Platform.NETEASE, daily.copy(error = "network")).error)
        // A refresh that failed keeps the old list on screen and says so in a notice instead.
        assertNull(recommendationTracksOf(Platform.NETEASE, daily.copy(snapshot = snapshot("a"), error = "network")).error)
        assertNull(recommendationTracksOf(Platform.NETEASE, daily.copy(error = "cancelled")).error)
        assertTrue(recommendationTracksOf(Platform.NETEASE, daily.copy(loading = true)).loading)
        assertFalse(recommendationTracksOf(Platform.NETEASE, daily.copy(loading = true, snapshot = snapshot("a"))).loading)
    }

    @Test fun refreshNotices() {
        assertNull(recommendationRefreshNotice(daily))
        assertNull(recommendationRefreshNotice(daily.copy(error = "cancelled")))
        assertEquals("刚刷新过，12 秒后再试", recommendationRefreshNotice(daily.copy(error = "rate", retryAfterMs = 11_200)))
        assertEquals("刚刷新过，请稍后再试", recommendationRefreshNotice(daily.copy(error = "rate")))
        assertEquals("平台暂时拦截了请求，稍后再试", recommendationRefreshNotice(daily.copy(error = "risk")))
    }

    @Test fun playFailures() {
        assertEquals("推荐已更新，请重新选择", recommendationPlayFailureText(LibraryPageFailure("stale_revision")))
        assertEquals("每日推荐已关闭", recommendationPlayFailureText(LibraryPageFailure("recommendations_disabled")))
        assertEquals("没能开始播放，请稍后重试", recommendationPlayFailureText(IllegalStateException()))
    }

    @Test fun artistsLine() {
        assertEquals("A、B 等", recommendationArtists(listOf(song("1", "A"), song("2", "B"), song("3", "C"))))
        assertEquals("A", recommendationArtists(listOf(song("1", "A"), song("2", "A"))))
    }
}
