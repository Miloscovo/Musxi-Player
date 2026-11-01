package tech.musxi.player.design

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.toArgb
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.ui.design.PlayerSurfaceSheet
import tech.musxi.player.ui.design.lyricsTimed
import tech.musxi.player.ui.design.playerNormal
import tech.musxi.player.ui.player.LyricsUi
import tech.musxi.player.ui.player.playerPalette
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File
import kotlin.math.abs

/**
 * The continuous player frozen at three moments (p = 0, 0.5, 1) in light, and the full page in dark.
 * Progress is injected, so no gesture is needed to reach any of them. The assertions are structural:
 * which colour sits at the top edge says how far the surface has grown, and the accent play button
 * only exists once the full page is up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class PlayerSurfaceSheetTest {

    @Test fun docked() = capture("player_surface_p0_mist_light", ThemeMode.LIGHT, 0f)
    @Test fun halfway() = capture("player_surface_p05_mist_light", ThemeMode.LIGHT, 0.5f)
    @Test fun expanded() = capture("player_surface_p1_mist_light", ThemeMode.LIGHT, 1f)
    @Test fun expandedDark() = capture("player_surface_p1_mist_dark", ThemeMode.DARK, 1f)
    @Test fun lyrics() = capture("player_surface_lyrics_mist_light", ThemeMode.LIGHT, 1f, lyricsTimed())

    private fun capture(name: String, mode: ThemeMode, p: Float, lyrics: LyricsUi? = null) {
        val ui = playerNormal()
        val out = File("build/design/$name.png")
        captureRoboImage(out.path) { PlayerSurfaceSheet(Accent.MIST, mode, ui, p, lyrics) }
        if (!out.exists()) return
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertTrue("$name failed to decode", bitmap != null)
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val palette = playerPalette(ui.coverSeed, mode != ThemeMode.LIGHT)

        // Top edge, centre column: the cover-colour backdrop once the surface has reached the top.
        val topEdge = pixels[4 * w + w / 2]
        val backdrop = palette.backdropTop.toArgb()
        val atTop = close(topEdge, backdrop, 8)
        when {
            p >= 1f -> assertTrue("$name: backdrop missing at the top edge", atTop)
            else -> assertTrue("$name: surface should not reach the top edge yet", !atTop)
        }

        // The accent play button belongs to the full page only.
        val accent = palette.accent.toArgb()
        var accentPixels = 0
        for (y in (h * 0.60f).toInt() until h) for (x in 0 until w) if (pixels[y * w + x] == accent) accentPixels++
        if (p >= 1f) {
            assertTrue("$name: no accent play button found ($accentPixels px)", accentPixels > 200)
        } else if (p <= 0f) {
            assertTrue("$name: full-page controls leaked into the dock ($accentPixels px)", accentPixels < 50)
        }
    }

    private fun close(a: Int, b: Int, tolerance: Int): Boolean =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= tolerance &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= tolerance &&
            abs((a and 0xFF) - (b and 0xFF)) <= tolerance
}
