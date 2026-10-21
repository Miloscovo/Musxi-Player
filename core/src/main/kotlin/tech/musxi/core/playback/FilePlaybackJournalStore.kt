package tech.musxi.core.playback

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.UTFDataFormatException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.zip.CRC32
import tech.musxi.core.account.Platform
import tech.musxi.core.library.ArtworkPolicy
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary

/**
 * Synchronous, metadata-only playback journal storage for one platform directory.
 * The caller supplies a private directory and coordinates ownership/generation fencing.
 */
class FilePlaybackJournalStore(
    private val directory: File,
    private val platform: Platform,
) : PlaybackJournalStore {
    @Synchronized
    override fun read(owner: String): PlaybackJournal {
        if (!OWNER.matches(owner)) throw unavailable()
        return try {
            readInternal(owner)
        } catch (_: IOException) {
            throw unavailable()
        } catch (_: Exception) {
            emptyJournal()
        }
    }

    @Synchronized
    override fun write(owner: String, journal: PlaybackJournal) {
        try {
            if (!OWNER.matches(owner)) throw Invalid()
            val snapshot = validatedSnapshot(journal)
            val encoded = encode(owner, snapshot)
            atomicReplace(journalFile(), encoded)
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            throw unavailable()
        }
    }

    @Synchronized
    override fun clear() {
        try {
            ensureDirectory(create = false)
            OWNED_NAMES.forEach { eraseOwned(File(directory, it)) }
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            throw unavailable()
        }
    }

    override fun toString(): String = "FilePlaybackJournalStore(redacted)"

    private fun readInternal(owner: String): PlaybackJournal {
        ensureDirectory(create = false)
        if (!directory.isDirectory) return emptyJournal()

        val target = journalFile()
        if (!target.exists()) return emptyJournal()
        if (!target.isFile || Files.isSymbolicLink(target.toPath()) ||
            target.length() !in HEADER_BYTES.toLong()..MAX_FILE_BYTES.toLong()
        ) return emptyJournal()

        val bytes = target.readBytes()
        return decode(owner, bytes) ?: emptyJournal()
    }

    private fun validatedSnapshot(value: PlaybackJournal): PlaybackJournal {
        val queue = value.queue?.let {
            if (it.songs.size > MAX_QUEUE) throw Invalid()
            val songs = immutableList(it.songs.map { song ->
                copySong(song, platform)
            })
            if (songs.isEmpty() || it.index !in songs.indices) throw Invalid()
            if (it.positionMs !in 0..MAX_PLAYBACK_MS || it.savedAtEpochMs < 0 ||
                it.repeatMode !in REPEAT_MODES
            ) throw Invalid()
            SavedPlaybackQueue(
                songs,
                it.index,
                it.positionMs,
                it.repeatMode,
                it.shuffleEnabled,
                it.savedAtEpochMs,
            )
        }
        if (value.recent.size > MAX_RECENT) throw Invalid()
        val recent = immutableList(value.recent.map {
            if (it.playedAtEpochMs < 0) throw Invalid()
            RecentPlay(
                copySong(it.original, platform),
                copySong(it.source, it.source.key.platform),
                it.playedAtEpochMs,
            )
        })
        return PlaybackJournal(queue, recent)
    }

    private fun copySong(song: SongSummary, expectedPlatform: Platform): SongSummary {
        if (song.key.platform != expectedPlatform) throw Invalid()
        validateKey(song.key)
        text(song.title, MAX_TITLE)
        text(song.album, MAX_ALBUM)
        if (song.artists.size > MAX_ARTISTS) throw Invalid()
        song.artists.forEach { text(it, MAX_ARTIST) }
        if (song.durationMs !in 0..MAX_PLAYBACK_MS) throw Invalid()
        return SongSummary(
            MediaKey(song.key.platform, song.key.id),
            song.title,
            immutableList(song.artists.map { it }),
            song.album,
            song.durationMs,
            ArtworkPolicy.sanitize(song.key.platform, song.coverUrl),
        )
    }

    private fun encode(owner: String, journal: PlaybackJournal): ByteArray {
        val payload = ByteArrayOutputStream()
        val limited = object : OutputStream() {
            override fun write(value: Int) {
                if (payload.size() >= MAX_PAYLOAD_BYTES) throw TooLarge()
                payload.write(value)
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length > MAX_PAYLOAD_BYTES - payload.size()) throw TooLarge()
                payload.write(bytes, offset, length)
            }
        }
        DataOutputStream(limited).use { data ->
            data.writeUTF(owner)
            data.writeByte(platform.ordinal)
            data.writeBoolean(journal.queue != null)
            journal.queue?.let { queue ->
                data.writeInt(queue.songs.size)
                queue.songs.forEach { writeSong(data, it) }
                data.writeInt(queue.index)
                data.writeLong(queue.positionMs)
                data.writeUTF(queue.repeatMode)
                data.writeBoolean(queue.shuffleEnabled)
                data.writeLong(queue.savedAtEpochMs)
            }
            data.writeInt(journal.recent.size)
            journal.recent.forEach {
                writeSong(data, it.original)
                writeSong(data, it.source)
                data.writeLong(it.playedAtEpochMs)
            }
        }
        val body = payload.toByteArray()
        val envelope = ByteArrayOutputStream(HEADER_BYTES + body.size)
        DataOutputStream(envelope).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeInt(body.size)
            data.writeLong(CRC32().apply { update(body) }.value)
            data.write(body)
        }
        return envelope.toByteArray().also {
            if (it.size > MAX_FILE_BYTES) throw TooLarge()
        }
    }

    private fun writeSong(data: DataOutputStream, song: SongSummary) {
        data.writeByte(song.key.platform.ordinal)
        data.writeUTF(song.key.id)
        data.writeUTF(song.title)
        data.writeInt(song.artists.size)
        song.artists.forEach(data::writeUTF)
        data.writeUTF(song.album)
        data.writeLong(song.durationMs)
        data.writeUTF(song.coverUrl.orEmpty())
    }

    private fun decode(expectedOwner: String, bytes: ByteArray): PlaybackJournal? {
        return try {
            if (bytes.size !in HEADER_BYTES..MAX_FILE_BYTES) throw Invalid()
            val envelope = DataInputStream(ByteArrayInputStream(bytes))
            if (envelope.readInt() != MAGIC || envelope.readInt() != VERSION) throw Invalid()
            val payloadSize = envelope.readInt()
            if (payloadSize < 0 || payloadSize != bytes.size - HEADER_BYTES) throw Invalid()
            val expectedCrc = envelope.readLong()
            val payload = ByteArray(payloadSize)
            envelope.readFully(payload)
            if (envelope.available() != 0 ||
                CRC32().apply { update(payload) }.value != expectedCrc
            ) throw Invalid()

            val data = DataInputStream(ByteArrayInputStream(payload))
            if (data.readUTF() != expectedOwner || data.readUnsignedByte() != platform.ordinal) throw Invalid()
            val queue = if (data.readBoolean()) {
                val count = data.readInt()
                if (count !in 1..MAX_QUEUE) throw Invalid()
                val songs = immutableList(List(count) { readSong(data) })
                val index = data.readInt()
                if (index !in songs.indices) throw Invalid()
                SavedPlaybackQueue(
                    songs,
                    index,
                    data.readLong(),
                    data.readUTF(),
                    data.readBoolean(),
                    data.readLong(),
                )
            } else {
                null
            }
            val recentCount = data.readInt()
            if (recentCount !in 0..MAX_RECENT) throw Invalid()
            val recent = immutableList(List(recentCount) {
                RecentPlay(readSong(data), readSong(data), data.readLong())
            })
            if (data.available() != 0) throw Invalid()
            validatedSnapshot(PlaybackJournal(queue, recent))
        } catch (_: Invalid) {
            null
        } catch (_: EOFException) {
            null
        } catch (_: UTFDataFormatException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun readSong(data: DataInputStream): SongSummary {
        val ordinal = data.readUnsignedByte()
        val songPlatform = Platform.entries.getOrNull(ordinal) ?: throw Invalid()
        val id = data.readUTF()
        val title = data.readUTF()
        val artistCount = data.readInt()
        if (artistCount !in 0..MAX_ARTISTS) throw Invalid()
        val artists = immutableList(List(artistCount) { data.readUTF() })
        val album = data.readUTF()
        val duration = data.readLong()
        val cover = ArtworkPolicy.sanitize(songPlatform, data.readUTF().ifEmpty { null })
        return SongSummary(MediaKey(songPlatform, id), title, artists, album, duration, cover)
    }

    private fun validateKey(key: MediaKey) {
        when (key.platform) {
            Platform.NETEASE -> {
                if (!key.id.matches(Regex("[1-9][0-9]{0,18}")) ||
                    key.id.toLongOrNull() == null
                ) throw Invalid()
            }
            Platform.QQ -> {
                if (!key.id.matches(Regex("[A-Za-z0-9]{14}"))) throw Invalid()
            }
            Platform.KUGOU -> {
                val parts = key.id.split('_')
                if (parts.size != 3 || !parts[0].matches(Regex("[0-9a-f]{32}")) ||
                    !kugouNumber(parts[1]) || !kugouNumber(parts[2])
                ) throw Invalid()
            }
        }
    }

    private fun kugouNumber(value: String): Boolean =
        value.matches(Regex("(?:0|[1-9][0-9]{0,15})")) &&
            (value.toLongOrNull() ?: Long.MAX_VALUE) <= 9_007_199_254_740_991L

    private fun text(value: String, limit: Int) {
        if (value.length > limit || value.any { it.isISOControl() }) throw Invalid()
    }

    private fun ensureDirectory(create: Boolean) {
        if (Files.isSymbolicLink(directory.toPath())) throw Invalid()
        if (directory.exists()) {
            if (!directory.isDirectory) throw Invalid()
            return
        }
        if (create && !directory.mkdirs() && !directory.isDirectory) throw IOException()
    }

    private fun atomicReplace(target: File, bytes: ByteArray) {
        if (bytes.size > MAX_FILE_BYTES) throw TooLarge()
        ensureDirectory(create = true)
        if (Files.isSymbolicLink(target.toPath()) ||
            (target.exists() && !target.isFile)
        ) throw Invalid()
        val temporary = File(directory, target.name + ".tmp")
        if (Files.isSymbolicLink(temporary.toPath()) ||
            (temporary.exists() && !temporary.isFile)
        ) throw Invalid()
        if (temporary.exists()) eraseOwned(temporary)
        try {
            FileOutputStream(temporary).use {
                it.write(bytes)
                it.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            eraseOwned(temporary)
            throw error
        }
    }

    private fun eraseOwned(file: File) {
        if (file.exists() || Files.isSymbolicLink(file.toPath())) Files.delete(file.toPath())
    }

    private fun journalFile() = File(directory, JOURNAL_NAME)

    private fun emptyJournal() = PlaybackJournal(recent = immutableList(emptyList()))

    private fun unavailable() = IOException(UNAVAILABLE)

    private class Invalid : Exception()
    private class TooLarge : Exception()

    companion object {
        private const val MAGIC = 0x4d584a31
        private const val VERSION = 1
        private const val HEADER_BYTES = 20
        private const val MAX_FILE_BYTES = 8 * 1024 * 1024
        private const val MAX_PAYLOAD_BYTES = MAX_FILE_BYTES - HEADER_BYTES
        private const val MAX_QUEUE = 20_000
        private const val MAX_RECENT = 100
        private const val MAX_ARTISTS = 100
        private const val MAX_TITLE = 2_048
        private const val MAX_ALBUM = 2_048
        private const val MAX_ARTIST = 512
        private const val MAX_PLAYBACK_MS = 86_400_000L
        private const val JOURNAL_NAME = "playback-journal-v1.bin"
        private const val UNAVAILABLE = "Playback journal unavailable"
        private val OWNER = Regex("[0-9a-f]{64}")
        private val REPEAT_MODES = setOf("OFF", "ONE", "ALL")
        private val OWNED_NAMES = setOf(
            JOURNAL_NAME,
            "$JOURNAL_NAME.tmp",
        )

        private fun <T> immutableList(values: List<T>): List<T> =
            Collections.unmodifiableList(ArrayList(values))
    }
}
