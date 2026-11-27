package tech.musxi.player.localaudio

import java.io.InputStream

internal data class LocalSourceFormat(
    val codec: String,
    val sampleRateHz: Int?,
    val bitsPerSample: Int?,
    val channelCount: Int?,
)

/**
 * Reads only enough container metadata to identify a local source's declared audio format.
 * It never infers a format from a filename and does not decode or validate the audio payload.
 */
internal object LocalAudioFormatInspector {
    fun inspect(open: () -> InputStream): LocalSourceFormat? = open().use { source ->
        val input = BoundedInput(source, MAX_READ_BYTES)
        val first = input.readBytes(12) ?: return null
        when {
            first.copyOfRange(0, 4).contentEquals(byteArrayOf(68, 83, 68, 32)) -> {
                val rest = input.readBytes(80) ?: return null
                val info = try { tech.musxi.core.localaudio.DsfFileParser.parse(first + rest) }
                    catch (_: tech.musxi.core.localaudio.DsfParseFailure) { return null }
                LocalSourceFormat("dsd", info.sampleRateHz, 1, info.channelCount)
            }
            first.copyOfRange(0, FLAC_MAGIC.size).contentEquals(FLAC_MAGIC) -> flac(first, input)
            first.size == RIFF_HEADER_BYTES && first.startsWith(RIFF_MAGIC) &&
                first.copyOfRange(8, 12).contentEquals(WAVE_MAGIC) -> wav(WordInput(input), first)
            else -> null
        }
    }

    private fun flac(first: ByteArray, input: ByteInput): LocalSourceFormat? {
        val header = first.copyOf(FLAC_PROBE_BYTES)
        val remaining = input.readBytes(FLAC_PROBE_BYTES - first.size) ?: return null
        remaining.copyInto(header, first.size)
        if (!blockHeaderValid(header)) return null
        val sampleRate = ((header[18].toInt() and 0xff) shl 12) or
            ((header[19].toInt() and 0xff) shl 4) or
            ((header[20].toInt() and 0xff) ushr 4)
        val channels = ((header[20].toInt() ushr 1) and 7) + 1
        val bitsPerSample = (((header[20].toInt() and 1) shl 4) or
            ((header[21].toInt() and 0xff) ushr 4)) + 1
        if (sampleRate !in 1..MAX_SAMPLE_RATE || bitsPerSample !in 4..32) return null
        return LocalSourceFormat("flac", sampleRate, bitsPerSample, channels)
    }

    private fun wav(input: WordInput, initial: ByteArray): LocalSourceFormat? {
        val riffSize = le32(initial, 4)
        if (riffSize < RIFF_PAYLOAD_MIN_BYTES) return null
        val riffEnd = RIFF_PREFIX_BYTES.toLong() + riffSize
        var fmt: WaveFormat? = null
        val checked = mutableSetOf<Long>()
        var chunks = 0
        while (input.position < riffEnd) {
            if (!checked.add(input.position)) return null
            if (++chunks > MAX_WAVE_CHUNKS) return null
            if (input.position + CHUNK_HEADER_BYTES > riffEnd) return null
            if (input.position + CHUNK_HEADER_BYTES > input.limit) return null
            val id = input.readAscii(4) ?: return null
            val size = input.readLe32() ?: return null
            val paddedEnd = try {
                Math.addExact(input.position, Math.addExact(size, size and 1L))
            } catch (_: ArithmeticException) {
                return null
            }
            if (paddedEnd > riffEnd) return null
            when (id) {
                "fmt " -> {
                    if (fmt != null || size != WAVE_FMT_BYTES) return null
                    val bytes = input.readBytes(WAVE_FMT_BYTES.toInt()) ?: return null
                    fmt = waveFormat(bytes) ?: return null
                }
                "data" -> {
                    if (fmt == null) return null
                    if (size == 0L) return null
                    return fmt.format
                }
            }
            if (input.position < paddedEnd && !input.moveTo(paddedEnd)) return null
        }
        return null
    }

    private fun blockHeaderValid(header: ByteArray): Boolean {
        if (header.size != FLAC_PROBE_BYTES) return false
        val type = header[4].toInt() and 0x7f
        val length = ((header[5].toInt() and 0xff) shl 16) or
            ((header[6].toInt() and 0xff) shl 8) or (header[7].toInt() and 0xff)
        return type == FLAC_STREAMINFO_TYPE && length == FLAC_STREAMINFO_BYTES
    }

    private fun waveFormat(bytes: ByteArray): WaveFormat? {
        val encoding = le16(bytes, 0)
        val channels = le16(bytes, 2)
        val sampleRate = le32(bytes, 4)
        val byteRate = le32(bytes, 8)
        val blockAlign = le16(bytes, 12)
        val bitsPerSample = le16(bytes, 14)
        val codec = when {
            encoding == WAVE_FORMAT_PCM -> when (bitsPerSample) {
                8 -> "pcm_u8"
                16 -> "pcm_s16le"
                24 -> "pcm_s24le"
                32 -> "pcm_s32le"
                else -> return null
            }
            encoding == WAVE_FORMAT_IEEE_FLOAT -> when (bitsPerSample) {
                32 -> "pcm_f32le"
                64 -> "pcm_f64le"
                else -> return null
            }
            else -> return null
        }
        if (channels !in 1..8 || sampleRate !in 1..MAX_SAMPLE_RATE) return null
        val bytesPerSample = bitsPerSample / 8
        if (blockAlign.toLong() != channels.toLong() * bytesPerSample) return null
        if (byteRate != sampleRate * blockAlign) return null
        return WaveFormat(LocalSourceFormat(codec, sampleRate.toInt(), bitsPerSample, channels))
    }

    private interface ByteInput {
        val position: Long
        val limit: Long
        fun readBytes(count: Int): ByteArray?
        fun moveTo(position: Long): Boolean
    }

    private class BoundedInput(
        private val source: InputStream,
        private val maximumBytes: Int,
    ) : ByteInput {
        private var consumed = 0
        override val position: Long get() = consumed.toLong()
        override val limit: Long get() = maximumBytes.toLong()

        override fun readBytes(count: Int): ByteArray? {
            if (count <= 0 || consumed + count > maximumBytes) return null
            val available = count
            val bytes = ByteArray(available)
            var offset = 0
            while (offset < available) {
                val read = source.read(bytes, offset, available - offset)
                if (read < 0) {
                    consumed += offset
                    return null
                }
                if (read == 0) {
                    val one = source.read()
                    if (one < 0) {
                        consumed += offset
                        return null
                    }
                    bytes[offset++] = one.toByte()
                } else {
                    offset += read
                }
            }
            consumed += offset
            return bytes
        }

        override fun moveTo(position: Long): Boolean {
            if (position < consumed || position > maximumBytes) return false
            var remaining = position - consumed
            val scratch = ByteArray(8 * 1024)
            while (remaining > 0) {
                val count = minOf(remaining, scratch.size.toLong()).toInt()
                val bytes = readBytes(count) ?: return false
                if (bytes.size != count) return false
                remaining -= count
            }
            return true
        }
    }

    private class WordInput(private val input: ByteInput) {
        val position: Long get() = input.position
        val limit: Long get() = input.limit
        fun readAscii(count: Int) = input.readBytes(count)?.toString(Charsets.US_ASCII)
        fun readLe32(): Long? = input.readBytes(4)?.let { le32(it, 0) }
        fun readBytes(count: Int) = input.readBytes(count)
        fun moveTo(position: Long) = input.moveTo(position)
    }

    private data class WaveFormat(val format: LocalSourceFormat)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun le16(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private const val MAX_READ_BYTES = 256 * 1024
    private const val MAX_SAMPLE_RATE = 655_350
    private const val RIFF_HEADER_BYTES = 12
    private const val RIFF_PREFIX_BYTES = 8
    private const val RIFF_PAYLOAD_MIN_BYTES = 4L
    private const val CHUNK_HEADER_BYTES = 8L
    private const val WAVE_FMT_BYTES = 16L
    private const val MAX_WAVE_CHUNKS = 4_096
    private const val FLAC_PROBE_BYTES = 42
    private const val FLAC_STREAMINFO_BYTES = 34
    private const val FLAC_STREAMINFO_TYPE = 0
    private const val WAVE_FORMAT_PCM = 1
    private const val WAVE_FORMAT_IEEE_FLOAT = 3
    private val FLAC_MAGIC = "fLaC".toByteArray(Charsets.US_ASCII)
    private val RIFF_MAGIC = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WAVE_MAGIC = "WAVE".toByteArray(Charsets.US_ASCII)
}
