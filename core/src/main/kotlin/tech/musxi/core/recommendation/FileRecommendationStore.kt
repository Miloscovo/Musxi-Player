package tech.musxi.core.recommendation

import java.io.*
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary

/** One complete bounded metadata snapshot per platform. Contains no credentials or stream URLs. */
class FileRecommendationStore(private val directory: File, private val platform: Platform) : RecommendationStore {
    @Synchronized override fun read(owner: String): RecommendationSnapshot? {
        owner(owner)
        if (!directory.exists()) return null
        directory()
        val file = File(directory, FILE)
        if (!file.exists()) return null
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() !in 20..MAX_BYTES.toLong()) {
            erase(file); return null
        }
        val bytes = file.readBytes()
        return try {
            val outer = DataInputStream(ByteArrayInputStream(bytes))
            require(outer.readInt() == MAGIC && outer.readInt() == VERSION)
            val size = outer.readInt()
            val checksum = outer.readLong()
            require(size == bytes.size - 20)
            val payload = ByteArray(size)
            outer.readFully(payload)
            require(CRC32().apply { update(payload) }.value == checksum)
            val input = DataInputStream(ByteArrayInputStream(payload))
            require(input.readUTF() == platform.name)
            require(input.readUTF() == RecommendationsPolicy.kind(platform).name)
            val savedOwner = input.readUTF()
            owner(savedOwner)
            if (savedOwner != owner) return null
            val at = input.readLong()
            val count = input.readInt()
            require(count in 0..RecommendationsPolicy.MAX_SONGS)
            val songs = List(count) {
                val key = MediaKey(platform, input.readUTF())
                val title = input.readUTF()
                val artists = input.strings()
                val album = input.readUTF()
                val duration = input.readLong()
                val cover = input.nullable()
                val artistIds = input.strings()
                val albumId = input.nullable()
                SongSummary(key, title, artists, album, duration, cover, artistIds, albumId)
            }
            require(input.available() == 0)
            RecommendationSnapshot(platform, songs, at)
        } catch (_: EOFException) { erase(file); null }
        catch (_: UTFDataFormatException) { erase(file); null }
        catch (_: IllegalArgumentException) { erase(file); null }
    }

    @Synchronized override fun write(owner: String, snapshot: RecommendationSnapshot) {
        owner(owner)
        // Revalidate caller-provided snapshots against this store's platform.
        val songs = RecommendationsPolicy.freeze(platform, snapshot.songs)
        val payload = LimitedOutput(MAX_BYTES - 20)
        DataOutputStream(payload).use { out ->
            out.writeUTF(platform.name); out.writeUTF(snapshot.kind.name); out.writeUTF(owner); out.writeLong(snapshot.fetchedAtEpochMs)
            out.writeInt(songs.size)
            songs.forEach { song ->
                out.writeUTF(song.key.id); out.writeUTF(song.title); out.strings(song.artists)
                out.writeUTF(song.album); out.writeLong(song.durationMs); out.nullable(song.coverUrl)
                out.strings(song.artistIds); out.nullable(song.albumId)
            }
        }
        val bytes = payload.toByteArray()
        directory()
        val target = File(directory, FILE)
        val temporary = File(directory, TEMP)
        check(!Files.isSymbolicLink(target.toPath()) && !Files.isSymbolicLink(temporary.toPath())) { "Cache unavailable" }
        try {
            FileOutputStream(temporary).use { stream ->
                val out = DataOutputStream(stream)
                out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(bytes.size)
                out.writeLong(CRC32().apply { update(bytes) }.value)
                out.write(bytes); out.flush(); stream.fd.sync()
            }
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { erase(temporary) }
    }

    @Synchronized override fun clear() {
        if (!directory.exists()) return
        directory()
        erase(File(directory, FILE)); erase(File(directory, TEMP))
    }

    private fun directory() {
        check(!Files.isSymbolicLink(directory.toPath()) &&
            ((directory.exists() && directory.isDirectory) || directory.mkdirs())) { "Cache unavailable" }
    }
    private fun erase(file: File) { check(!file.exists() || file.delete()) { "Cache unavailable" } }
    private fun owner(value: String) { require(value.matches(Regex("[a-f0-9]{64}"))) }
    private fun DataInputStream.nullable(): String? = if (readBoolean()) readUTF() else null
    private fun DataOutputStream.nullable(value: String?) { writeBoolean(value != null); if (value != null) writeUTF(value) }
    private fun DataInputStream.strings(): List<String> {
        val size = readInt(); require(size in 0..100)
        return List(size) { readUTF() }
    }
    private fun DataOutputStream.strings(values: List<String>) { writeInt(values.size); values.forEach(::writeUTF) }
    private class LimitedOutput(private val limit: Int) : ByteArrayOutputStream() {
        override fun write(value: Int) { if (count >= limit) throw IOException("Cache limit"); super.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length > limit - count) throw IOException("Cache limit")
            super.write(bytes, offset, length)
        }
    }
    override fun toString() = "FileRecommendationStore(redacted)"
    private companion object {
        const val MAGIC = 0x4d585244
        const val VERSION = 1
        const val MAX_BYTES = 2 * 1024 * 1024
        const val FILE = "daily.bin"
        const val TEMP = "daily.tmp"
    }
}
