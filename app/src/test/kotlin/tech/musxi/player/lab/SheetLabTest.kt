package tech.musxi.player.lab

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.design.DesignTestApplication
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File
import kotlin.math.abs

/**
 * Screenshots of the sheet lab with a sheet open: auto source switch searching (MEDIUM), the
 * version list (MEDIUM), the failure state, and the 30-playlist list (FULL). The sheet position is
 * asserted before each capture, so a sheet stuck mid-slide or never shown fails instead of
 * producing a misleading picture. Images are written only when Roborazzi is recording.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class SheetLabTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searching() = shot("lab_sheet_searching", SheetKind.SOURCE_SEARCHING, LabSheetDetent.MEDIUM)

    @Test fun choose() = shot("lab_sheet_choose", SheetKind.SOURCE_CHOOSE, LabSheetDetent.MEDIUM)

    @Test fun longListFull() = shot("lab_sheet_long_full", SheetKind.LONG_LIST, LabSheetDetent.FULL)

    @Test fun closed() = shot("lab_sheet_closed", SheetKind.SOURCE_SEARCHING, LabSheetDetent.MEDIUM, open = false)

    @Test fun failed() = shot("lab_sheet_failed", SheetKind.SOURCE_FAILED, LabSheetDetent.MEDIUM)

    @Test fun chooseDark() = shot("lab_sheet_choose_dark", SheetKind.SOURCE_CHOOSE, LabSheetDetent.MEDIUM, dark = true)

    @Test fun chooseSolid() = shot("lab_sheet_choose_solid", SheetKind.SOURCE_CHOOSE, LabSheetDetent.MEDIUM, glass = false)

    private fun shot(
        name: String,
        kind: SheetKind,
        detent: LabSheetDetent,
        dark: Boolean = false,
        glass: Boolean = true,
        open: Boolean = true,
    ) {
        val env = LabEnv(
            songs = (0 until 12).map { i ->
                LabSong(
                    id = "test-$i",
                    title = SongTitles[i % SongTitles.size],
                    artist = SongArtists[i % SongArtists.size],
                    album = "示例专辑",
                    durationMs = 180_000L + i * 11_000L,
                    coverUrl = null,
                )
            },
            dark = dark,
            tuning = LabTuning.STANDARD,
            onTuning = {},
            onToggleDark = {},
            onExit = {},
            glass = glass,
        )
        compose.setContent {
            MusxiTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    SheetLab(env, initialKind = kind, initialOpen = open, initialDetent = detent)
                }
            }
        }
        compose.waitForIdle()

        if (open) {
            val expectedTop = if (detent == LabSheetDetent.FULL) 8f else 352f
            val top = compose.onNodeWithTag("lab-sheet").getUnclippedBoundsInRoot().top.value
            assertTrue("$name: sheet top $top dp, expected about $expectedTop dp", abs(top - expectedTop) < 6f)
        } else {
            compose.onNodeWithTag("lab-sheet").assertDoesNotExist()
        }

        val out = File("build/design/$name.png")
        compose.onRoot().captureRoboImage(out.path)
        if (!out.exists()) return
        assertTrue("$name: screenshot does not decode", BitmapFactory.decodeFile(out.path) != null)
    }

    private companion object {
        val SongTitles = listOf("夜航", "回声", "薄雾", "海盐", "晴空万里", "慢速列车", "静默时区", "月光白")
        val SongArtists = listOf("林间", "Aster", "沈宁", "Noa", "周屿", "Kite", "宋禾", "Luma")
    }
}
