package tech.musxi.player.design

import android.graphics.BitmapFactory
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.player.ui.design.LyricsSheet
import tech.musxi.player.ui.design.lyricsBilingual
import tech.musxi.player.ui.design.lyricsNativeBilingual
import tech.musxi.player.ui.design.lyricsBrowsing
import tech.musxi.player.ui.design.lyricsEmpty
import tech.musxi.player.ui.design.lyricsError
import tech.musxi.player.ui.design.lyricsLoading
import tech.musxi.player.ui.design.lyricsLongLines
import tech.musxi.player.ui.design.lyricsPlain
import tech.musxi.player.ui.design.lyricsTimed
import tech.musxi.player.ui.design.lyricsTranslationNone
import tech.musxi.player.ui.design.lyricsTranslationWarning
import tech.musxi.player.ui.player.LyricsErrorKind
import tech.musxi.player.ui.player.LyricsUi
import tech.musxi.player.ui.player.TranslationChip
import tech.musxi.player.ui.player.TranslationUi
import tech.musxi.player.ui.theme.Accent
import tech.musxi.player.ui.theme.ThemeMode
import java.io.File

/**
 * Lyrics sheets: the real block inside the real player page, in the states the owner asked for —
 * single language, bilingual, long lines, browsing, a picked line, and the two translation
 * outcomes that are easy to conflate.
 *
 * The block now takes the cover's slot *and the air above it*, so these images are also the check
 * on how much context bilingual reading actually gets.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = DesignTestApplication::class)
class LyricsSheetTest {

    /** Single language, no translation route: no chip. */
    @Test fun timed() = capture("lyrics_timed_mist_light", ThemeMode.LIGHT, lyricsTimed())

    @Test fun timedDark() = capture("lyrics_timed_mist_dark", ThemeMode.DARK, lyricsTimed())

    /** The case that drove the layout: original plus translation, two rows deliberately missing. */
    @Test fun bilingual() = capture("lyrics_bilingual_mist_light", ThemeMode.LIGHT, lyricsBilingual())

    /** Chinese originals and Latin translations use the same emphasis rules as the reverse pair. */
    @Test fun nativeBilingual() = capture("lyrics_native_bilingual_mist_light", ThemeMode.LIGHT, lyricsNativeBilingual())

    @Test fun bilingualDark() = capture("lyrics_bilingual_mist_dark", ThemeMode.DARK, lyricsBilingual())

    /** Long original and long translation: this is where the block height is paid for. */
    @Test fun longLines() = capture("lyrics_longlines_mist_light", ThemeMode.LIGHT, lyricsLongLines())

    @Test fun longLinesLargeFont() = capture(
        "lyrics_longlines_largefont_mist_light", ThemeMode.LIGHT, lyricsLongLines(), fontScale = 1.3f,
    )

    /** Hidden translation metadata must not change a Chinese original's weight or wrapping. */
    @Test fun hiddenTranslationDataDoesNotChangeOriginals() {
        val hidden = lyricsNativeBilingual().copy(
            translation = TranslationUi(TranslationChip.OFF, canRefresh = true),
        )
        val withoutData = hidden.copy(lines = hidden.lines.map { it.copy(translation = null) })
        val withName = "lyrics_native_hidden_withdata_mist_light"
        val withoutName = "lyrics_native_hidden_withoutdata_mist_light"
        capture(withName, ThemeMode.LIGHT, hidden)
        capture(withoutName, ThemeMode.LIGHT, withoutData)
        val withFile = File("build/design/$withName.png")
        val withoutFile = File("build/design/$withoutName.png")
        if (!withFile.exists() || !withoutFile.exists()) return
        assertTrue(
            "Hidden translation data changed original text rendering",
            BitmapFactory.decodeFile(withFile.path).sameAs(BitmapFactory.decodeFile(withoutFile.path)),
        )
    }

    /** Scrolled away from the current line: the "back to current" pill is on screen. */
    @Test fun browsing() = capture("lyrics_browsing_mist_light", ThemeMode.LIGHT, lyricsBrowsing())

    /** Translations on, latest lookup failed: text stays, the chip becomes the retry. */
    @Test fun translationWarning() = capture(
        "lyrics_translation_warning_mist_light", ThemeMode.LIGHT, lyricsTranslationWarning(),
    )

    /** The platform really has no translation: the chip is visible but inert. */
    @Test fun translationNone() = capture(
        "lyrics_translation_none_mist_light", ThemeMode.LIGHT, lyricsTranslationNone(),
    )

    /**
     * The failure that used to be mislabelled: a protocol or over-limit read is our failure, not
     * "this platform does not provide lyrics", and it keeps its retry.
     */
    @Test fun errorFormat() = capture(
        "lyrics_error_format_mist_light", ThemeMode.LIGHT, lyricsError(LyricsErrorKind.FORMAT),
    )

    @Test fun errorUnavailable() = capture(
        "lyrics_error_unavailable_mist_light",
        ThemeMode.LIGHT,
        lyricsError(LyricsErrorKind.UNAVAILABLE),
    )

    @Test fun plain() = capture("lyrics_plain_mist_light", ThemeMode.LIGHT, lyricsPlain())

    @Test fun empty() = capture("lyrics_empty_mist_light", ThemeMode.LIGHT, lyricsEmpty())

    @Test fun loading() = capture("lyrics_loading_mist_light", ThemeMode.LIGHT, lyricsLoading())

    private fun capture(name: String, mode: ThemeMode, ui: LyricsUi, fontScale: Float = 1f) {
        val out = File("build/design/$name.png")
        captureRoboImage(out.path) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                LyricsSheet(Accent.MIST, mode, ui)
            }
        }
        if (!out.exists()) return
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertTrue("$name failed to decode", bitmap != null)
        val w = bitmap.width
        val h = bitmap.height
        val samples = IntArray(w * h)
        bitmap.getPixels(samples, 0, w, 0, 0, w, h)
        var distinct = 0
        var previous = samples[0]
        for (i in samples.indices) {
            if (samples[i] != previous) distinct++
            previous = samples[i]
        }
        assertTrue("$name looks blank ($distinct transitions)", distinct > w)
    }
}
