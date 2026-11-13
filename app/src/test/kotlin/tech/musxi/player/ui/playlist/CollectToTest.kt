package tech.musxi.player.ui.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.CreatedPlaylistSongResult
import tech.musxi.core.library.EditablePlaylist
import tech.musxi.core.library.LibraryPageFailure
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.PlaylistMembershipEntry
import tech.musxi.core.library.PlaylistMembershipResult
import tech.musxi.core.library.PlaylistSummary
import tech.musxi.core.library.SongPlaylistMembership
import tech.musxi.core.library.SongSummary

/** Task 9: the heart's long press ("收藏到") and making a new playlist from the sheet. */
class CollectToTest {
    private val song = SongSummary(MediaKey(Platform.QQ, "song"), "晴天", listOf("周杰伦"), "叶惠美", 269_000)
    private fun key(id: String) = MediaKey(Platform.QQ, id)
    private fun target(id: String, favorite: Boolean = false) =
        EditablePlaylist(PlaylistSummary(key(id), if (favorite) "我喜欢" else "歌单 $id", null, 10), 1, favorite)
    private fun snapshot(vararg entries: Pair<String, Boolean>, liked: Boolean = false) = SongPlaylistMembership(
        song.key, 1,
        entries.map { (id, present) -> PlaylistMembershipEntry(target(id), present) } +
            PlaylistMembershipEntry(target("liked", favorite = true), liked),
    )

    private class Fake(val creates: Boolean = true) : MembershipSource {
        val reads = ArrayDeque<(Result<SongPlaylistMembership>) -> Unit>()
        val made = ArrayDeque<Pair<String, (Result<CreatedPlaylistSongResult>) -> Unit>>()
        override fun read(song: MediaKey, result: (Result<SongPlaylistMembership>) -> Unit) { reads += result }
        override fun update(
            song: SongSummary, snapshot: SongPlaylistMembership, desired: Map<MediaKey, Boolean>,
            result: (Result<PlaylistMembershipResult>) -> Unit,
        ) = Unit
        override fun canCreate(platform: Platform) = creates
        override fun create(song: SongSummary, name: String, result: (Result<CreatedPlaylistSongResult>) -> Unit) {
            made += name to result
        }
    }

    private val settled = mutableListOf<MembershipSettled>()

    private fun opened(source: Fake, mode: MembershipMode, read: SongPlaylistMembership): MembershipEditor {
        val editor = MembershipEditor(source, { settled += it })
        editor.open(song, mode = mode)
        source.reads.removeFirst()(Result.success(read))
        return editor
    }

    @Test fun theHeartsSheetListsLikedFirstAndTheMenuOneDoesNot() {
        val collect = opened(Fake(), MembershipMode.COLLECT, snapshot("a" to false, "b" to true, liked = true))
        assertEquals(listOf("liked", "a", "b"), collect.state!!.rows.map { it.target.playlist.key.id })
        assertTrue(collect.state!!.rows.first().checked)
        assertEquals("收藏到", collect.state!!.mode.title)
        val menu = opened(Fake(), MembershipMode.PLAYLISTS, snapshot("a" to false, liked = true))
        assertEquals(listOf("a"), menu.state!!.rows.map { it.target.playlist.key.id })
    }

    @Test fun aNewListIsMadeOnceAndTheUnsavedTicksSurviveTheReread() {
        val source = Fake()
        val editor = opened(source, MembershipMode.COLLECT, snapshot("a" to false))
        editor.toggle(key("liked"))
        editor.startCreate()
        assertTrue(editor.state!!.naming)
        editor.create("  雨天  ")
        editor.create("雨天") // A second tap while the first runs sends nothing.
        val (name, reply) = source.made.single().also { source.made.clear() }
        assertEquals("雨天", name)
        assertTrue(editor.state!!.creating)
        assertFalse(editor.state!!.canSave)
        reply(Result.success(CreatedPlaylistSongResult(target("new"), Result.success(Unit))))
        assertEquals(MembershipPhase.CHECKING, editor.state!!.phase)
        assertFalse(editor.state!!.naming)
        assertEquals("已新建「歌单 new」并加入", editor.state!!.message)
        assertEquals(key("new"), settled.single().results.single().playlist)
        source.reads.removeFirst()(Result.success(snapshot("a" to false, "new" to true)))
        val rows = editor.state!!.rows.associateBy { it.target.playlist.key.id }
        assertTrue("the liked tick was kept", rows.getValue("liked").checked)
        assertTrue(rows.getValue("new").present)
        assertEquals(1, editor.state!!.changeCount)
    }

    @Test fun aRefusedNameKeepsTheDialogAndAnUnsureCreateIsNotRetried() {
        val source = Fake()
        val editor = opened(source, MembershipMode.COLLECT, snapshot("a" to false))
        editor.startCreate()
        editor.create("雨天")
        source.made.removeFirst().second(Result.failure(LibraryPageFailure("invalid_request")))
        assertTrue(editor.state!!.naming)
        assertFalse(editor.state!!.creating)
        assertEquals("平台不接受这个名称，请换一个", editor.state!!.nameError)
        editor.create("雨天 2")
        source.made.removeFirst().second(Result.failure(LibraryPageFailure("network")))
        assertFalse(editor.state!!.naming)
        assertTrue(editor.state!!.message!!.startsWith("新建结果未确认"))
        assertTrue(source.made.isEmpty())
        assertEquals(1, source.reads.size)
    }

    @Test fun kugouCannotCreateYet() {
        val editor = opened(Fake(creates = false), MembershipMode.COLLECT, snapshot("a" to false))
        assertFalse(editor.state!!.canCreate)
        editor.startCreate()
        assertFalse(editor.state!!.naming)
    }

    @Test fun nameRules() {
        assertEquals("请输入歌单名称", playlistNameProblem("  ", Platform.KUGOU))
        assertNull(playlistNameProblem("二十个字".repeat(5), Platform.QQ))
        assertEquals("QQ 音乐歌单名称最多 20 个字", playlistNameProblem("x".repeat(21), Platform.QQ))
        assertNull(playlistNameProblem("x".repeat(80), Platform.NETEASE))
        assertEquals("名称最多 80 个字", playlistNameProblem("x".repeat(81), Platform.NETEASE))
    }
}
