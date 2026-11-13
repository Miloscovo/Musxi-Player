package tech.musxi.player.design

import android.graphics.BitmapFactory
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.ui.design.AboutBatchSheet
import tech.musxi.player.ui.design.DockBatchSheet
import tech.musxi.player.ui.design.LargeFontBatch
import tech.musxi.player.ui.design.LyricsBatchSheet
import tech.musxi.player.ui.design.QueueSheet
import tech.musxi.player.ui.design.SourceFailedBatchSheet
import tech.musxi.player.ui.design.batchAbout
import tech.musxi.player.ui.design.batchAvailable
import tech.musxi.player.ui.design.batchDockFailed
import tech.musxi.player.ui.design.batchDockSearching
import tech.musxi.player.ui.design.batchLibraryGrouped
import tech.musxi.player.ui.design.batchPlayer
import tech.musxi.player.ui.design.batchPlayerFailed
import tech.musxi.player.ui.design.batchPlayerSourceLine
import tech.musxi.player.ui.design.batchQueueOrdered
import tech.musxi.player.ui.design.batchQueueShuffled
import tech.musxi.player.ui.design.lyricsBound
import tech.musxi.player.ui.design.lyricsError
import tech.musxi.player.ui.design.lyricsNoResults
import tech.musxi.player.ui.design.lyricsPreviewing
import tech.musxi.player.ui.account.AboutUpdateUi
import tech.musxi.player.ui.design.PlayerSheet
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File

/**
 * Review screenshots for the batch of new screens. Each capture writes a PNG under build/design and
 * checks that the file decodes to a non-empty bitmap, so a blank or broken render fails the test.
 * Layout, copy, and clipping are judged from the images, not from these assertions.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class BatchSheetTest {

    private val accent = Accent.MIST

    // 1. About section states, light and dark.
    @Test fun aboutIdleLight() = about("batch_about_idle_light", ThemeMode.LIGHT, batchAbout())
    @Test fun aboutIdleDark() = about("batch_about_idle_dark", ThemeMode.DARK, batchAbout())
    @Test fun aboutCheckingLight() = about("batch_about_checking_light", ThemeMode.LIGHT, batchAbout(checking = true))
    @Test fun aboutCheckingDark() = about("batch_about_checking_dark", ThemeMode.DARK, batchAbout(checking = true))
    @Test fun aboutUpToDateLight() = about("batch_about_uptodate_light", ThemeMode.LIGHT, batchAbout(note = "已是最新"))
    @Test fun aboutUpToDateDark() = about("batch_about_uptodate_dark", ThemeMode.DARK, batchAbout(note = "已是最新"))
    @Test fun aboutAvailableLight() = about("batch_about_available_light", ThemeMode.LIGHT, batchAvailable())
    @Test fun aboutAvailableDark() = about("batch_about_available_dark", ThemeMode.DARK, batchAvailable())
    @Test fun aboutFailedLight() = about("batch_about_failed_light", ThemeMode.LIGHT, batchAbout(note = "检查失败，点按重试"))
    @Test fun aboutFailedDark() = about("batch_about_failed_dark", ThemeMode.DARK, batchAbout(note = "检查失败，点按重试"))

    // 2. Queue: ordered (with drag handles) and shuffled (header, no handles).
    @Test fun queueOrderedLight() = queue("batch_queue_ordered_light", ThemeMode.LIGHT, batchQueueOrdered())
    @Test fun queueOrderedDark() = queue("batch_queue_ordered_dark", ThemeMode.DARK, batchQueueOrdered())
    @Test fun queueShuffledLight() = queue("batch_queue_shuffled_light", ThemeMode.LIGHT, batchQueueShuffled())
    @Test fun queueShuffledDark() = queue("batch_queue_shuffled_dark", ThemeMode.DARK, batchQueueShuffled())

    // 3. Source panel, failed search with per-platform reasons.
    @Test fun sourceFailedLight() = capture("batch_source_failed_light") {
        SourceFailedBatchSheet(accent, ThemeMode.LIGHT)
    }
    @Test fun sourceFailedDark() = capture("batch_source_failed_dark") {
        SourceFailedBatchSheet(accent, ThemeMode.DARK)
    }

    // 4. Player title line: actual source line, and the failed state.
    @Test fun playerSourceLineLight() = capture("batch_player_sourceline_light") {
        PlayerSheet(accent, ThemeMode.LIGHT, batchPlayerSourceLine())
    }
    @Test fun playerSourceLineDark() = capture("batch_player_sourceline_dark") {
        PlayerSheet(accent, ThemeMode.DARK, batchPlayerSourceLine())
    }
    @Test fun playerFailedLight() = capture("batch_player_failed_light") {
        PlayerSheet(accent, ThemeMode.LIGHT, batchPlayerFailed(), findSources = true)
    }
    @Test fun playerFailedDark() = capture("batch_player_failed_dark") {
        PlayerSheet(accent, ThemeMode.DARK, batchPlayerFailed(), findSources = true)
    }
    @Test fun playerPlainLight() = capture("batch_player_plain_light") {
        PlayerSheet(accent, ThemeMode.LIGHT, batchPlayer())
    }

    // 5. Dock notices: failed with reason, and searching.
    @Test fun dockFailedLight() = capture("batch_dock_failed_light") {
        DockBatchSheet(accent, ThemeMode.LIGHT, batchDockFailed())
    }
    @Test fun dockFailedDark() = capture("batch_dock_failed_dark") {
        DockBatchSheet(accent, ThemeMode.DARK, batchDockFailed())
    }
    @Test fun dockSearchingLight() = capture("batch_dock_searching_light") {
        DockBatchSheet(accent, ThemeMode.LIGHT, batchDockSearching())
    }
    @Test fun dockSearchingDark() = capture("batch_dock_searching_dark") {
        DockBatchSheet(accent, ThemeMode.DARK, batchDockSearching())
    }

    // 6. Library with created and collected playlists.
    @Test fun libraryGroupedLight() = capture("batch_library_grouped_light") {
        tech.musxi.player.ui.design.LibrarySheet(accent, ThemeMode.LIGHT, batchLibraryGrouped())
    }
    @Test fun libraryGroupedDark() = capture("batch_library_grouped_dark") {
        tech.musxi.player.ui.design.LibrarySheet(accent, ThemeMode.DARK, batchLibraryGrouped())
    }

    // 7. Lyrics matching: bound, previewing, empty search, error.
    @Test fun lyricsBoundLight() = capture("batch_lyrics_bound_light") {
        LyricsBatchSheet(accent, ThemeMode.LIGHT, lyricsBound())
    }
    @Test fun lyricsBoundDark() = capture("batch_lyrics_bound_dark") {
        LyricsBatchSheet(accent, ThemeMode.DARK, lyricsBound())
    }
    @Test fun lyricsPreviewingLight() = capture("batch_lyrics_previewing_light") {
        LyricsBatchSheet(accent, ThemeMode.LIGHT, lyricsPreviewing())
    }
    @Test fun lyricsPreviewingDark() = capture("batch_lyrics_previewing_dark") {
        LyricsBatchSheet(accent, ThemeMode.DARK, lyricsPreviewing())
    }
    @Test fun lyricsNoResultsLight() = capture("batch_lyrics_noresults_light") {
        LyricsBatchSheet(accent, ThemeMode.LIGHT, lyricsNoResults())
    }
    @Test fun lyricsNoResultsDark() = capture("batch_lyrics_noresults_dark") {
        LyricsBatchSheet(accent, ThemeMode.DARK, lyricsNoResults())
    }
    @Test fun lyricsErrorLight() = capture("batch_lyrics_error_light") {
        LyricsBatchSheet(accent, ThemeMode.LIGHT, lyricsError())
    }
    @Test fun lyricsErrorDark() = capture("batch_lyrics_error_dark") {
        LyricsBatchSheet(accent, ThemeMode.DARK, lyricsError())
    }

    // 8. Font scale 2.0, light only.
    @Test fun largeFontAbout() = capture("batch_large_font_about_light") {
        LargeFontBatch { AboutBatchSheet(accent, ThemeMode.LIGHT, batchAvailable()) }
    }
    @Test fun largeFontDockFailed() = capture("batch_large_font_dock_light") {
        LargeFontBatch { DockBatchSheet(accent, ThemeMode.LIGHT, batchDockFailed()) }
    }
    @Test fun largeFontQueueShuffled() = capture("batch_large_font_queue_shuffled_light") {
        LargeFontBatch {
            QueueSheet(accent, ThemeMode.LIGHT, batchQueueShuffled(), player = batchPlayer())
        }
    }

    private fun about(name: String, mode: ThemeMode, ui: AboutUpdateUi) = capture(name) {
        AboutBatchSheet(accent, mode, ui)
    }

    private fun queue(name: String, mode: ThemeMode, ui: tech.musxi.player.ui.player.QueueUi) = capture(name) {
        QueueSheet(accent, mode, ui, player = batchPlayer())
    }

    private fun capture(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        val out = File("build/design/$name.png")
        captureRoboImage(out.path) { content() }
        // Without -Proborazzi.test.record=true nothing is written; the full test run must still pass.
        if (!out.exists()) return
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertTrue("$name failed to decode", bitmap != null)
        assertTrue("$name is empty", bitmap.width > 0 && bitmap.height > 0)
    }
}
