package tech.musxi.core.playback

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary

class FilePlaybackJournalStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val ownerA = "a".repeat(64)
    private val ownerB = "b".repeat(64)

    @Test
    fun coldInstanceRoundTripSanitizesArtworkAndExposesImmutableLists() {
        val directory = temporary.newFolder("round-trip")
        val mutableArtists = mutableListOf("Artist")
        val mutableSongs = mutableListOf(neteaseSong(1, "Original", mutableArtists, "http://example.com/bad.jpg"))
        val mutableRecent = mutableListOf(
            RecentPlay(
                neteaseSong(1, "Original", mutableArtists, "https://p1.music.126.net/good.jpg"),
                song(Platform.QQ, "AbCdEf12345678", "Source", listOf("Other"),
                    "https://y.gtimg.cn/music/photo.jpg"),
                123,
            ),
        )
        val journal = PlaybackJournal(
            SavedPlaybackQueue(
                mutableSongs,
                index = 0,
                positionMs = 4_000,
                repeatMode = "ALL",
                shuffleEnabled = true,
                savedAtEpochMs = 99,
            ),
            mutableRecent,
        )

        FilePlaybackJournalStore(directory, Platform.NETEASE).write(ownerA, journal)
        mutableArtists += "Mutated"
        mutableSongs.clear()
        mutableRecent.clear()

        val restored = FilePlaybackJournalStore(directory, Platform.NETEASE).read(ownerA)
        val queue = restored.queue!!
        assertEquals(1, queue.songs.size)
        assertEquals("Original", queue.songs.single().title)
        assertEquals(listOf("Artist"), queue.songs.single().artists)
        assertNull(queue.songs.single().coverUrl)
        assertEquals("https://p1.music.126.net/good.jpg", restored.recent.single().original.coverUrl)
        assertEquals(Platform.QQ, restored.recent.single().source.key.platform)
        assertEquals("https://y.gtimg.cn/music/photo.jpg", restored.recent.single().source.coverUrl)

        assertThrowsUnsupported { (queue.songs as MutableList<SongSummary>).clear() }
        assertThrowsUnsupported { (queue.songs.single().artists as MutableList<String>).clear() }
        assertThrowsUnsupported { (restored.recent as MutableList<RecentPlay>).clear() }
    }

    @Test
    fun ownerChangeCannotReadPreviousOwnerJournal() {
        val directory = temporary.newFolder("owner")
        val store = FilePlaybackJournalStore(directory, Platform.KUGOU)
        store.write(ownerA, PlaybackJournal(recent = listOf(recent(kugouSong("a"), kugouSong("b"), 1))))

        store.write(ownerB, PlaybackJournal())

        assertEquals(PlaybackJournal(), store.read(ownerA))
        assertEquals(PlaybackJournal(), store.read(ownerB))
    }

    @Test
    fun queueRequiresSamePlatformButRecentSourceMayUseAnotherPlatform() {
        val directory = temporary.newFolder("platforms")
        val store = FilePlaybackJournalStore(directory, Platform.KUGOU)
        val original = kugouSong("a")
        val history = recent(original, neteaseSong(2, "Netease source", mutableListOf(), null), 7)
        store.write(ownerA, PlaybackJournal(SavedPlaybackQueue(
            listOf(original), 0, 0, "OFF", false, 5,
        ), listOf(history)))
        assertEquals(Platform.NETEASE, store.read(ownerA).recent.single().source.key.platform)

        assertRejectedAndPreserved(directory, store, PlaybackJournal(
            recent = listOf(recent(neteaseSong(3, "Wrong original", mutableListOf(), null), original, 8)),
        ))
        assertRejectedAndPreserved(directory, store, PlaybackJournal(
            SavedPlaybackQueue(listOf(neteaseSong(4, "Wrong queue", mutableListOf(), null)),
                0, 0, "ALL", false, 9),
        ))
    }

    @Test
    fun emptyQueueIsRejectedOnWriteAndRead() {
        val directory = temporary.newFolder("empty-queue")
        val store = FilePlaybackJournalStore(directory, Platform.NETEASE)
        val valid = PlaybackJournal(recent = listOf(recent(
            neteaseSong(1, "One", mutableListOf(), null),
            neteaseSong(2, "Two", mutableListOf(), null),
            1,
        )))
        store.write(ownerA, valid)

        assertRejectedAndPreserved(directory, store, PlaybackJournal(
            SavedPlaybackQueue(emptyList(), 0, 0, "OFF", false, 1),
        ))
        assertEquals(valid, store.read(ownerA))

        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeUTF(ownerA)
                data.writeByte(Platform.NETEASE.ordinal)
                data.writeBoolean(true)
                data.writeInt(0)
                data.writeInt(0)
                data.writeLong(0)
                data.writeUTF("OFF")
                data.writeBoolean(false)
                data.writeLong(1)
                data.writeInt(0)
            }
        }.toByteArray()
        val encoded = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeInt(0x4d584a31)
                data.writeInt(1)
                data.writeInt(payload.size)
                data.writeLong(CRC32().apply { update(payload) }.value)
                data.write(payload)
            }
        }.toByteArray()
        File(directory, "playback-journal-v1.bin").writeBytes(encoded)

        assertEquals(PlaybackJournal(), store.read(ownerA))
    }

    @Test
    fun rejectsCountsOutsideLimitsAndPreservesExistingFile() {
        val directory = temporary.newFolder("limits")
        val store = FilePlaybackJournalStore(directory, Platform.QQ)
        val valid = PlaybackJournal(recent = listOf(recent(qqSong("a"), qqSong("b"), 1)))
        store.write(ownerA, valid)

        val tooManySongs = List(20_001) { qqSong(it.toString().padStart(14, '0')) }
        assertRejectedAndPreserved(directory, store, PlaybackJournal(
            SavedPlaybackQueue(tooManySongs, 0, 0, "OFF", false, 1),
        ))

        val recent = List(101) { recent(qqSong("a"), qqSong("b"), it.toLong()) }
        assertRejectedAndPreserved(directory, store, PlaybackJournal(recent = recent))
        val largeFile = List(20_000) {
            qqSong(it.toString().padStart(14, '0')).copy(title = "x".repeat(512))
        }
        assertRejectedAndPreserved(directory, store, PlaybackJournal(
            SavedPlaybackQueue(largeFile, 0, 0, "OFF", false, 1),
        ))
        assertEquals(valid, store.read(ownerA))
    }

    @Test
    fun crcDamageVersionDamageAndTruncationReturnEmptyJournal() {
        val directory = temporary.newFolder("damage")
        val store = FilePlaybackJournalStore(directory, Platform.NETEASE)
        val valid = PlaybackJournal(recent = listOf(recent(
            neteaseSong(1, "One", mutableListOf(), null), neteaseSong(2, "Two", mutableListOf(), null), 1,
        )))

        store.write(ownerA, valid)
        val file = journalFile(directory)
        val original = file.readBytes()
        original[original.lastIndex] = (original.last().toInt() xor 1).toByte()
        file.writeBytes(original)
        assertEquals(PlaybackJournal(), FilePlaybackJournalStore(directory, Platform.NETEASE).read(ownerA))

        store.write(ownerA, valid)
        val version = file.readBytes()
        version[7] = (version[7].toInt() xor 1).toByte()
        file.writeBytes(version)
        assertEquals(PlaybackJournal(), store.read(ownerA))

        store.write(ownerA, valid)
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() - 1))
        assertEquals(PlaybackJournal(), store.read(ownerA))
    }

    @Test
    fun clearRemovesOnlyItsOwnFiles() {
        val directory = temporary.newFolder("clear")
        val store = FilePlaybackJournalStore(directory, Platform.NETEASE)
        store.write(ownerA, PlaybackJournal())
        val unrelated = File(directory, "unrelated.bin").apply { writeText("keep") }
        val nested = File(directory, "unrelated-dir").apply { mkdirs() }

        store.clear()

        assertEquals(PlaybackJournal(), store.read(ownerA))
        assertTrue(unrelated.isFile)
        assertEquals("keep", unrelated.readText())
        assertTrue(nested.isDirectory)
        assertFalse(File(directory, "playback-journal-v1.bin").exists())
    }

    @Test
    fun invalidTextDurationKeyAndModeAreRejectedWithFixedIoException() {
        val directory = temporary.newFolder("invalid")
        val store = FilePlaybackJournalStore(directory, Platform.KUGOU)
        val valid = PlaybackJournal()
        store.write(ownerA, valid)

        val invalidCases = listOf(
            PlaybackJournal(SavedPlaybackQueue(
                listOf(kugouSong("a").copy(title = "bad\u0000title")), 0, 0, "ALL", false, 0,
            )),
            PlaybackJournal(SavedPlaybackQueue(
                listOf(kugouSong("a").copy(durationMs = 86_400_001)), 0, 0, "ALL", false, 0,
            )),
            PlaybackJournal(SavedPlaybackQueue(
                listOf(kugouSong("a")), 0, 0, "SHUFFLE", false, 0,
            )),
            PlaybackJournal(recent = listOf(recent(
                song(Platform.KUGOU, "bad", "Bad", emptyList(), null), kugouSong("a"), 0,
            ))),
        )
        invalidCases.forEach { invalid -> assertRejectedAndPreserved(directory, store, invalid) }

        val readError = try {
            store.read("A".repeat(64))
            null
        } catch (caught: IOException) {
            caught
        }
        assertEquals("Playback journal unavailable", readError!!.message)

        val malformedOwner = try {
            store.write("bad-owner", invalidCases.first())
            null
        } catch (caught: IOException) {
            caught
        }
        assertEquals("Playback journal unavailable", malformedOwner!!.message)
    }

    private fun assertRejectedAndPreserved(
        directory: File,
        store: PlaybackJournalStore,
        invalid: PlaybackJournal,
    ) {
        val before = journalFile(directory).readBytes()
        val error = try {
            store.write(ownerA, invalid)
            null
        } catch (caught: IOException) {
            caught
        }
        assertTrue(error != null)
        assertEquals("Playback journal unavailable", error!!.message)
        assertArrayEquals(before, journalFile(directory).readBytes())
    }

    private fun journalFile(directory: File): File =
        directory.listFiles()!!.single { it.name.startsWith("playback-journal-v1") }

    private fun assertThrowsUnsupported(block: () -> Unit) {
        val failed = try {
            block()
            false
        } catch (_: UnsupportedOperationException) {
            true
        }
        assertTrue(failed)
    }

    private fun recent(original: SongSummary, source: SongSummary, time: Long) =
        RecentPlay(original, source, time)

    private fun neteaseSong(
        id: Int,
        title: String,
        artists: MutableList<String>,
        cover: String?,
    ) = song(Platform.NETEASE, id.toString(), title, artists, cover)

    private fun qqSong(id: String) =
        song(Platform.QQ, id.padStart(14, '0'), "QQ song", emptyList(), null)

    private fun kugouSong(hashChar: String) = song(
        Platform.KUGOU,
        hashChar.repeat(32) + "_0_0",
        "Kugou song",
        emptyList(),
        null,
    )

    private fun song(
        platform: Platform,
        id: String,
        title: String,
        artists: List<String>,
        cover: String?,
    ) = SongSummary(MediaKey(platform, id), title, artists, "Album", 1_000, cover)
}
