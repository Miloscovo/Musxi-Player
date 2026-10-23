package tech.musxi.player.ui.player

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.design.LyricsSheet
import tech.musxi.player.ui.design.lyricsLongLines
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class PlayerTypographyLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun platformLineIsNotSqueezedOutByLargeTitleAndArtist() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                LyricsSheet(Accent.MIST, ThemeMode.LIGHT, lyricsLongLines())
            }
        }
        compose.onNodeWithText("晴天").assertIsDisplayed()
        compose.onNodeWithText("周杰伦 · 叶惠美").assertIsDisplayed()
        compose.onNodeWithText("网易云音乐").assertIsDisplayed().assertHeightIsAtLeast(20.dp)
    }
}
