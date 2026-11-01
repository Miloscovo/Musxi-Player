package tech.musxi.player.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.PlaylistKind
import tech.musxi.core.library.PlaylistSummary
import tech.musxi.core.library.SongSummary
import tech.musxi.core.playback.PlaybackJournalState
import tech.musxi.core.playback.RecentPlay
import tech.musxi.core.playback.SavedPlaybackQueue

class LibraryHomeTest {
    private fun song(id: String) = SongSummary(
        key = MediaKey(Platform.NETEASE, id),
        title = "歌 $id",
        artists = listOf("歌手"),
        album = "专辑",
        durationMs = 200_000,
    )

    private fun recent(id: String, at: Long = 0L) = RecentPlay(song(id), song(id), at)

    private fun queue(count: Int, index: Int, positionMs: Long = 0L) = SavedPlaybackQueue(
        songs = List(count) { song("q$it") },
        index = index,
        positionMs = positionMs,
        repeatMode = "off",
        shuffleEnabled = false,
        savedAtEpochMs = 0L,
    )

    private fun journal(
        queue: SavedPlaybackQueue? = null,
        recent: List<RecentPlay> = emptyList(),
        verified: Boolean = true,
        restoring: Boolean = false,
    ) = PlaybackJournalState(queue = queue, recent = recent, accountVerified = verified, restoring = restoring)

    private fun playlist(title: String, kind: PlaylistKind = PlaylistKind.OTHER) =
        PlaylistSummary(MediaKey(Platform.NETEASE, title.hashCode().toString()), title, null, 10, kind)

    @Test fun noJournalGivesAnEmptyHome() {
        assertEquals(LibraryHomeUi(), libraryHomeUiOf(null, null, playingQueue = false))
    }

    @Test fun accountNameSurvivesAMissingJournal() {
        assertEquals("小明", libraryHomeUiOf(null, "小明", playingQueue = false).accountName)
    }

    @Test fun aBlankAccountNameIsDropped() {
        assertNull(libraryHomeUiOf(null, "  ", playingQueue = false).accountName)
    }

    @Test fun aSavedQueueBecomesTheResumeCard() {
        val ui = libraryHomeUiOf(
            journal(queue = queue(count = 5, index = 2, positionMs = 83_000)),
            accountName = null,
            playingQueue = false,
        )
        val resume = ui.resume
        assertNotNull(resume)
        assertEquals("q2", resume!!.song.key.id)
        assertEquals(2, resume.index)
        assertEquals(5, resume.count)
        assertEquals(83_000L, resume.positionMs)
        assertFalse(resume.restoring)
    }

    @Test fun restoringIsCarriedThrough() {
        val ui = libraryHomeUiOf(journal(queue = queue(3, 0), restoring = true), null, playingQueue = false)
        assertTrue(ui.resume!!.restoring)
    }

    @Test fun noCardWhileAQueueIsAlreadyPlaying() {
        val ui = libraryHomeUiOf(journal(queue = queue(3, 1)), null, playingQueue = true)
        assertNull(ui.resume)
    }

    @Test fun noCardForAnUnverifiedAccount() {
        // The backend refuses to restore for an unverified account; the card would open an empty player.
        val ui = libraryHomeUiOf(journal(queue = queue(3, 1), verified = false), null, playingQueue = false)
        assertNull(ui.resume)
    }

    @Test fun noCardWithoutASavedQueue() {
        assertNull(libraryHomeUiOf(journal(queue = null), null, playingQueue = false).resume)
    }

    @Test fun noCardForAnEmptyQueue() {
        assertNull(libraryHomeUiOf(journal(queue = queue(0, 0)), null, playingQueue = false).resume)
    }

    @Test fun noCardWhenTheIndexIsOutOfRange() {
        assertNull(libraryHomeUiOf(journal(queue = queue(3, 3)), null, playingQueue = false).resume)
        assertNull(libraryHomeUiOf(journal(queue = queue(3, 99)), null, playingQueue = false).resume)
        assertNull(libraryHomeUiOf(journal(queue = queue(3, -1)), null, playingQueue = false).resume)
    }

    @Test fun recentKeepsTheOriginalSongsInOrder() {
        val ui = libraryHomeUiOf(
            journal(recent = listOf(recent("a"), recent("b"), recent("c"))),
            null,
            playingQueue = false,
        )
        assertEquals(listOf("a", "b", "c"), ui.recent.map { it.key.id })
    }

    @Test fun recentUsesTheOriginalNotTheSourceRecording() {
        val played = RecentPlay(original = song("orig"), source = song("alt"), playedAtEpochMs = 1)
        val ui = libraryHomeUiOf(journal(recent = listOf(played)), null, playingQueue = false)
        assertEquals(listOf("orig"), ui.recent.map { it.key.id })
    }

    @Test fun recentDropsRepeatsAndKeepsTheFirstOccurrence() {
        val ui = libraryHomeUiOf(
            journal(recent = listOf(recent("a"), recent("b"), recent("a"), recent("c"), recent("b"))),
            null,
            playingQueue = false,
        )
        assertEquals(listOf("a", "b", "c"), ui.recent.map { it.key.id })
    }

    @Test fun recentStopsAtTwelve() {
        val ui = libraryHomeUiOf(
            journal(recent = List(30) { recent("s$it") }),
            null,
            playingQueue = false,
        )
        assertEquals(12, ui.recent.size)
        assertEquals("s0", ui.recent.first().key.id)
        assertEquals("s11", ui.recent.last().key.id)
    }

    @Test fun repeatsDoNotUseUpThePlacesOfTheLimit() {
        // 20 entries but only 15 distinct songs: the cap applies after de-duplication.
        val entries = List(20) { recent("s${it % 15}") }
        val ui = libraryHomeUiOf(journal(recent = entries), null, playingQueue = false)
        assertEquals(12, ui.recent.size)
        assertEquals(12, ui.recent.map { it.key.id }.toSet().size)
    }

    @Test fun recentShowsEvenWhileAQueueIsPlaying() {
        val ui = libraryHomeUiOf(journal(recent = listOf(recent("a"))), null, playingQueue = true)
        assertEquals(1, ui.recent.size)
    }

    @Test fun likedPlaylistIsRecognisedByName() {
        assertTrue(isLikedPlaylist(playlist("我喜欢")))
        assertTrue(isLikedPlaylist(playlist("我喜欢的音乐")))
        assertTrue(isLikedPlaylist(playlist("听众甲喜欢的音乐")))
        assertTrue(isLikedPlaylist(playlist("  我喜欢 ")))
        assertTrue(isLikedPlaylist(playlist("我 喜欢的音乐")))
    }

    @Test fun otherPlaylistsAreNotLiked() {
        assertFalse(isLikedPlaylist(playlist("默认收藏")))
        assertFalse(isLikedPlaylist(playlist("TOP500")))
        assertFalse(isLikedPlaylist(playlist("")))
        assertFalse(isLikedPlaylist(playlist("喜欢")))
        assertFalse(isLikedPlaylist(playlist("我喜欢的歌手")))
    }

    @Test fun theKindDecidesWhenItIsKnown() {
        // A LIKED playlist is liked whatever its title says.
        assertTrue(isLikedPlaylist(playlist("周杰伦精选", PlaylistKind.LIKED)))
        // A created or collected playlist named like the liked list is not promoted.
        assertFalse(isLikedPlaylist(playlist("我喜欢的音乐", PlaylistKind.CREATED)))
        assertFalse(isLikedPlaylist(playlist("我喜欢", PlaylistKind.COLLECTED)))
    }

    @Test fun otherFallsBackToTheNameForOldCaches() {
        // OTHER is what every playlist reads as in a cache written before `kind` existed.
        assertTrue(isLikedPlaylist(playlist("我喜欢", PlaylistKind.OTHER)))
    }

    // The module's heading is "我喜欢"; the row under it must not say the same words again.

    @Test fun aTitleThatOnlyRepeatsTheHeadingBecomesAllSongs() {
        assertEquals("全部歌曲", likedRowTitle("我喜欢"))
        assertEquals("全部歌曲", likedRowTitle("我喜欢的音乐"))
        assertEquals("全部歌曲", likedRowTitle("  我 喜欢 的音乐 "))
        assertEquals("全部歌曲", likedRowTitle(""))
    }

    @Test fun aTitleThatAddsSomethingIsKept() {
        assertEquals("阿青喜欢的音乐", likedRowTitle("阿青喜欢的音乐"))
        assertEquals("Liked Songs", likedRowTitle("Liked Songs"))
    }
}
