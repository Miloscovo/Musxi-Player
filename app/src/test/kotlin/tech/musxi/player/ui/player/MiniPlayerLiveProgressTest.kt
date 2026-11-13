package tech.musxi.player.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.design.sampleMiniPlaying
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File

/**
 * Spec section 12: a playback tick moves the mini row's progress and recomposes nothing around it.
 * The position is handed down as a provider and read only in the leaf that draws it.
 *
 * The row's own recompositions are counted through MiniPlayer's `onRowRecompose` hook, which runs
 * from a SideEffect in MiniPlayer's body. If MiniPlayer read the provider anywhere in that body
 * (rather than in the BAND leaf or the RING's draw block), every tick would invalidate the body's
 * scope and the count would rise.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class MiniPlayerLiveProgressTest {
    @get:Rule val compose = createComposeRule()

    private val position = mutableFloatStateOf(0.25f)
    private val live: () -> Float = { position.floatValue }
    private var rowCompositions = 0

    private fun show(style: ProgressStyle, embedded: Boolean) {
        compose.setContent {
            MusxiTheme(accent = Accent.MIST, mode = ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize()) {
                    MiniPlayer(
                        ui = sampleMiniPlaying(), onExpand = {}, onPlayPause = {}, onPrevious = {}, onNext = {},
                        style = style, embedded = embedded, liveProgress = live,
                        onRowRecompose = { rowCompositions++ },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun tick(to: Float) {
        position.floatValue = to
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun fillWidth(): Int =
        compose.onNodeWithTag(MiniProgressFillTag, useUnmergedTree = true).fetchSemanticsNode().size.width

    @Test fun aNewPositionMovesTheBarWithoutRecomposingTheRow() {
        show(ProgressStyle.BAND, embedded = true)
        val composedAtStart = rowCompositions
        assertTrue("the hook should have run on the first composition", composedAtStart > 0)
        val quarter = fillWidth()
        assertTrue("the bar should show some progress at 25%", quarter > 0)

        tick(0.75f)
        val threeQuarters = fillWidth()

        assertEquals("the mini row recomposed on a progress tick", composedAtStart, rowCompositions)
        assertEquals("the bar should be three times as long", quarter * 3f, threeQuarters.toFloat(), 3f)
    }

    /**
     * The live default. The ring reads the provider in its draw block, so a tick must change the
     * pixels of the ring and nothing else, and must not recompose the row.
     *
     * Geometry (360dp wide, not embedded): the row ends 8dp from the right edge; the controls are
     * 32dp + 40dp + 32dp, so the ring's 40dp slot spans x 280..320dp and y 12..52dp of the 64dp row.
     */
    @Test fun aNewPositionRedrawsTheRingWithoutRecomposingTheRow() {
        show(ProgressStyle.RING, embedded = false)
        val composedAtStart = rowCompositions
        val before = picture("ring-25")

        tick(0.75f)
        val after = picture("ring-75")

        assertEquals("the mini row recomposed on a progress tick", composedAtStart, rowCompositions)
        val ring = Area(280f, 12f, 320f, 52f)
        val changedInRing = before.changedPixels(after, inside = ring)
        val changedElsewhere = before.changedPixels(after, outside = ring)
        // 25% -> 75% adds half a circle of 2.5dp stroke: far more than a handful of pixels.
        assertTrue("the ring did not redraw ($changedInRing pixels changed)", changedInRing > 40)
        assertEquals("a tick changed pixels outside the ring", 0, changedElsewhere)
    }

    @Test fun withoutAProviderTheModelsOwnProgressIsDrawn() {
        compose.setContent {
            MusxiTheme(accent = Accent.MIST, mode = ThemeMode.LIGHT) {
                MiniPlayer(
                    ui = sampleMiniPlaying().copy(progress = 0.5f), onExpand = {}, onPlayPause = {}, onPrevious = {}, onNext = {},
                    style = ProgressStyle.BAND, embedded = true,
                )
            }
        }
        compose.waitForIdle()
        // 360dp wide, 16dp inset each side: a 328dp track at xhdpi is 656px; half is 328px.
        assertEquals(328f, fillWidth().toFloat(), 2f)
    }

    /** Rectangle in dp. */
    private data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private class Picture(val bitmap: Bitmap) {
        private val scale = bitmap.width / 360f

        private fun contains(a: Area, x: Int, y: Int): Boolean {
            val xDp = x / scale
            val yDp = y / scale
            return xDp >= a.left && xDp < a.right && yDp >= a.top && yDp < a.bottom
        }

        /** Counts differing pixels, restricted to [inside] or to everything but [outside]. */
        fun changedPixels(other: Picture, inside: Area? = null, outside: Area? = null): Int {
            var n = 0
            val w = minOf(bitmap.width, other.bitmap.width)
            val h = minOf(bitmap.height, other.bitmap.height)
            for (y in 0 until h) for (x in 0 until w) {
                if (inside != null && !contains(inside, x, y)) continue
                if (outside != null && contains(outside, x, y)) continue
                if (bitmap.getPixel(x, y) != other.bitmap.getPixel(x, y)) n++
            }
            return n
        }
    }

    /**
     * Compose's own `captureToImage` waits for a redraw that Robolectric's paused looper never delivers
     * (see GlassRefractionRenderTest), so this goes through Roborazzi into a scratch file in build/tmp.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun picture(name: String): Picture {
        val out = File("build/tmp/mini-live-progress/$name.png")
        compose.onRoot().captureRoboImage(out.path, RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertNotNull("$name was not written", bitmap)
        return Picture(bitmap)
    }
}
