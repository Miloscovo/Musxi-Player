package tech.musxi.core.localaudio

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** A published managed copy. [name] is the 64 hex content key; the file is `name.audio`. */
data class CopiedLocalAudio(val name: String, val bytes: Long, val created: Boolean)

/**
 * Content-addressed file layer for `LocalImportMode.COPY`.
 *
 * [directory] is the dedicated directory itself, e.g. `File(filesDir, "local-audio-v1")`; copies
 * are `<sha256>.audio` inside it and become visible only through an fsync'd `part-*.tmp` file moved
 * with ATOMIC_MOVE, so a visible copy is always complete. Every method validates that the directory
 * still resolves to the same canonical parent.
 *
 * Copies are serialized. Resolving an immutable copy and collecting an unreferenced copy do not
 * wait behind a large import; the library owner coordinates these short index/file transactions.
 * [cleanupAbandoned] and [prune] run only at startup, before any imports or playback.
 *
 * This layer stores bytes only. Publishing a record, keeping the copy until that succeeds, and
 * calling [discard] for a copy that no record references is the caller's duty.
 */
class LocalAudioFiles(
    directory: File,
    internal val maxBytes: Long = LocalAudioLimits.MAX_FILE_BYTES,
) {
    private val root: Path = directory.toPath().toAbsolutePath().normalize()
    private val expectedParent: Path? = root.parent?.toFile()?.canonicalFile?.toPath()

    init {
        require(maxBytes in 1..LocalAudioLimits.MAX_FILE_BYTES) { "Invalid local audio byte limit" }
        require(expectedParent != null && root.nameCount >= 1) { "Local audio directory is not usable" }
    }

    /**
     * Streams [openInput] into a new managed copy while hashing the same buffer, so the audio is
     * never held in memory and never read twice. The content key is known only after the last
     * byte; [expectedBytes] also serves as the early size check.
     */
    @Synchronized
    fun copy(
        openInput: () -> InputStream,
        expectedBytes: Long? = null,
        checkCancelled: () -> Unit = {},
        progress: (Long) -> Unit = {},
    ): CopiedLocalAudio {
        validateExpectedBytes(expectedBytes)
        checkCancelled()
        ensureRoot()
        requireSpace(expectedBytes)

        val temporary = root.resolve(TEMP_PREFIX + UUID.randomUUID() + TEMP_SUFFIX)
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(temporary)) {
            throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0L
            var nextSpaceCheck = SPACE_CHECK_BYTES
            val source = openInputOrFail(openInput)
            try {
                FileOutputStream(temporary.toFile()).use { output ->
                    while (true) {
                        checkCancelled()
                        val count = readChunk(source, buffer)
                        if (count < 0) break
                        val next = total + count
                        // Limits are checked before the bytes are written or digested, so an
                        // oversized source never touches the device beyond the limit.
                        if (next > maxBytes) throw LocalAudioFailure(LocalAudioError.TOO_LARGE)
                        if (expectedBytes != null && next > expectedBytes) {
                            throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
                        }
                        digest.update(buffer, 0, count)
                        try {
                            output.write(buffer, 0, count)
                        } catch (_: IOException) {
                            throw failureForWrite()
                        }
                        total = next
                        if (total >= nextSpaceCheck) {
                            checkSpaceDuringCopy()
                            nextSpaceCheck = total + SPACE_CHECK_BYTES
                        }
                        progress(total)
                    }
                    try {
                        output.flush()
                        output.fd.sync()
                    } catch (_: IOException) {
                        throw failureForWrite()
                    }
                }
            } finally {
                runCatching { source.close() }
            }
            if (expectedBytes != null && total != expectedBytes) {
                throw LocalAudioFailure(LocalAudioError.SOURCE_CHANGED)
            }
            // LocalAudioMetadata only accepts 1..MAX_FILE_BYTES, so an empty copy could never be
            // registered and would only become an orphan.
            if (total == 0L) throw LocalAudioFailure(LocalAudioError.READ_FAILED)
            val name = hex(digest.digest())
            val created = publish(temporary, managedPath(name), total)
            return CopiedLocalAudio(name, total, created)
        } catch (error: Throwable) {
            deleteQuietly(temporary)
            throw when (error) {
                is LocalAudioFailure -> error
                is IOException -> failureForWrite()
                is SecurityException -> LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
                else -> error
            }
        }
    }

    /** Cheap lookup for playback: validates the key and never rehashes the audio. */
    fun resolve(name: String): File {
        val path = managedPath(name)
        requireRootDirectory()
        if (!isOwnedFile(path)) throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
        return path.toFile()
    }

    /** Deletes exactly one managed copy. A missing copy is already in the desired state. */
    fun discard(name: String) {
        val path = managedPath(name)
        if (!rootDirectoryExists()) return
        if (!isOwnedFile(path)) return
        try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
            throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        }
    }

    /**
     * Removes only the `part-*.tmp` staging files this layer creates, e.g. after the process died
     * mid-copy. Published copies are never touched.
     */
    @Synchronized
    fun cleanupAbandoned() {
        ownDirectoryEntries()?.forEach { path ->
            if (!TEMP_NAME.matches(path.fileName.toString())) return@forEach
            if (!isOwnedFile(path)) return@forEach
            deleteQuietly(path)
        }
    }

    /**
     * Drops published copies that no record references — the state left when the process died
     * between publishing a copy and committing the row that points at it. Unknown files and names
     * outside this layer's namespace are kept.
     */
    @Synchronized
    fun prune(keep: Set<String>) {
        if (keep.any { !NAME.matches(it) }) throw LocalAudioFailure(LocalAudioError.INVALID_NAME)
        var failed = false
        ownDirectoryEntries()?.forEach { path ->
            val fileName = path.fileName.toString()
            if (!FILE_NAME.matches(fileName)) return@forEach
            if (keep.contains(fileName.removeSuffix(EXTENSION))) return@forEach
            if (!isOwnedFile(path)) return@forEach
            if (!deleteQuietly(path)) failed = true
        }
        if (failed) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
    }

    override fun toString(): String = "LocalAudioFiles(redacted)"

    /** Publishes through an atomic move; an identical existing copy is reused, never replaced. */
    private fun publish(temporary: Path, target: Path, bytes: Long): Boolean {
        if (Files.isSymbolicLink(target)) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            reuseOrFail(target, bytes)
            deleteQuietly(temporary)
            return false
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            return true
        } catch (_: FileAlreadyExistsException) {
            deleteQuietly(temporary)
            reuseOrFail(target, bytes)
            return false
        } catch (_: IOException) {
            throw failureForWrite()
        }
    }

    private fun reuseOrFail(target: Path, bytes: Long) {
        if (!isOwnedFile(target)) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        val existing = try {
            Files.size(target)
        } catch (_: IOException) {
            throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        }
        // The same content key with a different length means the stored copy is damaged. It is
        // reported instead of overwritten, matching "reuse, never replace".
        if (existing != bytes) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
    }

    private fun readChunk(source: InputStream, buffer: ByteArray): Int = try {
        var read = source.read(buffer)
        // Some streams report 0 without being at the end; read a single byte so the copy loop
        // cannot spin forever.
        if (read == 0) {
            val single = source.read()
            if (single >= 0) {
                buffer[0] = single.toByte()
                read = 1
            } else {
                read = -1
            }
        }
        if (read > buffer.size) throw LocalAudioFailure(LocalAudioError.READ_FAILED)
        read
    } catch (failure: LocalAudioFailure) {
        throw failure
    } catch (_: IOException) {
        throw LocalAudioFailure(LocalAudioError.READ_FAILED)
    } catch (_: SecurityException) {
        throw LocalAudioFailure(LocalAudioError.READ_FAILED)
    }

    private fun openInputOrFail(openInput: () -> InputStream): InputStream = try {
        openInput()
    } catch (failure: LocalAudioFailure) {
        throw failure
    } catch (_: IOException) {
        throw LocalAudioFailure(LocalAudioError.READ_FAILED)
    } catch (_: SecurityException) {
        throw LocalAudioFailure(LocalAudioError.READ_FAILED)
    }

    private fun validateExpectedBytes(expectedBytes: Long?) {
        if (expectedBytes == null) return
        if (expectedBytes > maxBytes) throw LocalAudioFailure(LocalAudioError.TOO_LARGE)
        if (expectedBytes < 1L) throw LocalAudioFailure(LocalAudioError.READ_FAILED)
    }

    private fun failureForWrite(): LocalAudioFailure {
        val usable = usableBytes()
        val plenty = usable <= 0L || usable >= RESERVED_SPACE_BYTES
        return LocalAudioFailure(
            if (plenty) LocalAudioError.STORAGE_FAILED else LocalAudioError.NOT_ENOUGH_SPACE,
        )
    }

    private fun requireSpace(expectedBytes: Long?) {
        val usable = usableBytes()
        // An unknown measurement (0) must not block an import that may still succeed.
        if (usable <= 0L) return
        if (usable < RESERVED_SPACE_BYTES + (expectedBytes ?: 0L)) {
            throw LocalAudioFailure(LocalAudioError.NOT_ENOUGH_SPACE)
        }
    }

    private fun checkSpaceDuringCopy() {
        val usable = usableBytes()
        if (usable <= 0L) return
        if (usable < RESERVED_SPACE_BYTES) throw LocalAudioFailure(LocalAudioError.NOT_ENOUGH_SPACE)
    }

    private fun usableBytes(): Long = try {
        root.toFile().usableSpace
    } catch (_: SecurityException) {
        0L
    }

    private fun ensureRoot() {
        val parentValid = try { root.parent.toRealPath() == expectedParent } catch (_: IOException) { false }
        if (!parentValid) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        if (Files.isSymbolicLink(root)) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(root)
            } catch (_: IOException) {
                throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
            }
        }
        requireRootDirectory()
    }

    /**
     * The managed directory must exist, be a real directory, and its canonical parent must equal
     * the canonical parent of the configured path, so an alias or link cannot redirect it.
     */
    private fun requireRootDirectory() {
        if (Files.isSymbolicLink(root)) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            throw LocalAudioFailure(LocalAudioError.NOT_FOUND)
        }
        if (!isDirectoryNoFollow(root)) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        val expected = expectedParent
        val valid = try {
            val realRoot = root.toRealPath()
            val realParent = expected
            realParent != null && realRoot.parent == realParent &&
                realRoot == realParent.resolve(root.fileName.toString())
        } catch (_: IOException) {
            false
        }
        if (!valid) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
    }

    /** True when the managed directory is absent; throws when it exists but is unusable. */
    private fun rootDirectoryExists(): Boolean {
        if (Files.isSymbolicLink(root)) throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return false
        requireRootDirectory()
        return true
    }

    /** Direct children of the managed directory, or null when it does not exist. */
    private fun ownDirectoryEntries(): List<Path>? {
        if (!rootDirectoryExists()) return null
        val children = ArrayList<Path>()
        try {
            Files.newDirectoryStream(root).use { entries ->
                for (entry in entries) children.add(entry)
            }
        } catch (_: IOException) {
            throw LocalAudioFailure(LocalAudioError.STORAGE_FAILED)
        }
        return children
    }

    private fun managedPath(name: String): Path {
        if (!NAME.matches(name)) throw LocalAudioFailure(LocalAudioError.INVALID_NAME)
        val candidate = root.resolve(name + EXTENSION).normalize()
        if (candidate.parent != root || candidate.nameCount != root.nameCount + 1) {
            throw LocalAudioFailure(LocalAudioError.INVALID_NAME)
        }
        return candidate
    }

    private fun isDirectoryNoFollow(path: Path): Boolean =
        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)

    private fun isOwnedFile(path: Path): Boolean =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)

    private fun deleteQuietly(path: Path): Boolean = try {
        Files.deleteIfExists(path)
    } catch (_: IOException) {
        false
    }

    private fun hex(value: ByteArray): String {
        val chars = CharArray(value.size * 2)
        value.forEachIndexed { index, byte ->
            val unsigned = byte.toInt() and 0xff
            chars[index * 2] = HEX[unsigned ushr 4]
            chars[index * 2 + 1] = HEX[unsigned and 0xf]
        }
        return String(chars)
    }

    private companion object {
        const val EXTENSION = ".audio"
        const val TEMP_PREFIX = "part-"
        const val TEMP_SUFFIX = ".tmp"
        const val BUFFER_BYTES = 64 * 1024
        const val SPACE_CHECK_BYTES = 4L * 1024 * 1024
        const val RESERVED_SPACE_BYTES = 1L * 1024 * 1024
        val NAME = Regex("[0-9a-f]{64}")
        val FILE_NAME = Regex("[0-9a-f]{64}\\.audio")
        val TEMP_NAME = Regex("part-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tmp")
        val HEX = "0123456789abcdef".toCharArray()
    }
}
