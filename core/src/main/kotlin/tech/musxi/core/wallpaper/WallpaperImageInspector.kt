package tech.musxi.core.wallpaper

import java.io.File
import java.io.EOFException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption

/** Bounded container inspection; the Android importer must still decode the actual pixels. */
object WallpaperImageInspector {
    fun inspect(file: File): WallpaperImageInfo {
        try {
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) bad()
            val length = file.length()
            if (length > WallpaperLimits.MAX_SOURCE_BYTES) fail(WallpaperError.IMAGE_TOO_LARGE)
            if (length < 12) bad()
            return RandomAccessFile(file, "r").use { input ->
                val header = ByteArray(12).also(input::readFully)
                input.seek(0)
                when {
                    header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> png(input, length)
                    header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() -> jpeg(input, length)
                    String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> webp(input, length)
                    else -> fail(WallpaperError.UNSUPPORTED_FORMAT)
                }.also { if (input.length() != length) bad() }
            }
        } catch (error: WallpaperFailure) {
            throw error
        } catch (_: EOFException) {
            fail(WallpaperError.INVALID_IMAGE)
        } catch (_: IOException) {
            fail(WallpaperError.READ_FAILED)
        }
    }

    private fun png(input: RandomAccessFile, length: Long): WallpaperImageInfo {
        input.seek(8)
        var info: WallpaperImageInfo? = null
        var pixels = false
        var chunks = 0
        while (input.filePointer < length && ++chunks <= 100_000) {
            if (length - input.filePointer < 12) bad()
            val size = be32(input)
            val tag = tag(input)
            val start = input.filePointer
            val end = start + size + 4
            if (end > length) bad()
            if (chunks == 1 && tag != "IHDR") bad()
            when (tag) {
                "acTL" -> fail(WallpaperError.ANIMATED_IMAGE)
                "IHDR" -> {
                    if (size != 13L || info != null) bad()
                    info = dimensions(WallpaperFormat.PNG, be32(input), be32(input))
                }
                "IDAT" -> { if (info == null) bad(); pixels = true }
                "IEND" -> {
                    if (size != 0L || !pixels || end != length) bad()
                    return info ?: bad()
                }
            }
            input.seek(end)
        }
        bad()
    }

    private fun jpeg(input: RandomAccessFile, length: Long): WallpaperImageInfo {
        input.seek(2)
        var info: WallpaperImageInfo? = null
        var segments = 0
        while (input.filePointer < length && ++segments <= 100_000) {
            if (input.readUnsignedByte() != 0xff) bad()
            var marker: Int
            do { marker = input.readUnsignedByte() } while (marker == 0xff)
            if (marker == 0 || marker == 0xd8 || marker == 0xd9) bad()
            if (marker == 0x01 || marker in 0xd0..0xd7) continue
            if (length - input.filePointer < 2) bad()
            val size = input.readUnsignedShort()
            if (size < 2 || input.filePointer + size - 2 > length) bad()
            val end = input.filePointer + size - 2
            if (marker in SOF) {
                if (size < 8 || info != null) bad()
                input.readUnsignedByte() // Sample precision; actual decoder validates supported modes.
                val height = input.readUnsignedShort().toLong()
                val width = input.readUnsignedShort().toLong()
                info = dimensions(WallpaperFormat.JPEG, width, height)
            }
            if (marker == 0xda) {
                if (info == null || end + 2 > length) bad()
                input.seek(length - 2)
                if (input.readUnsignedShort() != 0xffd9) bad()
                return info
            }
            input.seek(end)
        }
        bad()
    }

    private fun webp(input: RandomAccessFile, length: Long): WallpaperImageInfo {
        input.seek(4)
        if (le32(input) + 8 != length) bad()
        input.seek(12)
        var canvas: WallpaperImageInfo? = null
        var image: WallpaperImageInfo? = null
        var chunks = 0
        while (input.filePointer < length && ++chunks <= 100_000) {
            if (length - input.filePointer < 8) bad()
            val tag = tag(input)
            val size = le32(input)
            val start = input.filePointer
            val end = start + size + (size and 1)
            if (end > length) bad()
            when (tag) {
                "ANIM", "ANMF" -> fail(WallpaperError.ANIMATED_IMAGE)
                "VP8X" -> {
                    if (chunks != 1 || size != 10L || canvas != null) bad()
                    val flags = input.readUnsignedByte()
                    if (flags and 2 != 0) fail(WallpaperError.ANIMATED_IMAGE)
                    input.skipBytes(3)
                    canvas = dimensions(WallpaperFormat.WEBP, le24(input) + 1, le24(input) + 1)
                }
                "VP8 " -> {
                    if (size < 10 || image != null) bad()
                    val frame = le24(input)
                    if (frame and 1L != 0L || le24(input) != 0x2a019dL) bad()
                    val width = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
                    val height = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
                    image = dimensions(WallpaperFormat.WEBP, (width and 0x3fff).toLong(), (height and 0x3fff).toLong())
                }
                "VP8L" -> {
                    if (size < 5 || image != null || input.readUnsignedByte() != 0x2f) bad()
                    val bits = le32(input)
                    if (bits ushr 29 != 0L) bad()
                    image = dimensions(WallpaperFormat.WEBP, (bits and 0x3fff) + 1, ((bits ushr 14) and 0x3fff) + 1)
                }
            }
            input.seek(end)
        }
        if (input.filePointer != length || image == null || (canvas != null && canvas != image)) bad()
        return image
    }

    private fun dimensions(format: WallpaperFormat, width: Long, height: Long): WallpaperImageInfo {
        if (width <= 0 || height <= 0) bad()
        if (width > WallpaperLimits.MAX_SOURCE_EDGE || height > WallpaperLimits.MAX_SOURCE_EDGE ||
            width * height > WallpaperLimits.MAX_SOURCE_PIXELS
        ) fail(WallpaperError.IMAGE_TOO_LARGE)
        return WallpaperImageInfo(format, width.toInt(), height.toInt())
    }

    private fun tag(input: RandomAccessFile) = String(ByteArray(4).also(input::readFully), Charsets.US_ASCII)
    private fun be32(input: RandomAccessFile): Long = input.readInt().toLong() and 0xffffffffL
    private fun le24(input: RandomAccessFile): Long = input.readUnsignedByte().toLong() or
        (input.readUnsignedByte().toLong() shl 8) or (input.readUnsignedByte().toLong() shl 16)
    private fun le32(input: RandomAccessFile): Long = le24(input) or (input.readUnsignedByte().toLong() shl 24)
    private fun bad(): Nothing = fail(WallpaperError.INVALID_IMAGE)
    private fun fail(reason: WallpaperError): Nothing = throw WallpaperFailure(reason)
    private val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private val SOF = setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)
}
