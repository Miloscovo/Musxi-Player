package tech.musxi.player.wallpaper

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.musxi.core.wallpaper.WallpaperError
import tech.musxi.core.wallpaper.WallpaperFailure
import tech.musxi.core.wallpaper.WallpaperFormat
import tech.musxi.core.wallpaper.WallpaperLimits

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidWallpaperImporterTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun importer() = AndroidWallpaperImporter(File(temporary.root, "imports"))

    @Test fun transparentPngKeepsAlphaAndReleasesOriginalStream() {
        val bytes = image(60, 90, Bitmap.CompressFormat.PNG, true)
        var closed = false
        val output = importer().prepare({ object : ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        } })
        assertTrue(closed)
        assertEquals(WallpaperFormat.PNG, output.info.format)
        assertEquals(60, output.info.width)
        val decoded = output.file.inputStream().use { BitmapFactory.decodeStream(it) }!!
        try { assertEquals(100, Color.alpha(decoded.getPixel(10, 10))) }
        finally { decoded.recycle(); output.file.delete() }
        val remaining = File(temporary.root, "imports").listFiles()!!
        assertTrue("Unexpected temporary files: ${remaining.map { it.name }}", remaining.isEmpty())
    }

    @Test fun jpegAndStaticWebpProduceDecodableOpaqueManagedJpegs() {
        for (format in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.WEBP)) {
            val output = importer().prepare({ ByteArrayInputStream(image(90, 60, format, false)) })
            try {
                assertEquals(WallpaperFormat.JPEG, output.info.format)
                val decoded = output.file.inputStream().use { BitmapFactory.decodeStream(it) }!!
                assertNotNull(decoded)
                decoded.recycle()
            } finally { output.file.delete() }
        }
    }

    @Test fun oversizedBitmapIsSampledBeforeOutputAndMeetsPixelBudget() {
        val output = importer().prepare({ ByteArrayInputStream(image(4096, 3072, Bitmap.CompressFormat.JPEG, false)) })
        try {
            assertTrue(output.info.width <= WallpaperLimits.MAX_DISPLAY_EDGE)
            assertTrue(output.info.height <= WallpaperLimits.MAX_DISPLAY_EDGE)
            assertTrue(output.info.width.toLong() * output.info.height <= WallpaperLimits.MAX_DISPLAY_PIXELS)
            assertTrue(output.info.width < 4096)
            assertTrue(output.file.length() <= WallpaperLimits.MAX_DISPLAY_BYTES)
        } finally { output.file.delete() }
    }

    @Test fun allExifRotationsAndMirrorsAreNormalizedAndPrivateMetadataIsRemoved() {
        for (orientation in 1..8) {
            val source = File(temporary.root, "exif-$orientation.jpg").apply {
                writeBytes(image(60, 90, Bitmap.CompressFormat.JPEG, false))
            }
            ExifInterface(source.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "1/1,2/1,3/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                saveAttributes()
            }
            val output = importer().prepare({ source.inputStream() })
            try {
                assertEquals(if (orientation >= 5) 90 else 60, output.info.width)
                assertEquals(if (orientation >= 5) 60 else 90, output.info.height)
                val decoded = output.file.inputStream().use { BitmapFactory.decodeStream(it) }!!
                try {
                    // Source is asymmetric: its red top-left quadrant identifies rotation/mirror.
                    val redCorner = when (orientation) { 1, 5 -> 0; 2, 6 -> 1; 3, 7 -> 2; else -> 3 }
                    val positions = arrayOf(5 to 5, decoded.width - 6 to 5,
                        decoded.width - 6 to decoded.height - 6, 5 to decoded.height - 6)
                    positions.forEachIndexed { index, (x, y) ->
                        assertEquals("EXIF $orientation corner $index", index == redCorner, Color.red(decoded.getPixel(x, y)) > 180)
                    }
                } finally { decoded.recycle() }
                val normalizedExif = ExifInterface(output.file.path)
                assertNull(normalizedExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
                assertTrue(normalizedExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    in setOf(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL))
                assertTrue(source.isFile)
            } finally { output.file.delete(); source.delete() }
        }
    }

    @Test fun invalidImageAndCancelledReadLeaveNoTemporaryFiles() {
        try { importer().prepare({ ByteArrayInputStream(byteArrayOf(1, 2, 3)) }); fail() }
        catch (error: WallpaperFailure) { assertEquals(WallpaperError.INVALID_IMAGE, error.reason) }
        var checks = 0
        var closed = false
        try {
            importer().prepare({ object : ByteArrayInputStream(image(60, 90, Bitmap.CompressFormat.PNG, true)) {
                override fun close() { closed = true; super.close() }
            } }, { if (++checks == 2) throw CancellationException() })
            fail()
        } catch (_: CancellationException) { }
        assertTrue(closed)
        assertTrue(File(temporary.root, "imports").listFiles()!!.isEmpty())
    }

    @Test fun rejectedAnimatedPngNeverReachesPixelDecodeAndLeavesNoCandidate() {
        val source = image(60, 90, Bitmap.CompressFormat.PNG, true)
        val animation = byteArrayOf(0, 0, 0, 8, 97, 99, 84, 76, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0)
        val apng = source.copyOfRange(0, 33) + animation + source.copyOfRange(33, source.size)
        try { importer().prepare({ ByteArrayInputStream(apng) }); fail() }
        catch (error: WallpaperFailure) { assertEquals(WallpaperError.ANIMATED_IMAGE, error.reason) }
        assertTrue(File(temporary.root, "imports").listFiles()!!.isEmpty())
    }

    @Test fun abandonedInputCleanupLeavesUnknownFilesAlone() {
        val directory = File(temporary.root, "imports").apply { mkdirs() }
        File(directory, "source-123.tmp").writeText("partial input")
        File(directory, "display-456.png").writeText("partial output")
        val sentinel = File(directory, "notes.txt").apply { writeText("keep") }
        importer().cleanupAbandoned()
        assertEquals(listOf(sentinel.name), directory.listFiles()!!.map { it.name })
    }

    @Test fun actualInputBytesAreLimitedAndUnreadableProvidersReportReadFailure() {
        var remaining = WallpaperLimits.MAX_SOURCE_BYTES + 1
        var closed = false
        try {
            importer().prepare({ object : java.io.InputStream() {
                override fun read(): Int = if (remaining-- > 0) 0 else -1
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (remaining <= 0) return -1
                    val count = minOf(length.toLong(), remaining).toInt()
                    remaining -= count
                    return count
                }
                override fun close() { closed = true }
            } })
            fail()
        } catch (error: WallpaperFailure) { assertEquals(WallpaperError.IMAGE_TOO_LARGE, error.reason) }
        assertTrue(closed)
        try { importer().prepare({ throw java.io.IOException("provider unavailable") }); fail() }
        catch (error: WallpaperFailure) { assertEquals(WallpaperError.READ_FAILED, error.reason) }
        assertTrue(File(temporary.root, "imports").listFiles()!!.isEmpty())
    }

    private fun image(width: Int, height: Int, format: Bitmap.CompressFormat, transparent: Boolean): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.setHasAlpha(transparent)
            bitmap.eraseColor(if (transparent) Color.argb(100, 20, 90, 160) else Color.BLUE)
            if (!transparent) android.graphics.Canvas(bitmap).drawRect(0f, 0f, width / 2f, height / 2f,
                android.graphics.Paint().apply { color = Color.RED })
            ByteArrayOutputStream().also { check(bitmap.compress(format, 90, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }
}
