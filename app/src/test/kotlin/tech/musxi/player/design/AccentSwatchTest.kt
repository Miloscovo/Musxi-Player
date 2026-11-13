package tech.musxi.player.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/**
 * Every accent side by side in the places the app spends it: a selected mark, a link, played
 * progress and a soft selected container. One image per brightness, for choosing the palette.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class AccentSwatchTest {
    @get:Rule val compose = createComposeRule()

    @Composable
    private fun SwatchRow(accent: Accent) {
        val cs = MaterialTheme.colorScheme
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(cs.primary))
            Spacer(Modifier.width(10.dp))
            Text(accent.label, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, modifier = Modifier.width(64.dp))
            Text("查看全部", style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.width(72.dp))
            Column(Modifier.weight(1f)) {
                LinearProgressIndicator(
                    progress = { 0.62f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = cs.primary,
                    trackColor = cs.onSurface.copy(alpha = 0.10f),
                    drawStopIndicator = {},
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "已选",
                style = MaterialTheme.typography.labelMedium,
                color = cs.onPrimaryContainer,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(cs.primaryContainer)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }

    private fun show(mode: ThemeMode) {
        compose.setContent {
            MusxiTheme(mode = mode) {
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp),
                    verticalArrangement = Arrangement.Top,
                ) {
                    Accent.entries.forEach { accent -> MusxiTheme(accent = accent, mode = mode) { SwatchRow(accent) } }
                }
            }
        }
        compose.waitForIdle()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) =
        captureScreenRoboImage("build/design/$name.png", RoborazziOptions(taskType = RoborazziTaskType.Record))

    @Test fun light() {
        show(ThemeMode.LIGHT)
        capture("accent_swatch_light")
    }

    @Test fun dark() {
        show(ThemeMode.DARK)
        capture("accent_swatch_dark")
    }
}
