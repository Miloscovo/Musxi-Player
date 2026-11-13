package tech.musxi.player.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.design.playerNormal
import tech.musxi.player.ui.model.formatDuration
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/**
 * Spec section 12 on the player page: the live position reaches the seek bar and its times through
 * `liveProgress`, while the page's `ui` model stays the same object. The model's own position
 * (1:58 in [playerNormal]) must never show once a provider is given.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class FullPlayerLiveProgressTest {
    @get:Rule val compose = createComposeRule()

    private val duration = 269_000L
    private var hostCompositions = 0
    private val seenUi = mutableListOf<PlayerUi>()

    private fun show(ui: PlayerUi, start: PlaybackProgress): androidx.compose.runtime.MutableState<PlaybackProgress> {
        val live = mutableStateOf(start)
        val provider: () -> PlaybackProgress = { live.value }
        compose.setContent {
            MusxiTheme(accent = Accent.MIST, mode = ThemeMode.LIGHT) {
                // Stands in for the shell: holds the state, never reads it, records the model it passes.
                SideEffect { hostCompositions++; seenUi += ui }
                Box(Modifier.fillMaxSize()) {
                    FullPlayer(ui = ui, dark = false, liveProgress = provider)
                }
            }
        }
        compose.waitForIdle()
        return live
    }

    private fun tick(state: androidx.compose.runtime.MutableState<PlaybackProgress>, to: PlaybackProgress) {
        state.value = to
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun fillWidth(): Int =
        compose.onNodeWithTag(PlayerProgressFillTag, useUnmergedTree = true).fetchSemanticsNode().size.width

    /** 360dp wide, 32dp inset each side: a 296dp track, 592px at xhdpi. */
    private fun expectedFill(positionMs: Long): Float = 592f * positionMs / duration

    private fun assertElapsed(positionMs: Long) {
        compose.onNodeWithText(formatDuration(positionMs)).assertExists()
    }

    @Test fun aPlayingTickMovesTheTimeAndTheBarWithTheSameModel() {
        val ui = playerNormal()
        val live = show(
            ui,
            PlaybackProgress(positionMs = 30_000, sampledAtElapsedMs = 1_000, canExtrapolate = true, durationMs = duration),
        )
        assertElapsed(30_000)
        // The model's own 1:58 must not leak through while a provider is given.
        compose.onNodeWithText(formatDuration(ui.positionMs)).assertDoesNotExist()
        assertEquals(expectedFill(30_000), fillWidth().toFloat(), 3f)
        val composedAtStart = hostCompositions

        tick(live, PlaybackProgress(positionMs = 200_000, sampledAtElapsedMs = 171_000, canExtrapolate = true, durationMs = duration))

        assertElapsed(200_000)
        compose.onNodeWithText(formatDuration(30_000)).assertDoesNotExist()
        assertEquals(expectedFill(200_000), fillWidth().toFloat(), 3f)
        assertEquals("the host recomposed on a tick", composedAtStart, hostCompositions)
        seenUi.forEach { assertSame("the page should keep the same ui object", ui, it) }
    }

    /**
     * Paused, nothing to extrapolate: a seek changes only positionMs. The bar and the time must still
     * follow, because nothing else in the page changes to carry them along.
     */
    @Test fun aSeekWhilePausedMovesTheTimeAndTheBar() {
        val ui = playerNormal().copy(playing = false)
        val paused = PlaybackProgress(positionMs = 60_000, sampledAtElapsedMs = 5_000, canExtrapolate = false, durationMs = duration)
        val live = show(ui, paused)
        assertElapsed(60_000)
        assertEquals(expectedFill(60_000), fillWidth().toFloat(), 3f)
        val composedAtStart = hostCompositions

        tick(live, paused.copy(positionMs = 150_000))

        assertElapsed(150_000)
        compose.onNodeWithText(formatDuration(60_000)).assertDoesNotExist()
        assertEquals(expectedFill(150_000), fillWidth().toFloat(), 3f)
        assertEquals("the host recomposed on a seek", composedAtStart, hostCompositions)
        seenUi.forEach { assertSame("the page should keep the same ui object", ui, it) }
    }
}
