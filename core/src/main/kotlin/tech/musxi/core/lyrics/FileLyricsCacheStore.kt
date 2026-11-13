package tech.musxi.core.lyrics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.zip.CRC32
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey

/**
 * One-file-per-key lyrics cache. Files are partitioned by a validated owner digest and named by a
 * digest of the platform media key, so neither value is used as an unchecked path.
 */
class FileLyricsCacheStore(
    directory: java.io.File,
    private val now: () -> Long = System::currentTimeMillis,
    internal val maxEntries: Int = LyricsCacheStore.MAX_ENTRIES,
    internal val maxBytes: Long = LyricsCacheStore.MAX_BYTES,
) : LyricsCacheStore {
    private val root: Path = directory.toPath().toAbsolutePath().normalize()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxBytes >= MIN_FILE_BYTES) { "maxBytes is too small" }
    }

    @Synchronized
    override fun read(owner: String, key: MediaKey): LyricsCacheHit? {
        val ownerDirectory = ownerDirectory(owner)
        validateMediaKey(key)
        val path = ownerDirectory.resolve(entryName(key))
        try {
            if (!rootExists(create = false)) return null
            if (Files.isSymbolicLink(ownerDirectory) ||
                !Files.isDirectory(ownerDirectory, LinkOption.NOFOLLOW_LINKS)
            ) return null
            if (!isRegularFile(path)) return null

            val entry = readEntry(path, owner, key) ?: return null
            val currentTime = clockNow()
            val expiresAt = saturatingAdd(entry.cachedAtEpochMs, entry.ttlMs)
            val stale = currentTime < entry.cachedAtEpochMs || currentTime >= expiresAt
            if (currentTime > entry.accessedAtEpochMs) {
                val touched = entry.copy(accessedAtEpochMs = currentTime)
                try {
                    atomicReplace(path, encode(touched))
                } catch (error: Throwable) {
                    if (error is VirtualMachineError || error is ThreadDeath) throw error
                    // A successful decode remains a hit even when a best-effort LRU touch fails.
                }
            }
            return LyricsCacheHit(entry.lyrics, entry.binding, entry.cachedAtEpochMs, expiresAt, stale)
        } catch (error: IOException) {
            return null
        } catch (error: RuntimeException) {
            return null
        }
    }

    @Synchronized
    override fun write(
        owner: String,
        key: MediaKey,
        lyrics: ParsedLyrics,
        binding: LyricsCacheBinding?,
    ) {
        ownerDirectory(owner)
        validateMediaKey(key)
        val currentTime = clockNow()
        val copiedLyrics = copyLyrics(lyrics)
        val copiedBinding = copyBinding(binding)
        val ttlMs = if (usesNoLyricsTtl(copiedLyrics)) {
            LyricsCacheStore.NO_LYRICS_TTL_MS
        } else {
            LyricsCacheStore.LYRICS_TTL_MS
        }
        val entry = StoredEntry(
            owner = owner,
            key = key,
            cachedAtEpochMs = currentTime,
            accessedAtEpochMs = currentTime,
            ttlMs = ttlMs,
            binding = copiedBinding,
            lyrics = copiedLyrics,
        )
        val encoded = encode(entry)
        require(encoded.size.toLong() <= maxBytes) { "Lyrics cache entry exceeds capacity" }

        try {
            val ownerDirectory = prepareOwnerDirectory(owner)
            atomicReplace(ownerDirectory.resolve(entryName(key)), encoded)
            evictToLimits()
        } catch (error: IOException) {
            throw IllegalStateException(UNAVAILABLE, error)
        } catch (error: RuntimeException) {
            throw IllegalStateException(UNAVAILABLE, error)
        }
    }

    @Synchronized
    override fun clearOwner(owner: String) {
        val ownerDirectory = ownerDirectory(owner)
        try {
            if (!rootExists(create = false)) return
            clearOwnerDirectory(ownerDirectory)
        } catch (error: IOException) {
            throw IllegalStateException(UNAVAILABLE, error)
        } catch (error: RuntimeException) {
            throw IllegalStateException(UNAVAILABLE, error)
        }
    }

    @Synchronized
    override fun clear() {
        try {
            if (!rootExists(create = false)) return
            val owners = ArrayList<Path>()
            Files.newDirectoryStream(root).use { children ->
                for (child in children) {
                    if (OWNER.matches(child.fileName.toString())) owners.add(child)
                }
            }
            owners.forEach(::clearOwnerDirectory)
        } catch (error: IOException) {
            throw IllegalStateException(UNAVAILABLE, error)
        } catch (error: RuntimeException) {
            throw IllegalStateException(UNAVAILABLE, error)
        }
    }

    override fun toString(): String = "FileLyricsCacheStore(redacted)"

    @Synchronized
    fun clearPlatform(platform: Platform) {
        ownerDirectories().forEach { directory ->
            Files.newDirectoryStream(directory, "*.mxl").use { files ->
                files.forEach { file ->
                    val entry = readEntry(file)
                    if (entry?.key?.platform == platform) delete(file)
                }
            }
            deleteIfEmptyDirectory(directory)
        }
    }

    private fun readEntry(path: Path, expectedOwner: String, expectedKey: MediaKey): StoredEntry? {
        if (!isRegularFile(path) || Files.size(path) > maxBytes) return null
        val bytes = try {
            Files.readAllBytes(path)
        } catch (error: IOException) {
            return null
        }
        val entry = decode(bytes)
        if (entry == null || entry.owner != expectedOwner || entry.key != expectedKey ||
            entry.ttlMs != expectedTtl(entry.lyrics)
        ) {
            deleteQuietly(path)
            return null
        }
        return entry
    }

    private fun encode(entry: StoredEntry): ByteArray {
        validateStoredEntry(entry)
        val payloadLimit = minOf(maxBytes, Int.MAX_VALUE.toLong()).toInt() - HEADER_BYTES
        val payloadBuffer = LimitedByteArrayOutputStream(payloadLimit)
        try {
            DataOutputStream(payloadBuffer).use { output ->
                writeString(output, entry.owner, MAX_OWNER_BYTES)
                output.writeByte(entry.key.platform.ordinal)
                writeString(output, entry.key.id, MAX_KEY_BYTES)
                output.writeLong(entry.cachedAtEpochMs)
                output.writeLong(entry.accessedAtEpochMs)
                output.writeLong(entry.ttlMs)
                writeBinding(output, entry.binding)
                writeLyrics(output, entry.lyrics)
            }
        } catch (_: EntryTooLarge) {
            throw IllegalArgumentException("Lyrics cache entry exceeds capacity")
        }

        val payload = payloadBuffer.toByteArray()
        val crc = CRC32().apply { update(payload) }.value.toInt()
        val result = ByteArray(HEADER_BYTES + payload.size)
        val header = ByteArrayOutputStream(HEADER_BYTES)
        DataOutputStream(header).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(CURRENT_VERSION)
            output.writeInt(payload.size)
            output.writeInt(crc)
        }
        System.arraycopy(header.toByteArray(), 0, result, 0, HEADER_BYTES)
        System.arraycopy(payload, 0, result, HEADER_BYTES, payload.size)
        return result
    }

    private fun decode(bytes: ByteArray): StoredEntry? {
        if (bytes.size !in HEADER_BYTES..minOf(maxBytes, Int.MAX_VALUE.toLong()).toInt()) return null
        return try {
            val input = ByteArrayInputStream(bytes)
            val header = DataInputStream(input)
            if (header.readInt() != MAGIC) return null
            val version = header.readInt()
            if (version !in MIN_VERSION..CURRENT_VERSION) return null
            val payloadLength = header.readInt()
            val expectedCrc = header.readInt()
            if (payloadLength < 0 || payloadLength != bytes.size - HEADER_BYTES) return null

            val payload = ByteArray(payloadLength)
            header.readFully(payload)
            if (input.available() != 0) return null
            val actualCrc = CRC32().apply { update(payload) }.value.toInt()
            if (actualCrc != expectedCrc) return null

            val data = DataInputStream(ByteArrayInputStream(payload))
            val owner = readString(data, MAX_OWNER_BYTES)
            if (!OWNER.matches(owner)) return null
            val platform = Platform.entries.getOrNull(data.readUnsignedByte()) ?: return null
            val key = mediaKey(platform, readString(data, MAX_KEY_BYTES)) ?: return null
            val cachedAt = data.readLong()
            val accessedAt = data.readLong()
            val ttlMs = data.readLong()
            val binding = readBinding(data)
            val lyrics = readLyrics(data, version)
            if (data.available() != 0) return null

            StoredEntry(owner, key, cachedAt, accessedAt, ttlMs, binding, lyrics)
                .also(::validateStoredEntry)
        } catch (_: InvalidData) {
            null
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun writeLyrics(output: DataOutputStream, lyrics: ParsedLyrics) {
        output.writeInt(lyrics.lines.size)
        lyrics.lines.forEach { line ->
            output.writeLong(line.timeMs)
            writeString(output, line.text, MAX_LINE_BYTES)
            output.writeBoolean(line.translation != null)
            line.translation?.let { writeString(output, it, MAX_LINE_BYTES) }
            output.writeInt(line.words.size)
            line.words.forEach { word ->
                output.writeInt(word.textStart)
                output.writeInt(word.textEnd)
                output.writeLong(word.startTimeMs)
                output.writeLong(word.endTimeMs)
            }
        }
        writeString(output, lyrics.plainText, MAX_PLAIN_TEXT_BYTES)
        output.writeByte(lyrics.translation.availability.ordinal)
        output.writeByte(lyrics.translation.lookup.status.ordinal)
        output.writeBoolean(lyrics.translation.lookup.reason != null)
        lyrics.translation.lookup.reason?.let { output.writeByte(it.ordinal) }
        output.writeInt(lyrics.timingRevision)
    }

    private fun readLyrics(input: DataInputStream, version: Int): ParsedLyrics {
        val lineCount = input.readInt()
        if (lineCount !in 0..MAX_LINES) invalidData()
        val lines = ArrayList<LyricLine>(lineCount)
        var remainingWords = LyricsWordTimeline.MAX_WORDS_PER_DOCUMENT
        repeat(lineCount) {
            val timeMs = input.readLong()
            val text = readString(input, MAX_LINE_BYTES)
            val translation = if (input.readBoolean()) readString(input, MAX_LINE_BYTES) else null
            val words = if (version >= WORDS_VERSION) {
                readWords(input, text, remainingWords)
            } else {
                emptyList()
            }
            remainingWords -= words.size
            lines.add(LyricLine(timeMs, text, translation, words))
        }
        val plainText = readString(input, MAX_PLAIN_TEXT_BYTES)
        val availability = TranslationAvailability.entries.getOrNull(input.readUnsignedByte())
            ?: invalidData()
        val lookupStatus = TranslationLookupStatus.entries.getOrNull(input.readUnsignedByte())
            ?: invalidData()
        val reason = if (input.readBoolean()) {
            TranslationReason.entries.getOrNull(input.readUnsignedByte()) ?: invalidData()
        } else {
            null
        }
        val timingRevision = if (version >= TIMING_REVISION_VERSION) {
            val stored = input.readInt()
            if (stored !in 0..MAX_TIMING_REVISION) invalidData()
            stored
        } else {
            0
        }
        return ParsedLyrics(
            lines = immutableList(lines),
            plainText = plainText,
            translation = LyricsTranslation(availability, TranslationLookup(lookupStatus, reason)),
            timingRevision = timingRevision,
        )
    }

    /**
     * Reads per-line word timings within [remainingBudget] so an oversized count is rejected before
     * any list or array is allocated for it.
     */
    private fun readWords(input: DataInputStream, text: String, remainingBudget: Int): List<LyricWord> {
        val wordCount = input.readInt()
        if (wordCount !in 0..LyricsWordTimeline.MAX_WORDS_PER_LINE) invalidData()
        if (wordCount > remainingBudget) invalidData()
        if (wordCount > input.available() / WORD_BYTES) invalidData()
        val words = ArrayList<LyricWord>(wordCount)
        repeat(wordCount) {
            words.add(
                LyricWord(
                    textStart = input.readInt(),
                    textEnd = input.readInt(),
                    startTimeMs = input.readLong(),
                    endTimeMs = input.readLong(),
                )
            )
        }
        val immutable = immutableList(words)
        if (!LyricsWordTimeline.valid(text, immutable)) invalidData()
        return immutable
    }

    private fun writeBinding(output: DataOutputStream, binding: LyricsCacheBinding?) {
        output.writeBoolean(binding != null)
        if (binding == null) return
        output.writeBoolean(binding.origin != null)
        binding.origin?.let {
            output.writeByte(it.platform.ordinal)
            writeString(output, it.id, MAX_KEY_BYTES)
        }
        output.writeLong(binding.offsetMs)
        output.writeInt(binding.candidateKeys.size)
        binding.candidateKeys.forEach {
            output.writeByte(it.platform.ordinal)
            writeString(output, it.id, MAX_KEY_BYTES)
        }
    }

    private fun readBinding(input: DataInputStream): LyricsCacheBinding? {
        if (!input.readBoolean()) return null
        val origin = if (input.readBoolean()) {
            val platform = Platform.entries.getOrNull(input.readUnsignedByte()) ?: invalidData()
            mediaKey(platform, readString(input, MAX_KEY_BYTES)) ?: invalidData()
        } else {
            null
        }
        val offsetMs = input.readLong()
        val candidateCount = input.readInt()
        if (candidateCount !in 0..MAX_BINDING_CANDIDATES) invalidData()
        val candidates = ArrayList<MediaKey>(candidateCount)
        repeat(candidateCount) {
            val platform = Platform.entries.getOrNull(input.readUnsignedByte()) ?: invalidData()
            candidates.add(mediaKey(platform, readString(input, MAX_KEY_BYTES)) ?: invalidData())
        }
        return LyricsCacheBinding(origin, offsetMs, immutableList(candidates))
    }

    private fun copyLyrics(lyrics: ParsedLyrics): ParsedLyrics {
        // Bound the input before copying so an oversized document is rejected without allocating
        // a full duplicate of every line and word list first.
        if (lyrics.lines.size > MAX_LINES) throw IllegalArgumentException("Invalid lyrics cache entry")
        var totalWords = 0L
        lyrics.lines.forEach { line ->
            if (line.words.size > LyricsWordTimeline.MAX_WORDS_PER_LINE) {
                throw IllegalArgumentException("Invalid lyrics cache entry")
            }
            totalWords += line.words.size
            if (totalWords > LyricsWordTimeline.MAX_WORDS_PER_DOCUMENT) {
                throw IllegalArgumentException("Invalid lyrics cache entry")
            }
        }
        val copied = ParsedLyrics(
            lines = immutableList(
                lyrics.lines.map { line ->
                    LyricLine(
                        timeMs = line.timeMs,
                        text = line.text,
                        translation = line.translation,
                        words = immutableList(
                            line.words.map {
                                LyricWord(it.textStart, it.textEnd, it.startTimeMs, it.endTimeMs)
                            }
                        ),
                    )
                }
            ),
            plainText = lyrics.plainText,
            translation = LyricsTranslation(
                availability = lyrics.translation.availability,
                lookup = TranslationLookup(
                    status = lyrics.translation.lookup.status,
                    reason = lyrics.translation.lookup.reason,
                ),
            ),
            timingRevision = lyrics.timingRevision,
        )
        try {
            validateLyrics(copied)
        } catch (_: EntryTooLarge) {
            throw IllegalArgumentException("Lyrics cache entry exceeds capacity")
        } catch (_: InvalidData) {
            throw IllegalArgumentException("Invalid lyrics cache entry")
        }
        return copied
    }

    private fun copyBinding(binding: LyricsCacheBinding?): LyricsCacheBinding? {
        if (binding == null) return null
        if (binding.offsetMs !in -MAX_TIME_MS..MAX_TIME_MS ||
            binding.candidateKeys.size > MAX_BINDING_CANDIDATES
        ) {
            throw IllegalArgumentException("Invalid lyrics cache binding")
        }
        binding.origin?.let(::validateMediaKey)
        binding.candidateKeys.forEach(::validateMediaKey)
        return LyricsCacheBinding(
            origin = binding.origin,
            offsetMs = binding.offsetMs,
            candidateKeys = immutableList(binding.candidateKeys.map { MediaKey(it.platform, it.id) }),
        )
    }

    private fun validateStoredEntry(entry: StoredEntry) {
        if (!OWNER.matches(entry.owner)) invalidData()
        validateMediaKey(entry.key)
        if (entry.cachedAtEpochMs < 0 || entry.accessedAtEpochMs < entry.cachedAtEpochMs ||
            entry.ttlMs != expectedTtl(entry.lyrics)
        ) invalidData()
        try {
            validateLyrics(entry.lyrics)
            entry.binding?.let { binding ->
                if (binding.offsetMs !in -MAX_TIME_MS..MAX_TIME_MS ||
                    binding.candidateKeys.size > MAX_BINDING_CANDIDATES
                ) invalidData()
                binding.origin?.let(::validateMediaKey)
                binding.candidateKeys.forEach(::validateMediaKey)
            }
        } catch (_: IllegalArgumentException) {
            invalidData()
        } catch (_: EntryTooLarge) {
            invalidData()
        }
    }

    private fun validateLyrics(lyrics: ParsedLyrics) {
        if (lyrics.lines.size > MAX_LINES) invalidData()
        var totalWords = 0L
        lyrics.lines.forEach {
            if (it.timeMs !in 0L..MAX_TIME_MS) invalidData()
            validateText(it.text, MAX_LINE_BYTES)
            it.translation?.let { translation -> validateText(translation, MAX_LINE_BYTES) }
            totalWords += it.words.size
            if (totalWords > LyricsWordTimeline.MAX_WORDS_PER_DOCUMENT) invalidData()
            if (!LyricsWordTimeline.valid(it.text, it.words)) invalidData()
        }
        validateText(lyrics.plainText, MAX_PLAIN_TEXT_BYTES)
        if (lyrics.timingRevision !in 0..MAX_TIMING_REVISION) invalidData()
    }

    private fun validateText(value: String, maxBytes: Int) {
        if (value.length > maxBytes) invalidData()
        val encoded = utf8(value)
        if (encoded.size > maxBytes) invalidData()
    }

    private fun writeString(output: DataOutputStream, value: String, maxBytes: Int) {
        val bytes = utf8(value)
        if (bytes.size > maxBytes) throw EntryTooLarge()
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream, maxBytes: Int): String {
        val length = input.readInt()
        if (length !in 0..maxBytes || length > input.available()) invalidData()
        val bytes = ByteArray(length)
        input.readFully(bytes)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            invalidData()
        }
    }

    private fun utf8(value: String): ByteArray {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (String(bytes, StandardCharsets.UTF_8) != value) invalidData()
        return bytes
    }

    private fun evictToLimits() {
        if (!rootExists(create = false)) return
        removeTemporaryFiles()
        val entries = ArrayList<CacheFile>()
        ownerDirectories().forEach { ownerDirectory ->
            try {
                Files.newDirectoryStream(ownerDirectory, "*.mxl").use { files ->
                    for (path in files) {
                        if (!isRegularFile(path)) continue
                        val entry = readEntry(path) ?: continue
                        entries.add(CacheFile(path, entry.accessedAtEpochMs, Files.size(path)))
                    }
                }
            } catch (_: IOException) {
                // One unreadable owner directory must not disable the rest of the cache.
            }
        }
        entries.sortWith(compareBy<CacheFile> { it.accessedAtEpochMs }.thenBy { it.path.toString() })
        var totalBytes = entries.sumOf { it.bytes }
        var index = 0
        while (entries.size - index > maxEntries || totalBytes > maxBytes) {
            val victim = entries[index++]
            if (delete(victim.path)) totalBytes -= victim.bytes
        }
        ownerDirectories().forEach(::deleteIfEmptyDirectory)
    }

    private fun readEntry(path: Path): StoredEntry? {
        if (!isRegularFile(path) || Files.size(path) > maxBytes) {
            deleteQuietly(path)
            return null
        }
        val bytes = try {
            Files.readAllBytes(path)
        } catch (_: IOException) {
            return null
        }
        return decode(bytes).also { if (it == null) deleteQuietly(path) }
    }

    private fun removeTemporaryFiles() {
        ownerDirectories().forEach { ownerDirectory ->
            try {
                Files.newDirectoryStream(ownerDirectory, "*.tmp").use { files ->
                    files.forEach(::deleteQuietly)
                }
            } catch (_: IOException) {
                // Temporary files are also retried by a later write.
            }
        }
    }

    private fun ownerDirectories(): List<Path> {
        if (!rootExists(create = false)) return emptyList()
        val result = ArrayList<Path>()
        try {
            Files.newDirectoryStream(root).use { children ->
                for (child in children) {
                    if (OWNER.matches(child.fileName.toString()) &&
                        !Files.isSymbolicLink(child) &&
                        Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                    ) result.add(child)
                }
            }
        } catch (_: IOException) {
            return emptyList()
        }
        return result
    }

    private fun clearOwnerDirectory(ownerDirectory: Path) {
        if (Files.isSymbolicLink(ownerDirectory)) {
            Files.deleteIfExists(ownerDirectory)
            return
        }
        if (!Files.isDirectory(ownerDirectory, LinkOption.NOFOLLOW_LINKS)) return
        Files.newDirectoryStream(ownerDirectory).use { children ->
            for (child in children) {
                if (OWNED_FILE.matches(child.fileName.toString())) delete(child)
            }
        }
        deleteIfEmptyDirectory(ownerDirectory)
    }

    private fun deleteIfEmptyDirectory(path: Path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return
        try {
            Files.newDirectoryStream(path).use { if (!it.iterator().hasNext()) Files.deleteIfExists(path) }
        } catch (_: IOException) {
            // A populated or concurrently changed directory is left in place.
        }
    }

    private fun prepareOwnerDirectory(owner: String): Path {
        val ownerDirectory = ownerDirectory(owner)
        ensureRoot(create = true)
        if (Files.isSymbolicLink(ownerDirectory)) throw IOException(UNAVAILABLE)
        if (!Files.exists(ownerDirectory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(ownerDirectory)
        }
        // Parent and child must use the same canonicalization. Android's NOFOLLOW
        // path can retain a parent alias that the real root has already expanded.
        // Reject the child link both before and after resolving, and still require
        // the canonical child to be exactly the expected direct descendant.
        val realRoot = root.toRealPath()
        val expected = realRoot.resolve(owner).normalize()
        val actual = ownerDirectory.toRealPath().normalize()
        if (actual.parent != realRoot || actual != expected || expected.parent != realRoot) {
            throw IOException(UNAVAILABLE)
        }
        if (Files.isSymbolicLink(ownerDirectory) ||
            !Files.isDirectory(ownerDirectory, LinkOption.NOFOLLOW_LINKS)
        ) throw IOException(UNAVAILABLE)
        return ownerDirectory
    }

    private fun atomicReplace(target: Path, bytes: ByteArray) {
        if (Files.isSymbolicLink(target) ||
            (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
        ) throw IOException(UNAVAILABLE)
        val temporary = target.resolveSibling("${target.fileName}.${UUID.randomUUID()}.tmp")
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(temporary)) {
            throw IOException(UNAVAILABLE)
        }
        try {
            java.io.FileOutputStream(temporary.toFile()).use {
                it.write(bytes)
                it.fd.sync()
            }
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            deleteQuietly(temporary)
            throw error
        }
    }

    private fun ownerDirectory(owner: String): Path {
        if (!OWNER.matches(owner)) throw IllegalArgumentException("Invalid lyrics cache owner")
        val candidate = root.resolve(owner).normalize()
        if (candidate.parent != root || candidate.nameCount != root.nameCount + 1) {
            throw IllegalArgumentException("Invalid lyrics cache owner")
        }
        return candidate
    }

    private fun entryName(key: MediaKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "${key.platform.name}\n${key.id}".toByteArray(StandardCharsets.UTF_8)
        )
        return buildString(digest.size * 2 + 4) {
            digest.forEach {
                append(HEX[(it.toInt() ushr 4) and 0xf])
                append(HEX[it.toInt() and 0xf])
            }
            append(".mxl")
        }
    }

    private fun validateMediaKey(key: MediaKey) {
        try {
            when (key.platform) {
                Platform.NETEASE -> {
                    if (!key.id.matches(NETEASE_KEY) || key.id.toLongOrNull() == null) invalidData()
                }
                Platform.QQ -> if (!key.id.matches(QQ_KEY)) invalidData()
                Platform.KUGOU -> {
                    val parts = key.id.split('_')
                    if (parts.size != 3 || !parts[0].matches(KUGOU_HASH) ||
                        !kugouNumber(parts[1]) || !kugouNumber(parts[2])
                    ) invalidData()
                }
            }
        } catch (_: IllegalArgumentException) {
            invalidData()
        }
    }

    private fun mediaKey(platform: Platform, id: String): MediaKey? = try {
        MediaKey(platform, id).also(::validateMediaKey)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun kugouNumber(value: String): Boolean =
        value.matches(KUGOU_NUMBER) &&
            (value.toLongOrNull() ?: Long.MAX_VALUE) <= 9_007_199_254_740_991L

    private fun rootExists(create: Boolean): Boolean {
        if (Files.isSymbolicLink(root)) throw IOException(UNAVAILABLE)
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) return false
            ensureRoot(create = true)
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw IOException(UNAVAILABLE)
        return true
    }

    private fun ensureRoot(create: Boolean) {
        if (Files.isSymbolicLink(root)) throw IOException(UNAVAILABLE)
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw IOException(UNAVAILABLE)
            return
        }
        if (create) Files.createDirectories(root)
    }

    private fun isRegularFile(path: Path): Boolean =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)

    private fun delete(path: Path): Boolean = try {
        Files.deleteIfExists(path)
    } catch (_: IOException) {
        false
    }

    private fun deleteQuietly(path: Path) {
        delete(path)
    }

    private fun clockNow(): Long {
        val value = now()
        if (value < 0) throw IllegalArgumentException("Invalid lyrics cache clock")
        return value
    }

    private fun expectedTtl(lyrics: ParsedLyrics): Long = if (usesNoLyricsTtl(lyrics)) {
        LyricsCacheStore.NO_LYRICS_TTL_MS
    } else {
        LyricsCacheStore.LYRICS_TTL_MS
    }

    private fun usesNoLyricsTtl(lyrics: ParsedLyrics): Boolean =
        lyrics.lines.isEmpty() && lyrics.plainText.isBlank()

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun invalidData(): Nothing = throw InvalidData()

    private data class StoredEntry(
        val owner: String,
        val key: MediaKey,
        val cachedAtEpochMs: Long,
        val accessedAtEpochMs: Long,
        val ttlMs: Long,
        val binding: LyricsCacheBinding?,
        val lyrics: ParsedLyrics,
    )

    private data class CacheFile(val path: Path, val accessedAtEpochMs: Long, val bytes: Long)

    private class InvalidData : RuntimeException()
    private class EntryTooLarge : RuntimeException()

    private class LimitedByteArrayOutputStream(private val limit: Int) : OutputStream() {
        private val delegate = ByteArrayOutputStream()

        override fun write(value: Int) {
            if (delegate.size() >= limit) throw EntryTooLarge()
            delegate.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length > limit - delegate.size()) throw EntryTooLarge()
            delegate.write(bytes, offset, length)
        }

        fun toByteArray(): ByteArray = delegate.toByteArray()
    }

    companion object {
        private const val MAGIC = 0x4d584c31
        /**
         * Schema 1 stored line-level timings only; schema 2 appends per-line word spans; schema 3
         * appends the provider normalization generation.
         */
        private const val MIN_VERSION = 1
        private const val WORDS_VERSION = 2
        private const val TIMING_REVISION_VERSION = 3
        private const val CURRENT_VERSION = TIMING_REVISION_VERSION
        private const val HEADER_BYTES = 16
        private const val WORD_BYTES = 24
        private const val MAX_TIMING_REVISION = 1_000_000
        private const val MIN_FILE_BYTES = 256L
        private const val MAX_LINES = 20_000
        private const val MAX_BINDING_CANDIDATES = 32
        private const val MAX_OWNER_BYTES = 64
        private const val MAX_KEY_BYTES = 128
        private const val MAX_LINE_BYTES = 64 * 1024
        private const val MAX_PLAIN_TEXT_BYTES = 4 * 1024 * 1024
        private const val MAX_TIME_MS = 24L * 60L * 60L * 1_000L
        private const val UNAVAILABLE = "Lyrics cache unavailable"
        private val OWNER = Regex("[0-9a-f]{64}")
        private val OWNED_FILE = Regex("[0-9a-f]{64}\\.mxl(?:\\.[0-9a-f-]{36}\\.tmp)?")
        private val NETEASE_KEY = Regex("[1-9][0-9]{0,18}")
        private val QQ_KEY = Regex("[A-Za-z0-9]{14}")
        private val KUGOU_HASH = Regex("[0-9a-f]{32}")
        private val KUGOU_NUMBER = Regex("(?:0|[1-9][0-9]{0,15})")
        private val HEX = "0123456789abcdef".toCharArray()

        private fun <T> immutableList(values: List<T>): List<T> =
            Collections.unmodifiableList(ArrayList(values))
    }
}
