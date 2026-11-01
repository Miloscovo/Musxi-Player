package tech.musxi.core.lyrics

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.musxi.core.account.Platform
import tech.musxi.core.library.MediaKey

class FileLyricsBindingStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val ownerA = "a".repeat(64)
    private val ownerB = "b".repeat(64)
    private val candidateOwner = "c".repeat(64)
    private val original = MediaKey(Platform.QQ, "original")
    private val candidate = MediaKey(Platform.NETEASE, "candidate")

    @Test
    fun bindingSurvivesColdStoreAndKeepsOffset() {
        val directory = temporary.newFolder("bindings")
        val binding = LyricsBinding(original, candidate, 12_345, candidateOwner, 7)
        FileLyricsBindingStore(directory).write(ownerA, binding)

        assertEquals(binding, FileLyricsBindingStore(directory).read(ownerA, original))
        assertNull(FileLyricsBindingStore(directory).read(ownerB, original))
        assertTrue(directory.walkTopDown().filter { it.isFile }.none { it.name.contains(original.id) })
    }

    @Test
    fun removeAndClearAreScopedToSafeOwnerAndOriginalKey() {
        val directory = temporary.newFolder("owner-scope")
        val store = FileLyricsBindingStore(directory)
        val otherOriginal = MediaKey(Platform.QQ, "other")
        store.write(ownerA, LyricsBinding(original, candidate, 1, candidateOwner, 2))
        store.write(ownerA, LyricsBinding(otherOriginal, candidate, 3, candidateOwner, 2))
        store.write(ownerB, LyricsBinding(original, candidate, 5, candidateOwner, 2))

        store.remove(ownerA, original)

        assertNull(store.read(ownerA, original))
        assertEquals(3L, store.read(ownerA, otherOriginal)?.offsetMs)
        assertEquals(5L, store.read(ownerB, original)?.offsetMs)

        store.clear(ownerA)
        assertNull(store.read(ownerA, otherOriginal))
        assertEquals(5L, store.read(ownerB, original)?.offsetMs)
    }

    @Test
    fun unsafeOwnerIsNeverUsedAsAPath() {
        val directory = File(temporary.root, "safe")
        val store = FileLyricsBindingStore(directory)

        assertNull(store.read("../outside", original))
        store.remove("../outside", original)
        store.clear("../outside")

        assertTrue(!File(temporary.root, "outside").exists())
    }

    @Test
    fun removePlatformDeletesEitherSideAcrossOwnersButKeepsUnrelated() {
        val directory = temporary.newFolder("platform-cleanup")
        val store = FileLyricsBindingStore(directory)
        val qqOriginal = MediaKey(Platform.QQ, "qq-original")
        val kgOriginal = MediaKey(Platform.KUGOU, "kg-original")
        val qqCandidate = MediaKey(Platform.QQ, "qq-candidate")
        val otherKgOriginal = MediaKey(Platform.KUGOU, "kg-other")
        val netease = MediaKey(Platform.NETEASE, "netease")
        val unrelated = LyricsBinding(otherKgOriginal, netease, 3, candidateOwner, 1)
        store.write(ownerA, LyricsBinding(qqOriginal, netease, 1, candidateOwner, 1))
        store.write(ownerB, LyricsBinding(kgOriginal, qqCandidate, 2, candidateOwner, 1))
        store.write(ownerB, unrelated)

        store.removePlatform(Platform.QQ)

        assertNull(store.read(ownerA, qqOriginal))
        assertNull(store.read(ownerB, kgOriginal))
        assertEquals(unrelated, store.read(ownerB, unrelated.originalKey))
    }

    @Test
    fun removePlatformDoesNotFollowSymbolicLink() {
        val directory = temporary.newFolder("platform-symlink")
        val store = FileLyricsBindingStore(directory)
        val ownerDirectory = File(directory, ownerA)
        ownerDirectory.mkdirs()
        val outsideDirectory = temporary.newFolder("outside-store")
        val outsideStore = FileLyricsBindingStore(outsideDirectory)
        val outsideBinding = LyricsBinding(original, candidate, 1, candidateOwner, 1)
        outsideStore.write(ownerB, outsideBinding)
        val outside = File(outsideDirectory, ownerB)
            .listFiles()
            .orEmpty()
            .single { it.extension == "binding" }
        val link = File(ownerDirectory, "qq-${"f".repeat(64)}.binding")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: java.io.IOException) {
            return
        } catch (_: SecurityException) {
            return
        }

        store.removePlatform(Platform.NETEASE)

        assertTrue(outside.exists())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals(outsideBinding, outsideStore.read(ownerB, original))
    }
}
