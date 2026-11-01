package tech.musxi.player.ui.player

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Side of the square a cover is shrunk to before it is averaged, as on the PC client. */
private const val SAMPLE_SIDE = 32

/** Covers whose colour is remembered; the shell and the player page read the same entries. */
private const val COVER_COLOR_CACHE_SIZE = 64

/** What sampling one cover address gave: [argb] is null when it failed, which is also remembered. */
internal class CoverSample(val argb: Int?)

/** Least recently used colours by cover address. Failures are kept too, so they are not retried. */
internal class CoverColorCache(private val capacity: Int = COVER_COLOR_CACHE_SIZE) {
    private val entries = object : LinkedHashMap<String, CoverSample>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CoverSample>?): Boolean = size > capacity
    }

    /** Null when the address has never been sampled. Reading counts as use. */
    @Synchronized fun get(url: String): CoverSample? = entries[url]

    @Synchronized fun put(url: String, argb: Int?) {
        entries[url] = CoverSample(argb)
    }

    @Synchronized fun size(): Int = entries.size
}

private val SharedCoverColors = CoverColorCache()

/**
 * The average colour of the cover at [url] as an opaque ARGB int, or null while it is not known:
 * no address, still loading, or the cover could not be read (a failure is not retried).
 *
 * While the next cover loads, the previous song's colour is kept so a track change fades once
 * instead of detouring through the fallback colour. An address that is already known answers on the
 * first frame.
 */
@Composable
fun rememberCoverColor(url: String?): Int? {
    val context = LocalPlatformContext.current
    var held by remember { mutableStateOf<Int?>(null) }
    val address = url?.takeIf { it.isNotBlank() }
    LaunchedEffect(address) {
        if (address == null) {
            held = null
            return@LaunchedEffect
        }
        val known = SharedCoverColors.get(address)
        if (known != null) {
            held = known.argb
            return@LaunchedEffect
        }
        val sampled = try {
            loadCoverColor(context, address)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        SharedCoverColors.put(address, sampled)
        held = sampled
    }
    if (address == null) return null
    val known = SharedCoverColors.get(address)
    return if (known != null) known.argb else held
}

/**
 * Asks the app's shared Coil loader for [url] at thumbnail size, so a cover the lists already
 * fetched comes from its caches instead of the network, and averages it off the main thread.
 * Memory cache is read-only: a 32px decode must not replace the larger bitmap a list is showing.
 */
internal suspend fun loadCoverColor(context: Context, url: String): Int? {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(SAMPLE_SIDE)
        .precision(Precision.INEXACT)
        .allowHardware(false)
        .memoryCachePolicy(CachePolicy.READ_ONLY)
        .build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    val bitmap = (result.image as? BitmapImage)?.bitmap ?: return null
    return withContext(Dispatchers.Default) { sampleBitmapColor(bitmap) }
}

/**
 * Shrinks [source] to 32×32 and averages it with [averageCoverColor]. The bitmap is only read: it
 * may be the one the memory cache hands to every list row, so it is never recycled or drawn into.
 */
internal fun sampleBitmapColor(source: Bitmap): Int? {
    // A hardware bitmap has no pixels the CPU can read; copy it once.
    val readable = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
    } else {
        source
    }
    val small = Bitmap.createScaledBitmap(readable, SAMPLE_SIDE, SAMPLE_SIDE, true)
    val pixels = IntArray(SAMPLE_SIDE * SAMPLE_SIDE)
    small.getPixels(pixels, 0, SAMPLE_SIDE, 0, 0, SAMPLE_SIDE, SAMPLE_SIDE)
    return averageCoverColor(pixels)
}
