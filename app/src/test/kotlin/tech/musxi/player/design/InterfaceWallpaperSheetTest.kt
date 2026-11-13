package tech.musxi.player.design

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.core.wallpaper.WallpaperFrame
import tech.musxi.player.ui.components.LocalPageBackdrop
import tech.musxi.player.ui.components.PageBackdrop
import tech.musxi.player.ui.design.LibrarySheet
import tech.musxi.player.ui.design.libraryHomeSample
import tech.musxi.player.ui.design.libraryLoaded
import tech.musxi.player.ui.library.RecommendationCardUi
import tech.musxi.player.ui.library.RecommendationPhase
import tech.musxi.player.ui.design.sampleSongs
import tech.musxi.player.ui.player.WallpaperLook
import tech.musxi.player.ui.player.drawWallpaper
import tech.musxi.player.ui.player.interfaceWallpaperScrim
import tech.musxi.player.ui.player.lumaOf
import tech.musxi.player.ui.player.wallpaperSource
import tech.musxi.player.ui.player.wallpaperTone
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.ThemeMode
import tech.musxi.player.ui.wallpaper.WallpaperKind
import tech.musxi.player.ui.wallpaper.WallpaperPreviewBar
import tech.musxi.player.ui.wallpaper.WallpaperRow
import tech.musxi.player.ui.wallpaper.WallpaperRowUi
import java.io.File

/**
 * The 界面壁纸 on the library home (the page its preview is judged on), light and dark, a few
 * strengths, plus the two 外观 rows and the preview bar. Drawn with the same backdrop the shell
 * provides; the glass dock falls back to solid under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class InterfaceWallpaperSheetTest {

    @Test fun sunset() {
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (s in listOf(35, 100, 150)) library("sunset", mode, s)
    }

    @Test fun night() {
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) library("night", mode, 100)
    }

    @Test fun mist() = library("mist", ThemeMode.DARK, 100)

    /** The far end of the stretch past 100, on the photos that test the floor hardest. */
    @Test fun beyond() {
        library("night", ThemeMode.LIGHT, 150)
        library("mist", ThemeMode.DARK, 150)
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test fun rows() {
        val image = load("sunset")
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            val theme = if (mode == ThemeMode.LIGHT) "light" else "dark"
            captureRoboImage(
                File("build/design/iwall_rows_$theme.png").path,
                RoborazziOptions(taskType = RoborazziTaskType.Record),
            ) {
                MusxiTheme(Accent.MIST, mode) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.padding(Space.page)) {
                            WallpaperRow(
                                WallpaperKind.INTERFACE, WallpaperRowUi(image, WallpaperFrame(), enabled = true),
                                {}, {}, {}, {}, {},
                            )
                            WallpaperRow(
                                WallpaperKind.PLAYER, WallpaperRowUi(), {}, {}, {}, {}, {},
                                hasPicture = false, onUseOther = {},
                            )
                            for (strength in listOf(35, 100, 130)) WallpaperPreviewBar(
                                strength = strength, preferDark = strength == 35, working = false,
                                onStrength = {}, onStrengthDone = {}, onAdjust = {}, onCancel = {}, onApply = {},
                                modifier = Modifier.padding(top = 24.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun library(photo: String, mode: ThemeMode, strength: Int) {
        val theme = if (mode == ThemeMode.LIGHT) "light" else "dark"
        val look = WallpaperLook(load(photo), WallpaperFrame(), strength, load(photo))
        val home = libraryHomeSample().copy(
            recommendation = RecommendationCardUi("每日推荐", RecommendationPhase.READY, songs = sampleSongs()),
        )
        captureRoboImage(
            File("build/design/iwall_${photo}_${theme}_s$strength.png").path,
            RoborazziOptions(taskType = RoborazziTaskType.Record),
        ) {
            MusxiTheme(Accent.MIST, mode) {
                val base = MaterialTheme.colorScheme.background
                val dark = mode != ThemeMode.LIGHT
                val backdrop = PageBackdrop { topLeft, window ->
                    val source = wallpaperSource(look.image.width, look.image.height, window.width, window.height, look.frame)
                    val tone = wallpaperTone(look.image, look.probe, source)
                    val scrim = interfaceWallpaperScrim(look.strength, dark, tone, lumaOf(base))
                    drawWallpaper(look, source, base, scrim, 1f, topLeft, window)
                }
                CompositionLocalProvider(LocalPageBackdrop provides backdrop) {
                    LibrarySheet(Accent.MIST, mode, libraryLoaded(), home = home)
                }
            }
        }
    }

    private fun load(photo: String): ImageBitmap {
        val stream = requireNotNull(javaClass.getResourceAsStream("/wallpaper/$photo.jpg")) { "missing $photo" }
        return stream.use { BitmapFactory.decodeStream(it) }.asImageBitmap()
    }
}
