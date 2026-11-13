package tech.musxi.player.localaudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import java.io.File
import java.io.IOException

/** Pixel decoding over an immutable, capped source spool. Does not own or retain that file. */
internal object LocalArtworkImageDecoder {
    fun decode(source: File, crop: LocalArtworkCrop?, checkCancelled: () -> Unit,
               maxEdge: Int, maxPixels: Long, maxLongEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (!LocalArtworkImages.pickedSizeAccepted(bounds.outWidth, bounds.outHeight, maxPixels, maxLongEdge)) return null
        checkCancelled()
        // Platform support varies by Android version/container. Unreadable orientation is normal.
        val orientation = try {
            source.inputStream().use { input ->
                ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
        val region = LocalArtworkCropGeometry.region(bounds.outWidth, bounds.outHeight, orientation, crop)
        val options = BitmapFactory.Options().apply {
            inSampleSize = LocalArtworkImages.sampleSize(region.width, region.height, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            inScaled = false
        }
        val owned = mutableSetOf<Bitmap>()
        var transferred: Bitmap? = null
        try {
            checkCancelled()
            var bitmap = if (crop == null) source.inputStream().use { BitmapFactory.decodeStream(it, null, options) } else {
                // Never fall back to a full-frame decode when a format lacks region support.
                source.inputStream().use { input ->
                    @Suppress("DEPRECATION")
                    val decoder = BitmapRegionDecoder.newInstance(input, false) ?: return null
                    try {
                        checkCancelled()
                        decoder.decodeRegion(Rect(region.left, region.top, region.right, region.bottom), options)
                    } finally { decoder.recycle() }
                }
            } ?: return null
            owned += bitmap
            checkCancelled()
            // Some platform codecs round sampling differently; bound the allocation before any
            // transformed copy. No full-size fallback is allowed for either branch.
            if (maxOf(bitmap.width, bitmap.height) > maxEdge + 1) return null
            val transform = orientationMatrix(orientation)
            if (!transform.isIdentity) {
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
                owned += bitmap
            }
            checkCancelled()
            bitmap = LocalArtworkImages.scaleToMaxEdge(bitmap, maxEdge).also { owned += it }
            checkCancelled()
            transferred = bitmap
            return bitmap
        } finally {
            owned.forEach { if (it !== transferred && !it.isRecycled) it.recycle() }
        }
    }

    private fun orientationMatrix(orientation: Int) = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
}
