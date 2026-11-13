package tech.musxi.player.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.CachedPlaylistSearchResult
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary

class PlaylistSearchUiTest {
    private fun song(id: String) = SongSummary(MediaKey(Platform.NETEASE, id), "歌 $id", listOf("歌手"), "专辑", 200_000)

    private fun result(items: List<SongSummary>, loaded: Int, total: Int?, complete: Boolean) =
        CachedPlaylistSearchResult(items, loaded, total, complete, setOf(0), null)

    @Test fun nothingIsSaidUntilSomethingIsTyped() {
        assertNull(playlistSearchNote(PlaylistSearchUi()))
        assertNull(playlistSearchNote(PlaylistSearchUi(open = true, query = "  ")))
        assertFalse(PlaylistSearchUi(open = true).active)
    }

    @Test fun aPartlyReadPlaylistSaysHowMuchWasSearched() {
        val ui = PlaylistSearchUi(open = true).with("雨", Result.success(result(emptyList(), 120, 2000, false)))
        assertEquals("在已载入的 120 首里没有找到（共 2000 首）", playlistSearchNote(ui))
        val found = PlaylistSearchUi(open = true).with("雨", Result.success(result(listOf(song("1")), 120, 2000, false)))
        assertEquals("在已载入的 120 首里找到 1 首（共 2000 首）", playlistSearchNote(found))
    }

    @Test fun aFullyReadPlaylistGivesAFinalAnswer() {
        val none = PlaylistSearchUi(open = true).with("雨", Result.success(result(emptyList(), 40, 40, true)))
        assertEquals("这个歌单里没有找到", playlistSearchNote(none))
        val some = PlaylistSearchUi(open = true).with("雨", Result.success(result(listOf(song("1"), song("2")), 40, 40, true)))
        assertEquals("找到 2 首", playlistSearchNote(some))
    }

    @Test fun typingKeepsThePreviousAnswerUntilTheNextOneLands() {
        val first = PlaylistSearchUi(open = true).with("雨", Result.success(result(listOf(song("1")), 40, 40, true)))
        // The next keystroke: searching, but the note and the rows are still the first answer's.
        val typing = first.copy(query = "雨天", searching = true)
        assertEquals("找到 1 首", playlistSearchNote(typing))
        assertEquals(1, typing.results.size)
        // Before any answer at all, the line stays empty instead of flashing "searching".
        assertNull(playlistSearchNote(PlaylistSearchUi(open = true, query = "雨", searching = true)))
    }

    @Test fun aPartlyReadResultOffersToReadTheRest() {
        val partial = PlaylistSearchUi(open = true).with("雨", Result.success(result(emptyList(), 120, 2000, false)))
        assertTrue(partial.canFill)
        val full = PlaylistSearchUi(open = true).with("雨", Result.success(result(emptyList(), 40, 40, true)))
        assertFalse("nothing left to read", full.canFill)
        val running = partial.copy(filling = true, searched = 600)
        assertFalse(running.canFill)
        assertEquals("正在载入全部歌曲 · 600 / 2000 首", playlistSearchNote(running))
    }

    @Test fun aFillThatStoppedShortSaysWhy() {
        assertEquals("歌单太长，只能载入前 5000 首", playlistFillErrorText("fill_limit"))
        assertEquals("请求太频繁，稍后再试", playlistFillErrorText("risk"))
        assertNull("finished or cancelled", playlistFillErrorText(null))
        assertEquals("没能载入全部，请稍后再试", playlistFillErrorText("unclassified"))
    }

    @Test fun aFailureIsNotPassedOffAsNoResults() {
        val ui = PlaylistSearchUi(open = true).with("雨", Result.failure(RuntimeException("storage")))
        assertTrue(ui.failed)
        assertEquals("暂时搜不了，请稍后再试", playlistSearchNote(ui))
    }
}
