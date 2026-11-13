package tech.musxi.player.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CancellationException
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt
import tech.musxi.core.wallpaper.WallpaperError
import tech.musxi.core.wallpaper.WallpaperFailure
import tech.musxi.core.wallpaper.WallpaperImageInfo
import tech.musxi.core.wallpaper.WallpaperImageInspector
import tech.musxi.core.wallpaper.WallpaperLimits

data class PreparedWallpaper(val file: File, val info: WallpaperImageInfo)

/** Synchronous IO adapter: callers dispatch to IO and own the successful temporary output. */
class AndroidWallpaperImporter(private val workingDirectory: File) {
    /** Only called at facade startup, before any commands; never clears an in-flight import. */
    fun cleanupAbandoned() {
        if (Files.isSymbolicLink(workingDirectory.toPath())) fail(WallpaperError.STORAGE_FAILED)
        workingDirectory.listFiles()?.forEach { file ->
            if (file.name.matches(Regex("(source-[0-9-]+\\.tmp|display-[0-9-]+\\.(jpg|png))")) &&
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) file.delete()
        }
    }

    fun prepare(openInput: () -> InputStream, checkCancelled: () -> Unit = {}): PreparedWallpaper {
        if (Files.isSymbolicLink(workingDirectory.toPath()) ||
            (!workingDirectory.isDirectory && !workingDirectory.mkdirs())
        ) fail(WallpaperError.STORAGE_FAILED)
        val source = File.createTempFile("source-", ".tmp", workingDirectory)
        var output: File? = null
        val bitmaps = mutableSetOf<Bitmap>()
        var transferred = false
        try {
            checkCancelled()
            readInput(openInput).use { input ->
                FileOutputStream(source).use { destination ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        checkCancelled()
                        var count = readInput { input.read(buffer) }
                        if (count < 0) break
                        if (count == 0) {
                            val single = readInput { input.read() }
                            if (single < 0) break
                            buffer[0] = single.toByte()
                            count = 1
                        }
                        total += count
                        if (total > WallpaperLimits.MAX_SOURCE_BYTES) fail(WallpaperError.IMAGE_TOO_LARGE)
                        destination.write(buffer, 0, count)
                    }
                }
            }

            val info = WallpaperImageInspector.inspect(source)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            source.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth != info.width || bounds.outHeight != info.height) fail(WallpaperError.INVALID_IMAGE)
            var sample = 1
            // Power-of-two sampling bounds the first real bitmap, before any rotation or scaling.
            while ((info.width + sample - 1) / sample > WallpaperLimits.MAX_DISPLAY_EDGE ||
                (info.height + sample - 1) / sample > WallpaperLimits.MAX_DISPLAY_EDGE ||
                ((info.width + sample - 1) / sample).toLong() * ((info.height + sample - 1) / sample) > WallpaperLimits.MAX_DISPLAY_PIXELS
            ) sample *= 2
            checkCancelled()
            var bitmap = source.inputStream().use { input ->
                BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                    inScaled = false
                })
            } ?: fail(WallpaperError.INVALID_IMAGE)
            bitmaps += bitmap
            checkCancelled()
            val orientation = try {
                ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
            val matrix = orientationMatrix(orientation)
            if (!matrix.isIdentity) {
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                bitmaps += bitmap
            }
            val scale = min(1.0, min(
                WallpaperLimits.MAX_DISPLAY_EDGE.toDouble() / maxOf(bitmap.width, bitmap.height),
                sqrt(WallpaperLimits.MAX_DISPLAY_PIXELS.toDouble() / (bitmap.width.toLong() * bitmap.height)),
            ))
            if (scale < 1) {
                bitmap = Bitmap.createScaledBitmap(bitmap, maxOf(1, floor(bitmap.width * scale).toInt()),
                    maxOf(1, floor(bitmap.height * scale).toInt()), true)
                bitmaps += bitmap
            }
            checkCancelled()
            val transparent = bitmap.hasAlpha()
            output = File.createTempFile("display-", if (transparent) ".png" else ".jpg", workingDirectory)
            FileOutputStream(output).use { destination ->
                if (!bitmap.compress(if (transparent) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, destination)) {
                    fail(WallpaperError.INVALID_IMAGE)
                }
                destination.fd.sync()
            }
            if (output.length() > WallpaperLimits.MAX_DISPLAY_BYTES) fail(WallpaperError.IMAGE_TOO_LARGE)
            checkCancelled()
            val normalized = WallpaperImageInspector.inspect(output)
            if (normalized.width > WallpaperLimits.MAX_DISPLAY_EDGE || normalized.height > WallpaperLimits.MAX_DISPLAY_EDGE ||
                normalized.width.toLong() * normalized.height > WallpaperLimits.MAX_DISPLAY_PIXELS
            ) fail(WallpaperError.IMAGE_TOO_LARGE)
            return PreparedWallpaper(output, normalized).also { transferred = true }
        } catch (error: WallpaperFailure) { throw error }
        catch (error: CancellationException) { throw error }
        catch (_: IOException) { fail(WallpaperError.STORAGE_FAILED) }
        catch (_: SecurityException) { fail(WallpaperError.STORAGE_FAILED) }
        catch (_: IllegalArgumentException) { fail(WallpaperError.INVALID_IMAGE) }
        finally {
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
            source.delete()
            if (!transferred) output?.delete()
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

    private inline fun <T> readInput(action: () -> T): T = try { action() }
    catch (_: IOException) { fail(WallpaperError.READ_FAILED) }
    catch (_: SecurityException) { fail(WallpaperError.READ_FAILED) }
    catch (_: android.os.OperationCanceledException) { fail(WallpaperError.READ_FAILED) }

    private fun fail(reason: WallpaperError): Nothing = throw WallpaperFailure(reason)
}
