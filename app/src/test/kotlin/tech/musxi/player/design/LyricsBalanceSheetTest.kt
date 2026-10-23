package tech.musxi.player.design

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.ui.design.FavoritesSheet
import tech.musxi.player.ui.design.favoriteLiked
import tech.musxi.player.ui.design.lyricsBilingual
import tech.musxi.player.ui.design.lyricsNativeBilingual
import tech.musxi.player.ui.player.LyricsUi
import tech.musxi.player.ui.player.TranslationChip
import tech.musxi.player.ui.player.TranslationUi
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode

/** Full player captures of document edges and short documents, using the production lyrics block. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class LyricsBalanceSheetTest {
    @Test fun chineseBeginning() = page("chinese_beginning", chinese(0))
    @Test fun chineseEnd() = page("chinese_end", chinese(7))
    @Test fun chineseLargeFont() = page("chinese_largefont", chinese(4), 1.3f)
    @Test fun bilingualBeginning() = page("bilingual_beginning", lyricsBilingual(activeIndex = 0))
    @Test fun bilingualEnd() = page("bilingual_end", lyricsBilingual(activeIndex = 13))
    @Test fun threeLines() = page("three_lines", chinese(1).let { it.copy(lines = it.lines.take(3)) })
    @Test fun oneBilingualGroup() = page("one_bilingual_group", lyricsBilingual(0).let { it.copy(lines = it.lines.take(1)) })

    private fun chinese(index: Int) = lyricsNativeBilingual(index).copy(
        translation = TranslationUi(TranslationChip.OFF, hasText = true),
    )

    private fun page(name: String, ui: LyricsUi, scale: Float = 1f) {
        captureRoboImage("build/design/lyrics_balance_$name.png") {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                FavoritesSheet(Accent.MIST, ThemeMode.LIGHT, favoriteLiked(), lyrics = ui)
            }
        }
    }
}
