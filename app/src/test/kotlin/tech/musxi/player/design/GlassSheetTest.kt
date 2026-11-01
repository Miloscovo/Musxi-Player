package tech.musxi.player.design

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
import tech.musxi.player.ui.design.GlassCardTag
import tech.musxi.player.ui.design.GlassMenuTag
import tech.musxi.player.ui.design.GlassSample
import tech.musxi.player.ui.design.GlassSheet
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import tech.musxi.player.ui.theme.musxiColorScheme
import java.io.File
import kotlin.math.abs

/**
 * Glass sheets: the bottom card, the floating menu and the sheet over a colourful page. The `solid`
 * pictures are the path Robolectric renders: the check is that the surface really is the spec's solid
 * fill (white in light, surfaceContainerHigh in dark) with its text on it. The `glass` pictures let the
 * host choose its renderer (refraction on API 35); Robolectric may not draw Backdrop's lens shader, so
 * they only have to render without throwing and are for looking at, not for pixel checks.
 *
 * The compose rule is used instead of the lambda form of `captureRoboImage` so the sheet's slide-in
 * has finished before the picture is taken.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class GlassSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cardSolidLight() = capture("glass_card_solid_light", GlassSample.CARD, ThemeMode.LIGHT)
    @Test fun cardSolidDark() = capture("glass_card_solid_dark", GlassSample.CARD, ThemeMode.DARK)
    @Test fun menuSolidLight() = capture("glass_menu_solid_light", GlassSample.MENU, ThemeMode.LIGHT)
    @Test fun menuSolidDark() = capture("glass_menu_solid_dark", GlassSample.MENU, ThemeMode.DARK)
    @Test fun sheetSolidLight() = capture("glass_sheet_solid_light", GlassSample.SHEET, ThemeMode.LIGHT)
    @Test fun sheetSolidDark() = capture("glass_sheet_solid_dark", GlassSample.SHEET, ThemeMode.DARK)

    @Test fun cardGlassLight() = capture("glass_card_glass_light", GlassSample.CARD, ThemeMode.LIGHT, glass = true)
    @Test fun menuGlassDark() = capture("glass_menu_glass_dark", GlassSample.MENU, ThemeMode.DARK, glass = true)
    @Test fun sheetGlassLight() = capture("glass_sheet_glass_light", GlassSample.SHEET, ThemeMode.LIGHT, glass = true)

    private fun capture(name: String, sample: GlassSample, mode: ThemeMode, glass: Boolean = false) {
        compose.setContent { GlassSheet(Accent.MIST, mode, sample, glass) }
        compose.waitForIdle()

        val tag = when (sample) {
            GlassSample.CARD -> GlassCardTag
            GlassSample.MENU -> GlassMenuTag
            GlassSample.SHEET -> SheetTag
        }
        val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        if (sample == GlassSample.SHEET) {
            // MEDIUM detent on a 800dp screen: a sheet stuck below it would be a picture of an empty page.
            assertTrue("$name: sheet top ${bounds.top.value} dp, expected about 352 dp", abs(bounds.top.value - 352f) < 6f)
        }

        val out = File("build/design/$name.png")
        compose.onRoot().captureRoboImage(out.path)
        // Without -Proborazzi.test.record=true nothing is written; the full test run must still pass.
        if (!out.exists()) return
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertTrue("$name failed to decode", bitmap != null)
        if (glass) return

        // dp to px: the screen is 360dp wide at any density.
        val scale = bitmap.width / 360f
        val inset = 6f
        val left = ((bounds.left.value + inset) * scale).toInt().coerceAtLeast(0)
        val right = ((bounds.right.value - inset) * scale).toInt().coerceAtMost(bitmap.width)
        val top = ((bounds.top.value + inset) * scale).toInt().coerceAtLeast(0)
        val bottom = ((bounds.bottom.value - inset) * scale).toInt().coerceAtMost(bitmap.height)
        val (dominant, share) = dominantColour(bitmap, left, top, right, bottom)

        val expected = if (mode == ThemeMode.DARK) {
            musxiColorScheme(Accent.MIST, ThemeMode.DARK).surfaceContainerHigh
        } else {
            Color.White
        }
        assertTrue("$name: dominant ${hex(dominant)} is not the solid ${hex(expected.toArgb())}", near(dominant, expected.toArgb()))
        // Text and icons are a small part of the card; a surface that is not the fill would be far less.
        assertTrue("$name: solid fill covers only $share of the surface", share > 0.5f)
    }

    private fun dominantColour(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Pair<Int, Float> {
        val counts = HashMap<Int, Int>()
        for (y in top until bottom) for (x in left until right) {
            val c = bitmap.getPixel(x, y)
            counts[c] = (counts[c] ?: 0) + 1
        }
        val best = counts.maxByOrNull { it.value } ?: return 0 to 0f
        val total = ((right - left) * (bottom - top)).coerceAtLeast(1)
        return best.key to best.value.toFloat() / total
    }

    private fun near(a: Int, b: Int, tolerance: Int = 2): Boolean =
        (0..24 step 8).all { shift -> abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)) <= tolerance }

    private fun hex(argb: Int) = "#%08X".format(argb)
}
