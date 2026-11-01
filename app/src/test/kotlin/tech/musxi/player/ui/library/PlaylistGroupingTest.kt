package tech.musxi.player.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.PlaylistKind
import tech.musxi.core.library.PlaylistSummary

class PlaylistGroupingTest {
    private fun playlist(title: String, kind: PlaylistKind) =
        PlaylistSummary(MediaKey(Platform.NETEASE, title), title, null, 10, kind)

    @Test fun oneKindStaysFlatWithoutAHeading() {
        val list = listOf(playlist("a", PlaylistKind.CREATED), playlist("b", PlaylistKind.CREATED))
        val sections = groupPlaylists(list)
        assertEquals(1, sections.size)
        assertNull(sections[0].title)
        assertEquals(list, sections[0].items)
    }

    @Test fun collectedOnlyStaysFlatWithoutAHeading() {
        val list = listOf(playlist("a", PlaylistKind.COLLECTED))
        val sections = groupPlaylists(list)
        assertEquals(1, sections.size)
        assertNull(sections[0].title)
    }

    @Test fun otherOnlyStaysFlatWithoutAHeading() {
        // An old cache where every playlist reads as OTHER must look exactly as it did before.
        val list = listOf(playlist("a", PlaylistKind.OTHER), playlist("b", PlaylistKind.OTHER))
        val sections = groupPlaylists(list)
        assertEquals(1, sections.size)
        assertNull(sections[0].title)
        assertEquals(list, sections[0].items)
    }

    @Test fun mixedKindsSplitIntoCreatedThenCollected() {
        val a = playlist("a", PlaylistKind.COLLECTED)
        val b = playlist("b", PlaylistKind.CREATED)
        val c = playlist("c", PlaylistKind.COLLECTED)
        val d = playlist("d", PlaylistKind.CREATED)
        val sections = groupPlaylists(listOf(a, b, c, d))
        assertEquals(listOf("创建的歌单", "收藏的歌单"), sections.map { it.title })
        assertEquals(listOf(b, d), sections[0].items)
        assertEquals(listOf(a, c), sections[1].items)
    }

    @Test fun otherGoesAtTheEndOfTheCreatedGroup() {
        val created = playlist("created", PlaylistKind.CREATED)
        val other = playlist("other", PlaylistKind.OTHER)
        val collected = playlist("collected", PlaylistKind.COLLECTED)
        val sections = groupPlaylists(listOf(other, created, collected))
        assertEquals(listOf(created, other), sections[0].items)
        assertEquals(listOf(collected), sections[1].items)
    }

    @Test fun emptyListIsOneUntitledEmptySection() {
        val sections = groupPlaylists(emptyList())
        assertEquals(1, sections.size)
        assertNull(sections[0].title)
        assertEquals(emptyList<PlaylistSummary>(), sections[0].items)
    }
}
