package tech.musxi.player.lab

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.musxi.player.ui.components.coverColors
import tech.musxi.player.ui.player.averageCoverColor

/**
 * Colours the player derives from one cover. [top] and [bottom] are the gradient stops and are
 * dark enough for white text; [luma] is the brightness of the raw cover average, used to decide how
 * strong the scrim over a blurred cover must be.
 */
@Immutable
internal data class CoverPalette(val top: Color, val bottom: Color, val luma: Float)

private const val WhiteTextContrast = 4.5f

/** WCAG contrast of white text on [background]. */
internal fun contrastWithWhite(background: Color): Float = 1.05f / (background.luminance() + 0.05f)

/**
 * Darkens [argb] until white text reads on it (contrast 4.5:1). Colours that are already dark
 * enough are returned unchanged, so a deep cover keeps its own colour.
 */
internal fun legibleCoverColor(argb: Int): Color {
    val base = Color(argb)
    for (step in 0..25) {
        val candidate = lerp(base, Color.Black, step * 0.04f)
        if (contrastWithWhite(candidate) >= WhiteTextContrast) return candidate
    }
    return Color.Black
}

internal fun paletteFromAverage(argb: Int): CoverPalette {
    val top = legibleCoverColor(argb)
    return CoverPalette(top = top, bottom = lerp(top, Color.Black, 0.5f), luma = Color(argb).luminance())
}

/**
 * Palette of a synthetic cover. The placeholder in Cover.kt is a gradient between two seeded
 * colours and has no bitmap to sample, so the middle of those two colours stands in for the average.
 */
internal fun paletteFromSeed(seed: String): CoverPalette {
    val (a, b) = coverColors(seed)
    return paletteFromAverage(lerp(a, b, 0.5f).toArgb())
}

private val paletteCache = HashMap<String, CoverPalette>()

/**
 * Reads the cover at 32x32 through Coil (software bitmap, so pixels are readable) and averages it
 * with the shared PC-compatible routine. Returns null if the image could not be loaded or had no
 * usable pixels; the caller then keeps the seed colours.
 */
private suspend fun loadPalette(context: Context, url: String): CoverPalette? = withContext(Dispatchers.IO) {
    val request = ImageRequest.Builder(context).data(url).size(32, 32).allowHardware(false).build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@withContext null
    val source = result.image.toBitmap()
    val small = if (source.width == 32 && source.height == 32) source else Bitmap.createScaledBitmap(source, 32, 32, true)
    val pixels = IntArray(32 * 32)
    small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
    averageCoverColor(pixels)?.let(::paletteFromAverage)
}

/** The palette for [song]: seed colours at once, replaced by the real cover colours when they load. */
@Composable
internal fun rememberCoverPalette(song: LabSong): CoverPalette {
    val context = LocalContext.current
    val fallback = remember(song.seed) { paletteFromSeed(song.seed) }
    val url = song.coverUrl
    val loaded by produceState<CoverPalette?>(initialValue = url?.let(paletteCache::get), url) {
        if (url.isNullOrBlank()) {
            value = null
        } else {
            value = paletteCache[url] ?: loadPalette(context, url)?.also { paletteCache[url] = it }
        }
    }
    return loaded ?: fallback
}
