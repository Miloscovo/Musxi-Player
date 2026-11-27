package tech.musxi.player

import android.content.Intent
import android.os.Debug
import android.os.SystemClock
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in CPU diagnostic. Optional existing Activity host; no playback or audio-library mutation. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class DsfPipelineIsolationDeviceTest {
    @Test fun collectIsolatedPipelineTimings() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allowDsfIsolation") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activityHost = args.getString("dsfIsolationActivityHost") == "true"
        val seconds = (args.getString("dsfIsolationSeconds")?.toIntOrNull() ?: 2).also { require(it in 1..8) }
        val requestedRate = args.getString("dsfIsolationRate")?.toIntOrNull()
        val rates = intArrayOf(2_822_400, 5_644_800, 11_289_600).filter { requestedRate == null || it == requestedRate }
        require(rates.isNotEmpty())
        if (activityHost) instrumentation.runOnMainSync {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        val report = File(context.filesDir, "dsf-pipeline-isolation-device.json")
        val rows = JSONArray()
        val out = JSONObject().put("status", "running").put("rows", rows)
            .put("startedAtMs", System.currentTimeMillis()).put("pid", android.os.Process.myPid())
            .put("uid", android.os.Process.myUid()).put("model", android.os.Build.MODEL)
            .put("activityHost", activityHost)
            .put("input", "deterministic_in_memory_blocks").put("productionPlaybackAcceptance", false)
        fun save() = report.writeText(out.toString(2))
        save()
        try {
            var state = 0x35a71e29
            val blocks = Array(64) { ByteArray(8192) {
                state = state xor (state shl 13); state = state xor (state ushr 17); state = state xor (state shl 5)
                state.toByte()
            } }
            kernel(blocks, 11_289_600, 1)
            extractor(blocks, 11_289_600, 1)
            for (rate in rates) {
                rows.put(kernel(blocks, rate, seconds)); save()
                rows.put(extractor(blocks, rate, seconds)); save()
            }
            out.put("status", "completed")
        } catch (error: Throwable) {
            out.put("status", "failed").put("failureType", error.javaClass.simpleName)
            throw error
        } finally {
            out.put("finishedAtMs", System.currentTimeMillis()); save()
        }
    }

    private fun kernel(blocks: Array<ByteArray>, rate: Int, seconds: Int): JSONObject {
        // Kotlin internal playback types remain encapsulated; reflection is limited to this test.
        val type = Class.forName("tech.musxi.playback.DsdPcmDecoder")
        val decoder = type.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType).newInstance(rate, 2, true)
        val decode = type.getMethod("decodeBlock", ByteArray::class.java, Int::class.javaPrimitiveType,
            Function1::class.java)
        val count = (seconds.toLong() * rate + 32767) / 32768
        var frames = 0L
        var checksum = 0.0
        val consume: (FloatArray) -> Unit = { frame -> frames++; checksum += frame[0] + frame[1] }
        val cpu = Debug.threadCpuTimeNanos()
        val wall = SystemClock.elapsedRealtimeNanos()
        repeat(count.toInt()) { decode.invoke(decoder, blocks[it % blocks.size], 32768, consume) }
        val row = timing("kernel_only", rate, count * 32768.0 / rate, wall, cpu)
        assertEquals(count * (32768 / (rate / 176400)), frames)
        assertTrue(checksum.isFinite())
        return row.put("frames", frames).put("checksum", checksum)
    }

    private fun extractor(blocks: Array<ByteArray>, rate: Int, seconds: Int): JSONObject {
        val samples = seconds.toLong() * rate
        val payload = ((samples + 32767) / 32768) * 8192
        val size = 92 + payload
        val header = ByteBuffer.allocate(92).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(68, 83, 68, 32)); putLong(28); putLong(size); putLong(0)
            put(byteArrayOf(102, 109, 116, 32)); putLong(52); putInt(1); putInt(0); putInt(2); putInt(2)
            putInt(rate); putInt(1); putLong(samples); putInt(4096); putInt(0)
            put(byteArrayOf(100, 97, 116, 97)); putLong(12 + payload)
        }.array()
        var cursor = 0L
        val reader = DataReader { target, offset, requested ->
            if (cursor >= size) -1 else {
                val count = minOf(requested.toLong(), size - cursor).toInt()
                var written = 0
                while (written < count) {
                    val take = if (cursor < 92) {
                        minOf(count - written, (92 - cursor).toInt()).also {
                            header.copyInto(target, offset + written, cursor.toInt(), cursor.toInt() + it)
                        }
                    } else {
                        val relative = cursor - 92
                        val at = (relative % 8192).toInt()
                        minOf(count - written, 8192 - at).also {
                            blocks[((relative / 8192) % blocks.size).toInt()].copyInto(target,
                                offset + written, at, at + it)
                        }
                    }
                    check(take > 0) { "Diagnostic source made no progress" }
                    cursor += take; written += take
                }
                count
            }
        }
        val output = DiscardOutput()
        val extractor = Class.forName("tech.musxi.playback.DsfPcmExtractor")
            .getConstructor().newInstance() as Extractor
        extractor.init(output)
        var input = DefaultExtractorInput(reader, 0, size)
        val holder = PositionHolder()
        val cpu = Debug.threadCpuTimeNanos()
        val wall = SystemClock.elapsedRealtimeNanos()
        try {
            var reads = 0L
            while (true) {
                check(++reads <= payload / 8192 + 4) { "Diagnostic extractor exceeded bounded reads" }
                when (extractor.read(input, holder)) {
                    Extractor.RESULT_END_OF_INPUT -> break
                    Extractor.RESULT_SEEK -> {
                        cursor = holder.position
                        input = DefaultExtractorInput(reader, cursor, size)
                    }
                }
            }
            val row = timing("actual_extractor_memory_source_null_track", rate, seconds.toDouble(), wall, cpu)
            assertEquals(samples / (rate / 176400) * 8, output.bytes)
            return row.put("pcmBytes", output.bytes).put("packets", output.packets)
        } finally { extractor.release() }
    }

    private fun timing(mode: String, rate: Int, source: Double, wall: Long, cpu: Long): JSONObject {
        val elapsed = (SystemClock.elapsedRealtimeNanos() - wall) / 1e9
        return JSONObject().put("mode", mode).put("rateHz", rate).put("sourceSeconds", source)
            .put("wallSeconds", elapsed).put("threadCpuSeconds", (Debug.threadCpuTimeNanos() - cpu) / 1e9)
            .put("sourceSecondsPerWallSecond", source / elapsed)
    }

    private class DiscardOutput : ExtractorOutput, TrackOutput {
        var bytes = 0L
        var packets = 0L
        override fun track(id: Int, type: Int): TrackOutput = this
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
        override fun format(format: Format) = Unit
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            error("Unexpected stream callback")
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { bytes += length }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int,
            cryptoData: TrackOutput.CryptoData?) { packets++ }
    }
}
