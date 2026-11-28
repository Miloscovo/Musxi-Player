package tech.musxi.core.localaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DsfFileTest {
    @Test
    fun parsesDsf64StereoStereoOneBlock() {
        val header = header(sampleCount = 32_768)

        val info = DsfFileParser.parse(header, headerSize(sampleCount = 32_768))

        assertEquals(2_822_400, info.sampleRateHz)
        assertEquals(2, info.channelCount)
        assertEquals(1, info.bitsPerSample)
        assertEquals(32_768L, info.sampleCount)
        assertEquals(4_096, info.blockSizePerChannel)
        assertEquals(8_192L, info.dataSize)
        assertEquals(92L, info.dataOffset)
        assertEquals(8_284L, info.fileSize)
        assertNull(info.metadataOffset)
        assertTrue(info.leastSignificantBitFirst)
        assertEquals(64, info.dsdMultiplier)
        assertEquals(11L, info.durationMs)
    }

    @Test
    fun parsesDsf256And512Rates() {
        val dsd256 = DsfFileParser.parse(
            header(sampleRate = 11_289_600, sampleCount = 32_769),
            headerSize(sampleRate = 11_289_600, sampleCount = 32_769),
        )
        val dsd512 = DsfFileParser.parse(
            header(sampleRate = 22_579_200, sampleCount = 65_536),
            headerSize(sampleRate = 22_579_200, sampleCount = 65_536),
        )

        assertEquals(11_289_600, dsd256.sampleRateHz)
        assertEquals(256, dsd256.dsdMultiplier)
        assertEquals(16_384L, dsd256.dataSize)
        assertEquals(22_579_200, dsd512.sampleRateHz)
        assertEquals(512, dsd512.dsdMultiplier)
        assertEquals(16_384L, dsd512.dataSize)
    }

    @Test
    fun readsBitOrderInEitherSupportedEncoding() {
        val lsbFirst = DsfFileParser.parse(header(bitOrder = 1))
        val msbFirst = DsfFileParser.parse(header(bitOrder = 8))
        val unsupported = assertThrows(DsfParseFailure::class.java) {
            DsfFileParser.parse(header(bitOrder = 2))
        }

        assertTrue(lsbFirst.leastSignificantBitFirst)
        assertEquals(false, msbFirst.leastSignificantBitFirst)
        assertEquals(DsfParseError.UNSUPPORTED_FORMAT, unsupported.reason)
        assertEquals(1, msbFirst.bitsPerSample)
    }

    @Test
    fun mapsEveryKnownChannelTypeAndRejectsCountMismatch() {
        val expected = mapOf(1 to 1, 2 to 2, 3 to 3, 4 to 4, 5 to 4, 6 to 5, 7 to 6)

        expected.forEach { (type, channels) ->
            val info = DsfFileParser.parse(header(channelType = type, channelCount = channels))
            assertEquals(channels, info.channelCount)
        }
        assertEquals(
            DsfParseError.INVALID_HEADER,
            assertThrows(DsfParseFailure::class.java) {
                DsfFileParser.parse(header(channelType = 5, channelCount = 5))
            }.reason,
        )
        assertEquals(
            DsfParseError.UNSUPPORTED_FORMAT,
            assertThrows(DsfParseFailure::class.java) {
                DsfFileParser.parse(header(channelType = 8, channelCount = 6))
            }.reason,
        )
    }

    @Test
    fun rejectsTruncatedShortAndUnknownChunks() {
        assertEquals(
            DsfParseError.TRUNCATED,
            assertThrows(DsfParseFailure::class.java) {
                DsfFileParser.parse(ByteArray(91))
            }.reason,
        )

        val shortDedicatedChunk = header(paddedDataSize = 8_192)
        putLong(shortDedicatedChunk, 4, 27)
        assertEquals(
            DsfParseError.INVALID_HEADER,
            assertThrows(DsfParseFailure::class.java) {
                DsfFileParser.parse(shortDedicatedChunk)
            }.reason,
        )

        val unknownFormat = header(formatId = 1)
        assertEquals(
            DsfParseError.UNSUPPORTED_FORMAT,
            assertThrows(DsfParseFailure::class.java) {
                DsfFileParser.parse(unknownFormat)
            }.reason,
        )
    }

    @Test
    fun rejectsUnsupportedAndMalformedFixedFields() {
        val unsupportedRate = header(sampleRate = 96_000)
        val unsupportedVersion = header(version = 2)
        val unsupportedFmtSize = header(formatChunkSize = 53)
        val unsupportedBlock = header(blockSize = 4_095)
        val unsupportedReserved = header(reserved = 1)

        listOf(unsupportedRate, unsupportedVersion, unsupportedFmtSize,
            unsupportedBlock, unsupportedReserved).forEach { bytes ->
            assertEquals(
                DsfParseError.UNSUPPORTED_FORMAT,
                assertThrows(DsfParseFailure::class.java) {
                    DsfFileParser.parse(bytes)
                }.reason,
            )
        }

        val wrongMagic = header().also { it[0] = 'X'.code.toByte() }
        val zeroSamples = header(sampleCount = 0)
        listOf(wrongMagic, zeroSamples).forEach { bytes ->
            assertEquals(
                DsfParseError.INVALID_HEADER,
                assertThrows(DsfParseFailure::class.java) {
                    DsfFileParser.parse(bytes)
                }.reason,
            )
        }
    }

    @Test
    fun rejectsNegativeOrUnrepresentableUnsigned64Fields() {
        val negativeFileSize = header(paddedDataSize = 8_192).also { it[12 + 7] = 0x80.toByte() }
        val overflowingDataSize = header(paddedDataSize = 8_192).also { it[84 + 7] = 0x80.toByte() }
        val negativeActualSize = header()

        listOf(negativeFileSize, overflowingDataSize).forEach { bytes ->
            assertEquals(
                DsfParseError.INVALID_HEADER,
                assertThrows(DsfParseFailure::class.java) {
                    DsfFileParser.parse(bytes)
                }.reason,
            )
        }
        assertEquals(
            DsfParseError.INVALID_HEADER,
            assertThrows(DsfParseFailure::class.java) {
                DsfFileParser.parse(negativeActualSize, -1)
            }.reason,
        )
    }

    @Test
    fun rejectsDataShortOfWholePaddedBlocks() {
        val lastChunkData = header(sampleCount = 1)
        putLong(lastChunkData, 84, 12)
        putLong(lastChunkData, 12, 104)
        assertEquals(
            DsfParseError.INVALID_HEADER,
            assertThrows(DsfParseFailure::class.java) {
                DsfFileParser.parse(lastChunkData)
            }.reason,
        )

        val truncatedDataChunk = header(dataChunkSize = 11)
        assertEquals(
            DsfParseError.INVALID_HEADER,
            assertThrows(DsfParseFailure::class.java) {
                DsfFileParser.parse(truncatedDataChunk)
            }.reason,
        )
    }

    @Test
    fun requiresPaddedTailThroughLastIncompleteBlock() {
        val sampleCount = 32_769L
        val valid = DsfFileParser.parse(header(sampleCount = sampleCount))

        assertEquals(16_384L, valid.dataSize)
        assertEquals(92L + 8_192L, valid.locate(11_610L).blockOffset)

        val previousBlockOnly = header(sampleCount = sampleCount, paddedDataSize = 8_192)
        putLong(previousBlockOnly, 84, 8_204)
        putLong(previousBlockOnly, 12, 8_284)
        assertEquals(
            DsfParseError.INVALID_HEADER,
            assertThrows(DsfParseFailure::class.java) {
                DsfFileParser.parse(previousBlockOnly)
            }.reason,
        )
    }

    @Test
    fun checksMetadataPointerBoundsAndDeclaredActualSize() {
        val dataEnd = headerSize()
        val fileSize = dataEnd + 10
        val atDataEnd = parseWithMetadata(dataEnd, fileSize)
        val valid = parseWithMetadata(dataEnd + 1, fileSize)

        assertEquals(dataEnd, atDataEnd.metadataOffset)
        assertEquals(dataEnd + 1, valid.metadataOffset)

        listOf(91L, dataEnd - 1, fileSize).forEach { offset ->
            val bytes = header(paddedDataSize = 8_192, fileSize = fileSize)
            putLong(bytes, 20, offset)
            assertEquals(
                DsfParseError.INVALID_HEADER,
                assertThrows(DsfParseFailure::class.java) {
                    DsfFileParser.parse(bytes)
                }.reason,
            )
        }
        assertEquals(
            DsfParseError.INVALID_HEADER,
            assertThrows(DsfParseFailure::class.java) {
                DsfFileParser.parse(header(), headerSize() - 1)
            }.reason,
        )
    }

    @Test
    fun locatesAcrossFourGiBWithoutTruncatingOffsets() {
        val groups = 700_000L
        val sampleCount = groups * 32_768L + 12_345L
        val paddedDataSize = (groups + 1) * 8_192L
        val fileSize = 92L + paddedDataSize
        val bytes = header(
            sampleCount = sampleCount,
            paddedDataSize = paddedDataSize,
            fileSize = fileSize,
        )
        val info = DsfFileParser.parse(bytes, fileSize)
        val positionMs = 7_000_000L
        val expectedSample = positionMs * 2_822_400L / 1_000L
        val expectedBlock = expectedSample / 32_768L
        val expectedOffset = 92L + expectedBlock * 8_192L

        assertTrue(expectedOffset > 0x1_0000_0000L)
        assertTrue(expectedOffset < fileSize)
        val position = info.locate(positionMs)
        assertEquals(expectedOffset, position.blockOffset)
        assertEquals(expectedBlock * 32_768L, position.sampleIndex)
        assertEquals(expectedSample - position.sampleIndex, position.samplesToDiscard)
    }

    @Test
    fun locatesExactBlockBoundaries() {
        val boundaryMs = 40_960L
        val sampleCount = boundaryMs * 2_822_400L / 1_000L + 1L
        val info = DsfFileParser.parse(header(sampleCount = sampleCount))

        val position = info.locate(boundaryMs)

        val expectedSample = boundaryMs * 2_822_400L / 1_000L
        assertEquals(92L + expectedSample / 32_768L * 8_192L, position.blockOffset)
        assertEquals(expectedSample, position.sampleIndex)
        assertEquals(0L, position.samplesToDiscard)
    }

    @Test
    fun clampsLongMaxAndPastEndToLastValidSample() {
        val info = DsfFileParser.parse(header(sampleCount = 32_769))
        val pastEnd = info.locate(Long.MAX_VALUE)
        val pastEndMs = info.durationMs + 1
        val atEnd = info.locate(pastEndMs)

        assertEquals(DsfBlockPosition(92L + 8_192L, 32_768L, 0L), pastEnd)
        assertEquals(pastEnd, atEnd)
        assertThrows(IllegalArgumentException::class.java) {
            info.locate(-1)
        }
    }

    @Test
    fun durationHandlesHugeSamplesWithoutOverflow() {
        val sampleRate = 2_822_400
        val sampleCount = Long.MAX_VALUE / 100
        val paddedDataSize = ((sampleCount - 1) / 32_768 + 1) * 4_096 * 2
        val fileSize = 92L + paddedDataSize
        val bytes = header(
            sampleCount = sampleCount,
            paddedDataSize = paddedDataSize,
            fileSize = fileSize,
        )

        val info = DsfFileParser.parse(bytes, fileSize)

        assertEquals(
            sampleCount / sampleRate * 1_000 + sampleCount % sampleRate * 1_000 / sampleRate,
            info.durationMs,
        )
    }

    private fun parseWithMetadata(metadataOffset: Long, fileSize: Long): DsfFileInfo {
        val bytes = header(fileSize = fileSize)
        putLong(bytes, 20, metadataOffset)
        return DsfFileParser.parse(bytes, fileSize)
    }

    private fun headerSize(
        sampleRate: Int = 2_822_400,
        sampleCount: Long = 32_768,
        channelCount: Int = 2,
    ): Long = 92L + paddedDataSize(sampleCount, channelCount)

    private fun paddedDataSize(sampleCount: Long, channelCount: Int): Long =
        ((sampleCount - 1) / 32_768 + 1) * 4_096 * channelCount

    private fun header(
        bitOrder: Int = 1,
        version: Int = 1,
        formatId: Int = 0,
        channelType: Int = 2,
        channelCount: Int = 2,
        sampleRate: Int = 2_822_400,
        sampleCount: Long = 32_768,
        blockSize: Int = 4_096,
        reserved: Int = 0,
        formatChunkSize: Long = 52,
        paddedDataSize: Long = ((sampleCount - 1) / 32_768 + 1) * 4_096 * channelCount,
        dataChunkSize: Long = paddedDataSize + 12,
        fileSize: Long = 80L + dataChunkSize,
    ): ByteArray {
        val bytes = ByteArray(92)
        putAscii(bytes, 0, "DSD ")
        putLong(bytes, 4, 28)
        putLong(bytes, 12, fileSize)
        putLong(bytes, 20, 0)
        putAscii(bytes, 28, "fmt ")
        putLong(bytes, 32, formatChunkSize)
        putInt(bytes, 40, version)
        putInt(bytes, 44, formatId)
        putInt(bytes, 48, channelType)
        putInt(bytes, 52, channelCount)
        putInt(bytes, 56, sampleRate)
        putInt(bytes, 60, bitOrder)
        putLong(bytes, 64, sampleCount)
        putInt(bytes, 72, blockSize)
        putInt(bytes, 76, reserved)
        putAscii(bytes, 80, "data")
        putLong(bytes, 84, dataChunkSize)
        return bytes
    }

    private fun putAscii(bytes: ByteArray, offset: Int, value: String) {
        value.forEachIndexed { index, character ->
            bytes[offset + index] = character.code.toByte()
        }
    }

    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { index ->
            bytes[offset + index] = (value ushr (index * 8)).toByte()
        }
    }

    private fun putLong(bytes: ByteArray, offset: Int, value: Long) {
        repeat(8) { index ->
            bytes[offset + index] = (value ushr (index * 8)).toByte()
        }
    }
}
