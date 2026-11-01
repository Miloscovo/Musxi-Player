package tech.musxi.core.lyrics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.CRC32
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey

/**
 * A durable manual match. The original account owner is the storage partition. The candidate
 * owner is the durable binding gate; its generation records the write-time attempt for diagnostics
 * and for the async work that subsequently previews the candidate.
 */
data class LyricsBinding(
    val originalKey: MediaKey,
    val candidateKey: MediaKey,
    val offsetMs: Long,
    val candidateOwner: String,
    val candidateGeneration: Long,
) {
    init {
        require(offsetMs in -MAX_OFFSET_MS..MAX_OFFSET_MS)
        require(candidateOwner.matches(SAFE_OWNER))
        require(candidateGeneration >= 0)
    }

    override fun toString(): String =
        "LyricsBinding(original=${originalKey.stableId}, candidate=${candidateKey.stableId}, " +
            "offsetMs=$offsetMs, candidateOwner=<redacted>, candidateGeneration=$candidateGeneration)"

    companion object {
        const val MAX_OFFSET_MS = 600_000L
        private val SAFE_OWNER = Regex("[0-9a-f]{64}")
    }
}

interface LyricsBindingStore {
    fun read(owner: String, original: MediaKey): LyricsBinding?
    fun write(owner: String, binding: LyricsBinding)
    fun remove(owner: String, original: MediaKey)
    fun clear(owner: String)
    fun removePlatform(platform: Platform)
}

/**
 * Metadata-only, account-partitioned binding storage. The caller supplies a private directory.
 * Bindings live in their own store and are therefore unaffected by lyrics response cache limits.
 */
class FileLyricsBindingStore(private val directory: File) : LyricsBindingStore {
    private class Invalid : Exception()

    @Synchronized
    override fun read(owner: String, original: MediaKey): LyricsBinding? {
        if (!SAFE_OWNER.matches(owner)) return null
        val file = bindingFile(owner, original)
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) ||
            file.length() !in HEADER_BYTES.toLong()..MAX_FILE_BYTES.toLong()
        ) return null
        return try {
            decode(owner, file.readBytes(), original)
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun write(owner: String, binding: LyricsBinding) {
        require(SAFE_OWNER.matches(owner))
        val ownerDirectory = ensureOwnerDirectory(owner)
        val target = File(ownerDirectory, fileStem(binding.originalKey) + ".binding")
        val temporary = File.createTempFile(".tmp-", ".binding", ownerDirectory)
        try {
            val encoded = encode(owner, binding)
            FileOutputStream(temporary).use {
                it.write(encoded)
                it.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Throwable) {
            temporary.delete()
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            throw IOException("Lyrics binding storage is unavailable")
        }
    }

    @Synchronized
    override fun remove(owner: String, original: MediaKey) {
        if (!SAFE_OWNER.matches(owner)) return
        val file = bindingFile(owner, original)
        if (file.exists() && (!file.isFile || Files.isSymbolicLink(file.toPath()))) {
            throw IOException("Lyrics binding storage is unavailable")
        }
        if (file.exists() && !file.delete()) throw IOException("Lyrics binding storage is unavailable")
    }

    @Synchronized
    override fun clear(owner: String) {
        if (!SAFE_OWNER.matches(owner)) return
        val ownerDirectory = File(directory, owner)
        if (!ownerDirectory.exists()) return
        if (!ownerDirectory.isDirectory || Files.isSymbolicLink(ownerDirectory.toPath())) {
            throw IOException("Lyrics binding storage is unavailable")
        }
        ownerDirectory.listFiles().orEmpty()
            .filter { OWNED_FILE.matches(it.name) }
            .forEach { file ->
                if (!file.isFile || Files.isSymbolicLink(file.toPath()) || !file.delete()) {
                    throw IOException("Lyrics binding storage is unavailable")
                }
            }
        ownerDirectory.delete()
    }

    /**
     * Erases bindings that involve [platform]. The caller serializes this with the account's cache
     * queue so logout cleanup cannot race a later bind and repopulate the binding.
     */
    @Synchronized
    override fun removePlatform(platform: Platform) {
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return
        for (ownerDirectory in directory.listFiles().orEmpty()) {
            if (!SAFE_OWNER.matches(ownerDirectory.name) || !ownerDirectory.isDirectory ||
                Files.isSymbolicLink(ownerDirectory.toPath())
            ) continue
            for (file in ownerDirectory.listFiles().orEmpty()) {
                if (!OWNED_FILE.matches(file.name) || !file.isFile ||
                    Files.isSymbolicLink(file.toPath()) ||
                    file.length() !in HEADER_BYTES.toLong()..MAX_FILE_BYTES.toLong()
                ) continue
                val binding = try {
                    decode(ownerDirectory.name, file.readBytes(), null) ?: continue
                } catch (_: IOException) {
                    continue
                } catch (_: Exception) {
                    continue
                }
                if (binding.originalKey.platform != platform &&
                    binding.candidateKey.platform != platform
                ) continue
                if (!file.delete()) throw IOException("Lyrics binding storage is unavailable")
            }
        }
    }

    override fun toString(): String = "FileLyricsBindingStore(redacted)"

    private fun ensureOwnerDirectory(owner: String): File {
        if (directory.exists() && (!directory.isDirectory || Files.isSymbolicLink(directory.toPath()))) {
            throw IOException("Lyrics binding storage is unavailable")
        }
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Lyrics binding storage is unavailable")
        }
        val ownerDirectory = File(directory, owner)
        if (ownerDirectory.exists() && (!ownerDirectory.isDirectory || Files.isSymbolicLink(ownerDirectory.toPath()))) {
            throw IOException("Lyrics binding storage is unavailable")
        }
        if (!ownerDirectory.isDirectory && !ownerDirectory.mkdir() && !ownerDirectory.isDirectory) {
            throw IOException("Lyrics binding storage is unavailable")
        }
        return ownerDirectory
    }

    private fun bindingFile(owner: String, original: MediaKey): File =
        File(File(directory, owner), fileStem(original) + ".binding")

    private fun fileStem(key: MediaKey): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return key.platform.name.lowercase() + "-" + digest
    }

    private fun encode(owner: String, binding: LyricsBinding): ByteArray {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { data ->
            data.writeUTF(owner)
            writeKey(data, binding.originalKey)
            writeKey(data, binding.candidateKey)
            data.writeLong(binding.offsetMs)
            data.writeUTF(binding.candidateOwner)
            data.writeLong(binding.candidateGeneration)
        }
        val body = payload.toByteArray()
        if (body.size > MAX_PAYLOAD_BYTES) throw Invalid()
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeInt(body.size)
            data.writeLong(CRC32().apply { update(body) }.value)
            data.write(body)
        }
        return output.toByteArray()
    }

    private fun decode(
        owner: String,
        bytes: ByteArray,
        expectedOriginal: MediaKey? = null,
    ): LyricsBinding? {
        if (bytes.size < HEADER_BYTES) return null
        val input = DataInputStream(ByteArrayInputStream(bytes))
        if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
        val length = input.readInt()
        if (length != bytes.size - HEADER_BYTES || length !in 0..MAX_PAYLOAD_BYTES) return null
        val checksum = input.readLong()
        val body = ByteArray(length)
        input.readFully(body)
        if (CRC32().apply { update(body) }.value != checksum) return null

        val data = DataInputStream(ByteArrayInputStream(body))
        if (data.readUTF() != owner) return null
        val original = readKey(data)
        if (expectedOriginal != null && original != expectedOriginal) return null
        val candidate = readKey(data)
        val offsetMs = data.readLong()
        val candidateOwner = data.readUTF()
        val candidateGeneration = data.readLong()
        if (data.available() != 0 || !candidateOwner.matches(SAFE_OWNER) ||
            candidateGeneration < 0 || offsetMs !in -LyricsBinding.MAX_OFFSET_MS..LyricsBinding.MAX_OFFSET_MS
        ) return null
        return LyricsBinding(original, candidate, offsetMs, candidateOwner, candidateGeneration)
    }

    private fun writeKey(data: DataOutputStream, key: MediaKey) {
        data.writeUTF(key.platform.name)
        data.writeUTF(key.id)
    }

    private fun readKey(data: DataInputStream): MediaKey {
        val platform = tech.musxi.core.account.Platform.valueOf(data.readUTF())
        return MediaKey(platform, data.readUTF())
    }

    companion object {
        private const val MAGIC = 0x4d58424c
        private const val VERSION = 1
        private const val HEADER_BYTES = 20
        private const val MAX_PAYLOAD_BYTES = 4_096
        private const val MAX_FILE_BYTES = HEADER_BYTES + MAX_PAYLOAD_BYTES
        private val SAFE_OWNER = Regex("[0-9a-f]{64}")
        private val OWNED_FILE = Regex("""(?:kugou|qq|netease)-[0-9a-f]{64}\.binding|\.tmp-[^.]+\.binding""")
    }
}
