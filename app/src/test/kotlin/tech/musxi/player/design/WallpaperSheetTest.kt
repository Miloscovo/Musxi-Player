package tech.musxi.player.design

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.core.wallpaper.WallpaperFrame
import tech.musxi.player.ui.design.PlayerSurfaceSheet
import tech.musxi.player.ui.design.lyricsTimed
import tech.musxi.player.ui.design.playerNormal
import tech.musxi.player.ui.player.WallpaperLook
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File

/**
 * Wallpaper sheets for the owner: the real continuous player, open, with a picture behind it.
 * Light and dark × cover page and lyrics page × three strengths on one photo, then two harder
 * photos (a bright misty one and a dark night one) at the default strength, which is where the
 * text floor has to earn its keep.
 *
 * The photos are generated for the repository (no borrowed images), see `src/test/resources/wallpaper`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class WallpaperSheetTest {

    @Test fun sunsetStrengths() {
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            for (lyrics in listOf(false, true)) {
                for (strength in listOf(15, 35, 60, 100, 150)) {
                    capture("sunset", mode, lyrics, strength, WallpaperFrame(centerX = 0.62f))
                }
            }
        }
    }

    @Test fun hardPhotos() {
        for (photo in listOf("mist", "night")) {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                for (lyrics in listOf(false, true)) capture(photo, mode, lyrics, 35, WallpaperFrame())
            }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(photo: String, mode: ThemeMode, lyrics: Boolean, strength: Int, frame: WallpaperFrame) {
        val page = if (lyrics) "lyrics" else "cover"
        val theme = if (mode == ThemeMode.LIGHT) "light" else "dark"
        val name = "wallpaper_${photo}_${page}_${theme}_s$strength"
        val out = File("build/design/$name.png")
        val look = WallpaperLook(load(photo), frame, strength)
        // Recorded explicitly: without a task type Roborazzi only compares and writes nothing.
        captureRoboImage(out.path, RoborazziOptions(taskType = RoborazziTaskType.Record)) {
            PlayerSurfaceSheet(Accent.MIST, mode, playerNormal(), 1f, if (lyrics) lyricsTimed() else null, look)
        }
        if (!out.exists()) return
        assertTrue("$name failed to decode", BitmapFactory.decodeFile(out.path) != null)
    }

    private fun load(photo: String): ImageBitmap {
        val stream = requireNotNull(javaClass.getResourceAsStream("/wallpaper/$photo.jpg")) { "missing $photo" }
        return stream.use { BitmapFactory.decodeStream(it) }.asImageBitmap()
    }
}
