package tech.musxi.core.wallpaper

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Serializes local wallpaper transactions. All entry points perform disk work and must run off main.
 * Preview/draft state is deliberately volatile: process death restores the last applied snapshot.
 */
class WallpaperRepository internal constructor(
    private val store: WallpaperStore,
    private val transactionLock: Any,
    private val collectImages: ((Set<WallpaperAsset>) -> Unit)?,
) {
    /** Standalone storage; shared image directories must instead be opened through WallpaperLibrary. */
    constructor(store: WallpaperStore) : this(store, Any(), null)

    private val mutable = MutableStateFlow(WallpaperState())
    val state = mutable.asStateFlow()

    fun load(): WallpaperError? = command(requireLoaded = false) {
        try {
            val saved = store.read()
            mutable.value = WallpaperState(loaded = true, saved = saved)
            collect()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutable.value = WallpaperState(loaded = true, error = WallpaperError.SAVED_IMAGE_UNAVAILABLE)
            throw WallpaperFailure(WallpaperError.SAVED_IMAGE_UNAVAILABLE)
        }
    }

    fun choose(normalizedImage: File): WallpaperError? = command {
        stage(normalizedImage)
    }

    /** The trusted decoder transfers ownership of its temporary result; originals are never passed here. */
    fun importImage(prepare: () -> File): WallpaperError? = command {
        val normalized = prepare()
        try { stage(normalized) } finally { normalized.delete() }
    }

    /** Reuse only a saved asset from this library; source settings and preview state never transfer. */
    fun chooseSaved(from: WallpaperRepository): WallpaperError? = command {
        if (transactionLock !== from.transactionLock) fail(WallpaperError.INVALID_ARGUMENT)
        val source = from.state.value
        if (!source.loaded) fail(WallpaperError.STORAGE_FAILED)
        val asset = source.saved.asset ?: fail(
            if (source.error == WallpaperError.SAVED_IMAGE_UNAVAILABLE) WallpaperError.SAVED_IMAGE_UNAVAILABLE
            else WallpaperError.NO_IMAGE,
        )
        try {
            store.resolve(asset)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            fail(WallpaperError.SAVED_IMAGE_UNAVAILABLE)
        }
        acceptCandidate(asset)
    }

    fun preview(expectedId: String): WallpaperError? = command {
        val current = mutable.value
        val asset = current.candidate ?: current.saved.asset ?: fail(WallpaperError.NO_IMAGE)
        if (asset.id != expectedId) fail(WallpaperError.STALE_CANDIDATE)
        store.resolve(asset)
        // Re-entering preview must keep edits. A new candidate starts centered; an existing
        // saved image keeps its applied frame when opening the position editor.
        val draft = current.draft ?: current.saved.preferences.copy(
            enabled = true,
            frame = if (current.candidate != null) WallpaperFrame() else current.saved.preferences.frame,
        )
        mutable.value = current.copy(previewing = true, draft = draft, error = null)
    }

    /** During preview this changes only the draft; otherwise it commits the current image settings. */
    fun setPreferences(preferences: WallpaperPreferences): WallpaperError? = command {
        preferences.validate()
        val current = mutable.value
        if (current.previewing) {
            mutable.value = current.copy(draft = preferences, error = null)
        } else {
            if (current.saved.asset == null && preferences.enabled) fail(WallpaperError.NO_IMAGE)
            val updated = current.saved.copy(preferences = preferences)
            store.write(updated)
            mutable.value = current.copy(saved = updated, error = null)
        }
    }

    fun apply(expectedId: String): WallpaperError? = command {
        val current = mutable.value
        if (!current.previewing) fail(WallpaperError.STALE_CANDIDATE)
        val asset = current.candidate ?: current.saved.asset ?: fail(WallpaperError.NO_IMAGE)
        if (asset.id != expectedId) fail(WallpaperError.STALE_CANDIDATE)
        val updated = SavedWallpaper(asset, current.draft ?: current.saved.preferences.copy(enabled = true))
        updated.preferences.validate()
        store.write(updated)
        mutable.value = current.copy(saved = updated, candidate = null, previewing = false, draft = null, error = null)
        collect()
    }

    fun cancel(): WallpaperError? = command {
        mutable.value = mutable.value.copy(candidate = null, previewing = false, draft = null, error = null)
        collect()
    }

    fun reset(): WallpaperError? = command {
        val updated = SavedWallpaper()
        store.write(updated)
        mutable.value = WallpaperState(loaded = true, saved = updated, busy = true)
        collect()
    }

    /** Returns only a known saved/candidate asset; UI cannot request arbitrary files by path. */
    fun resolveImage(id: String): File = synchronized(transactionLock) {
        val current = mutable.value
        val asset = listOfNotNull(current.candidate, current.saved.asset).firstOrNull { it.id == id }
            ?: fail(WallpaperError.STALE_CANDIDATE)
        try {
            store.resolve(asset)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            imageFailed(id)
            fail(WallpaperError.SAVED_IMAGE_UNAVAILABLE)
        }
    }

    /** A delayed image-loader failure must not clear a newer choice. */
    fun imageFailed(id: String): Unit = synchronized(transactionLock) {
        val current = mutable.value
        if (current.previewing && current.candidate != null && current.candidate.id != id) return
        when {
            current.candidate?.id == id && current.saved.asset?.id != id -> {
                mutable.value = current.copy(candidate = null, previewing = false, draft = null, error = WallpaperError.INVALID_IMAGE)
                collect()
            }
            current.saved.asset?.id == id -> mutable.value = current.copy(
                saved = current.saved.copy(preferences = current.saved.preferences.copy(enabled = false)),
                candidate = current.candidate?.takeUnless { it.id == id },
                previewing = false, draft = null, error = WallpaperError.SAVED_IMAGE_UNAVAILABLE,
            )
        }
    }

    fun reportImportFailure(reason: WallpaperError): Unit = synchronized(transactionLock) {
        mutable.value = mutable.value.copy(error = reason)
    }

    private fun command(requireLoaded: Boolean = true, action: () -> Unit): WallpaperError? = synchronized(transactionLock) {
        if (requireLoaded && !mutable.value.loaded) return WallpaperError.STORAGE_FAILED.also {
            mutable.value = mutable.value.copy(error = it)
        }
        mutable.value = mutable.value.copy(busy = true, error = null)
        try {
            action()
            null
        } catch (error: WallpaperFailure) {
            error.reason.also { mutable.value = mutable.value.copy(error = it) }
        } catch (_: IOException) {
            WallpaperError.STORAGE_FAILED.also { mutable.value = mutable.value.copy(error = it) }
        } catch (_: SecurityException) {
            WallpaperError.STORAGE_FAILED.also { mutable.value = mutable.value.copy(error = it) }
        } finally {
            mutable.value = mutable.value.copy(busy = false)
        }
    }

    private fun collect() {
        // Cleanup failure cannot roll back a manifest that was already committed. Startup retries it.
        try {
            val keep = setOfNotNull(mutable.value.saved.asset, mutable.value.candidate)
            if (collectImages != null) collectImages.invoke(keep) else store.prune(keep)
        }
        catch (_: IOException) { }
        catch (_: WallpaperFailure) { }
    }

    private fun stage(normalizedImage: File) {
        val asset = store.stage(normalizedImage)
        acceptCandidate(asset)
    }

    private fun acceptCandidate(asset: WallpaperAsset) {
        val current = mutable.value
        mutable.value = current.copy(
            candidate = asset,
            draft = current.draft?.copy(frame = WallpaperFrame()),
            error = null,
        )
        collect()
    }

    private fun fail(reason: WallpaperError): Nothing = throw WallpaperFailure(reason)
}
