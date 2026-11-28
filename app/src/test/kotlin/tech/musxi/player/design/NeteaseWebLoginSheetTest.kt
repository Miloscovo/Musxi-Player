package tech.musxi.player.design

import android.graphics.BitmapFactory
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
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
import tech.musxi.accounts.NeteaseWebStage
import tech.musxi.accounts.NeteaseWebUiState
import tech.musxi.player.ui.account.NeteaseWebCardTag
import tech.musxi.player.ui.account.NeteaseWebFooterTag
import tech.musxi.player.ui.account.NeteaseWebSubmitTag
import tech.musxi.player.ui.components.SheetTag
import tech.musxi.player.ui.design.NeteaseWebLoginDesignSheet
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class NeteaseWebLoginSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lightReady() = capture("netease_web_login_light.png", Accent.MIST, ThemeMode.LIGHT)

    @Test fun darkReady() = capture("netease_web_login_dark.png", Accent.TEAL, ThemeMode.DARK)

    @Test fun navigationBlockedLight() = capture(
        name = "netease_web_login_blocked_light.png",
        accent = Accent.MIST,
        mode = ThemeMode.LIGHT,
        state = readyState(NeteaseWebStage.NAVIGATION_BLOCKED),
    )

    @Test fun cookiesMissingDark() = capture(
        name = "netease_web_login_error_dark.png",
        accent = Accent.MIST,
        mode = ThemeMode.DARK,
        state = readyState(NeteaseWebStage.COOKIES_MISSING),
    )

    @Test fun solidDarkReady() = capture(
        name = "netease_web_login_solid_dark.png",
        accent = Accent.TEAL,
        mode = ThemeMode.DARK,
        transparency = false,
    )

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun narrowLargeFontSolidLight() = capture(
        name = "netease_web_login_narrow_large_solid_light.png",
        accent = Accent.AMBER,
        mode = ThemeMode.LIGHT,
        state = readyState(NeteaseWebStage.NAVIGATION_BLOCKED),
        transparency = false,
        fontScale = 2f,
    )

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun landscapeSolidDark() = capture(
        name = "netease_web_login_landscape_solid_dark.png",
        accent = Accent.DUSK,
        mode = ThemeMode.DARK,
        transparency = false,
    )

    private fun capture(
        name: String,
        accent: Accent,
        mode: ThemeMode,
        state: NeteaseWebUiState = readyState(),
        transparency: Boolean = true,
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                NeteaseWebLoginDesignSheet(
                    accent = accent,
                    mode = mode,
                    transparency = transparency,
                    state = state,
                )
            }
        }
        compose.waitForIdle()

        val sheet = compose.onNodeWithTag(SheetTag).getUnclippedBoundsInRoot()
        val card = compose.onNodeWithTag(NeteaseWebCardTag).getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag(NeteaseWebFooterTag).getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertEquals("$name: web card left inset", 12f, card.left.value - sheet.left.value, 0.6f)
        assertEquals("$name: web card right inset", 12f, sheet.right.value - card.right.value, 0.6f)
        assertTrue("$name: web area was squeezed out", (card.bottom - card.top).value >= 120f)
        assertTrue("$name: footer leaves the window", footer.bottom <= root.bottom + 1.dp)
        compose.onNodeWithTag(NeteaseWebSubmitTag).assertIsDisplayed()
        val density = compose.density

        val out = File("build/design/$name")
        compose.onRoot().captureRoboImage(out.path)
        if (!out.exists()) return
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertTrue("$name failed to decode", bitmap != null)
        assertTrue("$name has invalid dimensions", bitmap.width > 0 && bitmap.height > 0)
        assertEquals("$name: capture width", with(density) { (root.right - root.left).roundToPx() }, bitmap.width)
        assertTrue(
            "$name: capture does not match the scene",
            abs(bitmap.height - with(density) { (root.bottom - root.top).toPx() }) <= density.density + 1f,
        )
    }
}

private fun readyState(stage: NeteaseWebStage = NeteaseWebStage.READY) = NeteaseWebUiState(
    stage = stage,
    host = "y.music.163.com",
    progress = 100,
    canSubmit = true,
    canReload = true,
)
