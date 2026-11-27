package tech.musxi.core.localaudio

enum class DsfParseError {
    INVALID_HEADER,
    UNSUPPORTED_FORMAT,
    TRUNCATED,
}

class DsfParseFailure(val reason: DsfParseError) : IllegalArgumentException("DSF: ${reason.name}")

data class DsfBlockPosition(
    /** Absolute position of the complete channel block group. */
    val blockOffset: Long,
    /** First sample of that group, before any filter warm-up or requested sample discard. */
    val sampleIndex: Long,
    val samplesToDiscard: Long,
)

/** Validated container declarations, not proof that the audio payload can be decoded. */
data class DsfFileInfo internal constructor(
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val sampleCount: Long,
    val blockSizePerChannel: Int,
    val fileSize: Long,
    val dataOffset: Long,
    val dataSize: Long,
    val metadataOffset: Long?,
    val leastSignificantBitFirst: Boolean = true,
) {
    val durationMs: Long
        get() = sampleCount / sampleRateHz * 1_000 +
            sampleCount % sampleRateHz * 1_000 / sampleRateHz

    val dsdMultiplier: Int?
        get() = when (sampleRateHz) {
            2_822_400 -> 64
            5_644_800 -> 128
            11_289_600 -> 256
            22_579_200 -> 512
            45_158_400 -> 1_024
            else -> null
        }

    fun locate(positionMs: Long): DsfBlockPosition {
        require(positionMs >= 0)
        val rate = sampleRateHz.toLong()
        val wholeSeconds = positionMs / 1_000
        val lastSample = sampleCount - 1
        val requestedSample = if (wholeSeconds > lastSample / rate) {
            lastSample
        } else {
            val wholeSamples = wholeSeconds * rate
            val fractionalSamples = positionMs % 1_000 * rate / 1_000
            if (fractionalSamples > lastSample - wholeSamples) lastSample
            else wholeSamples + fractionalSamples
        }
        val block = requestedSample / DSF_SAMPLES_PER_BLOCK
        val sampleIndex = block * DSF_SAMPLES_PER_BLOCK
        return DsfBlockPosition(
            blockOffset = dataOffset + block * blockGroupBytes,
            sampleIndex = sampleIndex,
            samplesToDiscard = requestedSample - sampleIndex,
        )
    }

    private val blockGroupBytes: Long
        get() = blockSizePerChannel.toLong() * channelCount

    private companion object {
        const val DSF_SAMPLES_PER_BLOCK = 32_768L
    }
}

object DsfFileParser {
    const val HEADER_SIZE = 92

    private const val DSD_CHUNK_SIZE = 28
    private const val FORMAT_CHUNK_SIZE = 52
    private const val DATA_CHUNK_MIN_SIZE = 12
    private const val DATA_OFFSET = DSD_CHUNK_SIZE + FORMAT_CHUNK_SIZE + DATA_CHUNK_MIN_SIZE
    private const val FIXED_BLOCK_SIZE = 4_096
    private const val SAMPLES_PER_BLOCK = 32_768L

    fun parse(header: ByteArray, actualSize: Long? = null): DsfFileInfo {
        if (header.size < HEADER_SIZE) fail(DsfParseError.TRUNCATED)
        if (actualSize != null && actualSize < 0) fail(DsfParseError.INVALID_HEADER)

        if (!matches(header, 0, "DSD ")) fail(DsfParseError.INVALID_HEADER)
        if (readUInt64(header, 4) != DSD_CHUNK_SIZE.toLong()) fail(DsfParseError.INVALID_HEADER)
        val fileSize = readUInt64(header, 12)
        val metadataOffset = readUInt64(header, 20)
        if (!matches(header, 28, "fmt ")) fail(DsfParseError.INVALID_HEADER)
        if (readUInt64(header, 32) != FORMAT_CHUNK_SIZE.toLong()) {
            fail(DsfParseError.UNSUPPORTED_FORMAT)
        }
        val version = readUInt32(header, 40)
        val formatId = readUInt32(header, 44)
        val channelType = readUInt32(header, 48)
        val channelCount = CHANNEL_COUNTS[channelType]
            ?: fail(DsfParseError.UNSUPPORTED_FORMAT)
        if (readUInt32(header, 52) != channelCount) fail(DsfParseError.INVALID_HEADER)
        val sampleRate = readUInt32(header, 56)
        if (sampleRate !in SUPPORTED_SAMPLE_RATES) fail(DsfParseError.UNSUPPORTED_FORMAT)
        val bitOrder = readUInt32(header, 60)
        val sampleCount = readUInt64(header, 64)
        val blockSize = readUInt32(header, 72)
        val reserved = readUInt32(header, 76)
        if (!matches(header, 80, "data")) fail(DsfParseError.INVALID_HEADER)
        val dataChunkSize = readUInt64(header, 84)

        if (version != 1 || formatId != 0 || bitOrder !in setOf(1, 8) ||
            blockSize != FIXED_BLOCK_SIZE || reserved != 0
        ) {
            fail(DsfParseError.UNSUPPORTED_FORMAT)
        }
        if (sampleCount <= 0L) fail(DsfParseError.INVALID_HEADER)
        if (fileSize < DATA_OFFSET) fail(DsfParseError.INVALID_HEADER)
        if (actualSize != null && fileSize != actualSize) fail(DsfParseError.INVALID_HEADER)

        if (dataChunkSize < DATA_CHUNK_MIN_SIZE) fail(DsfParseError.INVALID_HEADER)
        val dataSize = dataChunkSize - DATA_CHUNK_MIN_SIZE
        val dataChunkOffset = (DATA_OFFSET - DATA_CHUNK_MIN_SIZE).toLong()
        val dataEnd = addExact(dataChunkOffset, dataChunkSize)
            ?: fail(DsfParseError.INVALID_HEADER)
        if (dataEnd > fileSize) fail(DsfParseError.INVALID_HEADER)

        val groups = (sampleCount - 1) / SAMPLES_PER_BLOCK + 1
        val blockGroup = FIXED_BLOCK_SIZE.toLong() * channelCount
        val paddedDataSize = multiplyExact(groups, blockGroup)
            ?: fail(DsfParseError.INVALID_HEADER)
        if (dataSize != paddedDataSize) fail(DsfParseError.INVALID_HEADER)

        if (metadataOffset != 0L &&
            (metadataOffset < dataEnd || metadataOffset >= fileSize)
        ) {
            fail(DsfParseError.INVALID_HEADER)
        }

        return DsfFileInfo(
            sampleRateHz = sampleRate,
            channelCount = channelCount,
            bitsPerSample = 1,
            sampleCount = sampleCount,
            blockSizePerChannel = blockSize,
            fileSize = fileSize,
            dataOffset = DATA_OFFSET.toLong(),
            dataSize = dataSize,
            metadataOffset = metadataOffset.takeUnless { it == 0L },
            leastSignificantBitFirst = bitOrder == 1,
        )
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readUInt64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in offset until offset + 8) {
            val next = (bytes[index].toLong() and 0xFF) shl ((index - offset) * 8)
            if (index == offset + 7 && next < 0L) {
                fail(DsfParseError.INVALID_HEADER)
            }
            value = value or next
        }
        return value
    }

    private fun matches(bytes: ByteArray, offset: Int, value: String): Boolean =
        value.indices.all { bytes[offset + it] == value[it].code.toByte() }

    private fun fail(reason: DsfParseError): Nothing = throw DsfParseFailure(reason)

    private fun addExact(left: Long, right: Long): Long? =
        if (right > Long.MAX_VALUE - left) null else left + right

    private fun multiplyExact(left: Long, right: Long): Long? =
        if (left != 0L && right > Long.MAX_VALUE / left) null else left * right

    private val CHANNEL_COUNTS = mapOf(
        1 to 1,
        2 to 2,
        3 to 3,
        4 to 4,
        5 to 4,
        6 to 5,
        7 to 6,
    )

    private val SUPPORTED_SAMPLE_RATES = setOf(
        2_822_400,
        5_644_800,
        11_289_600,
        22_579_200,
        45_158_400,
    )
}
