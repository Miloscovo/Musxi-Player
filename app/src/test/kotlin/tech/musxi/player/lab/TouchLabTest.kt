package tech.musxi.player.lab

import android.graphics.BitmapFactory
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File

/**
 * Screenshots of the touch lab: the haptic sampler at rest, and the long-press menu open on the
 * third song row. The screen is tall so row 3 stays on screen with its menu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp-xhdpi", application = DesignTestApplication::class)
class TouchLabTest {

    @get:Rule val compose = createComposeRule()

    @Test fun haptics() = capture("lab_touch_haptics", null)

    @Test fun menuOpenOnRowThree() = capture("lab_touch_menu_row3", 2)

    @Test fun menuOpenOnRowEleven() = capture("lab_touch_menu_row11", 10, scrollToEnd = true)

    private fun capture(name: String, menuRow: Int?, scrollToEnd: Boolean = false) {
        val out = File("build/design/$name.png")
        compose.setContent {
            MusxiTheme(mode = ThemeMode.LIGHT) {
                TouchLab(fakeEnv(), menuRow, scrollToEnd)
            }
        }
        // The menu is placed from coordinates that arrive after the first layout: capture the
        // settled frame, not the first one.
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(out.path)
        if (!out.exists()) return
        assertTrue("$name: screenshot does not decode", BitmapFactory.decodeFile(out.path) != null)
    }

    private fun fakeEnv(): LabEnv = LabEnv(
        songs = (0 until 12).map { i ->
            LabSong(
                id = "test-$i",
                title = "样例歌曲 ${i + 1}",
                artist = "示例歌手",
                album = "示例专辑",
                durationMs = 180_000L + i * 11_000L,
                coverUrl = null,
            )
        },
        dark = false,
        tuning = LabTuning.STANDARD,
        onTuning = {},
        onToggleDark = {},
        onExit = {},
    )
}
