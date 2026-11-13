package tech.musxi.core.wallpaper

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WallpaperImageInspectorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun pngAndJpegAreDetectedFromContentRegardlessOfName() {
        for ((format, expected) in listOf("png" to WallpaperFormat.PNG, "jpg" to WallpaperFormat.JPEG)) {
            val file = temporary.newFile("$format.txt")
            check(ImageIO.write(BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB), format, file))
            assertEquals(WallpaperImageInfo(expected, 120, 80), WallpaperImageInspector.inspect(file))
        }
    }

    @Test fun staticWebpContainerDimensionsAndAnimationBitsAreInspected() {
        val vp8l = byteArrayOf(0x2f, 119, 0xc0.toByte(), 19, 0) // 120 x 80
        val file = write(webp("VP8L", vp8l))
        assertEquals(WallpaperImageInfo(WallpaperFormat.WEBP, 120, 80), WallpaperImageInspector.inspect(file))
        val vp8x = byteArrayOf(2, 0, 0, 0, 119, 0, 0, 79, 0, 0)
        rejected(write(webp("VP8X", vp8x)), WallpaperError.ANIMATED_IMAGE)
        rejected(write(webp("ANIM", ByteArray(6))), WallpaperError.ANIMATED_IMAGE)
    }

    @Test fun apngTruncationAndUnsupportedFilesAreRejected() {
        val file = temporary.newFile("source.png")
        ImageIO.write(BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB), "png", file)
        val png = file.readBytes()
        val animation = byteArrayOf(0, 0, 0, 8, 97, 99, 84, 76) + ByteArray(12)
        rejected(write(png.copyOfRange(0, 33) + animation + png.copyOfRange(33, png.size)), WallpaperError.ANIMATED_IMAGE)
        rejected(write(png.copyOf(png.size - 3)), WallpaperError.INVALID_IMAGE)
        rejected(write("GIF89a unsupported file".toByteArray()), WallpaperError.UNSUPPORTED_FORMAT)
    }

    @Test fun oversizedDimensionsAndFileLengthsAreBoundedBeforePixelDecode() {
        val file = temporary.newFile("source.png")
        ImageIO.write(BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB), "png", file)
        val png = file.readBytes()
        png[16] = 0; png[17] = 0; png[18] = 0x40; png[19] = 1
        rejected(write(png), WallpaperError.IMAGE_TOO_LARGE)
        val huge = temporary.newFile("huge")
        java.io.RandomAccessFile(huge, "rw").use { it.setLength(WallpaperLimits.MAX_SOURCE_BYTES + 1) }
        rejected(huge, WallpaperError.IMAGE_TOO_LARGE)
    }

    private fun write(bytes: ByteArray) = temporary.newFile().apply { writeBytes(bytes) }
    private fun rejected(file: File, reason: WallpaperError) {
        try { WallpaperImageInspector.inspect(file); fail("Accepted an invalid image") }
        catch (error: WallpaperFailure) { assertEquals(reason, error.reason) }
    }
    private fun webp(tag: String, data: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes ->
        val output = DataOutputStream(bytes)
        output.writeBytes("RIFF")
        fun le(value: Int) { repeat(4) { output.writeByte(value ushr (it * 8)) } }
        le(4 + 8 + data.size + data.size % 2)
        output.writeBytes("WEBP$tag"); le(data.size); output.write(data)
        if (data.size % 2 != 0) output.writeByte(0)
    }.toByteArray()
}
