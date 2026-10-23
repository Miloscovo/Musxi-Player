package tech.musxi.player.design

import android.graphics.BitmapFactory
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.ui.design.SearchSheet
import tech.musxi.player.ui.design.allLoadedState
import tech.musxi.player.ui.design.emptyState
import tech.musxi.player.ui.design.errorState
import tech.musxi.player.ui.design.idleState
import tech.musxi.player.ui.design.loadingMoreState
import tech.musxi.player.ui.design.loadingState
import tech.musxi.player.ui.design.longTextState
import tech.musxi.player.ui.design.resultsState
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File

/**
 * Search page sheets. One image per result state: idle, results, empty, loading, failure — plus a
 * dark variant to check that muted text and the search field stay readable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class SearchSheetTest {

    @Test fun idle() = capture("search_idle_mist_light", Accent.MIST, ThemeMode.LIGHT, idleState())
    @Test fun results() = capture("search_results_mist_light", Accent.MIST, ThemeMode.LIGHT, resultsState())
    @Test fun empty() = capture("search_empty_mist_light", Accent.MIST, ThemeMode.LIGHT, emptyState())
    @Test fun loading() = capture("search_loading_mist_light", Accent.MIST, ThemeMode.LIGHT, loadingState())
    @Test fun failure() = capture("search_error_mist_light", Accent.MIST, ThemeMode.LIGHT, errorState())
    @Test fun loadingMore() = capture("search_loading_more_mist_light", Accent.MIST, ThemeMode.LIGHT, loadingMoreState())
    @Test fun allLoaded() = capture("search_all_loaded_mist_light", Accent.MIST, ThemeMode.LIGHT, allLoadedState())
    @Test fun longText() = capture("search_long_text_mist_light", Accent.MIST, ThemeMode.LIGHT, longTextState())
    @Test fun resultsDark() = capture("search_results_mist_dark", Accent.MIST, ThemeMode.DARK, resultsState())

    private fun capture(
        name: String,
        accent: Accent,
        mode: ThemeMode,
        state: tech.musxi.player.ui.search.SearchUiState,
    ) {
        val out = File("build/design/$name.png")
        captureRoboImage(out.path) { SearchSheet(accent, mode, state) }
        if (!out.exists()) return
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertTrue("$name failed to decode", bitmap != null)
        val background = parse(if (mode == ThemeMode.LIGHT) "#F7F7F5" else "#121212")
        val samples = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(samples, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val bgShare = samples.count { it == background } / samples.size.toFloat()
        assertTrue("$name: background only ${(bgShare * 100).toInt()}%", bgShare > 0.30f)
        // Something must actually be drawn on top of the background.
        val ink = samples.count { it != background }
        assertTrue("$name: nothing rendered", ink > bitmap.width)
    }

    private fun parse(hex: String): Int = (0xFF000000L or hex.removePrefix("#").toLong(16)).toInt()
}
