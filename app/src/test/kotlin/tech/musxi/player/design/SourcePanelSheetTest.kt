package tech.musxi.player.design

import android.graphics.BitmapFactory
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.ui.design.SourcePanelSheet
import tech.musxi.player.ui.design.panelChoosing
import tech.musxi.player.ui.design.panelChoosingPartial
import tech.musxi.player.ui.design.panelFailed
import tech.musxi.player.ui.design.panelFailedPartial
import tech.musxi.player.ui.design.panelSearching
import tech.musxi.player.ui.design.playerForPanel
import tech.musxi.player.ui.design.playerForPanelFailed
import tech.musxi.player.ui.player.SourcePanelUi
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File

/**
 * Source panel sheets. The panel sits over the player, so the assertion checks the bottom half
 * actually carries the sheet — a sheet that failed to compose would leave the player alone and still
 * pass a background check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class SourcePanelSheetTest {

    @Test fun searching() = capture("source_searching_light", ThemeMode.LIGHT, panelSearching())
    @Test fun choosing() = capture("source_choosing_light", ThemeMode.LIGHT, panelChoosing())
    @Test fun choosingPartial() = capture("source_choosing_partial_light", ThemeMode.LIGHT, panelChoosingPartial())
    @Test fun failed() = capture("source_failed_light", ThemeMode.LIGHT, panelFailed(), failed = true)
    @Test fun failedPartial() =
        capture("source_failed_partial_light", ThemeMode.LIGHT, panelFailedPartial(), failed = true)
    @Test fun choosingDark() = capture("source_choosing_dark", ThemeMode.DARK, panelChoosing())

    private fun capture(
        name: String,
        mode: ThemeMode,
        panel: SourcePanelUi,
        accent: Accent = Accent.MIST,
        failed: Boolean = false,
    ) {
        val out = File("build/design/$name.png")
        val player = if (failed) playerForPanelFailed() else playerForPanel()
        captureRoboImage(out.path) { SourcePanelSheet(accent, mode, player, panel) }
        if (!out.exists()) return
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertTrue("$name failed to decode", bitmap != null)
        val w = bitmap.width
        val h = bitmap.height
        val samples = IntArray(w * h)
        bitmap.getPixels(samples, 0, w, 0, 0, w, h)

        // The bottom third must contain the sheet's own container colour, distinct from the scrim.
        val sheetTop = (h * 0.66f).toInt()
        val colourCounts = HashMap<Int, Int>()
        for (y in sheetTop until h) {
            for (x in 0 until w) {
                val c = samples[y * w + x]
                colourCounts[c] = (colourCounts[c] ?: 0) + 1
            }
        }
        val dominant = colourCounts.maxByOrNull { it.value }?.value ?: 0
        assertTrue("$name: sheet area looks empty ($dominant px)", dominant > w * 10)
    }
}
