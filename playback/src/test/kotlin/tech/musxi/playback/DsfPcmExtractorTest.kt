package tech.musxi.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.musxi.core.localaudio.DsfFileParser

/** Container, sample-count, seek, isolation and truncation regressions for the gated extractor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class DsfPcmExtractorTest {
    @Test fun factoryIsOffByDefaultAndNeverAddsDsfForNetworkStreams() {
        assertFalse(DsfExtractorsFactory(false).createExtractors(Uri.parse("content://audio/one"), emptyMap()).any { it is DsfPcmExtractor })
        assertFalse(DsfExtractorsFactory(true).createExtractors(Uri.parse("https://audio/one"), emptyMap()).any { it is DsfPcmExtractor })
        assertTrue(DsfExtractorsFactory(true).createExtractors(Uri.parse("musxi-local://ticket/one"), emptyMap()).first() is DsfPcmExtractor)
    }

    @Test fun oneBlockProducesBoundedFloatPcmWithOriginalDurationAndNoPadding() {
        val fixture = source(samples = 32769)
        val out = Output()
        val extractor = DsfPcmExtractor(); extractor.init(out)
        run(extractor, fixture)
        assertEquals("audio/raw", out.format!!.sampleMimeType)
        assertEquals(C.ENCODING_PCM_FLOAT, out.format!!.pcmEncoding)
        assertEquals(176400, out.format!!.sampleRate)
        assertEquals(32769 / 16 * 2 * 4, out.bytes.size())
        assertEquals(0L, out.timestamps.first())
        assertEquals(DsfPcmExtractor.frameTimeUs(32769L / 16), out.map!!.durationUs)
        val floats = ByteBuffer.wrap(out.bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        while (floats.hasRemaining()) assertTrue(floats.get().isFinite())
    }

    @Test fun seekWarmsPreviousBlockAndMatchesContinuousDecodeAfterTarget() {
        val fixture = source(samples = 32768L * 7)
        val full = Output(); DsfPcmExtractor().also { it.init(full); run(it, fixture) }
        val sought = Output(); val extractor = DsfPcmExtractor(); extractor.init(sought)
        // Initialize tracks and the first block, then simulate Media3's seek.
        val input = input(fixture, 0)
        extractor.read(input, PositionHolder())
        sought.bytes.reset(); sought.timestamps.clear()
        val timeUs = 40_000L
        val point = sought.map!!.getSeekPoints(timeUs).first
        extractor.seek(point.position, timeUs)
        run(extractor, fixture, point.position)
        val frame = timeUs * 176400 / 1_000_000
        val expected = full.bytes.toByteArray().copyOfRange((frame * 8).toInt(), full.bytes.size())
        assertArrayEquals(expected, sought.bytes.toByteArray())
        assertEquals(DsfPcmExtractor.frameTimeUs(frame), sought.timestamps.first())
    }

    @Test fun seekMapKeepsLongOffsetsAndClampsEnd() {
        val fixture = source(samples = 22_937_600_000L, headerOnly = true)
        val info = DsfFileParser.parse(fixture)
        val map = DsfPcmSeekMap(info)
        val point = map.plan(7_000_000_000L)
        assertTrue(point.offset > 0x1_0000_0000L)
        assertTrue(point.offset < info.fileSize)
        assertEquals(info.sampleCount / 16, map.plan(Long.MAX_VALUE).firstFrame)
        assertEquals(0L, map.plan(-1).firstFrame)
    }

    @Test fun sniffRecognizesMagicWithoutConsumingInputAndBadHeaderFailsRead() {
        val fixture = source(samples = 32768)
        val reader = input(fixture, 0)
        val extractor = DsfPcmExtractor(); extractor.init(Output())
        assertTrue(extractor.sniff(reader)); assertEquals(0L, reader.position)
        fixture[28] = 0
        assertThrows(java.io.IOException::class.java) { run(extractor, fixture) }
    }

    @Test fun truncatedPayloadFailsWithoutReadingTagsAsAudio() {
        val fixture = source(samples = 32768)
        val truncated = fixture.copyOf(fixture.size - 20)
        val extractor = DsfPcmExtractor(); extractor.init(Output())
        assertThrows(java.io.IOException::class.java) { run(extractor, truncated, declaredSize = fixture.size.toLong()) }
    }

    private fun run(extractor: DsfPcmExtractor, bytes: ByteArray, start: Long = 0, declaredSize: Long = bytes.size.toLong()) {
        var input = input(bytes, start, declaredSize)
        val holder = PositionHolder()
        repeat(100) {
            when (extractor.read(input, holder)) {
                Extractor.RESULT_END_OF_INPUT -> return
                Extractor.RESULT_SEEK -> input = input(bytes, holder.position, declaredSize)
            }
        }
        error("Extractor failed to finish in bounded reads")
    }
    private fun input(bytes: ByteArray, start: Long, length: Long = bytes.size.toLong()): ExtractorInput {
        var cursor = start.toInt()
        val reader = DataReader { buffer, offset, count ->
            if (cursor >= bytes.size) -1 else minOf(count, bytes.size - cursor).also {
                bytes.copyInto(buffer, offset, cursor, cursor + it); cursor += it
            }
        }
        return DefaultExtractorInput(reader, start, length)
    }

    private fun source(samples: Long, headerOnly: Boolean = false): ByteArray {
        val payload = ((samples - 1) / 32768 + 1) * 8192
        val header = ByteBuffer.allocate(92).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("DSD ".toByteArray()); putLong(28); putLong(92 + payload); putLong(0)
            put("fmt ".toByteArray()); putLong(52); putInt(1); putInt(0); putInt(2); putInt(2)
            putInt(2822400); putInt(1); putLong(samples); putInt(4096); putInt(0)
            put("data".toByteArray()); putLong(12 + payload)
        }.array()
        return if (headerOnly) header else header + ByteArray(payload.toInt()) { (it * 73 + it / 8192).toByte() }
    }

    private class Output : ExtractorOutput, TrackOutput {
        var format: Format? = null
        var map: SeekMap? = null
        val bytes = ByteArrayOutputStream()
        val timestamps = mutableListOf<Long>()
        override fun track(id: Int, type: Int): TrackOutput = this
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) { map = seekMap }
        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int = error("Unused")
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val buffer = ByteArray(length); data.readBytes(buffer, 0, length); bytes.write(buffer)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            timestamps += timeUs
        }
    }
}
