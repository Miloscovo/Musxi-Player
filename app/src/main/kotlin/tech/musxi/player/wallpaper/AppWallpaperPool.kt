package tech.musxi.player.wallpaper

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tech.musxi.core.wallpaper.WallpaperFailure
import tech.musxi.core.wallpaper.WallpaperLibrary

/** Application-owned command queue; import cleanup and both loads precede every slot operation. */
internal class AppWallpaperPool private constructor(context: Context) {
    val app: Context = context.applicationContext
    val library = WallpaperLibrary(File(app.filesDir, "wallpapers-v1"))
    val importer = AndroidWallpaperImporter(File(app.cacheDir, "wallpaper-imports"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Mutex()
    val player = AppWallpaperRepository(this, library.player)
    val interfaceWallpaper = AppWallpaperRepository(this, library.interfaceWallpaper)

    private val initialization = scope.launch {
        withContext(Dispatchers.IO) {
            commands.withLock {
                try { importer.cleanupAbandoned() } catch (_: WallpaperFailure) { }
                catch (_: SecurityException) { }
                library.load()
            }
        }
    }

    fun <T> command(result: (T) -> Unit, action: suspend () -> T): Job = scope.launch {
        initialization.join()
        val response = withContext(Dispatchers.IO) { commands.withLock { action() } }
        result(response)
    }

    companion object {
        private val pools = mutableMapOf<String, AppWallpaperPool>()

        /** Even legacy facade construction must not create a second collector for the same files. */
        fun get(context: Context): AppWallpaperPool = synchronized(pools) {
            val app = context.applicationContext
            val directory = File(app.filesDir, "wallpapers-v1").absoluteFile.toPath().normalize().toString()
            pools.getOrPut(directory) { AppWallpaperPool(app) }
        }
    }
}
