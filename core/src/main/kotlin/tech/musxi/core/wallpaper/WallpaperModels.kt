package tech.musxi.core.wallpaper

/** Legacy serialized field. Rendering is determined by WallpaperSlot, never by this value. */
enum class WallpaperScope { PLAYER, GLOBAL }

/** Separate settings manifests backed by the same content-addressed image pool. */
enum class WallpaperSlot(internal val manifestName: String) {
    PLAYER("current.wallpaper"), INTERFACE("interface.wallpaper"),
}

enum class WallpaperFormat(val mimeType: String, val extension: String) {
    JPEG("image/jpeg", "jpg"), PNG("image/png", "png"), WEBP("image/webp", "webp"),
}

object WallpaperLimits {
    const val MAX_SOURCE_BYTES = 32L * 1024 * 1024
    const val MAX_SOURCE_EDGE = 16_384
    const val MAX_SOURCE_PIXELS = 40_000_000L
    const val MAX_DISPLAY_BYTES = 16L * 1024 * 1024
    const val MAX_DISPLAY_EDGE = 2_560
    const val MAX_DISPLAY_PIXELS = 4_000_000L
}

data class WallpaperImageInfo(val format: WallpaperFormat, val width: Int, val height: Int) {
    init {
        require(width in 1..WallpaperLimits.MAX_SOURCE_EDGE && height in 1..WallpaperLimits.MAX_SOURCE_EDGE)
        require(width.toLong() * height <= WallpaperLimits.MAX_SOURCE_PIXELS)
    }
}

/**
 * Framing of the whole EXIF-normalized managed image, independent of the window aspect ratio.
 * Zoom 1 fills the window; the renderer bounds the requested center to keep the image filled.
 * Commands validate values so invalid input is reported through WallpaperError.INVALID_ARGUMENT.
 */
data class WallpaperFrame(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val zoom: Float = 1f,
) {
    internal fun validate() {
        if (!centerX.isFinite() || centerX !in 0f..1f ||
            !centerY.isFinite() || centerY !in 0f..1f ||
            !zoom.isFinite() || zoom !in 1f..4f
        ) throw WallpaperFailure(WallpaperError.INVALID_ARGUMENT)
    }
}

/** Picture visibility intent (0..150); commands validate input without clamping it. */
data class WallpaperPreferences(
    val enabled: Boolean = false,
    val scope: WallpaperScope = WallpaperScope.PLAYER,
    val strength: Int = 35,
    val frame: WallpaperFrame = WallpaperFrame(),
) {
    internal fun validate() {
        if (strength !in 0..MAX_STRENGTH) throw WallpaperFailure(WallpaperError.INVALID_ARGUMENT)
        frame.validate()
    }

    companion object {
        const val MAX_STRENGTH = 150
    }
}

/** Content-addressed managed copy. External URIs and absolute paths never enter persisted state. */
data class WallpaperAsset(
    val id: String,
    val info: WallpaperImageInfo,
    val bytes: Long,
) {
    init {
        require(id.matches(Regex("[0-9a-f]{64}")))
        require(bytes in 1..WallpaperLimits.MAX_DISPLAY_BYTES)
        require(info.width <= WallpaperLimits.MAX_DISPLAY_EDGE && info.height <= WallpaperLimits.MAX_DISPLAY_EDGE)
        require(info.width.toLong() * info.height <= WallpaperLimits.MAX_DISPLAY_PIXELS)
    }
    val fileName: String get() = "$id.${info.format.extension}"
}

data class SavedWallpaper(
    val asset: WallpaperAsset? = null,
    val preferences: WallpaperPreferences = WallpaperPreferences(),
) {
    init { require(asset != null || !preferences.enabled) }
}

enum class WallpaperError {
    UNSUPPORTED_FORMAT, ANIMATED_IMAGE, INVALID_IMAGE, IMAGE_TOO_LARGE,
    READ_FAILED, STORAGE_FAILED, SAVED_IMAGE_UNAVAILABLE, STALE_CANDIDATE, NO_IMAGE,
    INVALID_ARGUMENT,
}

class WallpaperFailure(val reason: WallpaperError) : Exception(reason.name)

data class WallpaperState(
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val saved: SavedWallpaper = SavedWallpaper(),
    val candidate: WallpaperAsset? = null,
    val previewing: Boolean = false,
    val draft: WallpaperPreferences? = null,
    val error: WallpaperError? = null,
) {
    /** UI displays this image; a disabled wallpaper retains its saved file for re-enabling. */
    val effectiveAsset: WallpaperAsset? get() =
        if ((draft ?: saved.preferences).enabled) {
            if (previewing) candidate ?: saved.asset else saved.asset
        } else null
    val effectivePreferences: WallpaperPreferences get() = draft ?: saved.preferences
}
