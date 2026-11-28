package tech.musxi.core.localaudio

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DsfId3TagsTest {
    @Test
    fun parsesEnglishChineseAndUtf16InId3v23() {
        val title = textFrame(3, "TIT2", "English title", 0)
        val artist = textFrame(3, "TPE1", "中文歌手", 1, utf16BigEndianBom = true)
        val album = textFrame(3, "TALB", "专辑", 1)
        val track = textFrame(3, "TRCK", "03/12", 0)

        assertEquals(
            DsfTextTags("English title", "中文歌手", "专辑", "03/12"),
            DsfId3Tags.parse(tag(3, listOf(title, artist, album, track)))
        )
    }

    @Test
    fun parsesEnglishChineseUtf8AndUtf16BeInId3v24() {
        val title = textFrame(4, "TIT2", "中文标题", 3)
        val artist = textFrame(4, "TPE1", "English artist", 3)
        val album = textFrame(4, "TALB", "中文专辑", 2)
        val track = textFrame(4, "TRCK", "7", 3)

        assertEquals(
            DsfTextTags("中文标题", "English artist", "中文专辑", "7"),
            DsfId3Tags.parse(tag(4, listOf(title, artist, album, track)))
        )
    }

    @Test
    fun acceptsOnlyDocumentedHeaderFlags() {
        assertEquals(10, DsfId3Tags.requiredBytes(header(3, 0, 0x20)))
        assertEquals(10, DsfId3Tags.requiredBytes(header(4, 0, 0x20)))
        assertEquals(20, DsfId3Tags.requiredBytes(header(4, 0, 0x30)))

        for (version in listOf(3, 4)) {
            for (flags in listOf(0x80, 0x40)) {
                assertNull(DsfId3Tags.requiredBytes(header(version, 0, flags)))
            }
        }
        assertNull(DsfId3Tags.requiredBytes(header(3, 0, 0x10)))
        assertNull(DsfId3Tags.requiredBytes(header(3, 0, 0x01)))
        assertNull(DsfId3Tags.requiredBytes(header(4, 0, 0x01)))
        assertNull(DsfId3Tags.requiredBytes(header(3, 0, 0x10 or 0x20)))
    }

    @Test
    fun rejectsUnknownVersionsAndNonSynchsafeTagSizes() {
        assertNull(DsfId3Tags.requiredBytes(header(2, 0)))
        assertNull(DsfId3Tags.requiredBytes(header(5, 0)))
        assertNull(DsfId3Tags.requiredBytes(ByteArray(9)))

        for (version in listOf(3, 4)) {
            val invalidRevision = header(version, 0)
            invalidRevision[4] = 0xFF.toByte()
            assertNull(DsfId3Tags.requiredBytes(invalidRevision))
        }

        for (index in 6..9) {
            val malformed = header(4, 1)
            malformed[index] = 0x80.toByte()
            assertNull(DsfId3Tags.requiredBytes(malformed))
        }
    }

    @Test
    fun computesCompleteLengthWithFooterAndEnforcesTagLimit() {
        assertEquals(15, DsfId3Tags.requiredBytes(header(3, 5)))
        assertEquals(25, DsfId3Tags.requiredBytes(header(4, 5, 0x10)))
        assertEquals(
            DsfId3Tags.MAX_TAG_BYTES,
            DsfId3Tags.requiredBytes(header(3, DsfId3Tags.MAX_TAG_BYTES - 10))
        )
        assertEquals(
            DsfId3Tags.MAX_TAG_BYTES,
            DsfId3Tags.requiredBytes(header(4, DsfId3Tags.MAX_TAG_BYTES - 20, 0x10))
        )
        assertNull(
            DsfId3Tags.requiredBytes(header(3, DsfId3Tags.MAX_TAG_BYTES - 9))
        )
        assertNull(
            DsfId3Tags.requiredBytes(header(4, DsfId3Tags.MAX_TAG_BYTES - 19, 0x10))
        )
    }

    @Test
    fun acceptsFooterAndRejectsMalformedFooter() {
        val title = textFrame(4, "TIT2", "Footer title", 3)
        val valid = tag(4, listOf(title), flags = 0x10)
        val malformedMagic = valid.copyOf()
        malformedMagic[malformedMagic.size - 10] = 'X'.code.toByte()
        val malformedSize = valid.copyOf()
        malformedSize[malformedSize.size - 1] =
            ((malformedSize[malformedSize.size - 1].toInt() xor 1)).toByte()

        assertEquals("Footer title", DsfId3Tags.parse(valid)?.title)
        assertNull(DsfId3Tags.parse(malformedMagic))
        assertNull(DsfId3Tags.parse(malformedSize))
    }

    @Test
    fun skipsUnknownApicAndV24ExtendedTextFrames() {
        val apic = frame(
            4,
            "APIC",
            byteArrayOf(0, 'i'.code.toByte(), 'm'.code.toByte(), 'a'.code.toByte()) +
                ByteArray(8_192) { (it and 0xFF).toByte() },
        )
        val unknown = frame(4, "TXXX", textPayload(3, "ignored"))
        val title = textFrame(4, "TIT2", "Kept", 3)

        assertEquals("Kept", DsfId3Tags.parse(tag(4, listOf(apic, unknown, title)))?.title)
    }

    @Test
    fun skipsTextFramesWithFormatFlags() {
        val cases = listOf(
            3 to 0x80,
            3 to 0x40,
            3 to 0x20,
            4 to 0x40,
            4 to 0x08,
            4 to 0x04,
            4 to 0x02,
            4 to 0x01,
        )
        for ((version, formatFlags) in cases) {
            val flagged = frame(
                version,
                "TIT2",
                textPayload(if (version == 3) 0 else 3, "Hidden"),
                formatFlags = formatFlags,
            )
            val artist = textFrame(version, "TPE1", "Visible", if (version == 3) 0 else 3)
            val parsed = DsfId3Tags.parse(tag(version, listOf(flagged, artist)))
            assertNotNull(parsed)
            assertNull(parsed?.title)
            assertEquals("Visible", parsed?.artist)
        }
    }

    @Test
    fun allowsStatusFlagsOnTextFrames() {
        val v23Title = frame(
            3,
            "TIT2",
            textPayload(0, "v2.3 read only"),
            statusFlags = 0x20,
        )
        val v24Title = frame(
            4,
            "TIT2",
            textPayload(3, "Read only"),
            statusFlags = 0x20,
        )
        assertEquals("v2.3 read only", DsfId3Tags.parse(tag(3, listOf(v23Title)))?.title)
        assertEquals("Read only", DsfId3Tags.parse(tag(4, listOf(v24Title)))?.title)
    }

    @Test
    fun skipsTextFramesWithReservedStatusFlags() {
        for ((version, statusFlags) in listOf(
            3 to 0x10,
            3 to 0x01,
            4 to 0x08,
            4 to 0x80,
        )) {
            val flagged = frame(
                version,
                "TIT2",
                textPayload(if (version == 3) 0 else 3, "Ignored"),
                statusFlags = statusFlags,
            )
            val artist = textFrame(version, "TPE1", "Artist", if (version == 3) 0 else 3)
            val parsed = DsfId3Tags.parse(tag(version, listOf(flagged, artist)))
            assertNull(parsed?.title)
            assertEquals("Artist", parsed?.artist)
        }
    }

    @Test
    fun rejectsV23Utf8AndUtf16BeEncodingIds() {
        val title = textFrame(3, "TIT2", "ignored", 3)
        val artist = textFrame(3, "TPE1", "ignored", 2)
        val album = textFrame(3, "TALB", "kept", 0)
        val parsed = DsfId3Tags.parse(tag(3, listOf(title, artist, album)))

        assertNull(parsed?.title)
        assertNull(parsed?.artist)
        assertEquals("kept", parsed?.album)
    }

    @Test
    fun skipsMissingBomInvalidEncodingAndMalformedUtf8() {
        val missingBom = frame(3, "TIT2", byteArrayOf(1, 0, 'A'.code.toByte(), 0))
        val unknownEncoding = frame(3, "TPE1", byteArrayOf(4, 'A'.code.toByte()))
        val badUtf8 = frame(4, "TALB", byteArrayOf(3, 0xC3.toByte(), 0x28))
        val oddUtf16 = frame(4, "TRCK", byteArrayOf(2, '1'.code.toByte()))

        assertEquals(
            DsfTextTags(),
            DsfId3Tags.parse(tag(4, listOf(missingBom, unknownEncoding, badUtf8, oddUtf16)))
        )
    }

    @Test
    fun keepsFirstValidDuplicateAfterInvalidAndFlaggedFrames() {
        val invalidFirst = frame(4, "TIT2", byteArrayOf(1, 'A'.code.toByte()))
        val validFirst = textFrame(4, "TIT2", "First valid", 3)
        val duplicate = textFrame(4, "TIT2", "Second", 3)
        val flagged = frame(4, "TPE1", textPayload(3, "Hidden"), formatFlags = 0x02)
        val visibleArtist = textFrame(4, "TPE1", "Artist", 3)
        val duplicateArtist = textFrame(4, "TPE1", "Other artist", 3)

        assertEquals(
            DsfTextTags(title = "First valid", artist = "Artist"),
            DsfId3Tags.parse(
                tag(4, listOf(invalidFirst, validFirst, duplicate, flagged, visibleArtist, duplicateArtist))
            )
        )
    }

    @Test
    fun trimsAndRemovesControlCharacters() {
        val title = textFrame(4, "TIT2", " \u0000\u0007 Title\u0001 Line\nBreak \u009F ", 3)
        assertEquals("Title LineBreak", DsfId3Tags.parse(tag(4, listOf(title)))?.title)
    }

    @Test
    fun boundsTextFramesAndOutputCharacters() {
        val atLimit = textFrame(4, "TIT2", "a".repeat(4_095), 3)
        val overLimit = textFrame(4, "TIT2", "b".repeat(4_096), 3)
        val fallback = textFrame(4, "TIT2", "fallback", 3)
        val parsedAtLimit = DsfId3Tags.parse(tag(4, listOf(atLimit)))
        val parsedOverLimit = DsfId3Tags.parse(tag(4, listOf(overLimit)))
        val parsedWithFallback = DsfId3Tags.parse(tag(4, listOf(overLimit, fallback)))

        assertEquals(1_024, parsedAtLimit?.title?.length)
        assertNull(parsedOverLimit?.title)
        assertEquals("fallback", parsedWithFallback?.title)
    }

    @Test
    fun rejectsTruncatedFramesAndInvalidFrameSizes() {
        val truncated = buildTag(4, frameHeader(4, "TIT2", 20, 0, 0) + ByteArray(3))
        val badSynchsafe = buildTag(
            4,
            byteArrayOf(
                'T'.code.toByte(),
                'I'.code.toByte(),
                'T'.code.toByte(),
                '2'.code.toByte(),
                0x80.toByte(),
                0,
                0,
                1,
                0,
                0,
                0,
            )
        )
        val shortBody = header(4, 9) + ByteArray(9) { 1 }
        val extraBytes = tag(4, listOf(textFrame(4, "TIT2", "Title", 3))) + byteArrayOf(0)

        assertNull(DsfId3Tags.parse(truncated))
        assertNull(DsfId3Tags.parse(badSynchsafe))
        assertNull(DsfId3Tags.parse(shortBody))
        assertNull(DsfId3Tags.parse(extraBytes))
    }

    @Test
    fun rejectsMalformedFrameIdsAndNonZeroPadding() {
        val malformedId = buildTag(4, ByteArray(10))
        malformedId[10] = 'T'.code.toByte()
        malformedId[11] = '!'.code.toByte()
        val badPadding = tag(4, listOf(ByteArray(4), byteArrayOf(1)))

        assertNull(DsfId3Tags.parse(malformedId))
        assertNull(DsfId3Tags.parse(badPadding))
        assertEquals(DsfTextTags(), DsfId3Tags.parse(tag(4, listOf(ByteArray(12)))))
    }

    @Test
    fun reportsRequiredLengthForHeaderOnly() {
        val fullTag = tag(
            4,
            listOf(
                textFrame(4, "TIT2", "Title", 3),
                textFrame(4, "TPE1", "Artist", 3),
            ),
            flags = 0x10,
        )
        assertEquals(fullTag.size, DsfId3Tags.requiredBytes(fullTag.copyOfRange(0, 10)))
        assertTrue(fullTag.size > 20)
    }

    private fun tag(version: Int, frames: List<ByteArray>, flags: Int = 0): ByteArray {
        val body = frames.fold(ByteArray(0)) { result, frame -> result + frame }
        return buildTag(version, body, flags)
    }

    private fun buildTag(version: Int, body: ByteArray, flags: Int = 0): ByteArray {
        val header = header(version, body.size, flags)
        val footer = if (version == 4 && (flags and 0x10) != 0) {
            footer(version, body.size, flags)
        } else {
            ByteArray(0)
        }
        return header + body + footer
    }

    private fun header(version: Int, bodySize: Int, flags: Int = 0): ByteArray {
        val result = byteArrayOf(
            'I'.code.toByte(),
            'D'.code.toByte(),
            '3'.code.toByte(),
            version.toByte(),
            0,
            flags.toByte(),
            0,
            0,
            0,
            0,
        )
        writeSynchsafe(result, 6, bodySize)
        return result
    }

    private fun footer(version: Int, bodySize: Int, flags: Int): ByteArray {
        val result = byteArrayOf(
            '3'.code.toByte(),
            'D'.code.toByte(),
            'I'.code.toByte(),
            version.toByte(),
            0,
            flags.toByte(),
            0,
            0,
            0,
            0,
        )
        writeSynchsafe(result, 6, bodySize)
        return result
    }

    private fun frame(
        version: Int,
        id: String,
        payload: ByteArray,
        statusFlags: Int = 0,
        formatFlags: Int = 0,
    ): ByteArray =
        frameHeader(version, id, payload.size, statusFlags, formatFlags) + payload

    private fun frameHeader(
        version: Int,
        id: String,
        payloadSize: Int,
        statusFlags: Int,
        formatFlags: Int,
    ): ByteArray {
        require(id.length == 4)
        val result = ByteArray(10)
        for (index in id.indices) result[index] = id[index].code.toByte()
        if (version == 4) {
            writeSynchsafe(result, 4, payloadSize)
        } else {
            writeUInt32(result, 4, payloadSize)
        }
        result[8] = statusFlags.toByte()
        result[9] = formatFlags.toByte()
        return result
    }

    private fun textFrame(
        version: Int,
        id: String,
        value: String,
        encoding: Int,
        utf16BigEndianBom: Boolean = false,
    ): ByteArray =
        frame(version, id, textPayload(encoding, value, utf16BigEndianBom))

    private fun textPayload(
        encoding: Int,
        value: String,
        utf16BigEndianBom: Boolean = false,
    ): ByteArray {
        val content = when (encoding) {
            0 -> value.toByteArray(StandardCharsets.ISO_8859_1)
            1 -> {
                if (utf16BigEndianBom) {
                    byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
                        value.toByteArray(StandardCharsets.UTF_16BE)
                } else {
                    byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
                        value.toByteArray(StandardCharsets.UTF_16LE)
                }
            }
            2 -> value.toByteArray(StandardCharsets.UTF_16BE)
            3 -> value.toByteArray(StandardCharsets.UTF_8)
            else -> value.toByteArray(StandardCharsets.ISO_8859_1)
        }
        return byteArrayOf(encoding.toByte()) + content
    }

    private fun writeUInt32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun writeSynchsafe(bytes: ByteArray, offset: Int, value: Int) {
        require(value in 0..0x0FFFFFFF)
        bytes[offset] = ((value ushr 21) and 0x7F).toByte()
        bytes[offset + 1] = ((value ushr 14) and 0x7F).toByte()
        bytes[offset + 2] = ((value ushr 7) and 0x7F).toByte()
        bytes[offset + 3] = (value and 0x7F).toByte()
    }
}
