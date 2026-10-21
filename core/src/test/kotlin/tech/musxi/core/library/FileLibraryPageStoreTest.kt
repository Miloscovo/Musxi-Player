package tech.musxi.core.library

import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.musxi.core.account.Platform

class FileLibraryPageStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val ownerA = "a".repeat(64)
    private val ownerB = "b".repeat(64)
    private val playlist = MediaKey(Platform.NETEASE, "9")
    private fun playlists(title: String = "List", next: Int? = null, total: Int? = 1, cover: String? = null) =
        SavedLibraryPage(Page(listOf(PlaylistSummary(playlist, title, cover, 5)), next, total), 1234L)
    private fun tracks(id: String = "1", next: Int? = null, total: Int? = 1) =
        SavedLibraryPage(Page(listOf(SongSummary(MediaKey(Platform.NETEASE, id), "Song", listOf("Artist"), "Album", 12345)), next, total), 5678L)
    private fun directory() = temporary.newFolder()
    private fun collection(directory: File) = directory.listFiles()!!.single { it.name.startsWith("page-v1-") && it.name.endsWith(".bin") }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected cache failure") } catch (failure: IOException) {
            assertEquals("Library cache unavailable", failure.message); assertNull(failure.cause)
        }
    }

    @Test fun metadataPagesAndTimestampRestoreAcrossInstancesWithoutSignedCover() {
        val dir = directory(); val store = FileLibraryPageStore(dir)
        store.writePlaylists(ownerA, 0, playlists(cover = "https://p1.music.126.net/image?signature=private-fixture"))
        store.writeTracks(ownerA, playlist, 0, tracks())
        val restored = FileLibraryPageStore(dir)
        val list = restored.readPlaylists(ownerA, 0)!!
        assertEquals("List", list.page.items.single().title); assertNull(list.page.items.single().coverUrl)
        assertEquals(1234L, list.fetchedAtEpochMs)
        assertEquals(tracks(), restored.readTracks(ownerA, playlist, 0))
        assertFalse(dir.listFiles()!!.any { it.readBytes().toString(Charsets.ISO_8859_1).contains("private-fixture") })
        assertEquals("FileLibraryPageStore(redacted)", restored.toString())
    }

    @Test fun publicArtworkSurvivesColdReadAndUnsafeSongArtworkNeverReachesDisk() {
        val dir = directory(); val store = FileLibraryPageStore(dir)
        val cover = "https://p1.music.126.net/fixture.jpg"
        store.writePlaylists(ownerA, 0, playlists(cover = cover))
        val songPage = tracks().let { saved -> saved.copy(page = saved.page.copy(items = saved.page.items.map {
            it.copy(coverUrl = cover)
        })) }
        store.writeTracks(ownerA, playlist, 0, songPage)
        val restored = FileLibraryPageStore(dir)
        assertEquals(cover, restored.readPlaylists(ownerA, 0)!!.page.items.single().coverUrl)
        assertEquals(songPage, restored.readTracks(ownerA, playlist, 0))
        store.writeTracks(ownerA, playlist, 0, songPage.let { saved -> saved.copy(page = saved.page.copy(items = saved.page.items.map {
            it.copy(coverUrl = "https://p1.music.126.net/image?credential=private-fixture")
        })) })
        assertNull(restored.readTracks(ownerA, playlist, 0)!!.page.items.single().coverUrl)
        assertFalse(dir.listFiles()!!.any { it.readBytes().toString(Charsets.ISO_8859_1).contains("private-fixture") })
    }

    @Test fun legacyVersionOneCollectionsRemainReadableAndUpgradeOnWrite() {
        val dir = directory(); val store = FileLibraryPageStore(dir)
        store.writePlaylists(ownerA, 0, playlists())
        val file = collection(dir)
        val payload = java.io.ByteArrayOutputStream().also { bytes -> java.io.DataOutputStream(bytes).use { data ->
            data.writeUTF(ownerA); data.writeUTF(""); data.writeInt(1)
            data.writeInt(0); data.writeLong(1234); data.writeInt(-1); data.writeInt(1); data.writeInt(1)
            data.writeUTF(playlist.id); data.writeUTF("Legacy"); data.writeInt(5)
        } }.toByteArray()
        val oldMagic = ByteBuffer.wrap(file.readBytes()).int
        java.io.DataOutputStream(file.outputStream()).use { data ->
            data.writeInt(oldMagic); data.writeInt(1); data.writeInt(payload.size)
            data.writeLong(CRC32().apply { update(payload) }.value); data.write(payload)
        }
        assertEquals("Legacy", FileLibraryPageStore(dir).readPlaylists(ownerA, 0)!!.page.items.single().title)
        store.writePlaylists(ownerA, 0, playlists(cover = "https://p1.music.126.net/image.jpg"))
        assertEquals(2, ByteBuffer.wrap(file.readBytes()).getInt(4))
        assertNotNull(FileLibraryPageStore(dir).readPlaylists(ownerA, 0)!!.page.items.single().coverUrl)
    }

    @Test fun legacySongPagesPreserveArtistsAlbumDurationAndTimestampWithoutArtwork() {
        val dir = directory(); val store = FileLibraryPageStore(dir)
        store.writeTracks(ownerA, playlist, 0, tracks())
        val file = collection(dir)
        val payload = java.io.ByteArrayOutputStream().also { bytes -> java.io.DataOutputStream(bytes).use { data ->
            data.writeUTF(ownerA); data.writeUTF(playlist.id); data.writeInt(1)
            data.writeInt(0); data.writeLong(5678); data.writeInt(-1); data.writeInt(1); data.writeInt(1)
            data.writeUTF("1"); data.writeUTF("Song"); data.writeInt(1); data.writeUTF("Artist")
            data.writeUTF("Album"); data.writeLong(12345)
        } }.toByteArray()
        val oldMagic = ByteBuffer.wrap(file.readBytes()).int
        java.io.DataOutputStream(file.outputStream()).use { data ->
            data.writeInt(oldMagic); data.writeInt(1); data.writeInt(payload.size)
            data.writeLong(CRC32().apply { update(payload) }.value); data.write(payload)
        }
        assertEquals(tracks(), FileLibraryPageStore(dir).readTracks(ownerA, playlist, 0))
    }

    @Test fun switchingOwnerErasesPreviousOwnerAndClearOnlyRemovesOwnedFiles() {
        val dir = directory(); val foreign = File(dir, "unrelated.txt").apply { writeText("keep") }
        val store = FileLibraryPageStore(dir)
        store.writePlaylists(ownerA, 0, playlists("Old"))
        assertNull(store.readPlaylists(ownerB, 0))
        store.writePlaylists(ownerB, 0, playlists("New"))
        assertNull(FileLibraryPageStore(dir).readPlaylists(ownerA, 0))
        assertNull(store.readPlaylists(ownerB, 0))
        store.writeTracks(ownerB, playlist, 0, tracks())
        store.clear()
        assertEquals(listOf("unrelated.txt"), dir.listFiles()!!.map { it.name })
        assertEquals("keep", foreign.readText())
    }

    @Test fun refreshingFirstPageAtomicallyInvalidatesLaterPagesOfOnlyThatCollection() {
        val dir = directory(); val store = FileLibraryPageStore(dir)
        store.writePlaylists(ownerA, 0, playlists("First", next = 1, total = 2))
        store.writePlaylists(ownerA, 1, playlists("Second", total = 2))
        store.writeTracks(ownerA, playlist, 0, tracks())
        assertNotNull(store.readPlaylists(ownerA, 1))
        store.writePlaylists(ownerA, 0, playlists("Refreshed", next = 1, total = 2))
        assertNull(FileLibraryPageStore(dir).readPlaylists(ownerA, 1))
        assertNotNull(store.readTracks(ownerA, playlist, 0))
        store.writePlaylists(ownerA, 1, playlists("Current Second", total = 2))
        assertEquals("Current Second", store.readPlaylists(ownerA, 1)!!.page.items.single().title)
    }

    @Test fun paginationCannotBeginWithoutFirstPageOrJumpAcrossMissingPageOrChangedTotal() {
        val store = FileLibraryPageStore(directory())
        rejected { store.writeTracks(ownerA, playlist, 1, tracks(total = 2)) }
        store.writeTracks(ownerA, playlist, 0, tracks(next = 1, total = 3))
        rejected { store.writeTracks(ownerA, playlist, 2, tracks("3", total = 3)) }
        rejected { store.writeTracks(ownerA, playlist, 1, tracks("2", total = 2)) }
        store.writeTracks(ownerA, playlist, 1, tracks("2", next = 2, total = 3))
        store.writeTracks(ownerA, playlist, 2, tracks("3", total = 3))
        val restored = store.readTracks(ownerA, playlist, 1)!!
        assertEquals(2, restored.page.nextOffset); assertEquals(3, restored.page.total)
    }

    @Test fun truncatedChecksumMismatchAndUnsupportedSchemaAreMissesNotEmptySuccess() {
        val dir = directory(); val store = FileLibraryPageStore(dir)
        fun checkMutation(mutate: (ByteArray) -> ByteArray) {
            store.writePlaylists(ownerA, 0, playlists())
            val file = collection(dir)
            file.writeBytes(mutate(file.readBytes()))
            assertNull(FileLibraryPageStore(dir).readPlaylists(ownerA, 0))
            assertFalse(file.exists())
        }
        checkMutation { it.copyOf(10) }
        checkMutation { it.apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() } }
        checkMutation { it.apply { ByteBuffer.wrap(this).putInt(4, 99) } }
    }

    @Test fun corruptCollectionOwnerFailsClosedEvenWithValidChecksum() {
        val dir = directory(); val store = FileLibraryPageStore(dir)
        store.writePlaylists(ownerA, 0, playlists())
        val file = collection(dir); val bytes = file.readBytes()
        // First payload field is modified UTF owner. Alter its first byte then repair only CRC.
        bytes[22] = 'b'.code.toByte()
        ByteBuffer.wrap(bytes).putLong(12, CRC32().apply { update(bytes, 20, bytes.size - 20) }.value)
        file.writeBytes(bytes)
        assertNull(store.readPlaylists(ownerA, 0))
    }

    @Test fun interruptedTemporaryWriteDoesNotReplaceCommittedPage() {
        val dir = directory(); val store = FileLibraryPageStore(dir)
        store.writePlaylists(ownerA, 0, playlists("Committed"))
        File(dir, collection(dir).name + ".tmp").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals("Committed", FileLibraryPageStore(dir).readPlaylists(ownerA, 0)!!.page.items.single().title)
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun invalidMetadataAndOversizedFileCannotCorruptCommittedPage() {
        val dir = directory(); val store = FileLibraryPageStore(dir)
        store.writePlaylists(ownerA, 0, playlists("Committed"))
        rejected { store.writePlaylists("../escape", 0, playlists()) }
        rejected { store.writePlaylists(ownerA, -1, playlists()) }
        rejected { store.writePlaylists(ownerA, 0, playlists("x".repeat(2049))) }
        rejected { store.writePlaylists(ownerA, 0, playlists(next = 0)) }
        assertEquals("Committed", store.readPlaylists(ownerA, 0)!!.page.items.single().title)
        collection(dir).outputStream().use { it.write(ByteArray(2 * 1024 * 1024 + 1)) }
        assertNull(store.readPlaylists(ownerA, 0))
    }

    @Test fun successfulEmptyPageIsDistinctFromNoCachedPageAndListsCannotBeMutated() {
        val store = FileLibraryPageStore(directory())
        assertNull(store.readPlaylists(ownerA, 0))
        store.writePlaylists(ownerA, 0, SavedLibraryPage(Page(emptyList(), null, 0), 123))
        assertTrue(store.readPlaylists(ownerA, 0)!!.page.items.isEmpty())
        store.writeTracks(ownerA, playlist, 0, tracks())
        val page = store.readTracks(ownerA, playlist, 0)!!.page
        try { (page.items as MutableList).clear(); fail("immutable list") } catch (_: UnsupportedOperationException) { }
        try { (page.items.single().artists as MutableList).clear(); fail("immutable artists") } catch (_: UnsupportedOperationException) { }
    }

    @Test fun collectionCountIsBoundedAndClearFailureIsReported() {
        val dir = directory(); val store = FileLibraryPageStore(dir)
        repeat(130) { index -> store.writeTracks(ownerA, MediaKey(Platform.NETEASE, (index + 1).toString()), 0, tracks()) }
        assertTrue(dir.listFiles()!!.count { it.name.startsWith("page-v1-") } <= 128)
        val occupied = File(temporary.root, "occupied").apply { writeText("not directory") }
        rejected { FileLibraryPageStore(occupied).clear() }
    }

    @Test fun totalDiskBudgetEvictsOlderCollectionsBeforeNewCommit() {
        val dir = directory(); val store = FileLibraryPageStore(dir)
        val artists = List(100) { "a".repeat(512) }
        val items = List(30) { index -> SongSummary(MediaKey(Platform.NETEASE, (index + 1).toString()),
            "Song", artists, "Album", 1000) }
        val value = SavedLibraryPage(Page(items, null, 30), 123L)
        repeat(12) { index ->
            store.writeTracks(ownerA, MediaKey(Platform.NETEASE, (index + 1).toString()), 0, value)
            assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 16L * 1024 * 1024)
        }
        assertTrue(dir.listFiles()!!.count { it.name.startsWith("page-v1-") } < 12)
        assertNotNull(store.readTracks(ownerA, MediaKey(Platform.NETEASE, "12"), 0))
    }
    private val qqPlaylist = MediaKey(Platform.QQ, "0_201")
    private fun qqTracks(count: Int = 2, next: Int? = 2, total: Int = 3) = SavedLibraryPage(
        Page(List(count) { SongSummary(MediaKey(Platform.QQ, "S" + it.toString().padStart(13, '0')),
            "Song", listOf("Artist"), "Album", 1234) }, next, total), 5678L)

    @Test fun qqRestoresActualCountContinuationAndRefreshInvalidatesLaterPages() {
        val dir = directory(); val store = FileLibraryPageStore(dir, Platform.QQ)
        val lists = SavedLibraryPage(Page(listOf(PlaylistSummary(qqPlaylist, "Favorites", "https://example.test/private", 3)), null, 1), 123L)
        store.writePlaylists(ownerA, 0, lists)
        store.writeTracks(ownerA, qqPlaylist, 0, qqTracks())
        store.writeTracks(ownerA, qqPlaylist, 2, qqTracks(1, null))
        val restored = FileLibraryPageStore(dir, Platform.QQ)
        assertEquals(2, restored.readTracks(ownerA, qqPlaylist, 0)!!.page.nextOffset)
        assertEquals(qqTracks(1, null), restored.readTracks(ownerA, qqPlaylist, 2))
        assertNull(restored.readPlaylists(ownerA, 0)!!.page.items.single().coverUrl)
        store.writeTracks(ownerA, qqPlaylist, 0, qqTracks())
        assertNull(restored.readTracks(ownerA, qqPlaylist, 2))
        assertNotNull(restored.readPlaylists(ownerA, 0))
        assertFalse(dir.listFiles()!!.any { it.readBytes().toString(Charsets.ISO_8859_1).contains("private") })
    }

    @Test fun qqRejectsMalformedKeysUnknownTotalsAndIncoherentOrUnlinkedPages() {
        val store = FileLibraryPageStore(directory(), Platform.QQ)
        listOf("0_0", "00_201", "1_-1", "1_1000001", "9007199254740992_1", "1_01", "1", "1_1_1").forEach { id ->
            rejected { store.writeTracks(ownerA, MediaKey(Platform.QQ, id), 0, qqTracks()) }
        }
        rejected { store.writeTracks(ownerA, qqPlaylist, 0, qqTracks(next = 30)) }
        rejected { store.writeTracks(ownerA, qqPlaylist, 0, qqTracks(next = null)) }
        rejected { store.writeTracks(ownerA, qqPlaylist, 0, qqTracks(0, 0)) }
        rejected { store.writeTracks(ownerA, qqPlaylist, 0, qqTracks(31, null, 31)) }
        rejected { store.writeTracks(ownerA, qqPlaylist, 0, qqTracks().let { it.copy(page = it.page.copy(total = null)) }) }
        rejected { store.writeTracks(ownerA, qqPlaylist, 2, qqTracks(1, null)) }
        store.writeTracks(ownerA, qqPlaylist, 0, qqTracks())
        rejected { store.writeTracks(ownerA, qqPlaylist, 1, qqTracks(2, null)) }
        rejected { store.writeTracks(ownerA, playlist, 0, qqTracks()) }
        rejected { store.writeTracks(ownerA, qqPlaylist, 0, tracks()) }
        listOf("short", "a".repeat(15), "a".repeat(13) + "_", "a".repeat(13) + "-").forEach { id ->
            rejected { store.writeTracks(ownerA, qqPlaylist, 0, SavedLibraryPage(Page(listOf(
                SongSummary(MediaKey(Platform.QQ, id), "Song", emptyList(), "", 1)), null, 1), 1)) }
        }
        assertEquals(qqTracks(), store.readTracks(ownerA, qqPlaylist, 0))
    }

    @Test fun qqOwnerAndClearAreIsolatedFromBothOtherStoresAndMagicCannotBeReinterpreted() {
        val netease = FileLibraryPageStore(directory()); val kugou = FileLibraryPageStore(directory(), Platform.KUGOU)
        val qqDir = directory(); val qq = FileLibraryPageStore(qqDir, Platform.QQ)
        netease.writePlaylists(ownerA, 0, playlists())
        kugou.writePlaylists(ownerA, 0, kugouLists())
        qq.writeTracks(ownerA, qqPlaylist, 0, qqTracks())
        assertNull(qq.readTracks(ownerB, qqPlaylist, 0))
        qq.writeTracks(ownerB, qqPlaylist, 0, qqTracks())
        qq.clear()
        assertNotNull(netease.readPlaylists(ownerA, 0)); assertNotNull(kugou.readPlaylists(ownerA, 0))
        val lists = SavedLibraryPage(Page(listOf(PlaylistSummary(MediaKey(Platform.QQ, "9_1"), "QQ", null, 0)), null, 1), 1L)
        qq.writePlaylists(ownerA, 0, lists)
        val file = collection(qqDir); val bytes = file.readBytes()
        ByteBuffer.wrap(bytes).putInt(0, 0x4d584b43); file.writeBytes(bytes)
        assertNull(qq.readPlaylists(ownerA, 0))
        assertNotNull(netease.readPlaylists(ownerA, 0)); assertNotNull(kugou.readPlaylists(ownerA, 0))
    }

    private val kugouPlaylist = MediaKey(Platform.KUGOU, "9")
    private fun kugouLists(next: Int? = 30, total: Int? = null) = SavedLibraryPage(
        Page(listOf(PlaylistSummary(kugouPlaylist, "KG list", "https://example.test/cover?secret", 746)), next, total), 1234L)
    private fun kugouTracks(count: Int = 30, next: Int? = 30, total: Int? = 746) = SavedLibraryPage(
        Page(List(count) { SongSummary(MediaKey(Platform.KUGOU, "a".repeat(32) + "_0_${it}"),
            "Song", listOf("Artist"), "Album", 1000) }, next, total), 5678L)

    @Test fun kugouRestoresShortUnknownTotalContinuationAndExplicitEmptyTerminalPage() {
        val dir = directory(); val store = FileLibraryPageStore(dir, Platform.KUGOU)
        store.writePlaylists(ownerA, 0, kugouLists())
        store.writePlaylists(ownerA, 30, SavedLibraryPage(Page(emptyList(), null, null), 2345L))
        val restored = FileLibraryPageStore(dir, Platform.KUGOU)
        val first = restored.readPlaylists(ownerA, 0)!!
        assertEquals(Platform.KUGOU, first.page.items.single().key.platform)
        assertNull(first.page.items.single().coverUrl)
        assertEquals(30, first.page.nextOffset); assertNull(first.page.total)
        assertEquals(1234L, first.fetchedAtEpochMs)
        assertTrue(restored.readPlaylists(ownerA, 30)!!.page.items.isEmpty())
        assertNull(restored.readPlaylists(ownerA, 60))
        assertFalse(dir.listFiles()!!.any { it.readBytes().toString(Charsets.ISO_8859_1).contains("secret") })
    }

    @Test fun kugouTrackPagesRestoreCompositeIdsAndFirstRefreshInvalidatesOnlyLaterTrackPages() {
        val dir = directory(); val store = FileLibraryPageStore(dir, Platform.KUGOU)
        store.writePlaylists(ownerA, 0, kugouLists())
        store.writeTracks(ownerA, kugouPlaylist, 0, kugouTracks())
        store.writeTracks(ownerA, kugouPlaylist, 30, kugouTracks(next = 60))
        val restored = FileLibraryPageStore(dir, Platform.KUGOU)
        assertEquals(kugouTracks(next = 60), restored.readTracks(ownerA, kugouPlaylist, 30))
        store.writeTracks(ownerA, kugouPlaylist, 0, kugouTracks())
        assertNull(restored.readTracks(ownerA, kugouPlaylist, 30))
        assertNotNull(restored.readPlaylists(ownerA, 0))
        store.writeTracks(ownerA, kugouPlaylist, 0, kugouTracks(next = 30, total = 31))
        store.writeTracks(ownerA, kugouPlaylist, 30, kugouTracks(1, null, 31))
        assertNull(restored.readTracks(ownerA, kugouPlaylist, 30)!!.page.nextOffset)
    }

    @Test fun kugouRejectsWrongContinuationGapsChangedTotalAndMalformedOrCrossPlatformKeys() {
        val store = FileLibraryPageStore(directory(), Platform.KUGOU)
        rejected { store.writePlaylists(ownerA, 0, kugouLists(next = 1)) }
        rejected { store.writePlaylists(ownerA, 0, kugouLists(next = null)) }
        rejected { store.writeTracks(ownerA, kugouPlaylist, 0, kugouTracks(1, 30, 746)) }
        rejected { store.writeTracks(ownerA, kugouPlaylist, 30, kugouTracks()) }
        store.writeTracks(ownerA, kugouPlaylist, 0, kugouTracks())
        rejected { store.writeTracks(ownerA, kugouPlaylist, 60, kugouTracks(next = 90)) }
        rejected { store.writeTracks(ownerA, kugouPlaylist, 30, kugouTracks(1, null, 31)) }
        rejected { store.writeTracks(ownerA, kugouPlaylist, 1, kugouTracks()) }
        rejected { store.writeTracks(ownerA, playlist, 0, kugouTracks()) }
        rejected { store.writePlaylists(ownerA, 0, playlists()) }
        val malformed = listOf("1", "A".repeat(32) + "_0_0", "a".repeat(32) + "_00_1",
            "a".repeat(32) + "_-1_0", "a".repeat(32) + "_9007199254740992_0")
        malformed.forEach { id -> rejected {
            store.writeTracks(ownerA, kugouPlaylist, 0, SavedLibraryPage(Page(listOf(
                SongSummary(MediaKey(Platform.KUGOU, id), "Song", emptyList(), "", 1)), null, 1), 1))
        } }
        rejected { store.writeTracks(ownerA, kugouPlaylist, 0, tracks()) }
        rejected { store.writeTracks(ownerA, MediaKey(Platform.KUGOU, "9007199254740992"), 0, kugouTracks()) }
        assertEquals(kugouTracks(), store.readTracks(ownerA, kugouPlaylist, 0))
    }

    @Test fun distinctPlatformStoresKeepOwnersAndClearIndependentAndRejectReinterpretedV1Bytes() {
        val neteaseDir = directory(); val kugouDir = directory()
        val netease = FileLibraryPageStore(neteaseDir)
        val kugou = FileLibraryPageStore(kugouDir, Platform.KUGOU)
        netease.writePlaylists(ownerA, 0, playlists())
        kugou.writePlaylists(ownerA, 0, kugouLists())
        kugou.clear()
        assertEquals(playlists(), FileLibraryPageStore(neteaseDir).readPlaylists(ownerA, 0))
        kugou.writePlaylists(ownerB, 0, kugouLists())
        assertEquals(playlists(), netease.readPlaylists(ownerA, 0))
        netease.clear()
        assertNotNull(kugou.readPlaylists(ownerB, 0))
        netease.writePlaylists(ownerB, 0, playlists())
        val source = collection(neteaseDir)
        source.copyTo(File(kugouDir, source.name), overwrite = true)
        assertNull(kugou.readPlaylists(ownerB, 0))
        assertEquals(playlists(), netease.readPlaylists(ownerB, 0))
    }

}
