package tech.musxi.player.design

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.ui.components.SheetTag
import tech.musxi.player.ui.design.LargeFontBatch
import tech.musxi.player.ui.design.SourcePanelSheet
import tech.musxi.player.ui.design.panelChoosing
import tech.musxi.player.ui.design.panelChoosingPartial
import tech.musxi.player.ui.design.panelFailed
import tech.musxi.player.ui.design.panelFailedPartial
import tech.musxi.player.ui.design.panelSearching
import tech.musxi.player.ui.design.panelSearchingManual
import tech.musxi.player.ui.design.playerForPanel
import tech.musxi.player.ui.design.playerForPanelFailed
import tech.musxi.player.ui.player.SourcePanelUi
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File
import kotlin.math.abs

/**
 * Source panel sheets: the player behind, the sheet resting at the middle detent (top at 352dp on
 * this 360x800dp screen). The compose rule is used instead of the lambda form of `captureRoboImage`
 * so the slide-in has finished before the picture is taken; the sheet position is asserted first, so
 * a sheet stuck below the screen fails instead of producing a picture of an empty page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class SourcePanelSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searching() = capture("source_searching_light", ThemeMode.LIGHT, panelSearching())
    @Test fun searchingManual() = capture("source_searching_manual_light", ThemeMode.LIGHT, panelSearchingManual())
    @Test fun choosing() = capture("source_choosing_light", ThemeMode.LIGHT, panelChoosing())
    @Test fun choosingPartial() = capture("source_choosing_partial_light", ThemeMode.LIGHT, panelChoosingPartial())
    @Test fun failed() = capture("source_failed_light", ThemeMode.LIGHT, panelFailed(), failed = true)
    @Test fun failedPartial() =
        capture("source_failed_partial_light", ThemeMode.LIGHT, panelFailedPartial(), failed = true)
    @Test fun choosingDark() = capture("source_choosing_dark", ThemeMode.DARK, panelChoosing())

    // Font scale 2.0 (spec section 12): rows grow with the text instead of clipping it.
    @Test fun searchingLargeFont() =
        capture("source_searching_largefont_light", ThemeMode.LIGHT, panelSearching(), largeFont = true)
    @Test fun choosingLargeFont() =
        capture("source_choosing_largefont_light", ThemeMode.LIGHT, panelChoosing(), largeFont = true)
    @Test fun failedLargeFont() =
        capture("source_failed_largefont_light", ThemeMode.LIGHT, panelFailed(), failed = true, largeFont = true)

    private fun capture(
        name: String,
        mode: ThemeMode,
        panel: SourcePanelUi,
        accent: Accent = Accent.MIST,
        failed: Boolean = false,
        largeFont: Boolean = false,
    ) {
        val player = if (failed) playerForPanelFailed() else playerForPanel()
        compose.setContent {
            if (largeFont) {
                LargeFontBatch { SourcePanelSheet(accent, mode, player, panel) }
            } else {
                SourcePanelSheet(accent, mode, player, panel)
            }
        }
        compose.waitForIdle()

        val top = compose.onNodeWithTag(SheetTag).getUnclippedBoundsInRoot().top.value
        assertTrue("$name: sheet top $top dp, expected about 352 dp", abs(top - 352f) < 6f)

        val out = File("build/design/$name.png")
        compose.onRoot().captureRoboImage(out.path)
        // Without -Proborazzi.test.record=true nothing is written; the full test run must still pass.
        if (!out.exists()) return
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertTrue("$name failed to decode", bitmap != null)
        val w = bitmap.width
        val h = bitmap.height
        val samples = IntArray(w * h)
        bitmap.getPixels(samples, 0, w, 0, 0, w, h)

        // The bottom third must contain the sheet's own surface colour, not the page or the scrim.
        val sheetTop = (h * 0.66f).toInt()
        val colourCounts = HashMap<Int, Int>()
        for (y in sheetTop until h) {
            for (x in 0 until w) {
                val c = samples[y * w + x]
                colourCounts[c] = (colourCounts[c] ?: 0) + 1
            }
        }
        val surface = colourCounts.maxByOrNull { it.value }
        val dominant = surface?.value ?: 0
        assertTrue("$name: sheet area looks empty ($dominant px)", dominant > w * 10)
        // The surface follows the theme: a white sheet in dark mode would leave its light text unreadable.
        val luminance = Color(surface!!.key).luminance()
        if (mode == ThemeMode.DARK) {
            assertTrue("$name: dark sheet is too light ($luminance)", luminance < 0.5f)
        } else {
            assertTrue("$name: light sheet is not white ($luminance)", luminance > 0.9f)
        }
    }
}
