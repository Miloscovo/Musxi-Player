package tech.musxi.core.playback

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.*

class SourceSelectionTest {
    private fun song(platform: Platform, id: String, title: String = "AIZO") = SongSummary(
        MediaKey(platform, id), title, listOf("Artist"), "Album", 180000)
    private val original = song(Platform.QQ, "A0000000000001")
    private val first = SourceCandidate(song(Platform.NETEASE, "1"), 7)
    private val second = SourceCandidate(song(Platform.KUGOU, "a".repeat(32) + "_0_1"), 8)

    @Test fun timeoutStartsWhenResultsAreReadyAndPicksFirstSameNameOnce() {
        var now = 10L; val selection = SourceSelection { now }; val chosen = mutableListOf<SourceCandidate>()
        val token = selection.begin(original, chosen::add, {})
        now = 9000; selection.tick(token); assertTrue(chosen.isEmpty())
        val related = first.copy(song = first.song.copy(key = MediaKey(Platform.NETEASE, "99"), title = "AIZO live"))
        selection.offer(token, AlternativeSources(original, listOf(related, first, second)))
        assertEquals(14000L, selection.state.value.deadlineElapsedMs)
        assertEquals(1, selection.state.value.automaticIndex)
        now = 13999; selection.tick(token); assertTrue(chosen.isEmpty())
        now = 14000; selection.tick(token); selection.tick(token)
        assertEquals(listOf(first), chosen)
        assertFalse(selection.select(token, 2))
    }
    @Test fun manualSelectionWinsAndOldTimerOrResultsCannotSelectAfterNavigation() {
        var now = 0L; val selection = SourceSelection { now }; val chosen = mutableListOf<SourceCandidate>()
        val old = selection.begin(original, chosen::add, {})
        selection.offer(old, AlternativeSources(original, listOf(first, second)))
        assertTrue(selection.select(old, 1)); now = 6000; selection.tick(old)
        assertEquals(listOf(second), chosen)
        val next = selection.begin(original, chosen::add, {})
        selection.clear()
        selection.offer(next, AlternativeSources(original, listOf(first)))
        selection.tick(next); assertFalse(selection.select(next, 0))
        assertEquals(SourceSelectionPhase.NONE, selection.state.value.phase)
        assertEquals(1, chosen.size)
    }
    @Test fun cancelDuringSearchAndChoiceNeverInvokesSelectionOrLateResponse() {
        val selection = SourceSelection { 10000 }; var dismissed = 0; var selected = 0
        val token = selection.begin(original, { selected++ }, { dismissed++ })
        assertTrue(selection.cancel(token)); assertFalse(selection.cancel(token))
        selection.offer(token, AlternativeSources(original, listOf(first)))
        selection.tick(token)
        assertEquals(1, dismissed); assertEquals(0, selected)
        val next = selection.begin(original, { selected++ }, { dismissed++ })
        selection.offer(next, AlternativeSources(original, listOf(first)))
        selection.cancel(next); selection.tick(next)
        assertEquals(2, dismissed); assertEquals(0, selected)
    }
    @Test fun unmatchedTitlesRequireManualChoiceAndEmptyOrOriginalPlatformDoesNotAutoPlay() {
        val selection = SourceSelection { 0 }; var count = 0
        val token = selection.begin(original, { count++ }, {})
        selection.offer(token, AlternativeSources(original, listOf(first.copy(song = first.song.copy(title = "AIZO (Live)")))))
        assertNull(selection.state.value.deadlineElapsedMs)
        selection.tick(token); assertEquals(0, count)
        assertTrue(selection.select(token, 0))
        val next = selection.begin(original, { count++ }, {})
        selection.offer(next, AlternativeSources(original, listOf(SourceCandidate(original, 1))))
        assertEquals(SourceSelectionPhase.FAILED, selection.state.value.phase)
        assertNull(selection.state.value.deadlineElapsedMs)
        assertFalse(selection.select(next, 0))
    }
    @Test fun unicodeCaseAndWhitespaceMatchButVersionMarkersRemainDistinct() {
        assertTrue(SourceSelection.sameTitle(" Ａｉｚｏ ", "aizo"))
        assertFalse(SourceSelection.sameTitle("AIZO", "AIZO (Live)"))
        assertFalse(SourceSelection.sameTitle(" ", ""))
    }
}
