package tech.musxi.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import tech.musxi.core.localaudio.DsfPlaybackSupport

/**
 * Experimental, bounded DSF bitstream to float PCM converter.
 *
 * A byte lookup evaluates the first FIR without expanding its 257 taps for every output.
 * Numerical comparisons and isolated timings do not replace sustained playback acceptance.
 * Keep this converter experimental; source/rate recognition is not playback acceptance.
 */
internal class DsdPcmDecoder(
    val sourceRateHz: Int,
    val channels: Int,
    val leastSignificantBitFirst: Boolean,
) {
    val outputRateHz: Int = OUTPUT_RATE_HZ
    val decimation: Int
    val latencyFrames: Int
    private val firstStages: Array<ByteFir>
    private val halfBandLines: Array<Array<Ring>>
    private val halfBandPhase: Array<IntArray>
    private val frame = FloatArray(channels)
    private var upstreamPhase = 0
    private var drained = false

    init {
        require(DsfPlaybackSupport.supportsFormat(sourceRateHz, channels)) { "Unsupported DSD source format" }
        decimation = sourceRateHz / OUTPUT_RATE_HZ
        val halfBandCount = when (decimation) {
            16 -> 1
            32 -> 2
            64 -> 3
            else -> error("Unsupported DSD decimation")
        }
        latencyFrames = (FIR_DELAY + halfBandDelaySourceSamples(halfBandCount)) / decimation
        firstStages = Array(channels) { ByteFir() }
        halfBandLines = Array(halfBandCount) {
            val coefficients = createLowPass(HALF_BAND_TAPS, HALF_BAND_CUTOFF)
            Array(channels) { Ring(coefficients) }
        }
        halfBandPhase = Array(halfBandCount) { IntArray(channels) }
    }

    /**
     * Consumes bits zero through [validSamples] - 1 from every channel byte region in [block].
     * Every call starts at bit zero even when a short final block follows a full one.
     */
    fun decodeBlock(block: ByteArray, validSamples: Int, emit: (FloatArray) -> Unit) {
        check(!drained) { "Reset decoder after draining" }
        require(block.size == DSF_BLOCK_BYTES * channels) { "Invalid DSF block group size" }
        require(validSamples in 0..DSF_BLOCK_BITS) { "Invalid valid sample count" }
        var sample = 0
        // DSF block boundaries are byte-aligned. Keep the scalar path for partial-call tests/tail.
        if (upstreamPhase % 8 == 0) {
            while (sample + 8 <= validSamples) {
                for (channel in 0 until channels) {
                    val encoded = block[channel * DSF_BLOCK_BYTES + sample / 8].toInt() and 255
                    firstStages[channel].pushByte(if (leastSignificantBitFirst) REVERSE_BITS[encoded] else encoded)
                    pushHalfBands(channel, firstStages[channel].filter())
                }
                upstreamPhase += 8
                if (upstreamPhase == decimation) { upstreamPhase = 0; emit(frame) }
                sample += 8
            }
        }
        while (sample < validSamples) {
            val byteIndex = sample ushr 3
            val bitIndex = sample and 7
            val shift = if (leastSignificantBitFirst) bitIndex else 7 - bitIndex
            for (channel in 0 until channels) pushChannel(channel, decodedBit(block, channel, byteIndex, shift))
            emitIfReady(emit)
            sample++
        }
    }

    /**
     * Feeds filter-zero PCM-domain tail samples, not -1 DSD bits. This advances the bit timeline
     * by [latencyFrames] output periods and recovers the delayed final PCM frames once.
     */
    fun drain(emit: (FloatArray) -> Unit) {
        if (drained) return
        drained = true
        repeat(latencyFrames) {
            repeat(decimation) { decodeFilterSample(0f, emit) }
        }
    }

    fun reset() {
        firstStages.forEach { it.clear() }
        halfBandLines.forEach { stage -> stage.forEach { it.clear() } }
        halfBandPhase.forEach { it.fill(0) }
        frame.fill(0f)
        upstreamPhase = 0
        drained = false
    }

    private fun decodeFilterSample(bit: Float, emit: (FloatArray) -> Unit) {
        for (channel in 0 until channels) pushChannel(channel, bit)
        emitIfReady(emit)
    }

    private fun pushChannel(channel: Int, sample: Float) {
        val first = firstStages[channel]
        if (!first.push(sample)) return
        pushHalfBands(channel, first.filter())
    }

    private fun pushHalfBands(channel: Int, first: Float) {
        var value = first
        for (stage in halfBandLines.indices) {
            val line = halfBandLines[stage][channel]
            line.push(value)
            if (++halfBandPhase[stage][channel] != 2) return
            halfBandPhase[stage][channel] = 0
            value = line.filter()
        }
        frame[channel] = value
    }

    /** The callback consumes this reusable frame immediately; callers retain only a copy. */
    private fun emitIfReady(emit: (FloatArray) -> Unit) {
        if (++upstreamPhase == decimation) {
            upstreamPhase = 0
            emit(frame)
        }
    }

    private fun decodedBit(block: ByteArray, channel: Int, byteIndex: Int, shift: Int): Float {
        val offset = channel * DSF_BLOCK_BYTES + byteIndex
        return if ((block[offset].toInt() ushr shift) and 1 == 1) 1f else -1f
    }

    private class Ring(val coefficients: DoubleArray) {
        private val samples = FloatArray(HALF_BAND_TAPS * 2)
        private var write = HALF_BAND_TAPS - 1

        fun push(value: Float) {
            val at = write
            samples[at] = value
            samples[at + HALF_BAND_TAPS] = value
            write = if (at == 0) HALF_BAND_TAPS - 1 else at - 1
        }

        fun filter(): Float {
            var total = 0.0
            val start = write + 1
            // Keep hot accesses local even when the runtime does not inline property getters.
            val weights = coefficients
            val history = samples
            // Preserve symmetric-pair order and double accumulation exactly.
            for (index in 0 until HALF_BAND_DELAY) {
                total += weights[index] * (history[start + index].toDouble() +
                    history[start + HALF_BAND_TAPS - 1 - index].toDouble())
            }
            total += weights[HALF_BAND_DELAY] * history[start + HALF_BAND_DELAY]
            return total.toFloat()
        }

        fun clear() {
            samples.fill(0f)
            write = HALF_BAND_TAPS - 1
        }
    }

    /** Bits and validity are separate: reset/drain zeros are filter zeros, not DSD -1 bits. */
    private class ByteFir {
        private val words = IntArray(66)
        private val valid = IntArray(66)
        private var newest = 0
        private var word = 0
        private var mask = 0
        private var phase = 0
        private var fullWords = 0
        fun push(value: Float): Boolean {
            word = ((word shl 1) or if (value > 0) 1 else 0) and 255
            mask = ((mask shl 1) or if (value != 0f) 1 else 0) and 255
            if (++phase != 8) return false
            phase = 0
            store(word, mask)
            word = 0; mask = 0
            return true
        }
        fun pushByte(value: Int) {
            check(phase == 0)
            store(value, 255)
        }
        private fun store(value: Int, validity: Int) {
            newest = if (newest == 0) 32 else newest - 1
            words[newest] = value; words[newest + 33] = value
            valid[newest] = validity; valid[newest + 33] = validity
            fullWords = if (validity == 255) (fullWords + 1).coerceAtMost(33) else 0
        }
        fun filter(): Float {
            var sum = 0.0
            // Nested companion access otherwise adds calls for every one of the 32 byte groups.
            val signed = FIRST_SIGNED
            val coefficients = FIRST_COEFFICIENTS
            if (fullWords == 33) {
                for (group in 0 until 32) sum += signed[group * 256 + words[newest + group]]
                sum += coefficients[256] * if (words[newest + 32] and 1 != 0) 1.0 else -1.0
                return sum.toFloat()
            }
            val positive = FIRST_POSITIVE
            for (group in 0 until 32) {
                val index = newest + group
                val mask = valid[index]
                if (mask == 255) sum += signed[group * 256 + words[index]]
                else if (mask != 0) sum += 2 * positive[group][words[index] and mask] - positive[group][mask]
            }
            val index = newest + 32
            if (valid[index] and 1 != 0) sum += coefficients[256] * if (words[index] and 1 != 0) 1.0 else -1.0
            return sum.toFloat()
        }
        fun clear() { words.fill(0); valid.fill(0); newest = 0; word = 0; mask = 0; phase = 0; fullWords = 0 }
    }

    private companion object {
        const val OUTPUT_RATE_HZ = DsfPlaybackSupport.PCM_RATE_HZ
        const val DSF_BLOCK_BYTES = 4_096
        const val DSF_BLOCK_BITS = 32_768
        const val FIR_TAPS = 257
        const val HALF_BAND_TAPS = 65
        const val FIRST_STAGE_CUTOFF = 0.045
        const val HALF_BAND_CUTOFF = 0.225
        const val FIRST_STAGE_DECIMATION = 8
        const val FIR_DELAY = (FIR_TAPS - 1) / 2
        const val HALF_BAND_DELAY = (HALF_BAND_TAPS - 1) / 2
        val REVERSE_BITS = IntArray(256) { Integer.reverse(it).ushr(24) }
        val FIRST_COEFFICIENTS = createLowPass(FIR_TAPS, FIRST_STAGE_CUTOFF)
        val FIRST_POSITIVE = Array(32) { group -> DoubleArray(256) { word ->
            (0 until 8).sumOf { bit -> if (word and (1 shl bit) != 0) FIRST_COEFFICIENTS[group * 8 + bit] else 0.0 }
        } }
        val FIRST_SIGNED = DoubleArray(32 * 256) { index ->
            val group = index / 256; val word = index % 256
            2 * FIRST_POSITIVE[group][word] - FIRST_POSITIVE[group][255]
        }

        fun halfBandDelaySourceSamples(stageCount: Int): Int =
            (0 until stageCount).sumOf {
                HALF_BAND_DELAY * FIRST_STAGE_DECIMATION * (1 shl it)
            }

        /** Linear-phase DC-normalised sinc; this construction is local, not copied coefficients. */
        fun createLowPass(taps: Int, cutoff: Double): DoubleArray {
            val center = (taps - 1) / 2.0
            val coefficients = DoubleArray(taps)
            var sum = 0.0
            for (index in 0 until taps) {
                val offset = index - center
                val sinc = if (offset == 0.0) 2.0 * cutoff
                    else sin(2.0 * PI * cutoff * offset) / (PI * offset)
                val window = 0.42 - 0.5 * cos(2.0 * PI * index / (taps - 1)) +
                    0.08 * cos(4.0 * PI * index / (taps - 1))
                coefficients[index] = sinc * window
                sum += coefficients[index]
            }
            for (index in coefficients.indices) coefficients[index] /= sum
            return coefficients
        }
    }
}
