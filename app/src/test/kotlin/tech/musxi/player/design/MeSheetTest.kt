package tech.musxi.player.design

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.core.playback.PreferredAudioQuality
import tech.musxi.core.settings.LyricsFontSize
import tech.musxi.core.settings.ThemeMode as StoredThemeMode
import tech.musxi.player.ui.design.LargeFontBatch
import tech.musxi.player.ui.design.MeAccountsSheet
import tech.musxi.player.ui.design.MeSheet
import tech.musxi.player.ui.design.meSampleUi
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File

/**
 * The "我的" page on fake data: the whole page in both brightnesses, the account card with three
 * different states, the picker rows with chosen values and the page at font scale 2.0. The page is a long
 * scroll, so the window is made tall enough to hold all of it; rows have no fixed height, which the
 * 200% sheet is there to prove.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1500dp-xhdpi", application = DesignTestApplication::class)
class MeSheetTest {

    @Test fun pageLight() = capture("me_page_light", dark = false) { MeSheet(Accent.MIST, ThemeMode.LIGHT) }

    @Test fun pageDark() = capture("me_page_dark", dark = true) { MeSheet(Accent.MIST, ThemeMode.DARK) }

    @Test @Config(qualifiers = "w360dp-h340dp-xhdpi")
    fun accountCards() = capture("me_accounts_light", dark = false, minCard = 0.30f) {
        MeAccountsSheet(Accent.MIST, ThemeMode.LIGHT)
    }

    @Test @Config(qualifiers = "w360dp-h340dp-xhdpi")
    fun accountCardsDark() = capture("me_accounts_dark", dark = true, minCard = 0.30f) {
        MeAccountsSheet(Accent.MIST, ThemeMode.DARK)
    }

    /** The three states at font scale 2.0: the status moves under the second line, nothing is dropped. */
    @Test @Config(qualifiers = "w360dp-h900dp-xhdpi")
    fun accountCardsLargeFont() = capture("me_accounts_largefont_light", dark = false, minCard = 0.10f) {
        LargeFontBatch { MeAccountsSheet(Accent.MIST, ThemeMode.LIGHT) }
    }

    /** The common case: a member, a non-member and a platform not connected yet. */
    @Test @Config(qualifiers = "w360dp-h340dp-xhdpi")
    fun accountCardsCommon() = capture("me_accounts_common_light", dark = false, minCard = 0.30f) {
        MeAccountsSheet(Accent.MIST, ThemeMode.LIGHT, common = true)
    }

    /**
     * The four picker rows with values other than the defaults, in place of the old dark-mode page:
     * each row shows its value without a chevron, and the 主题色 dot takes the chosen accent. Their
     * menus are a popup window, which only a screen capture includes (MePickerTest).
     */
    @Test
    fun pickerValues() = capture("me_page_picker_values_light", dark = false) {
        val sample = meSampleUi(Accent.TEAL)
        MeSheet(
            Accent.TEAL, ThemeMode.LIGHT,
            sample.copy(
                playback = sample.playback.copy(quality = PreferredAudioQuality.LOSSLESS),
                lyrics = sample.lyrics.copy(fontSize = LyricsFontSize.LARGE),
                appearance = sample.appearance.copy(themeMode = StoredThemeMode.LIGHT),
            ),
        )
    }

    @Test @Config(qualifiers = "w360dp-h2200dp-xhdpi")
    fun pageLargeFont() = capture("me_page_largefont_light", dark = false) {
        LargeFontBatch { MeSheet(Accent.MIST, ThemeMode.LIGHT) }
    }

    /**
     * The page background and the card colour are the two surfaces every sheet must show. Whether the
     * rows, switches and marks look right is for the eye: the PNGs are the evidence.
     */
    private fun capture(name: String, dark: Boolean, minCard: Float = 0.15f, content: @Composable () -> Unit) {
        val out = File("build/design/$name.png")
        captureRoboImage(out.path) { content() }
        // Without -Proborazzi.test.record=true nothing is written; the full test run must still pass.
        if (!out.exists()) return
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertTrue("$name failed to decode", bitmap != null)
        val page = parse(if (dark) "#121212" else "#FAFAFA")
        val card = parse(if (dark) "#262626" else "#FFFFFF")
        val samples = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(samples, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val pageShare = samples.count { it == page } / samples.size.toFloat()
        val cardShare = samples.count { it == card } / samples.size.toFloat()
        assertTrue("$name: page colour only ${(pageShare * 100).toInt()}%", pageShare > 0.05f)
        assertTrue("$name: card surface only ${(cardShare * 100).toInt()}%", cardShare > minCard)
    }

    private fun parse(hex: String): Int = (0xFF000000L or hex.removePrefix("#").toLong(16)).toInt()
}
