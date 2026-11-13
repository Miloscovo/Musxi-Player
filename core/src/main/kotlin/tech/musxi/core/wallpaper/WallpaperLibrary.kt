package tech.musxi.core.wallpaper

import java.io.File
import java.io.IOException

/**
 * Two independent wallpaper transactions sharing one image pool and one monitor. Both manifests
 * are loaded before collection is enabled, including when only one slot is requested by the UI.
 */
class WallpaperLibrary internal constructor(
    private val playerStore: WallpaperStore,
    interfaceStore: WallpaperStore,
) {
    constructor(directory: File) : this(
        FileWallpaperStore(directory, WallpaperSlot.PLAYER),
        FileWallpaperStore(directory, WallpaperSlot.INTERFACE),
    )

    private val transactionLock = Any()
    private var initialized = false
    val player = WallpaperRepository(playerStore, transactionLock) { collect() }
    val interfaceWallpaper = WallpaperRepository(interfaceStore, transactionLock) { collect() }

    /** Each slot publishes its own load error; failure of one does not prevent loading the other. */
    fun load(): Unit = synchronized(transactionLock) {
        if (initialized) return
        player.load()
        interfaceWallpaper.load()
        initialized = true
        // Cleanup is best effort; it cannot invalidate an already loaded or committed manifest.
        try { collect() }
        catch (_: IOException) { }
        catch (_: WallpaperFailure) { }
    }

    private fun collect() {
        if (!initialized) return
        val keep = listOf(player.state.value, interfaceWallpaper.state.value)
            .flatMap { listOfNotNull(it.saved.asset, it.candidate) }.toSet()
        playerStore.prune(keep)
    }
}
