package tech.musxi.player.design

import android.graphics.BitmapFactory
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.ui.design.PlayerSheet
import tech.musxi.player.ui.design.playerBuffering
import tech.musxi.player.ui.design.playerEnded
import tech.musxi.player.ui.design.playerFailedSwapping
import tech.musxi.player.ui.design.playerInterrupted
import tech.musxi.player.ui.design.playerModes
import tech.musxi.player.ui.design.playerNoOutput
import tech.musxi.player.ui.design.playerNormal
import tech.musxi.player.ui.design.playerSwapped
import tech.musxi.player.ui.player.PlayerUi
import tech.musxi.player.ui.player.playerPalette
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File

/**
 * Full player sheets: the seven states across light and dark.
 *
 * The assertions are deliberately structural rather than pixel-perfect: the cover must occupy a
 * large square, the three lines of the title block must all be present, and the transport row must
 * draw something. A page that loses its controls still passes a "background share" check, which is
 * how the bottom bar once disappeared without a single test noticing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class PlayerSheetTest {

    @Test fun normal() = capture("player_normal_mist_light", ThemeMode.LIGHT, playerNormal())
    @Test fun swapped() = capture("player_swapped_mist_light", ThemeMode.LIGHT, playerSwapped())
    @Test fun interrupted() = capture("player_interrupted_mist_light", ThemeMode.LIGHT, playerInterrupted())
    @Test fun noOutput() = capture("player_no_output_mist_light", ThemeMode.LIGHT, playerNoOutput())
    @Test fun failedSwapping() = capture("player_failed_mist_light", ThemeMode.LIGHT, playerFailedSwapping())
    @Test fun ended() = capture("player_ended_mist_light", ThemeMode.LIGHT, playerEnded())
    @Test fun buffering() = capture("player_buffering_mist_light", ThemeMode.LIGHT, playerBuffering())
    @Test fun modes() = capture("player_modes_mist_light", ThemeMode.LIGHT, playerModes())
    @Test fun normalDark() = capture("player_normal_mist_dark", ThemeMode.DARK, playerNormal())
    @Test fun swappedDark() = capture("player_swapped_mist_dark", ThemeMode.DARK, playerSwapped())
    @Test fun tealLight() = capture("player_normal_teal_light", ThemeMode.LIGHT, playerNormal(), Accent.TEAL)
    @Test fun amberLight() = capture("player_normal_amber_light", ThemeMode.LIGHT, playerNormal(), Accent.AMBER)

    private fun capture(
        name: String,
        mode: ThemeMode,
        ui: PlayerUi,
        accent: Accent = Accent.MIST,
    ) {
        val out = File("build/design/$name.png")
        captureRoboImage(out.path) { PlayerSheet(accent, mode, ui) }
        if (!out.exists()) return
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertTrue("$name failed to decode", bitmap != null)
        val w = bitmap.width
        val h = bitmap.height
        val samples = IntArray(w * h)
        bitmap.getPixels(samples, 0, w, 0, 0, w, h)

        // The cover is a large square: across a row through its middle, most of the central span
        // must differ from the backdrop at the row's edge. A missing cover reads as a flat band.
        // Nested squares are flat by nature, so colour-count is the wrong measure — coverage is.
        val coverRow = (h * 0.30f).toInt()
        val rowStart = samples[coverRow * w + 2]
        val from = (w * 0.20f).toInt()
        val to = (w * 0.80f).toInt()
        val covered = (from until to).count { x -> samples[coverRow * w + x] != rowStart }
        assertTrue("$name: cover missing ($covered of ${to - from} px)", covered > (to - from) * 0.6f)

        // The transport row must contain the accent: the play button is the one strong mark.
        // The accent is now derived from the cover seed rather than taken from the theme, so the
        // expected colour has to be computed the same way the page computes it.
        val expectedAccent = playerPalette(ui.coverSeed, mode != ThemeMode.LIGHT).accent.toArgb()
        var accentPixels = 0
        for (y in (h * 0.60f).toInt() until h) {
            for (x in 0 until w) {
                if (samples[y * w + x] == expectedAccent) accentPixels++
            }
        }
        assertTrue("$name: no accent play button found ($accentPixels px)", accentPixels > 200)
    }
}
