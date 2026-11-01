package tech.musxi.core.cache

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheFileSizeTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun countsFilesRecursivelyAndReturnsZeroForMissingDirectory() {
        val cache = temporary.newFolder("cache")
        val nested = File(cache, "nested/deeper").apply { mkdirs() }
        File(cache, "metadata.json").writeBytes(ByteArray(3))
        File(nested, "entry.tmp").writeBytes(ByteArray(5))

        assertEquals(8L, CacheFileSize.bytes(cache))
        assertEquals(0L, CacheFileSize.bytes(File(cache, "missing")))
    }

    @Test
    fun ignoresSymbolicLinksWithoutSkippingValidSiblings() {
        val cache = temporary.newFolder("cache")
        val outside = temporary.newFolder("outside")
        val outsideFile = File(outside, "outside.bin").apply { writeBytes(ByteArray(11)) }
        val outsideDirectory = File(outside, "nested").apply { mkdirs() }
        File(outsideDirectory, "nested.bin").writeBytes(ByteArray(13))

        try {
            Files.createSymbolicLink(File(cache, "linked-file").toPath(), outsideFile.toPath())
            Files.createSymbolicLink(File(cache, "linked-directory").toPath(), outsideDirectory.toPath())
            Files.createSymbolicLink(File(cache, "linked-root").toPath(), outside.toPath())
        } catch (error: Exception) {
            assumeNoException("Symbolic links unavailable on this host", error)
        }
        File(cache, "local.bin").writeBytes(ByteArray(7))

        assertEquals(7L, CacheFileSize.bytes(cache))
        assertEquals(0L, CacheFileSize.bytes(File(cache, "linked-root")))
    }

    @Test
    fun rejectsRootThatIsNotADirectory() {
        val file = temporary.newFile("cache-file")

        assertThrows(IllegalArgumentException::class.java) {
            CacheFileSize.bytes(file)
        }
    }
}
