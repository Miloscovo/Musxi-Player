package tech.musxi.player.wallpaper

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tech.musxi.core.wallpaper.WallpaperError
import tech.musxi.core.wallpaper.WallpaperFailure
import tech.musxi.core.wallpaper.WallpaperPreferences
import tech.musxi.core.wallpaper.WallpaperRepository

/** Local-only facade. Commands are serialized, disk/decoding use IO, callbacks return on main. */
class AppWallpaperRepository internal constructor(
    private val pool: AppWallpaperPool,
    private val core: WallpaperRepository,
) {
    private constructor(pool: AppWallpaperPool) : this(pool, pool.library.player)

    /** Kept for callers of the original facade; it joins the application-owned pool. */
    constructor(context: Context) : this(AppWallpaperPool.get(context))

    val state = core.state

    /** Feed the single content URI from PickVisualMedia. Cancellation of the picker changes nothing. */
    fun choose(uri: Uri?, result: (WallpaperError?) -> Unit = {}): Job = submit(result) {
        if (uri == null) return@submit null
        if (uri.scheme != "content") {
            core.reportImportFailure(WallpaperError.READ_FAILED)
            return@submit WallpaperError.READ_FAILED
        }
        val job = currentCoroutineContext()
        core.importImage {
            pool.importer.prepare(
                openInput = { pool.app.contentResolver.openInputStream(uri) ?: throw WallpaperFailure(WallpaperError.READ_FAILED) },
                checkCancelled = { job.ensureActive() },
            ).file
        }
    }

    /** The source must be the other application-owned slot, never an arbitrary ID or path. */
    fun chooseSaved(from: AppWallpaperRepository, result: (WallpaperError?) -> Unit = {}): Job = submit(result) {
        if (pool !== from.pool) {
            core.reportImportFailure(WallpaperError.INVALID_ARGUMENT)
            WallpaperError.INVALID_ARGUMENT
        } else core.chooseSaved(from.core)
    }

    fun preview(expectedId: String, result: (WallpaperError?) -> Unit = {}): Job = submit(result) { core.preview(expectedId) }
    fun apply(expectedId: String, result: (WallpaperError?) -> Unit = {}): Job = submit(result) { core.apply(expectedId) }
    fun cancel(result: (WallpaperError?) -> Unit = {}): Job = submit(result) { core.cancel() }
    fun reset(result: (WallpaperError?) -> Unit = {}): Job = submit(result) { core.reset() }
    fun setPreferences(value: WallpaperPreferences, result: (WallpaperError?) -> Unit = {}): Job =
        submit(result) { core.setPreferences(value) }

    /** Resolve once when the asset ID changes, then pass the managed File to the existing Coil loader. */
    fun imageFile(id: String, result: (Result<File>) -> Unit): Job = pool.command(result) {
        try { Result.success(core.resolveImage(id)) }
        catch (error: WallpaperFailure) { Result.failure(error) }
    }

    fun imageFailed(expectedId: String): Job = pool.command({}) { core.imageFailed(expectedId) }

    private fun submit(
        result: (WallpaperError?) -> Unit = {},
        action: suspend () -> WallpaperError?,
    ): Job = pool.command(result, action)
}
