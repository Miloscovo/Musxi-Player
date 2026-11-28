package tech.musxi.core.localaudio

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class DsfTextTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val trackNumber: String? = null,
)

object DsfId3Tags {
    const val MAX_TAG_BYTES = 1024 * 1024

    private const val HEADER_BYTES = 10
    private const val FOOTER_BYTES = 10
    private const val MAX_TEXT_FRAME_BYTES = 4 * 1024
    private const val MAX_TEXT_CHARACTERS = 1024

    private const val VERSION_23 = 3
    private const val VERSION_24 = 4

    private const val TAG_UNSYNCHRONISATION = 0x80
    private const val TAG_EXTENDED_HEADER = 0x40
    private const val TAG_EXPERIMENTAL = 0x20
    private const val TAG_FOOTER = 0x10
    private const val MAX_REVISION = 0xFE

    private const val V23_STATUS_FLAGS = 0xE0
    private const val V24_STATUS_FLAGS = 0x70

    fun requiredBytes(header: ByteArray): Int? =
        readHeader(header, 0)?.totalBytes

    fun parse(bytes: ByteArray): DsfTextTags? {
        val header = readHeader(bytes, 0) ?: return null
        if (bytes.size != header.totalBytes) return null
        if (header.hasFooter && !hasValidFooter(bytes, header)) return null
        return parseFrames(bytes, HEADER_BYTES, HEADER_BYTES + header.bodyBytes, header)
    }

    private fun readHeader(bytes: ByteArray, offset: Int): TagHeader? {
        if (bytes.size - offset < HEADER_BYTES) return null
        if (!matches(bytes, offset, "ID3")) return null

        val version = bytes[offset + 3].toInt() and 0xFF
        if (version != VERSION_23 && version != VERSION_24) return null
        val revision = bytes[offset + 4].toInt() and 0xFF
        if (revision > MAX_REVISION) return null
        val flags = bytes[offset + 5].toInt() and 0xFF
        val allowedFlags = if (version == VERSION_24) {
            TAG_EXPERIMENTAL or TAG_FOOTER
        } else {
            TAG_EXPERIMENTAL
        }
        if ((flags and allowedFlags.inv()) != 0) return null
        if ((flags and (TAG_UNSYNCHRONISATION or TAG_EXTENDED_HEADER)) != 0) return null

        val bodyBytes = readSynchsafe(bytes, offset + 6) ?: return null
        val hasFooter = version == VERSION_24 && (flags and TAG_FOOTER) != 0
        val footerBytes = if (hasFooter) FOOTER_BYTES else 0
        val maxBodyBytes = MAX_TAG_BYTES - HEADER_BYTES - footerBytes
        if (bodyBytes > maxBodyBytes) return null

        return TagHeader(
            version = version,
            revision = revision,
            flags = flags,
            bodyBytes = bodyBytes,
            hasFooter = hasFooter,
        )
    }

    private fun hasValidFooter(bytes: ByteArray, header: TagHeader): Boolean {
        val offset = bytes.size - FOOTER_BYTES
        if (offset < HEADER_BYTES || !matches(bytes, offset, "3DI")) return false
        if ((bytes[offset + 3].toInt() and 0xFF) != header.version) return false
        if ((bytes[offset + 4].toInt() and 0xFF) != header.revision) return false
        if ((bytes[offset + 5].toInt() and 0xFF) != header.flags) return false
        return readSynchsafe(bytes, offset + 6) == header.bodyBytes
    }

    private fun parseFrames(
        bytes: ByteArray,
        start: Int,
        end: Int,
        header: TagHeader,
    ): DsfTextTags? {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var trackNumber: String? = null
        var offset = start

        while (offset < end) {
            if (end - offset < HEADER_BYTES) {
                return if (allZero(bytes, offset, end)) {
                    DsfTextTags(title, artist, album, trackNumber)
                } else {
                    null
                }
            }

            if (allZero(bytes, offset, offset + 4)) {
                return if (allZero(bytes, offset, end)) {
                    DsfTextTags(title, artist, album, trackNumber)
                } else {
                    null
                }
            }

            val frameId = readFrameId(bytes, offset) ?: return null
            val frameSize = readFrameSize(bytes, offset, header.version) ?: return null
            val availablePayload = end - offset - HEADER_BYTES
            if (frameSize > availablePayload.toLong()) return null

            val statusFlags = bytes[offset + 8].toInt() and 0xFF
            val formatFlags = bytes[offset + 9].toInt() and 0xFF
            val payloadStart = offset + HEADER_BYTES
            val payloadEnd = payloadStart + frameSize.toInt()

            if (formatFlags == 0 && hasValidStatusFlags(header.version, statusFlags) &&
                frameId in TEXT_FRAME_IDS &&
                frameSize in 1..MAX_TEXT_FRAME_BYTES.toLong()
            ) {
                val value = decodeText(bytes, payloadStart, payloadEnd, header.version)
                if (value != null) {
                    when (frameId) {
                        "TIT2" -> if (title == null) title = value
                        "TPE1" -> if (artist == null) artist = value
                        "TALB" -> if (album == null) album = value
                        "TRCK" -> if (trackNumber == null) trackNumber = value
                    }
                }
            }

            offset = payloadEnd
        }

        return DsfTextTags(title, artist, album, trackNumber)
    }

    private fun readFrameId(bytes: ByteArray, offset: Int): String? {
        for (index in offset until offset + 4) {
            val value = bytes[index].toInt() and 0xFF
            val valid = value in 'A'.code..'Z'.code || value in '0'.code..'9'.code
            if (!valid) return null
        }
        return String(bytes, offset, 4, StandardCharsets.US_ASCII)
    }

    private fun readFrameSize(bytes: ByteArray, offset: Int, version: Int): Long? =
        if (version == VERSION_23) {
            readUInt32(bytes, offset + 4)
        } else {
            readSynchsafe(bytes, offset + 4)?.toLong()
        }

    private fun hasValidStatusFlags(version: Int, flags: Int): Boolean {
        val allowed = if (version == VERSION_23) V23_STATUS_FLAGS else V24_STATUS_FLAGS
        return (flags and allowed.inv()) == 0
    }

    private fun decodeText(bytes: ByteArray, start: Int, end: Int, version: Int): String? {
        if (end - start < 2) return null
        val encoding = bytes[start].toInt() and 0xFF
        val contentStart = start + 1
        val decoded = when (encoding) {
            0 -> decodeStrict(bytes, contentStart, end, StandardCharsets.ISO_8859_1)
            1 -> decodeUtf16WithBom(bytes, contentStart, end)
            2 -> if (version == VERSION_24) {
                decodeStrict(bytes, contentStart, end, StandardCharsets.UTF_16BE)
            } else {
                null
            }
            3 -> if (version == VERSION_24) {
                decodeStrict(bytes, contentStart, end, StandardCharsets.UTF_8)
            } else {
                null
            }
            else -> null
        } ?: return null
        return normalize(if (encoding == 2 || encoding == 3) decoded.removePrefix("\uFEFF") else decoded)
    }

    private fun decodeUtf16WithBom(bytes: ByteArray, start: Int, end: Int): String? {
        if (end - start < 2) return null
        val first = bytes[start].toInt() and 0xFF
        val second = bytes[start + 1].toInt() and 0xFF
        val charset = when {
            first == 0xFF && second == 0xFE -> StandardCharsets.UTF_16LE
            first == 0xFE && second == 0xFF -> StandardCharsets.UTF_16BE
            else -> return null
        }
        return decodeStrict(bytes, start + 2, end, charset)
    }

    private fun decodeStrict(bytes: ByteArray, start: Int, end: Int, charset: Charset): String? =
        try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, start, end - start))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun normalize(decoded: String): String? {
        val filtered = StringBuilder(decoded.length)
        for (character in decoded) {
            val code = character.code
            if (code !in 0x00..0x1F && code !in 0x7F..0x9F) filtered.append(character)
        }
        val trimmed = filtered.toString().trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length <= MAX_TEXT_CHARACTERS) return trimmed

        var length = MAX_TEXT_CHARACTERS
        if (Character.isHighSurrogate(trimmed[length - 1])) length--
        return trimmed.substring(0, length)
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in offset until offset + 4) {
            value = (value shl 8) or (bytes[index].toLong() and 0xFF)
        }
        return value
    }

    private fun readSynchsafe(bytes: ByteArray, offset: Int): Int? {
        var value = 0
        for (index in offset until offset + 4) {
            val next = bytes[index].toInt() and 0xFF
            if ((next and 0x80) != 0) return null
            value = (value shl 7) or next
        }
        return value
    }

    private fun matches(bytes: ByteArray, offset: Int, value: String): Boolean =
        value.indices.all { bytes[offset + it] == value[it].code.toByte() }

    private fun allZero(bytes: ByteArray, start: Int, end: Int): Boolean {
        for (index in start until end) {
            if (bytes[index].toInt() != 0) return false
        }
        return true
    }

    private data class TagHeader(
        val version: Int,
        val revision: Int,
        val flags: Int,
        val bodyBytes: Int,
        val hasFooter: Boolean,
    ) {
        val totalBytes: Int
            get() = HEADER_BYTES + bodyBytes + if (hasFooter) FOOTER_BYTES else 0
    }

    private val TEXT_FRAME_IDS = setOf("TIT2", "TPE1", "TALB", "TRCK")
}
