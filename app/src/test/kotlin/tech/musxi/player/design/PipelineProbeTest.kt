package tech.musxi.player.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.ui.theme.MusxiTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi", application = DesignTestApplication::class)
class PipelineProbeTest {
    @Test fun probe() = captureRoboImage("build/design/probe.png") {
        MusxiTheme {
            Column(Modifier.background(MaterialTheme.colorScheme.background).padding(20.dp)) {
                Text("曲库 · 网易云 · 共 746 首 03:41", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = {}) { Text("播放全部") }
            }
        }
    }
}
