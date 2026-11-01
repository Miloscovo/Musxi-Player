package tech.musxi.player.design

import android.graphics.BitmapFactory
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.ui.design.LargeFontBatch
import tech.musxi.player.ui.design.ShellSheet
import tech.musxi.player.ui.design.miniBuffering
import tech.musxi.player.ui.design.miniHidden
import tech.musxi.player.ui.design.miniInterrupted
import tech.musxi.player.ui.design.miniPlayingSwapped
import tech.musxi.player.ui.player.MiniPlayerUi
import tech.musxi.player.ui.player.ProgressStyle
import tech.musxi.player.ui.shell.BarShape
import tech.musxi.player.ui.shell.ShellTab
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File

/**
 * Chrome sheets: the mini player's four states over the tab bar, plus a dark variant.
 *
 * The tab row is a 56dp minimum with the icon and label inside a 48dp capsule, so the check that
 * matters most is that it is actually drawn — an earlier layout let the parent shrink it until
 * nothing was visible. The dock variants put the dock over the page in a glass host, like the shell;
 * under Robolectric the glass draws its solid fallback.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class ShellSheetTest {

    @Test fun playing() = capture("shell_playing_mist_light", Accent.MIST, ThemeMode.LIGHT, miniPlayingSwapped())
    @Test fun interrupted() = capture("shell_interrupted_mist_light", Accent.MIST, ThemeMode.LIGHT, miniInterrupted())
    @Test fun buffering() = capture("shell_buffering_mist_light", Accent.MIST, ThemeMode.LIGHT, miniBuffering())
    @Test fun hidden() = capture("shell_hidden_mist_light", Accent.MIST, ThemeMode.LIGHT, miniHidden())
    @Test fun playingDark() = capture("shell_playing_mist_dark", Accent.MIST, ThemeMode.DARK, miniPlayingSwapped())
    @Test fun tabSearch() = capture("shell_tab_search_mist_light", Accent.MIST, ThemeMode.LIGHT,
        miniPlayingSwapped(), ShellTab.SEARCH)

    /** Variant comparison: same content, three ways of showing progress inside the card. */
    @Test fun variantBand() = capture("variant_c_band_72", Accent.MIST, ThemeMode.LIGHT,
        miniPlayingSwapped(), ShellTab.LIBRARY, BarShape.WIDE_PILL, ProgressStyle.BAND)
    @Test fun variantRing() = capture("variant_d_ring_64", Accent.MIST, ThemeMode.LIGHT,
        miniPlayingSwapped(), ShellTab.LIBRARY, BarShape.WIDE_PILL, ProgressStyle.RING)
    @Test fun variantFlush() = capture("variant_e_flush_64", Accent.MIST, ThemeMode.LIGHT,
        miniPlayingSwapped(), ShellTab.LIBRARY, BarShape.WIDE_PILL, ProgressStyle.FLUSH)
    @Test fun variantRingExtraRound() = capture("variant_f_ring_64_round28", Accent.MIST, ThemeMode.LIGHT,
        miniPlayingSwapped(), ShellTab.LIBRARY, BarShape.WIDE_PILL, ProgressStyle.RING, extraRound = true)
    /** The accent-solid button vs a tonal one: with a ring around it, two accent marks in one spot. */
    @Test fun variantTonal() = capture("variant_g_tonal_64", Accent.MIST, ThemeMode.LIGHT,
        miniPlayingSwapped(), ShellTab.LIBRARY, BarShape.WIDE_PILL, ProgressStyle.RING, tonalButton = true)
    /** The dock alternative: player and tabs in one container. */
    @Test fun dock() = capture("shell_dock_mist_light", Accent.MIST, ThemeMode.LIGHT,
        miniPlayingSwapped(), ShellTab.LIBRARY, BarShape.WIDE_PILL, ProgressStyle.RING, dock = true)
    @Test fun dockDark() = capture("shell_dock_mist_dark", Accent.MIST, ThemeMode.DARK,
        miniPlayingSwapped(), ShellTab.LIBRARY, BarShape.WIDE_PILL, ProgressStyle.RING, dock = true)

    @Test fun variantTonalDark() = capture("variant_g_tonal_64_dark", Accent.MIST, ThemeMode.DARK,
        miniPlayingSwapped(), ShellTab.LIBRARY, BarShape.WIDE_PILL, ProgressStyle.RING, tonalButton = true)

    /** The selected capsule on the other two tabs, and the dock with nothing playing. */
    @Test fun dockSearchTab() = capture("shell_dock_search_mist_light", Accent.MIST, ThemeMode.LIGHT,
        miniPlayingSwapped(), ShellTab.SEARCH, dock = true)
    @Test fun dockMeTabDark() = capture("shell_dock_me_mist_dark", Accent.MIST, ThemeMode.DARK,
        miniPlayingSwapped(), ShellTab.ME, dock = true)
    @Test fun dockWithoutPlayer() = capture("shell_dock_hidden_mist_light", Accent.MIST, ThemeMode.LIGHT,
        miniHidden(), ShellTab.LIBRARY, dock = true)

    /** Font scale 2.0: the tab row grows with the label instead of clipping it (spec section 12). */
    @Test fun dockLargeFont() = capture("shell_dock_largefont_mist_light", Accent.MIST, ThemeMode.LIGHT,
        miniPlayingSwapped(), ShellTab.LIBRARY, dock = true, largeFont = true)

    private fun capture(
        name: String,
        accent: Accent,
        mode: ThemeMode,
        mini: MiniPlayerUi,
        tab: ShellTab = ShellTab.LIBRARY,
        barShape: BarShape = BarShape.WIDE_PILL,
        progressStyle: ProgressStyle = ProgressStyle.RING,
        extraRound: Boolean = false,
        tonalButton: Boolean = false,
        dock: Boolean = false,
        largeFont: Boolean = false,
    ) {
        val out = File("build/design/$name.png")
        captureRoboImage(out.path) {
            if (largeFont) {
                LargeFontBatch { ShellSheet(accent, mode, mini, tab, barShape, progressStyle, extraRound, tonalButton, dock) }
            } else {
                ShellSheet(accent, mode, mini, tab, barShape, progressStyle, extraRound, tonalButton, dock)
            }
        }
        if (!out.exists()) return
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertTrue("$name failed to decode", bitmap != null)
        val background = parse(if (mode == ThemeMode.LIGHT) "#FAFAFA" else "#121212")
        val samples = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(samples, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val bgShare = samples.count { it == background } / samples.size.toFloat()
        assertTrue("$name: background only ${(bgShare * 100).toInt()}%", bgShare > 0.25f)
        // The bottom band must contain chrome: without it the bar has been squeezed out again.
        val barTop = (bitmap.height * 0.86f).toInt()
        var chrome = 0
        for (y in barTop until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (samples[y * bitmap.width + x] != background) chrome++
            }
        }
        assertTrue("$name: bottom chrome almost empty ($chrome px)", chrome > bitmap.width * 4)
    }

    private fun parse(hex: String): Int = (0xFF000000L or hex.removePrefix("#").toLong(16)).toInt()
}
