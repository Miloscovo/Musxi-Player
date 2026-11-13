package tech.musxi.core.recommendation

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.SongSummary

class FileRecommendationStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = "a".repeat(64)
    private val other = "b".repeat(64)
    private val at = 1_794_360_000_000L
    private fun song(platform: Platform = Platform.NETEASE) = SongSummary(MediaKey(platform, "123"),
        "歌曲", listOf("歌手甲", "歌手乙"), "专辑", 125_000, "http://p1.music.126.net/sample.jpg", listOf("1", "2"), "9")

    @Test fun reopensCompleteMetadataInOrderAndIsolatesOwnersAndPlatforms() {
        Platform.entries.forEach { platform ->
            val dir = temporary.newFolder(platform.name)
            val store = FileRecommendationStore(dir, platform)
            val songs = listOf(song(platform), song(platform).copy(key = MediaKey(platform, "456")))
            store.write(owner, RecommendationSnapshot(platform, songs, at))
            val reopened = FileRecommendationStore(dir, platform).read(owner)!!
            assertEquals(listOf("123", "456"), reopened.songs.map { it.key.id })
            assertEquals(songs.map { it.artists }, reopened.songs.map { it.artists })
            assertEquals(songs.map { it.artistIds }, reopened.songs.map { it.artistIds })
            assertEquals(at, reopened.fetchedAtEpochMs)
            assertNull(store.read(other))
            assertNull(FileRecommendationStore(dir, Platform.entries.first { it != platform }).read(owner))
        }
    }

    @Test fun snapshotFreezesCallerListsAndSanitizesArtwork() {
        val artists = mutableListOf("Artist")
        val ids = mutableListOf("1")
        val songs = mutableListOf(song().copy(artists = artists, artistIds = ids))
        val snapshot = RecommendationSnapshot(Platform.NETEASE, songs, at)
        artists.clear(); ids.clear(); songs.clear()
        assertEquals(listOf("Artist"), snapshot.songs.single().artists)
        assertEquals(listOf("1"), snapshot.songs.single().artistIds)
        assertTrue(snapshot.songs.single().coverUrl!!.startsWith("https://"))
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.songs as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.songs.single().artistIds as MutableList).clear() }
    }

    @Test fun corruptedAndUnsupportedSnapshotsAreCacheMissesAndGetRemoved() {
        val dir = temporary.newFolder()
        val store = FileRecommendationStore(dir, Platform.NETEASE)
        store.write(owner, RecommendationSnapshot(Platform.NETEASE, listOf(song()), at))
        val file = File(dir, "daily.bin")
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNull(store.read(owner)); assertFalse(file.exists())
        store.write(owner, RecommendationSnapshot(Platform.NETEASE, emptyList(), at))
        file.writeBytes(file.readBytes().also { it[7] = 99 })
        assertNull(store.read(owner)); assertFalse(file.exists())
        file.writeText("truncated")
        assertNull(store.read(owner)); assertFalse(file.exists())
    }

    @Test fun failedBoundedWritePreservesLastCommittedSnapshot() {
        val dir = temporary.newFolder()
        val store = FileRecommendationStore(dir, Platform.NETEASE)
        store.write(owner, RecommendationSnapshot(Platform.NETEASE, listOf(song()), at))
        val large = List(200) { song().copy(artists = List(100) { "x".repeat(1024) }) }
        assertThrows(java.io.IOException::class.java) {
            store.write(owner, RecommendationSnapshot(Platform.NETEASE, large, at + 1))
        }
        assertEquals(at, store.read(owner)!!.fetchedAtEpochMs)
        assertFalse(File(dir, "daily.tmp").exists())
    }

    @Test fun cacheClearOnlyDeletesOwnedFilesAndAllowsEmptySnapshot() {
        val dir = temporary.newFolder()
        val protected = File(dir, "retain.txt").apply { writeText("retain") }
        val store = FileRecommendationStore(dir, Platform.NETEASE)
        store.write(owner, RecommendationSnapshot(Platform.NETEASE, emptyList(), at))
        assertTrue(store.read(owner)!!.songs.isEmpty())
        store.clear()
        assertNull(store.read(owner))
        assertEquals("retain", protected.readText())
    }

    @Test fun invalidOwnerAndForeignPlatformCannotOverwriteValidSnapshot() {
        val dir = temporary.newFolder()
        val store = FileRecommendationStore(dir, Platform.NETEASE)
        store.write(owner, RecommendationSnapshot(Platform.NETEASE, listOf(song()), at))
        assertThrows(IllegalArgumentException::class.java) { store.read("raw-account-id") }
        assertThrows(IllegalArgumentException::class.java) {
            store.write(owner, RecommendationSnapshot(Platform.QQ, listOf(song(Platform.QQ)), at))
        }
        assertEquals(at, store.read(owner)!!.fetchedAtEpochMs)
    }

    @Test fun freshnessUsesRetrievalDayAndHandlesBackwardsWallClockConservatively() {
        val midnight = java.time.Instant.parse("2026-11-11T16:00:00Z").toEpochMilli()
        val snapshot = RecommendationSnapshot(Platform.NETEASE, emptyList(), midnight - 1)
        assertEquals("2026-11-11", snapshot.retrievedDate)
        assertTrue(snapshot.isFresh(midnight - 1))
        assertFalse(snapshot.isFresh(midnight))
        assertFalse(snapshot.isFresh(midnight - 2))
    }

    @Test fun qqReportsPersonalRadioAndUsesAnHourlyCacheRatherThanDailyFreshness() {
        val snapshot = RecommendationSnapshot(Platform.QQ, emptyList(), at)
        assertEquals(RecommendationKind.PERSONAL_RADIO, snapshot.kind)
        assertTrue(snapshot.isFresh(at + 1000))
        assertFalse(snapshot.isFresh(at + RecommendationsPolicy.RADIO_CACHE_MS))
        assertEquals(RecommendationKind.DAILY_SONGS, RecommendationSnapshot(Platform.KUGOU, emptyList(), at).kind)
    }
}
