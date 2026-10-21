package tech.musxi.core.library

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.CRC32
import tech.musxi.core.account.Platform

/** Bounded, metadata-only store. Each collection is replaced atomically, including its pages.
 * No Android dependencies; the caller supplies the app's private noBackup directory.
 */
class FileLibraryPageStore(
    private val directory: File,
    private val platform: Platform = Platform.NETEASE,
) : LibraryPageStore {
    // Keep existing formats readable; each platform has a distinct envelope.
    private val magic = when (platform) {
        Platform.NETEASE -> MAGIC
        Platform.KUGOU -> KUGOU_MAGIC
        Platform.QQ -> QQ_MAGIC
    }
    private val pageLimit get() = if (platform == Platform.NETEASE) 50 else 30
    private data class Collection(val owner: String, val playlist: MediaKey?, val pages: LinkedHashMap<Int, SavedLibraryPage<*>>)
    private class Invalid : Exception()

    @Synchronized override fun readPlaylists(owner: String, offset: Int): SavedLibraryPage<PlaylistSummary>? = access {
        bounds(owner, offset); prepare(owner)
        @Suppress("UNCHECKED_CAST")
        (read(file(owner, null), owner, null)?.pages?.get(offset) as? SavedLibraryPage<PlaylistSummary>)
    }
    @Synchronized override fun readTracks(owner: String, playlist: MediaKey, offset: Int): SavedLibraryPage<SongSummary>? = access {
        bounds(owner, offset); key(playlist, song = false); prepare(owner)
        @Suppress("UNCHECKED_CAST")
        (read(file(owner, playlist), owner, playlist)?.pages?.get(offset) as? SavedLibraryPage<SongSummary>)
    }
    @Synchronized override fun writePlaylists(owner: String, offset: Int, value: SavedLibraryPage<PlaylistSummary>) = access {
        write(owner, null, offset, SavedLibraryPage(value.page.copy(items = value.page.items.map {
            it.copy(coverUrl = ArtworkPolicy.sanitize(platform, it.coverUrl))
        }), value.fetchedAtEpochMs))
    }
    @Synchronized override fun writeTracks(owner: String, playlist: MediaKey, offset: Int, value: SavedLibraryPage<SongSummary>) = access {
        key(playlist, song = false)
        write(owner, playlist, offset, SavedLibraryPage(value.page.copy(items = value.page.items.map {
            it.copy(coverUrl = ArtworkPolicy.sanitize(platform, it.coverUrl))
        }), value.fetchedAtEpochMs))
    }
    @Synchronized override fun clear() = access { ensureDirectory(); ownedFiles().forEach(::erase) }
    override fun toString() = "FileLibraryPageStore(redacted)"

    private fun write(owner: String, playlist: MediaKey?, offset: Int, value: SavedLibraryPage<*>) {
        bounds(owner, offset); validatePage(playlist, offset, value); prepare(owner)
        val target = file(owner, playlist)
        val pages = if (offset == 0) linkedMapOf() else read(target, owner, playlist)?.pages ?: throw Invalid()
        if (offset != 0) {
            val first = pages[0] ?: throw Invalid()
            if (first.page.total != value.page.total || pages.values.none { it.page.nextOffset == offset }) throw Invalid()
        }
        pages.remove(offset); pages[offset] = value
        while (pages.size > MAX_PAGES) pages.keys.firstOrNull { it != 0 && it != offset }?.let(pages::remove) ?: throw Invalid()
        val collection = Collection(owner, playlist, pages)
        var encoded: ByteArray
        while (true) {
            try { encoded = encode(collection); break }
            catch (_: TooLarge) { pages.keys.firstOrNull { it != 0 && it != offset }?.let(pages::remove) ?: throw Invalid() }
        }
        // Evict before commit so even interrupted writes stay within the total disk budget.
        val others = ownedFiles().filter { it.name != target.name && it.name != OWNER_FILE }
            .sortedBy { it.lastModified() }.toMutableList()
        var size = others.sumOf { it.length() } + target.length() + encoded.size + 64
        while (others.size >= MAX_COLLECTIONS || size > MAX_TOTAL_BYTES) {
            val oldest = others.removeAt(0); size -= oldest.length(); erase(oldest)
        }
        atomic(target, encoded)
    }

    private fun prepare(owner: String) {
        ensureDirectory()
        val marker = File(directory, OWNER_FILE)
        val matches = marker.isFile && !Files.isSymbolicLink(marker.toPath()) && marker.length() == 64L && marker.readText(Charsets.US_ASCII) == owner
        if (!matches) {
            ownedFiles().forEach(::erase)
            atomic(marker, owner.toByteArray(Charsets.US_ASCII))
        }
        ownedFiles().filter { it.name.endsWith(".tmp") }.forEach(::erase)
    }

    private fun read(target: File, owner: String, playlist: MediaKey?): Collection? {
        if (!target.exists()) return null
        if (!target.isFile || Files.isSymbolicLink(target.toPath()) || target.length() !in 1..MAX_FILE_BYTES.toLong()) {
            erase(target); return null
        }
        val bytes = target.readBytes()
        return try {
            if (bytes.size < 20) throw Invalid()
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != magic) throw Invalid()
            val version = input.readInt()
            if (version !in 1..VERSION) throw Invalid()
            val length = input.readInt()
            if (length != bytes.size - 20 || length < 0) throw Invalid()
            val checksum = input.readLong()
            val payload = ByteArray(length); input.readFully(payload)
            if (CRC32().apply { update(payload) }.value != checksum) throw Invalid()
            val data = DataInputStream(ByteArrayInputStream(payload))
            if (data.readUTF() != owner || data.readUTF() != (playlist?.id ?: "")) throw Invalid()
            val count = data.readInt(); if (count !in 1..MAX_PAGES) throw Invalid()
            val pages = linkedMapOf<Int, SavedLibraryPage<*>>()
            repeat(count) {
                val offset = data.readInt(); bounds(owner, offset)
                val time = data.readLong()
                val next = data.readInt().let { if (it == -1) null else it }
                val total = data.readInt().let { if (it == -1) null else it }
                val size = data.readInt(); if (size !in 0..if (playlist == null) 30 else pageLimit) throw Invalid()
                val items: List<*> = if (playlist == null) List(size) {
                    val id = data.readUTF(); val title = data.readUTF(); val count = data.readInt()
                    PlaylistSummary(MediaKey(platform, id), title,
                        if (version >= 2) ArtworkPolicy.sanitize(platform, data.readUTF().ifEmpty { null }) else null, count)
                } else List(size) {
                    val id = data.readUTF(); val title = data.readUTF()
                    val artists = data.readInt(); if (artists !in 0..100) throw Invalid()
                    val names = Collections.unmodifiableList(List(artists) { data.readUTF() })
                    val album = data.readUTF(); val duration = data.readLong()
                    SongSummary(MediaKey(platform, id), title, names, album, duration,
                        if (version >= 2) ArtworkPolicy.sanitize(platform, data.readUTF().ifEmpty { null }) else null)
                }
                val page = SavedLibraryPage(Page(Collections.unmodifiableList(items), next, total), time)
                validatePage(playlist, offset, page)
                if (pages.put(offset, page) != null) throw Invalid()
            }
            if (data.available() != 0 || !pages.containsKey(0)) throw Invalid()
            Collection(owner, playlist, pages)
        } catch (_: Invalid) { erase(target); null }
          catch (_: EOFException) { erase(target); null }
          catch (_: UTFDataFormatException) { erase(target); null }
          catch (_: IllegalArgumentException) { erase(target); null }
    }

    private class TooLarge : IOException()
    private fun encode(value: Collection): ByteArray {
        val output = ByteArrayOutputStream()
        val limited = object : OutputStream() {
            override fun write(b: Int) { if (output.size() >= MAX_FILE_BYTES - 20) throw TooLarge(); output.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len > MAX_FILE_BYTES - 20 - output.size()) throw TooLarge()
                output.write(b, off, len)
            }
        }
        DataOutputStream(limited).use { data ->
            data.writeUTF(value.owner); data.writeUTF(value.playlist?.id ?: ""); data.writeInt(value.pages.size)
            value.pages.forEach { (offset, saved) ->
                validatePage(value.playlist, offset, saved)
                data.writeInt(offset); data.writeLong(saved.fetchedAtEpochMs)
                data.writeInt(saved.page.nextOffset ?: -1); data.writeInt(saved.page.total ?: -1); data.writeInt(saved.page.items.size)
                saved.page.items.forEach { item -> when (item) {
                    is PlaylistSummary -> {
                        data.writeUTF(item.key.id); data.writeUTF(item.title); data.writeInt(item.trackCount)
                        data.writeUTF(ArtworkPolicy.sanitize(platform, item.coverUrl).orEmpty())
                    }
                    is SongSummary -> {
                        data.writeUTF(item.key.id); data.writeUTF(item.title); data.writeInt(item.artists.size)
                        item.artists.forEach(data::writeUTF); data.writeUTF(item.album); data.writeLong(item.durationMs)
                        data.writeUTF(ArtworkPolicy.sanitize(platform, item.coverUrl).orEmpty())
                    }
                    else -> throw Invalid()
                } }
            }
        }
        val payload = output.toByteArray()
        val envelope = ByteArrayOutputStream(payload.size + 20)
        DataOutputStream(envelope).use {
            it.writeInt(magic); it.writeInt(VERSION); it.writeInt(payload.size)
            it.writeLong(CRC32().apply { update(payload) }.value); it.write(payload)
        }
        return envelope.toByteArray()
    }

    private fun validatePage(playlist: MediaKey?, offset: Int, value: SavedLibraryPage<*>) {
        val page = value.page
        if (value.fetchedAtEpochMs < 0 || page.items.size > if (playlist == null) 30 else pageLimit) throw Invalid()
        if (platform == Platform.KUGOU) {
            if (offset % 30 != 0) throw Invalid()
            if (page.total != null && (page.total !in 0..20_000 || offset + page.items.size > page.total)) throw Invalid()
            val expectedNext = when {
                page.items.isEmpty() -> {
                    if (page.total != null && page.total > offset) throw Invalid()
                    null
                }
                page.total != null && offset + page.items.size == page.total -> null
                else -> {
                    if (page.total != null && page.items.size < 30) throw Invalid()
                    offset + 30
                }
            }
            if (page.nextOffset != expectedNext || (expectedNext != null && expectedNext !in 1..20_000)) throw Invalid()
        } else if (platform == Platform.QQ) {
            val total = page.total ?: throw Invalid()
            val end = offset + page.items.size
            if (total !in 0..20_000 || end > total ||
                (end < total && page.items.isEmpty()) ||
                page.nextOffset != if (end < total) end else null) throw Invalid()
        } else if (page.nextOffset != null && (page.items.isEmpty() || page.nextOffset != offset + page.items.size || page.nextOffset !in 1..20_000)) throw Invalid()
        if (page.total != null && (page.total !in 0..20_000 || (page.items.isNotEmpty() && offset + page.items.size > page.total))) throw Invalid()
        if (page.total != null && page.nextOffset == null && offset < page.total && offset + page.items.size != page.total) throw Invalid()
        page.items.forEach { item -> when {
            playlist == null && item is PlaylistSummary -> {
                key(item.key, song = false); text(item.title, 2048)
                if (item.trackCount !in 0..1_000_000) throw Invalid()
            }
            playlist != null && item is SongSummary -> {
                key(item.key, song = true); text(item.title, 2048); text(item.album, 2048)
                if (item.artists.size > 100 || item.durationMs !in 0..604_800_000L) throw Invalid()
                item.artists.forEach { text(it, 512) }
            }
            else -> throw Invalid()
        } }
    }
    private fun key(key: MediaKey, song: Boolean) {
        if (key.platform != platform) throw Invalid()
        if (platform == Platform.NETEASE) {
            if (!key.id.matches(Regex("[1-9][0-9]{0,18}")) || key.id.toLongOrNull() == null) throw Invalid()
        } else if (platform == Platform.QQ) {
            if (song) {
                if (!key.id.matches(Regex("[A-Za-z0-9]{14}"))) throw Invalid()
            } else {
                val parts = key.id.split('_')
                if (parts.size != 2 || !kugouNumber(parts[0], true) ||
                    !parts[1].matches(Regex("(?:0|[1-9][0-9]{0,6})")) ||
                    (parts[1].toIntOrNull() ?: Int.MAX_VALUE) > 1_000_000 ||
                    (parts[0] == "0" && parts[1] != "201")) throw Invalid()
            }
        } else if (song) {
            val parts = key.id.split('_')
            if (parts.size != 3 || !parts[0].matches(Regex("[0-9a-f]{32}")) ||
                !kugouNumber(parts[1], true) || !kugouNumber(parts[2], true)) throw Invalid()
        } else if (!kugouNumber(key.id, false)) throw Invalid()
    }
    private fun kugouNumber(value: String, zero: Boolean): Boolean =
        value.matches(Regex(if (zero) "(?:0|[1-9][0-9]{0,15})" else "[1-9][0-9]{0,15}")) &&
            (value.toLongOrNull() ?: Long.MAX_VALUE) <= 9_007_199_254_740_991L
    private fun text(value: String, limit: Int) { if (value.length > limit || value.any { it.isISOControl() }) throw Invalid() }
    private fun bounds(owner: String, offset: Int) { if (!owner.matches(Regex("[0-9a-f]{64}")) || (offset !in 0..20_000 || (platform == Platform.KUGOU && offset % 30 != 0))) throw Invalid() }
    private fun file(owner: String, playlist: MediaKey?): File {
        val hash = MessageDigest.getInstance("SHA-256").digest((owner + ":" + (playlist?.id ?: "playlists")).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(directory, "page-v1-$hash.bin")
    }
    private fun ensureDirectory() {
        if (Files.isSymbolicLink(directory.toPath()) || (!directory.isDirectory && !directory.mkdirs())) throw Invalid()
    }
    private fun ownedFiles(): List<File> = directory.listFiles()?.filter {
        it.name == OWNER_FILE || it.name == "$OWNER_FILE.tmp" || it.name.matches(Regex("page-v1-[0-9a-f]{64}\\.bin(?:\\.tmp)?"))
    } ?: throw IOException("Library cache unavailable")
    private fun erase(file: File) { if (file.exists() || Files.isSymbolicLink(file.toPath())) Files.delete(file.toPath()) }
    private fun atomic(target: File, bytes: ByteArray) {
        val temporary = File(directory, target.name + ".tmp")
        erase(temporary)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            // Same private directory and filesystem. Unsupported atomic moves fail closed.
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { erase(temporary) }
    }
    private inline fun <T> access(work: () -> T): T = try { work() }
        catch (_: Exception) { throw IOException("Library cache unavailable") }

    companion object {
        private const val OWNER_FILE = "owner-v1.bin"
        private const val MAGIC = 0x4d585043
        private const val KUGOU_MAGIC = 0x4d584b43
        private const val QQ_MAGIC = 0x4d585143
        private const val VERSION = 2
        private const val MAX_FILE_BYTES = 2 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 16L * 1024 * 1024
        private const val MAX_COLLECTIONS = 128
        private const val MAX_PAGES = 128
    }
}
