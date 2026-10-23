package tech.musxi.player.design

import android.graphics.BitmapFactory
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.ui.design.LibrarySheet
import tech.musxi.player.ui.design.libraryEmptyDisconnected
import tech.musxi.player.ui.design.libraryEmptyZero
import tech.musxi.player.ui.design.libraryError
import tech.musxi.player.ui.design.libraryLoaded
import tech.musxi.player.ui.library.PlaylistUiState
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File

/**
 * Library sheets: the shipped tab, not a look-alike. Loaded, empty and failed in light, plus dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class LibrarySheetTest {

    @Test fun loaded() = capture("library_loaded_mist_light", ThemeMode.LIGHT, libraryLoaded())
    @Test fun emptyZero() = capture("library_empty_zero_mist_light", ThemeMode.LIGHT, libraryEmptyZero())
    @Test fun emptyDisconnected() =
        capture("library_empty_disconnected_mist_light", ThemeMode.LIGHT, libraryEmptyDisconnected())
    @Test fun failed() = capture("library_error_mist_light", ThemeMode.LIGHT, libraryError())
    @Test fun loadedDark() = capture("library_loaded_mist_dark", ThemeMode.DARK, libraryLoaded())

    private fun capture(name: String, mode: ThemeMode, state: PlaylistUiState) {
        val out = File("build/design/$name.png")
        captureRoboImage(out.path) { LibrarySheet(Accent.MIST, mode, state) }
        if (!out.exists()) return
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertTrue("$name failed to decode", bitmap != null)
        val w = bitmap.width
        val h = bitmap.height
        val samples = IntArray(w * h)
        bitmap.getPixels(samples, 0, w, 0, 0, w, h)
        // Rows must carry more than text: the cover placeholder is what proves the list has art.
        // Only meaningful when there are rows to draw.
        if (state.items.isNotEmpty()) {
            val rowBand = (h * 0.30f).toInt() until (h * 0.60f).toInt()
            var colourful = 0
            for (y in rowBand) for (x in 0 until w) {
                val c = samples[y * w + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (maxOf(r, g, b) - minOf(r, g, b) > 18) colourful++
            }
            assertTrue("$name: list rows have no artwork ($colourful px)", colourful > w)
        }
    }
}
