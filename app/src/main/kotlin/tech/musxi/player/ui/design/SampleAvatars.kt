package tech.musxi.player.ui.design

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePainter
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import coil3.compose.asPainter
import coil3.request.ImageRequest

/**
 * Sample avatars for the design sheets. A sample profile's avatar address is `sample-avatar:<name>`;
 * inside this wrapper Coil draws a generated picture for it instead of loading anything, so the
 * screenshots show a signed-in row without a network. The picture is a soft two-colour gradient
 * with a light spot, one palette per name, not a photo. Every other address draws nothing, as an
 * avatar that cannot be loaded does in the app.
 */
@OptIn(ExperimentalCoilApi::class)
@Composable
fun SampleAvatars(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalInspectionMode provides true,
        LocalAsyncImagePreviewHandler provides SampleAvatarHandler,
        content = content,
    )
}

internal fun sampleAvatarUrl(name: String) = "$SAMPLE_AVATAR$name"

private const val SAMPLE_AVATAR = "sample-avatar:"

// Coil's own preview result is a Loading state carrying the painter; a null painter draws nothing.
@OptIn(ExperimentalCoilApi::class)
private val SampleAvatarHandler = object : AsyncImagePreviewHandler {
    override suspend fun handle(imageLoader: ImageLoader, request: ImageRequest): AsyncImagePainter.State {
        val model = request.data as? String
        val image = if (model != null && model.startsWith(SAMPLE_AVATAR)) {
            sampleAvatar(model.removePrefix(SAMPLE_AVATAR)).asImage()
        } else {
            null
        }
        return AsyncImagePainter.State.Loading(image?.asPainter(request.context))
    }
}

private val Palettes = listOf(
    0xFFF2B9A6.toInt() to 0xFFB46E8A.toInt(),
    0xFF9DC8C2.toInt() to 0xFF4E7DA0.toInt(),
    0xFFE6CE98.toInt() to 0xFFB1825A.toInt(),
)

private fun sampleAvatar(name: String): Bitmap {
    val size = 120
    val (from, to) = Palettes[Math.floorMod(name.hashCode(), Palettes.size)]
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawPaint(
        Paint().apply { shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), from, to, Shader.TileMode.CLAMP) },
    )
    canvas.drawCircle(size * 0.32f, size * 0.30f, size * 0.26f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF })
    return bitmap
}
