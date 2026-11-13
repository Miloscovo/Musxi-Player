package tech.musxi.core.library

import tech.musxi.core.account.Platform
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Durable user preferences, deliberately separate from every metadata-cache directory. */
class FilePlaylistOrderStore(private val directory: File, private val platform: Platform) {
    @Synchronized fun read(owner: String): List<MediaKey> {
        val file = file(owner)
        if (!file.exists()) return emptyList()
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() !in 1..MAX_BYTES)
            throw IOException("Playlist order storage is unavailable")
        try {
            DataInputStream(ByteArrayInputStream(file.readBytes())).use { data ->
                if (data.readInt() != MAGIC || data.readUTF() != owner || data.readUTF() != platform.name)
                    throw IOException("Invalid playlist order")
                val count = data.readInt()
                if (count !in 0..MAX_ITEMS) throw IOException("Invalid playlist order")
                val values = List(count) { MediaKey(platform, data.readUTF()) }
                if (values.distinct().size != count || data.read() != -1) throw IOException("Invalid playlist order")
                return values
            }
        } catch (_: Exception) { throw IOException("Playlist order storage is unavailable") }
    }

    @Synchronized fun write(owner: String, order: List<MediaKey>) {
        val target = file(owner)
        require(order.size <= MAX_ITEMS && order.distinct().size == order.size && order.all { it.platform == platform })
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Playlist order storage is unavailable")
        if (target.exists() && (!target.isFile || Files.isSymbolicLink(target.toPath())))
            throw IOException("Playlist order storage is unavailable")
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC); data.writeUTF(owner); data.writeUTF(platform.name); data.writeInt(order.size)
                order.forEach { data.writeUTF(it.id) }
            }
        }.toByteArray()
        if (bytes.size > MAX_BYTES) throw IOException("Playlist order storage is unavailable")
        val temporary = File.createTempFile(".order-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private fun file(owner: String): File {
        require(owner.matches(Regex("[0-9a-f]{64}")))
        if (Files.isSymbolicLink(directory.toPath())) throw IOException("Playlist order storage is unavailable")
        return File(directory, "$owner.order")
    }

    companion object {
        const val MAX_ITEMS = 20_000
        private const val MAX_BYTES = 4_000_000L
        private const val MAGIC = 0x4d504f31 // MPO1
    }
}
