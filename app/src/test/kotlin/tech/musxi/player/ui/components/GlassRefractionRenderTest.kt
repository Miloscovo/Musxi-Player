package tech.musxi.player.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.theme.MusxiTheme
import java.io.File
import kotlin.math.abs

/**
 * Pixel checks that the refraction renderer really draws (API 35, a Robolectric level where AGSL
 * works). Two glass cards of the same size sit over red and blue stripes, the upper one at y 100-260dp
 * and the lower one at y 400-560dp. Where the shapes agree the pictures must agree; where only the
 * rounded one has a lens (the rim) they must differ.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class GlassRefractionRenderTest {
    @get:Rule val compose = createComposeRule()

    /** 30dp stripes, red first: stripe n covers x in [30n, 30n + 30). */
    @Composable
    private fun Stripes() {
        Row(Modifier.fillMaxSize()) {
            repeat(12) {
                Box(Modifier.width(30.dp).fillMaxHeight().background(if (it % 2 == 0) Color.Red else Color.Blue))
            }
        }
    }

    private fun stripesWithCards(upper: Shape, lower: Shape) {
        compose.setContent {
            MusxiTheme {
                GlassHost(
                    overlay = {
                        Box(
                            Modifier.align(Alignment.TopStart).offset(24.dp, 100.dp).size(280.dp, 160.dp)
                                .glass(LocalGlass.current, upper, GlassStyle.REGULAR),
                        )
                        Box(
                            Modifier.align(Alignment.TopStart).offset(24.dp, 400.dp).size(280.dp, 160.dp)
                                .glass(LocalGlass.current, lower, GlassStyle.REGULAR),
                        )
                    },
                ) { Stripes() }
            }
        }
        compose.waitForIdle()
    }

    private var shift by mutableIntStateOf(0)

    private class Picture(private val bitmap: Bitmap) {
        private val scale = bitmap.width / 360f
        fun at(xDp: Float, yDp: Float): Color = Color(bitmap.getPixel((xDp * scale).toInt(), (yDp * scale).toInt()))
    }

    /**
     * Compose's own `captureToImage` waits for a redraw that Robolectric's paused looper never delivers,
     * so this goes through Roborazzi, forced to write the picture even without `-Proborazzi.test.record`.
     * The file is scratch, in build/tmp, not in the screenshot folder.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun picture(name: String): Picture {
        val out = File("build/tmp/glass-render/$name.png")
        compose.onRoot().captureRoboImage(out.path, RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertNotNull("$name was not written", bitmap)
        return Picture(bitmap)
    }

    private fun gap(a: Color, b: Color) = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    @Test fun theLensBendsThePageAtTheRimAndOnlyThere() {
        stripesWithCards(upper = RoundedCornerShape(28.dp), lower = RectangleShape)
        val pic = picture("rim")
        // Mid-height of both cards, measured in from their left edge at 24dp, where the straight edges
        // have the same clip. The lens ring is 20dp wide, so the gap fades out before 20dp.
        val rim = (3..8).maxOf { gap(pic.at(24f + it, 180f), pic.at(24f + it, 480f)) }
        val middle = (60..120 step 10).maxOf { gap(pic.at(24f + it, 180f), pic.at(24f + it, 480f)) }
        assertTrue("the rim should show the page bent by the lens (gap $rim)", rim > 0.15f)
        assertTrue("the middle of the card must not be bent (gap $middle)", middle < 0.02f)
    }

    // The sheet slides and the menu scales through layout and layer offsets, with no recomposition. The
    // glass has to show the page under its new place, not the one it left: moved by one stripe (30dp)
    // it would flip from red to blue if it kept sampling the old spot.
    @Test fun movingWithoutRecompositionStillSamplesThePageUnderTheGlass() {
        compose.setContent {
            MusxiTheme {
                GlassHost(
                    overlay = {
                        Box(
                            Modifier.align(Alignment.TopStart).offset { IntOffset(shift.dp.roundToPx(), 0) }
                                .offset(0.dp, 100.dp).size(260.dp, 160.dp)
                                .glass(LocalGlass.current, RoundedCornerShape(28.dp), GlassStyle.REGULAR),
                        )
                    },
                ) { Stripes() }
            }
        }
        compose.waitForIdle()
        // The card spans x 0-260dp, then 30-290dp. x 75dp is the middle of the red stripe [60, 90) and 105dp
        // the middle of the blue one [90, 120), both deep inside the card before and after the move.
        val before = picture("move_before")
        // x 275dp is the bare blue page before the move (no tint, so no green) and inside the card after it.
        assertTrue("x 275dp should be bare page before the move (g ${before.at(275f, 190f).green})", before.at(275f, 190f).green < 0.1f)

        shift = 30
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        val after = picture("move_after")
        // A new frame: the glass has arrived at x 275dp and its tint shows there.
        assertTrue("x 275dp should be glass after the move (g ${after.at(275f, 190f).green})", after.at(275f, 190f).green > 0.3f)
        val red = after.at(75f, 190f)
        val blue = after.at(105f, 190f)
        assertTrue("the glass should show the red stripe at 75dp (r ${red.red}, b ${red.blue})", red.red - red.blue > 0.2f)
        assertTrue("the glass should show the blue stripe at 105dp (r ${blue.red}, b ${blue.blue})", blue.blue - blue.red > 0.2f)
    }

    // A red page, and a GlassLayer drawing blue over its top 400dp, as the player grows over a tab.
    // Glass in the layer's overlay must show the composite: blue above 400dp, red below. Glass in
    // the host's overlay, drawn over the blue on screen, must still show only the red page, as the
    // dock does under the rising player.
    @Test fun glassOverALayerShowsTheLayerAndTheHostGlassDoesNot() {
        compose.setContent {
            MusxiTheme {
                GlassHost(
                    overlay = {
                        GlassLayer(
                            active = true,
                            overlay = {
                                // Green that no recording holds. The glass hides it if it draws page
                                // and layer; a glass that sampled nothing would let it through.
                                Box(Modifier.align(Alignment.TopStart).offset(16.dp, 240.dp).size(160.dp, 320.dp).background(Color.Green))
                                Box(
                                    Modifier.align(Alignment.TopStart).offset(16.dp, 240.dp).size(160.dp, 320.dp)
                                        .glass(LocalGlass.current, RoundedCornerShape(28.dp), GlassStyle.REGULAR),
                                )
                            },
                        ) { Box(Modifier.fillMaxWidth().height(400.dp).background(Color.Blue)) }
                        Box(
                            Modifier.align(Alignment.TopStart).offset(184.dp, 240.dp).size(160.dp, 320.dp)
                                .glass(LocalGlass.current, RoundedCornerShape(28.dp), GlassStyle.REGULAR),
                        )
                    },
                ) { Box(Modifier.fillMaxSize().background(Color.Red)) }
            }
        }
        compose.waitForIdle()
        val pic = picture("layer")
        // 80dp above and below the edge at 400dp, well past the 8dp blur, and 80dp in from the card sides.
        val innerTop = pic.at(96f, 320f)
        val innerBottom = pic.at(96f, 480f)
        val hostTop = pic.at(264f, 320f)
        val hostBottom = pic.at(264f, 480f)
        assertTrue("layer glass should show the layer (r ${innerTop.red}, b ${innerTop.blue})", innerTop.blue - innerTop.red > 0.2f)
        assertTrue("layer glass should show the page below the layer (r ${innerBottom.red}, b ${innerBottom.blue})", innerBottom.red - innerBottom.blue > 0.2f)
        assertTrue("the green under the glass must not show (g ${innerTop.green}, ${innerBottom.green})", maxOf(innerTop.green, innerBottom.green) < 0.75f)
        assertTrue("host glass must not show the layer (r ${hostTop.red}, b ${hostTop.blue})", hostTop.red - hostTop.blue > 0.2f)
        assertTrue("host glass should show the page (r ${hostBottom.red}, b ${hostBottom.blue})", hostBottom.red - hostBottom.blue > 0.2f)
    }

    // The lens shader only knows rounded rectangles and throws for other shapes. The guard leaves
    // those with blur and tint, so the card still draws.
    @Test fun aRectangleStillDrawsWithBlurAndTint() {
        stripesWithCards(upper = RectangleShape, lower = RectangleShape)
        val centre = picture("rectangle").at(164f, 180f)
        // The page has no green anywhere; a white tint over it adds some.
        assertTrue("the tint should show over the page (green ${centre.green})", centre.green > 0.3f)
    }
}
