package tech.musxi.player.design

import android.graphics.BitmapFactory
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.ui.design.TrackListSheet
import tech.musxi.player.ui.library.NumberStyle
import tech.musxi.player.ui.library.TrackRowStyle
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File

/**
 * Playlist detail sheets: the real page, in the row treatment that shipped plus the two recorded
 * alternates.
 *
 * The form decision (overlay / second level / hero cover) is settled, and the three form images are
 * archived in `docs/design/candidates-20261021/`. Rendering rejected forms in the live suite would
 * only let the sheet drift away from the page that is actually being built.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class TrackListSheetTest {

    /** Shipped: numbered rows, number flush at the page margin and on the title's baseline. */
    @Test fun tracks() = capture("library_detail_tracks_mist_light", ThemeMode.LIGHT)

    @Test fun tracksDark() = capture("library_detail_tracks_mist_dark", ThemeMode.DARK)

    /** Recorded alternate: zero-padded numbers freeze the inner edge as well. */
    @Test fun zeroPadded() = capture(
        "library_detail_zeropad_mist_light", ThemeMode.LIGHT, numberStyle = NumberStyle.ZERO_PAD,
    )

    /** Recorded alternate: a 40dp cover per row, flush by construction, louder on the page. */
    @Test fun coverRows() = capture(
        "library_detail_cover_mist_light", ThemeMode.LIGHT, rowStyle = TrackRowStyle.COVER,
    )

    private fun capture(
        name: String,
        mode: ThemeMode,
        rowStyle: TrackRowStyle = TrackRowStyle.NUMBERED,
        numberStyle: NumberStyle = NumberStyle.FLUSH_SMALL,
    ) {
        val out = File("build/design/$name.png")
        captureRoboImage(out.path) {
            TrackListSheet(
                accent = Accent.MIST,
                mode = mode,
                rowStyle = rowStyle,
                numberStyle = numberStyle,
            )
        }
        if (!out.exists()) return
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertTrue("$name failed to decode", bitmap != null)
        val w = bitmap.width
        val h = bitmap.height
        val samples = IntArray(w * h)
        bitmap.getPixels(samples, 0, w, 0, 0, w, h)

        var distinct = 0
        var previous = samples[0]
        for (i in samples.indices) {
            if (samples[i] != previous) distinct++
            previous = samples[i]
        }
        // A page that failed to draw is one flat colour; a real one has text everywhere.
        assertTrue("$name looks blank ($distinct transitions)", distinct > w)

        // The measured rule this round was about: the page margin must be the same on both sides.
        // Only the row band is measured, because the header and the dock already sit at 20dp.
        val background = samples[0]
        var left = w
        var right = -1
        for (y in (h * 0.36f).toInt() until (h * 0.74f).toInt()) {
            for (x in 0 until w) {
                val c = samples[y * w + x]
                val diff = kotlin.math.abs((c shr 16 and 0xFF) - (background shr 16 and 0xFF)) +
                    kotlin.math.abs((c shr 8 and 0xFF) - (background shr 8 and 0xFF)) +
                    kotlin.math.abs((c and 0xFF) - (background and 0xFF))
                if (diff > 12) {
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        val leftDp = left / 2f
        val rightDp = (w - 1 - right) / 2f
        assertTrue(
            "$name: left margin ${leftDp}dp and right margin ${rightDp}dp are not within 4dp",
            kotlin.math.abs(leftDp - rightDp) < 4f,
        )
    }
}
