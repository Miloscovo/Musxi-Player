package tech.musxi.player.lab

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
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
 * Screenshots of the navigation lab: root page, playlist page, a push half way (the page below
 * shifted and dimmed), and the two back-gesture poses held mid-way. Each state is injected through
 * [NavigationLabPreview], and the position of the foreground is asserted before the capture so a
 * picture of the wrong state fails instead of passing. Images are written only when Roborazzi records.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class NavigationLabShotTest {
    @get:Rule val compose = createComposeRule()

    private val playlist = listOf<LabRoute>(LabRoute.Playlist(0))

    @Test fun root() = shot("lab_nav_root")

    @Test fun detail() = shot("lab_nav_detail", NavigationLabPreview(stack = playlist), foregroundLeft = 0f)

    @Test fun pushHalfway() =
        shot("lab_nav_push_half", NavigationLabPreview(stack = playlist, exit = 0.5f), foregroundLeft = 180f)

    @Test fun backFollowHeld() = shot(
        "lab_nav_back_follow",
        NavigationLabPreview(stack = playlist, exit = 0.35f, style = BackStyle.FOLLOW),
        foregroundLeft = 126f,
    )

    @Test fun backShrinkHeld() = shot(
        "lab_nav_back_shrink",
        NavigationLabPreview(
            stack = playlist, exit = LabPose.ShrinkPhase, style = BackStyle.SHRINK, backStyle = BackStyle.SHRINK,
        ),
        // Scaled to 0.9 about the centre (18dp in on each side) and drifted 0.04 of the width (14dp).
        foregroundLeft = 32f,
    )

    @Test fun pushHalfwayDark() =
        shot("lab_nav_push_half_dark", NavigationLabPreview(stack = playlist, exit = 0.5f), dark = true, foregroundLeft = 180f)

    @Test fun detailSolid() =
        shot("lab_nav_detail_solid", NavigationLabPreview(stack = playlist), glass = false, foregroundLeft = 0f)

    private fun shot(
        name: String,
        preview: NavigationLabPreview = NavigationLabPreview(),
        dark: Boolean = false,
        glass: Boolean = true,
        foregroundLeft: Float? = null,
    ) {
        compose.setContent {
            MusxiTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    NavigationLab(navTestEnv(dark = dark, glass = glass), preview)
                }
            }
        }
        compose.waitForIdle()

        if (foregroundLeft == null) {
            compose.onNodeWithText("12 个歌单").assertExists()
        } else {
            // The back button is the first thing at the left of the foreground page, 4dp in from its edge.
            val left = compose.onNodeWithContentDescription("返回").getUnclippedBoundsInRoot().left.value
            assertEquals("$name: foreground left edge", foregroundLeft + 4f, left, 14f)
        }

        val out = File("build/design/$name.png")
        compose.onRoot().captureRoboImage(out.path)
        if (!out.exists()) return
        assertTrue("$name: screenshot does not decode", BitmapFactory.decodeFile(out.path) != null)
    }
}
