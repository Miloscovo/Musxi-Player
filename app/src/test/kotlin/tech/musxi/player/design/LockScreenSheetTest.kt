package tech.musxi.player.design

import android.graphics.BitmapFactory
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
import tech.musxi.core.settings.LyricsFontSize
import tech.musxi.player.ui.design.LockScreenSheet
import tech.musxi.player.ui.design.lockScreenBilingual
import tech.musxi.player.ui.design.lockScreenLocal
import tech.musxi.player.ui.design.lockScreenNoLyrics
import tech.musxi.player.ui.design.lockScreenPaused
import tech.musxi.player.ui.design.lockScreenTimed
import tech.musxi.player.ui.lockscreen.LockScreenUi
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File

/** Sheets of Musxi's own lock-screen page (plan C), for the owner to judge before any wiring. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class LockScreenSheetTest {
    @Test fun timedLight() = capture("lockscreen_timed_light", ThemeMode.LIGHT, lockScreenTimed())

    @Test fun timedDark() = capture("lockscreen_timed_dark", ThemeMode.DARK, lockScreenTimed())

    @Test fun bilingualDark() = capture("lockscreen_bilingual_dark", ThemeMode.DARK, lockScreenBilingual())

    @Test fun largeFontDark() = capture(
        "lockscreen_timed_largefont_dark", ThemeMode.DARK, lockScreenTimed(), fontSize = LyricsFontSize.LARGE,
    )

    @Test fun pausedLight() = capture("lockscreen_paused_light", ThemeMode.LIGHT, lockScreenPaused())

    @Test fun noLyricsDark() = capture("lockscreen_nolyrics_dark", ThemeMode.DARK, lockScreenNoLyrics())

    @Test fun localDark() = capture("lockscreen_local_dark", ThemeMode.DARK, lockScreenLocal(), progress = 0.18f)

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(
        name: String,
        mode: ThemeMode,
        ui: LockScreenUi,
        progress: Float = 0.44f,
        fontSize: LyricsFontSize = LyricsFontSize.MEDIUM,
    ) {
        val out = File("build/design/$name.png")
        captureRoboImage(out.path, RoborazziOptions(taskType = RoborazziTaskType.Record)) {
            LockScreenSheet(mode, ui, progress, fontSize)
        }
        assertTrue("$name was not written", out.exists())
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertTrue("$name failed to decode", bitmap != null)
        val w = bitmap.width
        val pixels = IntArray(w * bitmap.height)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, bitmap.height)
        var transitions = 0
        for (i in 1 until pixels.size) if (pixels[i] != pixels[i - 1]) transitions++
        assertTrue("$name looks blank ($transitions transitions)", transitions > w)
    }
}
