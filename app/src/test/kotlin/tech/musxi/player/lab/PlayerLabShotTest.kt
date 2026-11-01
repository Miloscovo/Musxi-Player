package tech.musxi.player.lab

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
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
 * Frozen moments of the continuous player: the dock (p = 0), the surface half way (p = 0.5), the
 * full cover page (p = 1), the lyrics mode, queue A, and the blurred-cover backdrop. The initial
 * state is injected, so no gesture is needed to reach any of them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class PlayerLabShotTest {
    @Test fun mini() = capture("lab_player_p0", PlayerLabInitial(progress = 0f))

    @Test fun half() = capture("lab_player_p05", PlayerLabInitial(progress = 0.5f))

    @Test fun full() = capture("lab_player_p1", PlayerLabInitial(progress = 1f))

    @Test fun lyrics() = capture("lab_player_lyrics", PlayerLabInitial(progress = 1f, mode = PlayerMode.LYRICS))

    @Test fun queue() = capture("lab_player_queue", PlayerLabInitial(progress = 1f, mode = PlayerMode.QUEUE))

    @Test fun blur() = capture("lab_player_blur", PlayerLabInitial(progress = 1f, backdrop = PlayerBackdrop.BLUR))

    @Test fun fullDark() = capture("lab_player_p1_dark", PlayerLabInitial(progress = 1f), dark = true)

    @Test fun miniDark() = capture("lab_player_p0_dark", PlayerLabInitial(progress = 0f), dark = true)

    private fun capture(name: String, initial: PlayerLabInitial, dark: Boolean = false) {
        val out = File("build/design/$name.png")
        captureRoboImage(out.path) {
            MusxiTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize()) { PlayerLab(fakeEnv(dark), initial) }
            }
        }
        // Without -Proborazzi.test.record=true no file is written; the full test run must still pass.
        if (!out.exists()) return
        assertTrue("$name: screenshot does not decode", BitmapFactory.decodeFile(out.path) != null)
    }

    private fun fakeEnv(dark: Boolean): LabEnv = LabEnv(
        songs = (0 until 12).map { i ->
            val (title, artist) = Names[i % Names.size]
            LabSong("test-$i", title, artist, "示例专辑", 180_000L + i * 11_000L, coverUrl = null)
        },
        dark = dark,
        tuning = LabTuning.STANDARD,
        onTuning = {},
        onToggleDark = {},
        onExit = {},
    )

    private companion object {
        val Names = listOf("夜航" to "林间", "回声" to "Aster", "薄雾" to "沈宁", "海盐" to "Noa", "晴空万里" to "周屿")
    }
}
