package tech.musxi.player.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import tech.musxi.player.ui.player.TranslationChip
import tech.musxi.player.ui.player.TranslationChipButton
import tech.musxi.player.ui.player.TranslationUi
import tech.musxi.player.ui.player.playerPalette
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.MusxiTheme
import tech.musxi.player.ui.theme.ThemeMode

/** Real component captures, including plain backdrops for measuring the visible glyph and frame. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class TranslationButtonSheetTest {
    @Test fun onLight() = badge("translation_badge_on_light", TranslationChip.ON, ThemeMode.LIGHT)
    @Test fun offLight() = badge("translation_badge_off_light", TranslationChip.OFF, ThemeMode.LIGHT)
    @Test fun onDark() = badge("translation_badge_on_dark", TranslationChip.ON, ThemeMode.DARK)
    @Test fun offDark() = badge("translation_badge_off_dark", TranslationChip.OFF, ThemeMode.DARK)
    @Test fun onLargeFont() = badge("translation_badge_on_largefont", TranslationChip.ON, ThemeMode.LIGHT, 1.3f)
    @Test fun pageOn() = page("translation_page_on_light", TranslationChip.ON)
    @Test fun pageOff() = page("translation_page_off_light", TranslationChip.OFF, native = true)
    @Test fun pageDark() = page("translation_page_on_dark", TranslationChip.ON, ThemeMode.DARK)
    @Test fun pageLargeFont() = page("translation_page_on_largefont", TranslationChip.ON, scale = 1.3f)
    @Test fun pageFailure() = page("translation_page_failed", TranslationChip.WARNING)

    private fun badge(name: String, chip: TranslationChip, mode: ThemeMode, scale: Float = 1f) {
        captureRoboImage("build/design/$name.png") {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MusxiTheme(mode = mode) {
                    val palette = playerPalette("晴天", mode != ThemeMode.LIGHT)
                    Box(Modifier.fillMaxSize().background(Color(0xFFBFBFBF)), contentAlignment = Alignment.Center) {
                        TranslationChipButton(
                            TranslationUi(chip, hasText = true), onToggle = {}, onRefresh = {},
                            activeColor = palette.accent, onActiveColor = palette.onAccent,
                        )
                    }
                }
            }
        }
    }

    private fun page(
        name: String, chip: TranslationChip, mode: ThemeMode = ThemeMode.LIGHT,
        native: Boolean = false, scale: Float = 1f,
    ) {
        captureRoboImage("build/design/$name.png") {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                val lyrics = if (native) lyricsNativeBilingual() else lyricsBilingual(activeIndex = 4)
                FavoritesSheet(
                    Accent.MIST, mode, favoriteLiked(), lyrics = lyrics.copy(
                        translation = TranslationUi(chip, canRefresh = true, hasText = chip != TranslationChip.WARNING),
                    ),
                )
            }
        }
    }
}
