package tech.musxi.accounts

import java.io.File
import tech.musxi.core.account.Platform
import tech.musxi.core.cache.CacheFileSize
import tech.musxi.core.cache.CacheKind
import tech.musxi.core.library.FileLibraryPageStore
import tech.musxi.core.lyrics.FileLyricsCacheStore

/** Explicit cache allowlist. Account stores, manual lyric bindings and journals are not members. */
internal class ContentMetadataCache(noBackupDirectory: File) {
    private val pageDirectories = mapOf(
        Platform.NETEASE to File(noBackupDirectory, "library-pages-v1"),
        Platform.KUGOU to File(noBackupDirectory, "kugou-library-pages-v1"),
        Platform.QQ to File(noBackupDirectory, "qq-library-pages-v1"),
    )
    val libraryPages = pageDirectories.mapValues { (platform, directory) -> FileLibraryPageStore(directory, platform) }
    private val lyricsDirectory = File(noBackupDirectory, "lyrics-cache-v1")
    val lyrics = FileLyricsCacheStore(lyricsDirectory)

    // AccountRepository serializes all calls with normal store access on its disk queue.
    fun sizeBytes(kind: CacheKind): Long = when (kind) {
        CacheKind.LYRICS -> CacheFileSize.bytes(lyricsDirectory)
        CacheKind.LIBRARY_PAGES -> pageDirectories.values.fold(0L) { sum, directory ->
            val next = CacheFileSize.bytes(directory)
            if (next > Long.MAX_VALUE - sum) Long.MAX_VALUE else sum + next
        }
        CacheKind.COVERS -> error("Cover cache belongs to the image loader")
    }

    fun clear(kind: CacheKind) {
        when (kind) {
            CacheKind.LYRICS -> lyrics.clear()
            CacheKind.LIBRARY_PAGES -> {
                // A damaged platform cache must not prevent the other platforms from clearing.
                var failed = false
                libraryPages.values.forEach { store ->
                    try { store.clear() } catch (_: Exception) { failed = true }
                }
                check(!failed) { "Library cache unavailable" }
            }
            CacheKind.COVERS -> error("Cover cache belongs to the image loader")
        }
    }
}
