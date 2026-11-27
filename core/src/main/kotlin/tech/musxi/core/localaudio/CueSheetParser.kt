package tech.musxi.core.localaudio

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Collections

enum class CueIssueCode {
    TOO_LARGE,
    INVALID_ENCODING,
    INVALID_SYNTAX,
    NO_AUDIO_TRACKS,
    LIMIT_REACHED,
    UNSUPPORTED_MULTI_FILE,
    UNSUPPORTED_FILE,
    UNSAFE_REFERENCE,
    UNSUPPORTED_GAP,
    UNSUPPORTED_TRACK,
    UNSUPPORTED_DIRECTIVE,
    INVALID_INDEX,
    INVALID_ORDER,
    MISSING_INDEX,
}

data class CueIssue(val code: CueIssueCode, val line: Int = 0)

data class CueFile(
    val index: Int,
    val reference: String,
    val type: String,
) {
    override fun toString(): String = "CueFile(index=$index, type=$type, reference=<redacted>)"
}

data class CueTrack(
    val number: Int,
    val fileIndex: Int,
    val title: String?,
    val performer: String?,
    val startFrame: Long?,
    val line: Int,
)

class CueSheet(
    title: String?,
    performer: String?,
    val encoding: String,
    files: List<CueFile>,
    tracks: List<CueTrack>,
    issues: List<CueIssue>,
) {
    val title: String? = title
    val performer: String? = performer
    val files: List<CueFile> = Collections.unmodifiableList(ArrayList(files))
    val tracks: List<CueTrack> = Collections.unmodifiableList(ArrayList(tracks))
    val issues: List<CueIssue> = Collections.unmodifiableList(ArrayList(issues))

    override fun toString(): String =
        "CueSheet(encoding=$encoding, files=${files.size}, tracks=${tracks.size}, " +
            "issues=${issues.map { it.code.name }})"
}

object CueSheetParser {
    const val MAX_BYTES = 262_144

    private const val MAX_LINES = 4_096
    private const val MAX_LINE_CHARACTERS = 2_048
    private const val MAX_FILES = 99
    private const val MAX_TRACKS = 99
    private const val FRAMES_PER_SECOND = 75L
    private const val SECONDS_PER_MINUTE = 60L
    private const val FRAMES_PER_MINUTE = FRAMES_PER_SECOND * SECONDS_PER_MINUTE
    private const val MAX_FRAME_DIGITS = 18

    private val unsafePathSegment = Regex("[:/\\\\]")
    private val unsafeReferenceText = Regex("[\\x00-\\x1F\\x7F-\\x9F]")
    private val uriReference = Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*")
    private val supportedFileExtensions = setOf("flac")
    private val supportedFileTypes = setOf("FLAC", "WAVE")

    fun parse(bytes: ByteArray): CueSheet = Parsing(bytes).parse()

    private class Parsing(private val bytes: ByteArray) {
        private val issues = ArrayList<CueIssue>()
        private val files = ArrayList<CueFile>()
        private val tracks = ArrayList<CueTrack>()
        private var encoding = "UNKNOWN"
        private var title: String? = null
        private var performer: String? = null
        private var currentFileIndex = -1
        private var pendingTrack: PendingTrack? = null
        private var lastTrackNumber = 0
        private var finishedTracks = 0

        fun parse(): CueSheet {
            if (bytes.size > MAX_BYTES) {
                add(CueIssueCode.TOO_LARGE)
                return result()
            }
            if (bytes.isEmpty()) {
                add(CueIssueCode.INVALID_SYNTAX)
                return result()
            }

            val decoded = decode()
            if (decoded == null) {
                add(CueIssueCode.INVALID_ENCODING)
                return result()
            }
            encoding = decoded.encoding
            if (!decoded.valid) {
                add(CueIssueCode.INVALID_ENCODING)
                return result()
            }
            parseText(decoded.text)
            return result()
        }

        private fun decode(): Decoded? {
            val offset = when {
                startsWith(0xEF, 0xBB, 0xBF) -> 3
                startsWith(0xFF, 0xFE) -> 2
                startsWith(0xFE, 0xFF) -> 2
                else -> 0
            }
            if (offset > 0 && bytes.size == offset) return null

            val decoded = when {
                offset == 3 -> decodeBytes("UTF-8", bytes, offset)
                offset == 2 && bytes[0] == 0xFF.toByte() -> decodeBytes("UTF-16LE", bytes, offset)
                offset == 2 -> decodeBytes("UTF-16BE", bytes, offset)
                else -> decodeUtf8ThenGb18030()
            } ?: return null

            val text = decoded.text
            if (text.isEmpty()) return Decoded(decoded.encoding, "", false)
            if (text.any(::isForbiddenControl)) return Decoded(decoded.encoding, "", false)
            return Decoded(decoded.encoding, text, true)
        }

        private fun decodeUtf8ThenGb18030(): Decoded? {
            val utf8 = decodeWithoutException("UTF-8", bytes, 0)
            if (utf8 != null) return Decoded("UTF-8", utf8, true)
            val gb18030 = decodeWithoutException("GB18030", bytes, 0) ?: return null
            return Decoded("GB18030", gb18030, true)
        }

        private fun decodeBytes(name: String, data: ByteArray, offset: Int): Decoded? {
            val text = decodeWithoutException(name, data, offset) ?: return null
            return Decoded(name, text, true)
        }

        private fun parseText(text: String) {
            val lines = splitLines(text.replace("\r\n", "\n").replace('\r', '\n'))
            if (lines.size > MAX_LINES) {
                add(CueIssueCode.LIMIT_REACHED)
                return
            }

            for ((index, rawLine) in lines.withIndex()) {
                val lineNumber = index + 1
                if (rawLine.codePointCount(0, rawLine.length) > MAX_LINE_CHARACTERS) {
                    add(CueIssueCode.LIMIT_REACHED, lineNumber)
                    continue
                }
                val line = rawLine.trim()
                if (line.isEmpty()) continue

                val tokens = splitCommand(line)
                if (tokens == null) {
                    add(CueIssueCode.INVALID_SYNTAX, lineNumber)
                    continue
                }
                handle(tokens, lineNumber)
            }

            finishPendingTrack()
            if (files.isEmpty() && tracks.isEmpty()) add(CueIssueCode.INVALID_SYNTAX)
            if (tracks.isEmpty()) add(CueIssueCode.NO_AUDIO_TRACKS)
            if (files.size > 1) add(CueIssueCode.UNSUPPORTED_MULTI_FILE)
        }

        private fun handle(tokens: List<String>, line: Int) {
            val command = tokens[0].uppercase()
            when (command) {
                "REM" -> Unit
                "TITLE" -> {
                    metadataValue(tokens, line)?.let { value ->
                        if (pendingTrack == null) {
                            if (title != null) add(CueIssueCode.INVALID_SYNTAX, line)
                            else title = value
                        } else {
                            val pending = pendingTrack!!
                            if (pending.title != null) add(CueIssueCode.INVALID_SYNTAX, line)
                            else pending.title = value
                        }
                    }
                }
                "PERFORMER" -> {
                    metadataValue(tokens, line)?.let { value ->
                        if (pendingTrack == null) {
                            if (performer != null) add(CueIssueCode.INVALID_SYNTAX, line)
                            else performer = value
                        } else {
                            val pending = pendingTrack!!
                            if (pending.performer != null) add(CueIssueCode.INVALID_SYNTAX, line)
                            else pending.performer = value
                        }
                    }
                }
                "FILE" -> {
                    finishPendingTrack()
                    currentFileIndex = -1
                    parseFile(tokens, line)
                }
                "TRACK" -> {
                    finishPendingTrack()
                    parseTrack(tokens, line)
                }
                "INDEX" -> parseIndex(tokens, line)
                "FLAGS" -> {
                    if (tokens.size < 2) {
                        add(CueIssueCode.INVALID_SYNTAX, line)
                        return
                    }
                    if (pendingTrack == null) add(CueIssueCode.INVALID_ORDER, line)
                    else add(CueIssueCode.UNSUPPORTED_TRACK, line)
                }
                "PREGAP", "POSTGAP" -> {
                    if (tokens.size != 2) {
                        add(CueIssueCode.INVALID_SYNTAX, line)
                        return
                    }
                    if (pendingTrack == null) {
                        add(CueIssueCode.INVALID_ORDER, line)
                        return
                    }
                    val frame = parseFrame(tokens[1])
                    if (frame == null) add(CueIssueCode.INVALID_SYNTAX, line)
                    else if (frame != 0L) add(CueIssueCode.UNSUPPORTED_GAP, line)
                }
                "CATALOG", "ISRC" -> {
                    if (tokens.size < 2) {
                        add(CueIssueCode.INVALID_SYNTAX, line)
                        return
                    }
                    if (pendingTrack != null && command == "ISRC") add(CueIssueCode.UNSUPPORTED_TRACK, line)
                    else add(CueIssueCode.UNSUPPORTED_DIRECTIVE, line)
                }
                "CDTEXTFILE", "SONGWRITER", "COMPOSER", "ARRANGER", "MESSAGE", "DISC_ID",
                "GENRE", "TOC_INFO1", "TOC_INFO2", "UPC_EAN", "SIZE_INFO" -> {
                    if (tokens.size < 2) add(CueIssueCode.INVALID_SYNTAX, line)
                    else add(CueIssueCode.UNSUPPORTED_DIRECTIVE, line)
                }
                else -> {
                    add(CueIssueCode.UNSUPPORTED_DIRECTIVE, line)
                }
            }
        }

        private fun parseFile(tokens: List<String>, line: Int) {
            val raw = tokens.getOrNull(1) ?: run {
                add(CueIssueCode.INVALID_SYNTAX, line)
                return
            }
            val typeStart = raw.lastIndexOfAny(charArrayOf(' ', '\t')) + 1
            if (typeStart <= 0 || typeStart >= raw.length) {
                add(CueIssueCode.INVALID_SYNTAX, line)
                return
            }
            val reference = unquote(raw.substring(0, typeStart).trimEnd(' ', '\t'))
            val type = unquote(raw.substring(typeStart))?.uppercase()
            if (reference.isNullOrEmpty() || type.isNullOrEmpty()) {
                add(CueIssueCode.INVALID_SYNTAX, line)
                return
            }
            val extension = reference.substringAfterLast('.', "").lowercase()

            if (files.size >= MAX_FILES) {
                add(CueIssueCode.LIMIT_REACHED, line)
                return
            }
            if (!safeReference(reference)) {
                add(CueIssueCode.UNSAFE_REFERENCE, line)
                return
            }
            if (type !in supportedFileTypes || extension !in supportedFileExtensions) {
                add(CueIssueCode.UNSUPPORTED_FILE, line)
                return
            }

            currentFileIndex = files.size
            files.add(CueFile(currentFileIndex, reference, type))
        }

        private fun parseTrack(tokens: List<String>, line: Int) {
            if (tokens.size != 3) {
                add(CueIssueCode.INVALID_SYNTAX, line)
                return
            }
            val number = tokens[1].toIntOrNull()
            val type = tokens[2].uppercase()
            if (number == null || number !in 1..MAX_TRACKS) {
                add(CueIssueCode.INVALID_ORDER, line)
                return
            }
            if (tokens[1].length !in 1..2 || tokens[1].any { !it.isDigit() }) {
                add(CueIssueCode.INVALID_ORDER, line)
                return
            }
            if (type != "AUDIO") {
                add(CueIssueCode.UNSUPPORTED_TRACK, line)
                return
            }
            if (currentFileIndex < 0) {
                add(CueIssueCode.INVALID_ORDER, line)
                return
            }
            if (number <= lastTrackNumber) {
                add(CueIssueCode.INVALID_ORDER, line)
                return
            }
            if (finishedTracks >= MAX_TRACKS) {
                add(CueIssueCode.LIMIT_REACHED, line)
                return
            }
            lastTrackNumber = number
            pendingTrack = PendingTrack(number, currentFileIndex, line)
        }

        private fun parseIndex(tokens: List<String>, line: Int) {
            val pending = pendingTrack
            if (pending == null) {
                add(CueIssueCode.INVALID_ORDER, line)
                return
            }
            if (tokens.size != 3) {
                add(CueIssueCode.INVALID_SYNTAX, line)
                return
            }
            val index = tokens[1].toIntOrNull()
            if (index == null || index !in 0..99) {
                add(CueIssueCode.INVALID_INDEX, line)
                return
            }
            if (tokens[1].length != 2 || tokens[1].any { !it.isDigit() }) {
                add(CueIssueCode.INVALID_INDEX, line)
                return
            }
            val frame = parseFrame(tokens[2]) ?: run {
                add(CueIssueCode.INVALID_INDEX, line)
                return
            }
            if (index == 0) {
                add(CueIssueCode.UNSUPPORTED_GAP, line)
                return
            }
            if (index != 1) {
                add(CueIssueCode.UNSUPPORTED_GAP, line)
                return
            }
            if (pending.hasIndexOne) {
                add(CueIssueCode.INVALID_INDEX, line)
                return
            }
            val previousStart = tracks.lastOrNull()?.takeIf { it.fileIndex == pending.fileIndex }?.startFrame
            if (previousStart != null && frame <= previousStart) {
                add(CueIssueCode.INVALID_INDEX, line)
                return
            }
            pending.startFrame = frame
            pending.hasIndexOne = true
        }

        private fun finishPendingTrack() {
            val pending = pendingTrack ?: return
            pendingTrack = null
            finishedTracks++
            if (pending.startFrame == null) {
                add(CueIssueCode.MISSING_INDEX, pending.line)
                return
            }
            tracks.add(CueTrack(
                number = pending.number,
                fileIndex = pending.fileIndex,
                title = pending.title,
                performer = pending.performer,
                startFrame = pending.startFrame,
                line = pending.line,
            ))
        }

        private fun parseFrame(value: String): Long? {
            val pieces = value.split(':')
            if (pieces.size != 3) return null
            if (pieces[1].length != 2 || pieces[2].length != 2) return null
            if (pieces.any { it.isEmpty() || it.length > MAX_FRAME_DIGITS || it.any { c -> !c.isDigit() } }) {
                return null
            }
            val minutes = pieces[0].toLongOrNull() ?: return null
            val seconds = pieces[1].toLongOrNull() ?: return null
            val frames = pieces[2].toLongOrNull() ?: return null
            if (seconds !in 0..59 || frames !in 0..74) return null
            if (minutes > (Long.MAX_VALUE - seconds * FRAMES_PER_SECOND - frames) / FRAMES_PER_MINUTE) {
                return null
            }
            return minutes * FRAMES_PER_MINUTE + seconds * FRAMES_PER_SECOND + frames
        }

        private fun metadataValue(tokens: List<String>, line: Int): String? {
            if (tokens.size != 2) {
                add(CueIssueCode.INVALID_SYNTAX, line)
                return null
            }
            val value = unquote(tokens[1])
            if (value.isNullOrBlank() || value.any(::isForbiddenControl)) {
                add(CueIssueCode.INVALID_SYNTAX, line)
                return null
            }
            return value
        }

        private fun unquote(value: String): String? {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return null
            val first = trimmed[0]
            if (first != '"' && first != '\'') return trimmed
            if (trimmed.length < 2 || trimmed.last() != first) return null
            val inner = trimmed.substring(1, trimmed.length - 1)
            if (inner.contains(first)) return null
            return inner
        }

        private fun splitCommand(line: String): List<String>? {
            val first = line.indexOfAny(charArrayOf(' ', '\t'))
            val command = if (first < 0) line else line.substring(0, first)
            val rawRest = if (first < 0) "" else line.substring(first + 1)
            val rest = rawRest.trim()
            if (rest.any(::isForbiddenControl)) return null
            if (rest.isEmpty()) return listOf(command)
            if (command.equals("FILE", ignoreCase = true) ||
                command.equals("TITLE", ignoreCase = true) ||
                command.equals("PERFORMER", ignoreCase = true)
            ) {
                return listOf(command, rest)
            }
            val pieces = rest.split(Regex("[\\t ]+"))
            return listOf(command) + pieces
        }

        private fun safeReference(reference: String): Boolean {
            if (reference.isEmpty() || reference.length > MAX_LINE_CHARACTERS) return false
            if (reference == "." || reference == "..") return false
            if (reference.startsWith("/") || reference.startsWith("\\")) return false
            if (uriReference.matches(reference)) return false
            if (unsafeReferenceText.containsMatchIn(reference)) return false
            if (unsafePathSegment.containsMatchIn(reference)) return false
            return true
        }

        private fun add(code: CueIssueCode, line: Int = 0) {
            if (issues.size < MAX_LINES) issues.add(CueIssue(code, line))
        }

        private fun result(): CueSheet {
            return CueSheet(
                title = title,
                performer = performer,
                encoding = encoding,
                files = files.toList(),
                tracks = tracks.toList(),
                issues = issues.toList(),
            )
        }

        private fun startsWith(vararg values: Int): Boolean =
            bytes.size >= values.size &&
                values.indices.all { bytes[it] == values[it].toByte() }

        private fun splitLines(text: String): List<String> {
            val lines = ArrayList<String>()
            var start = 0
            for (index in text.indices) {
                if (text[index] == '\n') {
                    lines.add(text.substring(start, index))
                    start = index + 1
                }
            }
            if (start < text.length) lines.add(text.substring(start))
            return lines
        }

        private fun decodeStrict(name: String, data: ByteArray, offset: Int): String =
            Charset.forName(name).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, offset, data.size - offset))
                .toString()

        private fun decodeWithoutException(name: String, data: ByteArray, offset: Int): String? = try {
            decodeStrict(name, data, offset)
        } catch (_: CharacterCodingException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun isForbiddenControl(character: Char): Boolean {
            val code = character.code
            return code in 0x00..0x08 || code == 0x0B || code == 0x0C ||
                code in 0x0E..0x1F ||
                code in 0x7F..0x9F
        }
    }

    private data class PendingTrack(
        val number: Int,
        val fileIndex: Int,
        val line: Int,
        var title: String? = null,
        var performer: String? = null,
        var startFrame: Long? = null,
        var hasIndexOne: Boolean = false,
    )

    private data class Decoded(val encoding: String, val text: String, val valid: Boolean)
}
