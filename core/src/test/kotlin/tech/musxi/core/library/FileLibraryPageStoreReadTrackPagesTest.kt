package tech.musxi.core.library

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.musxi.core.account.Platform

class FileLibraryPageStoreReadTrackPagesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = "a".repeat(64)
    private val playlist = MediaKey(Platform.KUGOU, "9")
    private val songPrefix = "0".repeat(32) + "_1_"

    @Test fun retainsGapPagesAndSortsThemByOffset() {
        val directory = temporary.newFolder()
        File(directory, "owner-v1.bin").writeText(owner, Charsets.US_ASCII)
        val target = File(directory, "page-v1-${fileHash()}.bin")
        target.writeBytes(collection(0 to 30, 60 to null))

        val pages = FileLibraryPageStore(directory, Platform.KUGOU).readTrackPages(owner, playlist)
        assertEquals(listOf(0, 60), pages.map { it.offset })
        assertEquals(listOf(30, 1), pages.map { it.saved.page.items.size })
        assertEquals(listOf(songPrefix + "30", songPrefix + "31"), pages.map { cached ->
            cached.saved.page.items.let { if (cached.offset == 0) it.last().key.id else it.single().key.id }
        })
    }

    private fun fileHash(): String =
        MessageDigest.getInstance("SHA-256")
            .digest((owner + ":" + playlist.id).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun collection(vararg pages: Pair<Int, Int?>): ByteArray {
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeUTF(owner)
                data.writeUTF(playlist.id)
                data.writeInt(pages.size)
                pages.forEachIndexed { index, (offset, next) ->
                    val itemCount = if (offset == 0) 30 else 1
                    data.writeInt(offset)
                    data.writeLong(10L + offset)
                    data.writeInt(next ?: -1)
                    data.writeInt(61)
                    data.writeInt(itemCount)
                    repeat(itemCount) { item ->
                        data.writeUTF(songPrefix + if (index == 0) (item + 1).toString() else "31")
                        data.writeUTF("Song")
                        data.writeInt(1)
                        data.writeUTF("Artist")
                        data.writeUTF("Album")
                        data.writeLong(1_000)
                        data.writeUTF("")
                    }
                }
            }
        }.toByteArray()
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeInt(0x4d584b43)
                data.writeInt(3)
                data.writeInt(payload.size)
                data.writeLong(CRC32().apply { update(payload) }.value)
                data.write(payload)
            }
        }.toByteArray()
    }
}
