package tech.musxi.player.localaudio

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
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
import tech.musxi.core.localaudio.LocalAudioError
import tech.musxi.core.localaudio.LocalAudioFailure

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalArtworkPickedImageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val uri = Uri.parse("content://photos/temporary-selection")
    private val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
    // Upright TL, TR, BR, BL for an asymmetric four-colour source, hand-calculated per EXIF.
    private val uprightColors = arrayOf(
        intArrayOf(0, 1, 2, 3), intArrayOf(1, 0, 3, 2), intArrayOf(2, 3, 0, 1), intArrayOf(3, 2, 1, 0),
        intArrayOf(0, 3, 2, 1), intArrayOf(3, 0, 1, 2), intArrayOf(2, 1, 0, 3), intArrayOf(1, 2, 3, 0),
    )
    private val corners = listOf(LocalArtworkCrop(0f, 0f, .5f, .5f), LocalArtworkCrop(.5f, 0f, 1f, .5f),
        LocalArtworkCrop(.5f, .5f, 1f, 1f), LocalArtworkCrop(0f, .5f, .5f, 1f))

    private fun image(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            val paint = Paint()
            val halfW = width / 2f; val halfH = height / 2f
            val tiles = arrayOf(floatArrayOf(0f, 0f, halfW, halfH), floatArrayOf(halfW, 0f, width.toFloat(), halfH),
                floatArrayOf(halfW, halfH, width.toFloat(), height.toFloat()), floatArrayOf(0f, halfH, halfW, height.toFloat()))
            tiles.forEachIndexed { index, tile ->
                paint.color = colors[index]; canvas.drawRect(tile[0], tile[1], tile[2], tile[3], paint)
            }
            ByteArrayOutputStream().also { check(bitmap.compress(format, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun jpeg(orientation: Int): ByteArray {
        val file = temporary.newFile("exif-$orientation-${System.nanoTime()}.jpg")
        file.writeBytes(image(384, 256, Bitmap.CompressFormat.JPEG))
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "1/1,2/1,3/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            saveAttributes()
        }
        return file.readBytes()
    }

    private fun artwork(bytes: ByteArray, opened: (() -> Unit) = {}) = AndroidLocalAudioArtwork(
        File(temporary.root, "covers"), LocalArtworkFrameSource { _, _, _ -> error("Image is not media") },
        LocalArtworkImageSource { opened(); ByteArrayInputStream(bytes) },
    )

    private fun assertColour(expected: Int, actual: Int) {
        assertEquals(Color.red(expected).toDouble(), Color.red(actual).toDouble(), 15.0)
        assertEquals(Color.green(expected).toDouble(), Color.green(actual).toDouble(), 15.0)
        assertEquals(Color.blue(expected).toDouble(), Color.blue(actual).toDouble(), 15.0)
    }

    @Test fun wholeImageNormalizesAllEightExifDirectionsAndDropsPrivateMetadata() {
        for (orientation in 1..8) {
            val source = artwork(jpeg(orientation))
            val lease = source.prepareImage(uri)!!
            try {
                val file = source.resolve(lease.key)!!
                val bitmap = file.inputStream().use { BitmapFactory.decodeStream(it) }!!
                try {
                    assertEquals(if (orientation < 5) 384 else 256, bitmap.width)
                    assertEquals(if (orientation < 5) 256 else 384, bitmap.height)
                    val points = arrayOf(20 to 20, bitmap.width - 21 to 20,
                        bitmap.width - 21 to bitmap.height - 21, 20 to bitmap.height - 21)
                    points.forEachIndexed { index, (x, y) -> assertColour(colors[uprightColors[orientation - 1][index]], bitmap.getPixel(x, y)) }
                } finally { bitmap.recycle() }
                assertNull(ExifInterface(file.path).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
                assertTrue(ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) in 0..1)
            } finally { lease.close(); source.prune(emptySet()) }
        }
    }

    @Test fun cropUsesUprightCoordinatesAtAllFourCornersForEveryExifDirection() {
        for (orientation in 1..8) {
            var opens = 0
            val source = artwork(jpeg(orientation)) { opens++ }
            corners.forEachIndexed { index, crop ->
                val lease = source.prepareImage(uri, crop)!!
                try {
                    val bitmap = source.resolve(lease.key)!!.inputStream().use { BitmapFactory.decodeStream(it) }!!
                    try {
                        assertEquals(if (orientation < 5) 192 else 128, bitmap.width)
                        assertEquals(if (orientation < 5) 128 else 192, bitmap.height)
                        assertColour(colors[uprightColors[orientation - 1][index]], bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                    } finally { bitmap.recycle() }
                } finally { lease.close() }
            }
            assertEquals(4, opens)
            source.prune(emptySet())
            assertTrue(File(temporary.root, "covers").listFiles()!!.isEmpty())
        }
    }

    @Test fun pngAndWebpSupportRegionDecodeAndCenterCropKeepsTheRequestedAspectRatio() {
        for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP)) {
            val source = artwork(image(800, 400, format))
            val lease = source.prepareImage(uri, LocalArtworkCrop(.25f, .25f, .75f, .75f))!!
            try {
                val bitmap = source.resolve(lease.key)!!.inputStream().use { BitmapFactory.decodeStream(it) }!!
                try { assertEquals(400, bitmap.width); assertEquals(200, bitmap.height) }
                finally { bitmap.recycle() }
            } finally { lease.close(); source.prune(emptySet()) }
        }
    }

    @Test fun smallRegionOfLargeImageUsesRegionDimensionsForSampling() {
        val source = artwork(image(4096, 3072, Bitmap.CompressFormat.PNG))
        val lease = source.prepareImage(uri, LocalArtworkCrop(0f, 0f, 128f / 4096, 128f / 3072))!!
        try {
            val bitmap = source.resolve(lease.key)!!.inputStream().use { BitmapFactory.decodeStream(it) }!!
            try {
                assertEquals(128, bitmap.width)
                assertTrue(bitmap.height in 128..129) // outward float/pixel rounding
                assertColour(Color.RED, bitmap.getPixel(64, 64))
            } finally { bitmap.recycle() }
        } finally { lease.close() }
    }

    @Test fun selectedRegionIsSampledAndNeverStoredLargerThan512() {
        val source = artwork(image(2400, 1600, Bitmap.CompressFormat.JPEG))
        val lease = source.prepareImage(uri, LocalArtworkCrop(.25f, 0f, 1f, 1f))!!
        try {
            val bitmap = source.resolve(lease.key)!!.inputStream().use { BitmapFactory.decodeStream(it) }!!
            try {
                assertEquals(512, bitmap.width)
                assertEquals(1800.0 / 1600, bitmap.width.toDouble() / bitmap.height, .01)
            } finally { bitmap.recycle() }
        } finally { lease.close() }
    }

    @Test fun tooSmallAndInvalidRegionsLeaveNoManagedFilesAndNullKeepsTinyImages() {
        var opens = 0
        val source = artwork(image(128, 128, Bitmap.CompressFormat.PNG)) { opens++ }
        val invalid = assertThrows(LocalAudioFailure::class.java) {
            source.prepareImage(uri, LocalArtworkCrop(-.1f, 0f, 1f, 1f))
        }
        assertEquals(LocalAudioError.INVALID_TARGET, invalid.error)
        assertEquals(0, opens)
        val tooSmall = assertThrows(LocalAudioFailure::class.java) {
            source.prepareImage(uri, LocalArtworkCrop(0f, 0f, .25f, .5f))
        }
        assertEquals(LocalAudioError.INVALID_TARGET, tooSmall.error)
        assertTrue(File(temporary.root, "covers").listFiles()!!.isEmpty())
        val tiny = artwork(image(32, 16, Bitmap.CompressFormat.PNG))
        val lease = tiny.prepareImage(uri)!!
        try { assertNotNull(tiny.resolve(lease.key)) } finally { lease.close() }
    }

    @Test fun interruptedSourceReadClosesProviderAndRemovesPrivateSourceSpool() {
        var closed = false
        var cancelled = false
        val source = AndroidLocalAudioArtwork(File(temporary.root, "covers"), LocalArtworkFrameSource { _, _, _ -> null },
            LocalArtworkImageSource {
                object : ByteArrayInputStream(image(128, 128, Bitmap.CompressFormat.PNG)) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        cancelled = true
                        return super.read(buffer, offset, length)
                    }
                    override fun close() { closed = true; super.close() }
                }
            })
        assertThrows(CancellationException::class.java) {
            source.prepareImage(uri, corners[0]) { if (cancelled) throw CancellationException() }
        }
        assertTrue(closed)
        assertTrue(File(temporary.root, "covers").listFiles()!!.isEmpty())
    }

    @Test fun pruneDuringSourceCopyCannotRemoveTheActiveSourceSpool() {
        lateinit var source: AndroidLocalAudioArtwork
        var pruned = false
        val bytes = image(256, 256, Bitmap.CompressFormat.PNG)
        source = AndroidLocalAudioArtwork(File(temporary.root, "covers"), LocalArtworkFrameSource { _, _, _ -> null },
            LocalArtworkImageSource {
                object : ByteArrayInputStream(bytes) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val count = super.read(buffer, offset, length)
                        source.prune(emptySet())
                        pruned = true
                        return count
                    }
                }
            })
        val lease = source.prepareImage(uri, corners[0])!!
        try {
            assertTrue(pruned)
            assertNotNull(source.resolve(lease.key))
            assertEquals(listOf(lease.key + ".jpg"), File(temporary.root, "covers").listFiles()!!.map { it.name })
        } finally { lease.close(); source.prune(emptySet()) }
        assertTrue(File(temporary.root, "covers").listFiles()!!.isEmpty())
    }
}
