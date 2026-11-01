package tech.musxi.player.design

import android.graphics.BitmapFactory
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.core.update.UpdateState
import tech.musxi.player.ui.design.UpdateSheet
import tech.musxi.player.ui.design.updateDevelopmentState
import tech.musxi.player.ui.design.updateDownloadingState
import tech.musxi.player.ui.design.updateFailedState
import tech.musxi.player.ui.design.updateFoundState
import tech.musxi.player.ui.design.updateMeteredState
import tech.musxi.player.ui.design.updatePermissionState
import tech.musxi.player.ui.design.updateReadyState
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File

/**
 * The "关于" group in every update state, light, plus the found state in dark and the tab bar with
 * its red dot. The page is mostly paper, so the checks are loose: it decoded, it has content, and
 * the two states that use the error colour really draw it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class UpdateSheetTest {

    @Test fun development() = capture("update_development_light", updateDevelopmentState())
    @Test fun found() = capture("update_found_light", updateFoundState())
    @Test fun foundExpanded() = capture("update_found_expanded_light", updateFoundState(), expanded = true)
    @Test fun metered() = capture("update_metered_light", updateMeteredState(), confirming = true)
    @Test fun downloading() = capture("update_downloading_light", updateDownloadingState())
    @Test fun ready() = capture("update_ready_light", updateReadyState())
    @Test fun permission() = capture("update_permission_light", updatePermissionState())
    @Test fun failed() = capture("update_failed_light", updateFailedState(), expectRed = true)
    @Test fun tabBadge() = capture("update_tab_badge_light", updateFoundState(), dock = true, expectRed = true)
    @Test fun foundDark() = capture("update_found_dark", updateFoundState(), mode = ThemeMode.DARK)

    private fun capture(
        name: String,
        state: UpdateState,
        mode: ThemeMode = ThemeMode.LIGHT,
        expanded: Boolean = false,
        confirming: Boolean = false,
        dock: Boolean = false,
        expectRed: Boolean = false,
    ) {
        val out = File("build/design/$name.png")
        captureRoboImage(out.path) { UpdateSheet(Accent.MIST, mode, state, expanded, confirming, dock) }
        // Without -Proborazzi.test.record=true nothing is written; the full test run must still pass.
        if (!out.exists()) return
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertTrue("$name failed to decode", bitmap != null)
        // The theme's page backgrounds: Neutrals.lightBackground and the dark base.
        val background = parse(if (mode == ThemeMode.LIGHT) "#FAFAFA" else "#121212")
        val samples = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(samples, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val bgShare = samples.count { it == background } / samples.size.toFloat()
        assertTrue("$name: background only ${(bgShare * 100).toInt()}%", bgShare > 0.30f)
        assertTrue("$name: page looks empty", bgShare < 0.995f)
        if (expectRed) {
            val red = samples.count { p ->
                ((p shr 16) and 0xFF) > 150 && ((p shr 8) and 0xFF) < 90 && (p and 0xFF) < 90
            }
            assertTrue("$name: no error-coloured pixels ($red)", red > 10)
        }
    }

    private fun parse(hex: String): Int = (0xFF000000L or hex.removePrefix("#").toLong(16)).toInt()
}
