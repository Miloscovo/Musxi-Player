package tech.musxi.player.design

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.ui.design.LargeFontBatch
import tech.musxi.player.ui.design.ScrollChromePage
import tech.musxi.player.ui.design.ScrollChromeSheet
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File

/**
 * Scroll linkage (spec section 10) on the four pages it applies to: at rest, and after a drag that
 * has folded the dock and put the small title on the top bar. The folded dock shows the tab that is
 * open; its tab icon brings the tabs back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class ScrollChromeSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun libraryLight() = pair("scroll_library_light", ThemeMode.LIGHT, ScrollChromePage.LIBRARY)
    @Test fun libraryDark() = pair("scroll_library_dark", ThemeMode.DARK, ScrollChromePage.LIBRARY)
    @Test fun searchLight() = pair("scroll_search_light", ThemeMode.LIGHT, ScrollChromePage.SEARCH)
    @Test fun meDark() = pair("scroll_me_dark", ThemeMode.DARK, ScrollChromePage.ME)
    @Test fun playlistLight() = pair("scroll_playlist_light", ThemeMode.LIGHT, ScrollChromePage.PLAYLIST)
    @Test fun playlistDark() = pair("scroll_playlist_dark", ThemeMode.DARK, ScrollChromePage.PLAYLIST)
    @Test fun libraryLargeFont() = pair("scroll_library_largefont", ThemeMode.LIGHT, ScrollChromePage.LIBRARY, largeFont = true)

    @Test fun theFoldedTabBringsTheTabsBack() {
        compose.setContent { ScrollChromeSheet(Accent.MIST, ThemeMode.LIGHT, ScrollChromePage.LIBRARY) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("曲库，展开标签栏").assertDoesNotExist()
        drag()
        compose.onNodeWithContentDescription("曲库，展开标签栏").assertExists()
        compose.onNodeWithContentDescription("曲库，展开标签栏").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("曲库，展开标签栏").assertDoesNotExist()
    }

    private fun drag() {
        compose.onRoot().performTouchInput {
            swipeUp(startY = centerY + 200f, endY = centerY - 300f, durationMillis = 400)
        }
        compose.waitForIdle()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun pair(name: String, mode: ThemeMode, page: ScrollChromePage, largeFont: Boolean = false) {
        compose.setContent {
            if (largeFont) LargeFontBatch { ScrollChromeSheet(Accent.MIST, mode, page) }
            else ScrollChromeSheet(Accent.MIST, mode, page)
        }
        compose.waitForIdle()
        val record = RoborazziOptions(taskType = RoborazziTaskType.Record)
        compose.onRoot().captureRoboImage(File("build/design/${name}_rest.png").path, record)
        drag()
        compose.onRoot().captureRoboImage(File("build/design/${name}_scrolled.png").path, record)
    }
}
