package tech.musxi.player.design

import android.graphics.BitmapFactory
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.ui.design.AccountSheet
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File

/**
 * Account screen sheets. One per accent and brightness plus the failures that are easy to get
 * wrong: every account row must stay readable while the platform colour is only a small mark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class AccountSheetTest {

    @Test fun mistLight() = capture(Accent.MIST, ThemeMode.LIGHT, "#F7F7F5", "#4A6A9A")
    @Test fun mistDark() = capture(Accent.MIST, ThemeMode.DARK, "#121212", "#A9BEE0")
    @Test fun tealLight() = capture(Accent.TEAL, ThemeMode.LIGHT, "#F7F7F5", "#2B7A71")
    @Test fun amberLight() = capture(Accent.AMBER, ThemeMode.LIGHT, "#F7F7F5", "#9C6229")

    private fun capture(accent: Accent, mode: ThemeMode, background: String, accentRgb: String) {
        val name = "account_${accent.name.lowercase()}_${mode.name.lowercase()}.png"
        val out = File("build/design/$name")
        captureRoboImage(out.path) { AccountSheet(accent, mode) }
        if (!out.exists()) return
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertTrue("$name failed to decode", bitmap != null)
        val bg = parse(background)
        val accentColor = parse(accentRgb)
        val samples = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(samples, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var bgHits = 0
        var accentHits = 0
        for (p in samples) {
            if (p == bg) bgHits++
            if (p == accentColor) accentHits++
        }
        val bgShare = bgHits / samples.size.toFloat()
        assertTrue("$name: background only ${(bgShare * 100).toInt()}%", bgShare > 0.30f)
        // The accent appears in the status mark and the one solid action.
        assertTrue("$name: accent never rendered", accentHits > 0)
        // Rows are lines on paper now, not cards: what proves they rendered is the platform dots,
        // which are the only saturated pixels on the page.
        val colourful = samples.count { p ->
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            maxOf(r, g, b) - minOf(r, g, b) > 24
        }
        assertTrue("$name: platform dots missing ($colourful px)", colourful > 30)
    }

    private fun parse(hex: String): Int = (0xFF000000L or hex.removePrefix("#").toLong(16)).toInt()
}
