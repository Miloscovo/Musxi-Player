package tech.musxi.player.design

import android.graphics.BitmapFactory
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.ui.design.QueueSheet
import tech.musxi.player.ui.design.queueEditing
import tech.musxi.player.ui.design.queueEmpty
import tech.musxi.player.ui.design.queueSample
import tech.musxi.player.ui.player.QueueUi
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File

/**
 * Queue sheet: the panel over the player, in the three states that matter — a normal queue, a queue
 * being edited, and an empty one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class QueueSheetTest {

    @Test fun normal() = capture("queue_normal_mist_light", ThemeMode.LIGHT, queueSample())

    @Test fun normalDark() = capture("queue_normal_mist_dark", ThemeMode.DARK, queueSample())

    /** A command is in flight: every edit control dims, and the panel says nothing. */
    @Test fun editing() = capture("queue_editing_mist_light", ThemeMode.LIGHT, queueEditing())

    @Test fun empty() = capture("queue_empty_mist_light", ThemeMode.LIGHT, queueEmpty())

    /** The other reading: no artwork per row, numbering carries the list. */
    @Test fun noCover() = capture(
        "queue_nocover_mist_light", ThemeMode.LIGHT, queueSample(), showCover = false,
    )

    @Test fun noCoverDark() = capture(
        "queue_nocover_mist_dark", ThemeMode.DARK, queueSample(), showCover = false,
    )

    private fun capture(
        name: String,
        mode: ThemeMode,
        ui: QueueUi,
        showCover: Boolean = true,
    ) {
        val out = File("build/design/$name.png")
        captureRoboImage(out.path) { QueueSheet(Accent.MIST, mode, ui, showCover = showCover) }
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
        assertTrue("$name looks blank ($distinct transitions)", distinct > w)
    }
}
