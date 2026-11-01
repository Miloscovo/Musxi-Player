package tech.musxi.player.ui.nav

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
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

@Composable
private fun ShotPage(key: String) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().testTag("shot-$key")) {
        Text(key, style = MaterialTheme.typography.headlineMedium, color = cs.onSurface, modifier = Modifier.padding(20.dp))
        repeat(8) { i ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(if (key == "library") cs.primary else cs.tertiary, RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("$key song ${i + 1}", color = cs.onSurface)
                    Text("artist", color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * A push held half way: the page below shifted a share of its 30% and dimmed, the pushed page
 * half across the screen with its edge shadow. The state is injected, and both positions are
 * asserted before the capture, so a picture of the wrong state fails. Images are written only when
 * Roborazzi records.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class NavStackHostShotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pushHalfway() = shot("nav_stack_push_half", ThemeMode.LIGHT)

    @Test fun pushHalfwayDark() = shot("nav_stack_push_half_dark", ThemeMode.DARK)

    private fun shot(name: String, mode: ThemeMode) {
        compose.setContent {
            MusxiTheme(mode = mode) {
                val scope = rememberCoroutineScope()
                val holder = rememberSaveableStateHolder()
                val nav = remember {
                    NavStackState(scope, holder, PageStack.of("library").push("playlist"), initialExit = 0.5f)
                }
                NavStackHost(nav) { key -> ShotPage(key) }
            }
        }
        compose.waitForIdle()

        // Half way through a push: the pushed page is half the 360dp width in, the page below is
        // half of its 30% shift (54dp) to the left.
        val pushed = compose.onNodeWithTag("shot-playlist").getUnclippedBoundsInRoot().left.value
        assertEquals("$name: pushed page left edge", 180f, pushed, 1f)
        val below = compose.onNodeWithTag("shot-library", useUnmergedTree = true).getUnclippedBoundsInRoot().left.value
        assertEquals("$name: page below left edge", -54f, below, 1f)

        val out = File("build/design/$name.png")
        compose.onRoot().captureRoboImage(out.path)
        if (!out.exists()) return
        assertTrue("$name: screenshot does not decode", BitmapFactory.decodeFile(out.path) != null)
    }
}
