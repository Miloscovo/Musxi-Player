package tech.musxi.core.playback

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*

class SourceSwitchPreferencesTest {
    @Test fun automaticSelectionOnlyUsesPreparedCandidatesAndKeepsClosestAmongThem() {
        val selection = SourceSelection { 0 }
        val closer = candidate("closer", 0)
        val playable = candidate("playable", 1000)
        var chosen: SourceCandidate? = null
        val token = selection.begin(original, { chosen = it }, {})
        selection.offer(token, AlternativeSources(original, listOf(closer, playable)), setOf(playable.song.key))
        assertEquals(playable.song.key, chosen?.song?.key)
        val next = selection.begin(original, { chosen = it }, {})
        chosen = null
        selection.offer(next, AlternativeSources(original, listOf(closer)), emptySet())
        assertNull(chosen)
        assertEquals(SourceSelectionPhase.CHOOSING, selection.state.value.phase)
        assertTrue(selection.select(next, 0))
    }
    private val original = SongSummary(MediaKey(Platform.KUGOU, "original"), "晴天", listOf("周杰伦"), "叶惠美", 270000)
    private fun candidate(id: String, delta: Long, album: String = "其他") = SourceCandidate(
        original.copy(key = MediaKey(Platform.QQ, id), durationMs = original.durationMs + delta, album = album), 2)

    @Test fun durationRelaxationRetainsArtistTitleVersionAndBothUpperBounds() {
        val relaxed = candidate("1", 12000).song
        assertFalse(SourceMatchPolicy.eligible(original, relaxed))
        assertTrue(SourceMatchPolicy.eligible(original, relaxed, true))
        for (bad in listOf(relaxed.copy(artists = listOf("Jay")), relaxed.copy(album = "翻唱"),
            relaxed.copy(title = "晴天 Live"), relaxed.copy(durationMs = 286000), relaxed.copy(durationMs = 0))) {
            assertFalse(SourceMatchPolicy.eligible(original, bad, true))
        }
        assertFalse(SourceMatchPolicy.eligible(original.copy(durationMs = 30000), relaxed.copy(durationMs = 33000), true))
    }

    @Test fun closestDurationWinsAndAlbumBreaksTies() {
        val ranked = SourceMatchPolicy.rank(original, listOf(candidate("far", 11000), candidate("tie", 1000),
            candidate("album", -1000, original.album), candidate("closest", 500)), true)
        assertEquals(listOf("closest", "album", "tie", "far"), ranked.map { it.song.key.id })
        assertTrue(ranked.last().relaxedMatch)
        assertEquals(11000L, ranked.last().durationDifferenceMs)
    }

    @Test fun automaticOffWaitsForChoiceAndConfirmedPlaybackIsSeparateFromSelection() {
        val selection = SourceSelection({ 0 }, { SourceSwitchPreferences(automatic = false, relaxDuration = true) })
        var selected = 0
        val token = selection.begin(original, { selected++ }, {})
        selection.offer(token, AlternativeSources(original, listOf(candidate("relaxed", 12000))))
        assertEquals(SourceSelectionPhase.CHOOSING, selection.state.value.phase)
        assertNull(selection.state.value.automaticIndex)
        assertEquals(0, selected)
        assertTrue(selection.select(token, 0))
        assertEquals(Platform.QQ, selection.state.value.selectedPlatform)
        assertTrue(selection.state.value.selectedRelaxedMatch)
        assertFalse(selection.state.value.playbackConfirmed)
        selection.confirmPlayback(candidate("relaxed", 12000).song.key)
        assertTrue(selection.state.value.playbackConfirmed)
    }

    @Test fun coverNeverAutoSelectedWithEitherPreference() {
        for (relax in listOf(false, true)) {
            val selection = SourceSelection({ 0 }, { SourceSwitchPreferences(relaxDuration = relax) })
            val token = selection.begin(original, { fail("cover selected") }, {})
            selection.offer(token, AlternativeSources(original, listOf(candidate("cover", 0).let {
                it.copy(song = it.song.copy(artists = listOf("翻唱歌手"))) })))
            assertNull(selection.state.value.selected)
        }
    }
}
