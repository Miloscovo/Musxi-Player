package tech.musxi.player.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode
import tech.musxi.player.ui.theme.tabular

/** Same-role samples separate script differences from the original/translation hierarchy. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class TypographySheetTest {
    @Test fun light() = capture("typography_light", ThemeMode.LIGHT)
    @Test fun dark() = capture("typography_dark", ThemeMode.DARK)
    @Test fun largeFont() = capture("typography_largefont", ThemeMode.LIGHT, fontScale = 1.3f)

    private fun capture(name: String, mode: ThemeMode, fontScale: Float = 1f) {
        var measured = 0
        val clipped = mutableSetOf<String>()
        captureRoboImage("build/design/$name.png") {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MusxiTheme(mode = mode) {
                    Surface {
                        Column(
                            Modifier.fillMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Musxi · 中英文对照", style = MaterialTheme.typography.titleLarge)
                            HorizontalDivider()
                            val roles = listOf(
                                "歌名" to MaterialTheme.typography.headlineSmall,
                                "正文" to MaterialTheme.typography.bodyLarge,
                                "次级正文" to MaterialTheme.typography.bodyMedium,
                                "标签" to MaterialTheme.typography.labelMedium,
                            )
                            roles.forEach { (role, style) ->
                                Column {
                                    Text(
                                        role,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    listOf("晴天与夜色", "Sunny Day", "晴天 Sunny Day").forEach { sample ->
                                        Text(
                                            sample,
                                            style = style,
                                            onTextLayout = { result ->
                                                measured++
                                                if (result.hasVisualOverflow) clipped += "$role: $sample"
                                            },
                                        )
                                    }
                                }
                            }
                            Text(
                                "01:11   08:58   2026 / 10 / 23",
                                style = MaterialTheme.typography.labelMedium.tabular,
                            )
                        }
                    }
                }
            }
        }
        // Roborazzi can skip composition when recording is disabled, like the other design tests.
        if (measured == 0) return
        assertTrue("Not all sample text was laid out", measured >= 12)
        assertTrue("Clipped type samples: $clipped", clipped.isEmpty())
    }
}
