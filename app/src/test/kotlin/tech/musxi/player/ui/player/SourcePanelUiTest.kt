package tech.musxi.player.ui.player

import org.junit.Assert.*
import org.junit.Test
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary
import tech.musxi.core.playback.*

class SourcePanelUiTest {
    private val original = SongSummary(MediaKey(Platform.QQ, "original"), "Song", listOf("Artist"), "Album", 180_000)
    private val source = SourceCandidate(original.copy(key = MediaKey(Platform.KUGOU, "source")), 1)

    @Test fun automaticallySelectedSourceClosesPanel() {
        val selection = SourceSelection { 0 }
        val token = selection.begin(original, {}, {})
        selection.offer(token, AlternativeSources(original, listOf(source)))
        assertEquals(SourceSelectionPhase.SELECTED, selection.state.value.phase)
        assertEquals(SourcePhase.HIDDEN, sourcePanelUiOf(selection.state.value, 0).phase)
    }

    @Test fun uncertainVersionStaysManualWithoutCountdown() {
        val selection = SourceSelection { 0 }
        val token = selection.begin(original, { error("manual choice required") }, {})
        selection.offer(token, AlternativeSources(original, listOf(source.copy(song = source.song.copy(album = "Live")))))
        val ui = sourcePanelUiOf(selection.state.value, 50_000)
        assertEquals(SourcePhase.CHOOSING, ui.phase)
        assertNull(ui.candidates.single().secondsLeft)
        assertFalse(ui.candidates.single().autoTarget)
    }
}
