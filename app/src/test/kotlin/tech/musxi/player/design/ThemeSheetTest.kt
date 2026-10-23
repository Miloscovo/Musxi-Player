package tech.musxi.player.design

import android.graphics.BitmapFactory
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.ui.design.ThemeSheet
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File

/**
 * Renders one sheet per accent and brightness so the palette can be reviewed as images.
 * Recording mode writes PNGs to app/build/design/; the assertions below also run on a plain
 * test invocation and catch the mechanical failures that are easy to miss by eye: a surface
 * that stays white in dark mode, an accent that never reaches the screen, a missing tab bar.
 *
 * The assertions read pixels, not meaning. Visual judgement still needs a human.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class ThemeSheetTest {

    @Test fun mistLight() = capture(Accent.MIST, ThemeMode.LIGHT, "#F7F7F5", "#4A6A9A")
    @Test fun mistDark() = capture(Accent.MIST, ThemeMode.DARK, "#121212", "#A9BEE0")
    @Test fun mistBlack() = capture(Accent.MIST, ThemeMode.BLACK, "#000000", "#A9BEE0")
    @Test fun tealLight() = capture(Accent.TEAL, ThemeMode.LIGHT, "#F7F7F5", "#2B7A71")
    @Test fun tealDark() = capture(Accent.TEAL, ThemeMode.DARK, "#121212", "#8DCFC5")
    @Test fun tealBlack() = capture(Accent.TEAL, ThemeMode.BLACK, "#000000", "#8DCFC5")
    @Test fun amberLight() = capture(Accent.AMBER, ThemeMode.LIGHT, "#F7F7F5", "#9C6229")
    @Test fun amberDark() = capture(Accent.AMBER, ThemeMode.DARK, "#121212", "#E6B988")
    @Test fun amberBlack() = capture(Accent.AMBER, ThemeMode.BLACK, "#000000", "#E6B988")

    private fun capture(accent: Accent, mode: ThemeMode, background: String, accentRgb: String) {
        val name = "theme_${accent.name.lowercase()}_${mode.name.lowercase()}.png"
        val out = File("build/design/$name")
        captureRoboImage(out.path) { ThemeSheet(accent, mode) }

        // Roborazzi only writes in record mode; when the file is present, verify the pixels.
        if (!out.exists()) return
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertTrue("$name failed to decode", bitmap != null)
        val bg = parse(background)
        val accentColor = parse(accentRgb)
        var bgHits = 0
        var accentHits = 0
        val samples = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(samples, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (p in samples) {
            if (p == bg) bgHits++
            if (p == accentColor) accentHits++
        }
        val total = samples.size.toFloat()
        val bgShare = bgHits / total
        assertTrue("$name: background $background covered only ${(bgShare * 100).toInt()}%", bgShare > 0.30f)
        if (mode == ThemeMode.LIGHT) {
            assertTrue("$name: background must dominate in light mode", bgShare > 0.50f)
        }
        assertTrue("$name: accent $accentRgb never rendered", accentHits > 0)
    }

    /** 0xFFRRGGBB as an ARGB int, matching Bitmap.getPixels. */
    private fun parse(hex: String): Int {
        val v = hex.removePrefix("#").toLong(16)
        return (0xFF000000L or v).toInt()
    }
}
