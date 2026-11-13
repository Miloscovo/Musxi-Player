package tech.musxi.player.ui.player

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import tech.musxi.core.settings.LyricsFontSize

/**
 * Text sizes of the lyrics page for each "字号" setting. The setting is for the lyrics only; the
 * rest of the app follows the system font size.
 *
 * MEDIUM is the size the page was designed at (16sp on a 24sp line: about sixteen Chinese
 * characters across the block). The current line is drawn about 1.12x larger on top of these, so
 * LARGE stays at 18sp to keep a normal line on one row at the largest step.
 */
data class LyricsTextSizes(
    val line: TextUnit,
    val lineHeight: TextUnit,
    val translation: TextUnit,
    val translationLineHeight: TextUnit,
)

/** Every size times [factor]; 1 returns this unchanged. */
fun LyricsTextSizes.scaledBy(factor: Float): LyricsTextSizes =
    if (factor == 1f) this else LyricsTextSizes(line * factor, lineHeight * factor, translation * factor, translationLineHeight * factor)

fun lyricsTextSizes(size: LyricsFontSize): LyricsTextSizes = when (size) {
    LyricsFontSize.SMALL -> LyricsTextSizes(14.sp, 21.sp, 12.sp, 18.sp)
    LyricsFontSize.MEDIUM -> LyricsTextSizes(16.sp, 24.sp, 13.sp, 20.sp)
    LyricsFontSize.LARGE -> LyricsTextSizes(18.sp, 27.sp, 14.sp, 21.sp)
}
