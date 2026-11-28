package tech.musxi.player.localaudio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAudioFormatInspectorTest {
    @Test fun flacCdBitsAreReadFromStreamInfoWithoutGuessingFromItsName() {
        assertEquals(LocalSourceFormat("flac", 44_100, 16, 2), inspect(flac(44_100, 16, 2)))
    }

    @Test fun flacHighResolutionStreamInfoKeepsItsDeclaredValues() {
        assertEquals(LocalSourceFormat("flac", 192_000, 24, 2), inspect(flac(192_000, 24, 2)))
    }

    @Test fun wavPcmFieldsAreValidatedAndReported() {
        assertEquals(LocalSourceFormat("pcm_s16le", 44_100, 16, 2), inspect(wav(44_100, 16, 2)))
    }

    @Test fun wavIeeeFloatRequiresAFloatBitDepth() {
        assertEquals(LocalSourceFormat("pcm_f32le", 48_000, 32, 2), inspect(wav(48_000, 32, 2, 3)))
        assertNull(inspect(wav(48_000, 16, 2, 3)))
    }

    @Test fun wavBoundsJunkAndUnknownChunksBeforeFindingFmtAndData() {
        val junk = ByteArray(64 * 1024) { (it % 251).toByte() }
        val bytes = wav(44_100, 16, 2, before = chunk("JUNK", junk) + chunk("LIST", ByteArray(7)))
        val reader = CountingInput(bytes)
        assertEquals(LocalSourceFormat("pcm_s16le", 44_100, 16, 2),
            LocalAudioFormatInspector.inspect { reader })
        assertEquals(1, reader.closes.get())
        assertTrue(reader.bytesRead.get() <= 256 * 1024)

        val oversized = wav(44_100, 16, 2, before = chunk("JUNK", ByteArray(300 * 1024)))
        val bounded = CountingInput(oversized)
        assertNull(LocalAudioFormatInspector.inspect { bounded })
        assertEquals(1, bounded.closes.get())
        assertTrue(bounded.bytesRead.get() <= 256 * 1024)
    }

    @Test fun wavDeclaredHugeDataNeedsOnlyItsHeader() {
        val dataSize = 112 * 1024 * 1024
        val bytes = wav(44_100, 16, 2, dataSize = dataSize, declaredRiffSize = 36L + dataSize)
        val reader = CountingInput(bytes)
        assertEquals(LocalSourceFormat("pcm_s16le", 44_100, 16, 2),
            LocalAudioFormatInspector.inspect { reader })
        assertEquals(1, reader.closes.get())
        assertTrue(reader.bytesRead.get() < 100)
    }

    @Test fun shortReadsNeverUsePartialHeaderBytes() {
        val flacBytes = flac(44_100, 16, 2)
        for (length in 0..41) assertNull(inspect(flacBytes.copyOf(length)))
        val wavBytes = wav(44_100, 16, 2)
        for (length in 0..35) assertNull(inspect(wavBytes.copyOf(length)))

        val split = wav(44_100, 16, 2)
        val reader = SplitInput(split)
        assertEquals(LocalSourceFormat("pcm_s16le", 44_100, 16, 2),
            LocalAudioFormatInspector.inspect { reader })
        assertEquals(1, reader.closes.get())
    }

    @Test fun malformedAndTruncatedContainersReturnNullAndAlwaysClose() {
        val invalid = listOf(
            flac(44_100, 16, 2).also { it[4] = 1 },
            flac(44_100, 16, 2).also {
                it[18] = 0; it[19] = 0
                it[20] = (it[20].toInt() and 0xf).toByte()
            },
            flac(44_100, 16, 2).copyOf(30),
            wav(44_100, 16, 2).also { it[36] = 0; it[37] = 0 },
            wav(44_100, 16, 2).copyOf(30),
            wav(44_100, 16, 2, before = byteArrayOf(1, 2)),
            "RIFF\u0004\u0000\u0000\u0000WAVE".toByteArray(Charsets.US_ASCII),
        )
        invalid.forEach { bytes ->
            val reader = CountingInput(bytes)
            assertNull(LocalAudioFormatInspector.inspect { reader })
            assertEquals(1, reader.closes.get())
        }
    }

    @Test fun unsupportedCompressedWavAndUnknownContainersReturnNull() {
        assertNull(inspect(wav(44_100, 16, 2, format = 6)))
        assertNull(inspect("ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000mpeg".toByteArray()))
    }

    @Test fun absentOrOversizedHeadersDoNotReadPastTheProbeLimit() {
        val empty = CountingInput(ByteArray(0))
        assertNull(LocalAudioFormatInspector.inspect { empty })
        assertEquals(1, empty.closes.get())

        val huge = ByteArray(300 * 1024) { 0x55 }
        val reader = CountingInput(huge)
        assertNull(LocalAudioFormatInspector.inspect { reader })
        assertEquals(1, reader.closes.get())
        assertTrue(reader.bytesRead.get() <= 256 * 1024)
    }

    private fun inspect(bytes: ByteArray) = LocalAudioFormatInspector.inspect { ByteArrayInputStream(bytes) }

    private fun flac(rate: Int, bits: Int, channels: Int): ByteArray {
        val output = ByteArrayOutputStream()
        output.write("fLaC".toByteArray(Charsets.US_ASCII))
        output.write(byteArrayOf(0x80.toByte(), 0, 0, 34))
        output.write(ByteArray(10))
        output.write((rate ushr 12) and 0xff)
        output.write((rate ushr 4) and 0xff)
        output.write(((rate and 0xf) shl 4) or ((channels - 1) shl 1) or ((bits - 1) ushr 4))
        output.write(((bits - 1) and 0xf) shl 4)
        output.write(ByteArray(20))
        return output.toByteArray()
    }

    private fun wav(
        rate: Int,
        bits: Int,
        channels: Int,
        format: Int = 1,
        before: ByteArray = ByteArray(0),
        after: ByteArray = ByteArray(0),
        dataSize: Int = 8,
        declaredRiffSize: Long? = null,
    ): ByteArray {
        val blockAlign = channels * (bits / 8)
        val fmt = ByteArrayOutputStream().also {
            it.writeLe16(format)
            it.writeLe16(channels)
            it.writeLe32(rate)
            it.writeLe32(rate.toLong() * blockAlign)
            it.writeLe16(blockAlign)
            it.writeLe16(bits)
        }.toByteArray()
        val fmtChunk = chunk("fmt ", fmt)
        val dataHeader = chunk("data", ByteArray(minOf(dataSize, 8)), declaredSize = dataSize)
        val body = before + fmtChunk + dataHeader + after
        return ByteArrayOutputStream().also {
            it.write("RIFF".toByteArray(Charsets.US_ASCII))
            it.writeLe32(declaredRiffSize ?: (4 + body.size).toLong())
            it.write("WAVE".toByteArray(Charsets.US_ASCII))
            it.write(body)
        }.toByteArray()
    }

    private fun chunk(id: String, payload: ByteArray, declaredSize: Int = payload.size): ByteArray = ByteArrayOutputStream().also {
        it.write(id.toByteArray(Charsets.US_ASCII))
        it.writeLe32(declaredSize)
        it.write(payload)
        if (declaredSize % 2 != 0) it.write(0)
    }.toByteArray()

    private fun ByteArrayOutputStream.writeLe16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeLe32(value: Int) {
        writeLe32(value.toLong())
    }

    private fun ByteArrayOutputStream.writeLe32(value: Long) {
        repeat(4) { write(((value ushr (it * 8)) and 0xff).toInt()) }
    }

    private class SplitInput(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        val closes = AtomicInteger()
        override fun read(): Int = delegate.read()
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            if (length == 0) 0 else delegate.read(bytes, offset, 1)

        override fun close() {
            closes.incrementAndGet()
            delegate.close()
        }
    }

    private class CountingInput(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        val bytesRead = AtomicInteger()
        val closes = AtomicInteger()

        override fun read(): Int = delegate.read().also { if (it >= 0) bytesRead.incrementAndGet() }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            delegate.read(bytes, offset, length).also { if (it > 0) bytesRead.addAndGet(it) }

        override fun close() {
            closes.incrementAndGet()
            delegate.close()
        }
    }
}
