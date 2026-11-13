package tech.musxi.core.library

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.musxi.core.account.Platform

class FilePlaylistOrderStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val ownerA = "a".repeat(64)
    private val ownerB = "b".repeat(64)
    private fun key(id: String) = MediaKey(Platform.NETEASE, id)

    @Test fun aNewInstanceRestoresOrderAndAccountsHaveSeparateFiles() {
        val directory = temporary.newFolder()
        val first = FilePlaylistOrderStore(directory, Platform.NETEASE)
        assertTrue(first.read(ownerA).isEmpty())
        first.write(ownerA, listOf(key("2"), key("1")))
        first.write(ownerB, listOf(key("3")))
        val restarted = FilePlaylistOrderStore(directory, Platform.NETEASE)
        assertEquals(listOf(key("2"), key("1")), restarted.read(ownerA))
        assertEquals(listOf(key("3")), restarted.read(ownerB))
        restarted.write(ownerA, emptyList())
        assertTrue(first.read(ownerA).isEmpty())
        assertEquals(listOf(key("3")), first.read(ownerB))
        assertFalse(directory.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun malformedAndTruncatedFilesAreErrorsRatherThanDefaultOrder() {
        val directory = temporary.newFolder()
        val store = FilePlaylistOrderStore(directory, Platform.NETEASE)
        store.write(ownerA, listOf(key("1")))
        val file = File(directory, "$ownerA.order")
        val original = file.readBytes()
        for (bad in listOf(byteArrayOf(), original.copyOf(4), original + byteArrayOf(1))) {
            file.writeBytes(bad)
            try { store.read(ownerA); fail("corruption must be observable") } catch (_: IOException) { }
            assertArrayEquals(bad, file.readBytes())
        }
    }

    @Test fun wrongPlatformAndDuplicateKeysCannotOverwriteAValidOrder() {
        val directory = temporary.newFolder()
        val store = FilePlaylistOrderStore(directory, Platform.NETEASE)
        store.write(ownerA, listOf(key("1")))
        for (bad in listOf(listOf(key("2"), key("2")), listOf(MediaKey(Platform.KUGOU, "2")))) {
            try { store.write(ownerA, bad); fail("invalid order") } catch (_: IllegalArgumentException) { }
            assertEquals(listOf(key("1")), store.read(ownerA))
        }
        // Production uses a separate directory per platform. A wrongly shared directory fails closed.
        try { FilePlaylistOrderStore(directory, Platform.QQ).read(ownerA); fail("wrong platform") }
        catch (_: IOException) { }
        val qq = FilePlaylistOrderStore(temporary.newFolder(), Platform.QQ)
        qq.write(ownerA, listOf(MediaKey(Platform.QQ, "9_1")))
        assertEquals(listOf(key("1")), store.read(ownerA))
        assertEquals(listOf(MediaKey(Platform.QQ, "9_1")), qq.read(ownerA))
    }

    @Test fun anOwnerCannotAddressAFileOutsideItsStore() {
        val directory = temporary.newFolder()
        val store = FilePlaylistOrderStore(directory, Platform.NETEASE)
        for (bad in listOf("../outside", "A".repeat(64), "")) {
            try { store.write(bad, emptyList()); fail("invalid owner") } catch (_: IllegalArgumentException) { }
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
