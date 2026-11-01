package tech.musxi.player.design

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.ui.design.sampleMiniPlaying
import tech.musxi.player.ui.player.MiniPlayer
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File
import kotlin.math.abs

/**
 * The mini row's title and artist are one block, and the ink of that block (not its line boxes)
 * sits in the middle of the cover's height: the distance from the title's top to the cover's top
 * edge equals the distance from the artist's bottom to the cover's bottom edge.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class MiniPlayerAlignmentTest {

    @Test fun textBlockIsCentredOnTheCover() {
        val out = File("build/design/mini_player_aligned_mist_light.png")
        captureRoboImage(out.path) {
            MusxiTheme(accent = Accent.MIST, mode = ThemeMode.LIGHT) {
                Column(Modifier.fillMaxSize().background(Color.White)) {
                    Spacer(Modifier.height(100.dp))
                    MiniPlayer(
                        ui = sampleMiniPlaying(), onExpand = {}, onPlayPause = {}, onPrevious = {}, onNext = {},
                        embedded = true,
                    )
                }
            }
        }
        if (!out.exists()) return
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertTrue("does not decode", bitmap != null)
        val w = bitmap.width
        val px = IntArray(w * bitmap.height)
        bitmap.getPixels(px, 0, w, 0, 0, w, bitmap.height)
        fun dark(x: Int, y: Int): Boolean {
            val c = px[y * w + x]
            return minOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF) < 215
        }
        // xhdpi: 2px per dp. The row spans 200..328 px; the cover is 48dp wide, 8dp from the left.
        val rowTop = 200
        val rowBottom = 328
        val coverRows = (rowTop until rowBottom).filter { dark(64, it) }
        val coverTop = coverRows.first()
        val coverBottom = coverRows.last() + 1
        // Text sits between the cover (ends 56dp) and the controls (start about 248dp).
        val inkRows = (rowTop until rowBottom).filter { y -> (140 until 480).any { x -> dark(x, y) } }
        val inkTop = inkRows.first()
        val inkBottom = inkRows.last() + 1
        val gaps = (inkTop until inkBottom).filter { it !in inkRows }
        val top = inkTop - coverTop
        val bottom = coverBottom - inkBottom
        val message = "cover=$coverTop..$coverBottom ink=$inkTop..$inkBottom top=$top bottom=$bottom " +
            "lineGap=${gaps.size}px"
        println("MINI-ALIGN $message")
        assertTrue("title top and artist bottom are not equidistant from the cover edges: $message", abs(top - bottom) <= 2)
        // Was 14px (7dp) with the theme's line boxes; packed, the two lines read as one block.
        assertTrue("title and artist are too far apart: $message", gaps.size <= 10)
    }
}
