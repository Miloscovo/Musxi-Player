package tech.musxi.accounts

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.musxi.core.account.Platform
import tech.musxi.core.cache.CacheKind
import tech.musxi.core.library.MediaKey
import tech.musxi.core.library.Page
import tech.musxi.core.library.PlaylistSummary
import tech.musxi.core.library.SavedLibraryPage
import tech.musxi.core.lyrics.LrcParser

class ContentMetadataCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = "a".repeat(64)

    @Test fun countsAndClearsRealStoresWithoutTouchingAccountsBindingsJournalsOrOtherCaches() {
        val root = temporary.newFolder("private")
        val noBackup = File(root, "no_backup").apply { mkdirs() }
        val protected = listOf(
            "shared_prefs/musxi-settings-v1.xml", "shared_prefs/account.xml",
            "no_backup/account-credentials.bin", "cache/app-updates/download.apk",
        ) + Platform.entries.flatMap { listOf(
            "no_backup/playback-journal-${it.name.lowercase()}-v1/journal.bin",
            "no_backup/lyrics-bindings-${it.name.lowercase()}-v1/binding.bin",
        ) }
        protected.forEach { name -> File(root, name).apply { parentFile!!.mkdirs(); writeText("retain:$name") } }
        val cache = ContentMetadataCache(noBackup)
        cache.libraryPages.forEach { (platform, store) ->
            store.writePlaylists(owner, 0, SavedLibraryPage(Page(
                listOf(PlaylistSummary(MediaKey(platform, if (platform == Platform.QQ) "12_0" else "12"), "Sample", null, 1)), null, 1), 100))
        }
        cache.lyrics.write(owner, MediaKey(Platform.NETEASE, "1"), LrcParser.parse("[00:01]line"))
        val before = cache.sizeBytes(CacheKind.LIBRARY_PAGES)
        assertTrue(before > 0)
        assertTrue(cache.sizeBytes(CacheKind.LYRICS) > 0)
        cache.clear(CacheKind.LYRICS)
        assertEquals(0L, cache.sizeBytes(CacheKind.LYRICS))
        assertEquals(before, cache.sizeBytes(CacheKind.LIBRARY_PAGES))
        cache.clear(CacheKind.LIBRARY_PAGES)
        assertEquals(0L, cache.sizeBytes(CacheKind.LIBRARY_PAGES))
        cache.libraryPages.values.forEach { assertNull(it.readPlaylists(owner, 0)) }
        protected.forEach { name -> assertEquals("retain:$name", File(root, name).readText()) }
        assertThrows(IllegalStateException::class.java) { cache.clear(CacheKind.COVERS) }
    }

    @Test fun aBrokenPlatformDoesNotPreventOtherPlatformsFromClearing() {
        val noBackup = temporary.newFolder("private")
        File(noBackup, "library-pages-v1").writeText("broken directory")
        val cache = ContentMetadataCache(noBackup)
        val qq = cache.libraryPages.getValue(Platform.QQ)
        qq.writePlaylists(owner, 0, SavedLibraryPage(Page(emptyList(), null, 0), 100))
        assertThrows(IllegalStateException::class.java) { cache.clear(CacheKind.LIBRARY_PAGES) }
        assertNull(qq.readPlaylists(owner, 0))
    }
}
