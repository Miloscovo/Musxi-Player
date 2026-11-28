package tech.musxi.playback

import org.junit.Assert.*
import org.junit.Test

/** Converter regressions, including scalar partial-bit and byte fast paths. */
class DsdPcmDecoderTest {
    @Test fun constantBitsSettleAtUnityWithIndependentStereoChannels() {
        val decoder = DsdPcmDecoder(11_289_600, 2, true)
        val block = ByteArray(8192) { if (it < 4096) 0xff.toByte() else 0 }
        val frames = decode(decoder, block)
        frames.drop(decoder.latencyFrames * 2 + 2).forEach {
            assertEquals(1f, it[0], 1e-5f)
            assertEquals(-1f, it[1], 1e-5f)
        }
    }

    @Test fun alternatingBitsAreSuppressedAfterFilterSettles() {
        val decoder = DsdPcmDecoder(11_289_600, 1, true)
        val frames = decode(decoder, ByteArray(4096) { 0x55 })
        frames.drop(decoder.latencyFrames * 2 + 2).forEach { assertTrue(kotlin.math.abs(it[0]) < 1e-3f) }
    }

    @Test fun lsbAndReversedMsbSourcesProduceIdenticalFrames() {
        val bytes = ByteArray(8192) { (it * 73 + it / 4096).toByte() }
        val reversed = ByteArray(bytes.size) { Integer.reverse(bytes[it].toInt() and 255).ushr(24).toByte() }
        val left = decode(DsdPcmDecoder(5_644_800, 2, true), bytes)
        val right = decode(DsdPcmDecoder(5_644_800, 2, false), reversed)
        assertFramesEqual(left, right)
    }

    @Test fun partialInputRetainsPhaseAndHistoryAcrossCalls() {
        val bytes = ByteArray(4096) { (it * 31 + 5).toByte() }
        val whole = decode(DsdPcmDecoder(2_822_400, 1, true), bytes)
        val decoder = DsdPcmDecoder(2_822_400, 1, true)
        val split = mutableListOf<FloatArray>()
        // Repack each source bit range at bit zero, as required by decodeBlock's contract.
        val lengths = listOf(4093, 16007, 12668)
        var start = 0
        for (length in lengths) {
            val fragment = ByteArray(4096)
            for (bit in 0 until length) {
                val original = start + bit
                if ((bytes[original / 8].toInt() ushr (original % 8)) and 1 != 0)
                    fragment[bit / 8] = (fragment[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
            }
            decoder.decodeBlock(fragment, length) { split += it.copyOf() }
            start += length
        }
        assertFramesEqual(whole, split)
    }

    @Test fun resetRestoresBitPhaseAndFilterState() {
        val bytes = ByteArray(8192) { (it * 17).toByte() }
        val decoder = DsdPcmDecoder(5_644_800, 2, true)
        val before = decode(decoder, bytes)
        decoder.decodeBlock(bytes, 7) {}
        decoder.drain {}
        decoder.reset()
        assertFramesEqual(before, decode(decoder, bytes))
    }

    @Test fun outputCountsAndGroupDelaysMatchEachSupportedRate() {
        for ((rate, ratio, delay) in listOf(Triple(2_822_400, 16, 24), Triple(5_644_800, 32, 28), Triple(11_289_600, 64, 30))) {
            val decoder = DsdPcmDecoder(rate, 2, true)
            assertEquals(ratio, decoder.decimation); assertEquals(delay, decoder.latencyFrames)
            assertEquals(32768 / ratio, decode(decoder, ByteArray(8192)).size)
            var tail = 0
            decoder.drain { tail++; assertTrue(it.all(Float::isFinite)) }
            assertEquals(delay, tail)
        }
    }

    @Test fun drainUsesFilterZeroAndIsIdempotentUntilReset() {
        val decoder = DsdPcmDecoder(11_289_600, 1, true)
        var count = 0
        decoder.drain { count++; assertEquals(0f, it[0], 0f) }
        assertEquals(30, count)
        decoder.drain { count++ }
        assertEquals(30, count)
        assertThrows(IllegalStateException::class.java) { decoder.decodeBlock(ByteArray(4096), 0) {} }
        decoder.reset()
        assertEquals(0, decode(decoder, ByteArray(4096), 8).size)
    }

    @Test fun invalidParametersAndOversizedBlocksAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { DsdPcmDecoder(44100, 2, true) }
        assertThrows(IllegalArgumentException::class.java) { DsdPcmDecoder(2822400, 3, true) }
        val decoder = DsdPcmDecoder(2822400, 2, true)
        assertThrows(IllegalArgumentException::class.java) { decoder.decodeBlock(ByteArray(4096), 0) {} }
        assertThrows(IllegalArgumentException::class.java) { decoder.decodeBlock(ByteArray(8192), 32769) {} }
        assertThrows(IllegalArgumentException::class.java) { decoder.decodeBlock(ByteArray(8192), -1) {} }
    }

    private fun decode(decoder: DsdPcmDecoder, bytes: ByteArray, valid: Int = 32768): List<FloatArray> {
        val frames = mutableListOf<FloatArray>()
        decoder.decodeBlock(bytes, valid) { frames += it.copyOf() }
        return frames
    }
    private fun assertFramesEqual(expected: List<FloatArray>, actual: List<FloatArray>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (a, b) -> assertArrayEquals(a, b, 0f) }
    }
}
