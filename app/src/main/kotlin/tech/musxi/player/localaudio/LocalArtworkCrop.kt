package tech.musxi.player.localaudio

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import tech.musxi.core.localaudio.LocalAudioError
import tech.musxi.core.localaudio.LocalAudioFailure

/**
 * Manual cover crop in normalized coordinates of the EXIF-upright picture, origin top-left.
 *
 * The data class accepts invalid numbers on purpose: the picker keeps a live crop while the user
 * drags, so construction must not fail. [validate] is the single check that the API boundary calls
 * before the crop is used.
 */
data class LocalArtworkCrop(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun validate() {
        val valid = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            left >= 0f && top >= 0f && right <= 1f && bottom <= 1f &&
            left < right && top < bottom
        if (!valid) throw LocalAudioFailure(LocalAudioError.INVALID_TARGET)
    }
}

/** Pixel rectangle of the raw (stored) picture, already rounded outward to whole pixels. */
internal data class LocalArtworkRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Maps a normalized crop of the upright picture back onto the stored pixel grid.
 *
 * Pure integer geometry: no Android graphics types, so the mapping can be unit tested and reused by
 * the region decoder later.
 */
internal object LocalArtworkCropGeometry {
    /** Shortest side a crop may have after outward rounding. */
    const val MIN_CROP_EDGE = 64

    fun region(
        rawWidth: Int,
        rawHeight: Int,
        orientation: Int,
        crop: LocalArtworkCrop?,
    ): LocalArtworkRegion {
        val width = rawWidth.coerceAtLeast(0)
        val height = rawHeight.coerceAtLeast(0)
        if (crop == null) return LocalArtworkRegion(0, 0, width, height)
        crop.validate()
        val swaps = orientation in 5..8
        val upWidth = if (swaps) height else width
        val upHeight = if (swaps) width else height
        val upLeft = floor(crop.left * upWidth).toInt().coerceIn(0, upWidth)
        val upTop = floor(crop.top * upHeight).toInt().coerceIn(0, upHeight)
        val upRight = ceil(crop.right * upWidth).toInt().coerceIn(0, upWidth)
        val upBottom = ceil(crop.bottom * upHeight).toInt().coerceIn(0, upHeight)
        if (min(upRight - upLeft, upBottom - upTop) < MIN_CROP_EDGE) {
            throw LocalAudioFailure(LocalAudioError.INVALID_TARGET)
        }
        // Every EXIF orientation is a symmetry of the rectangle, so two opposite corners are enough.
        val (x0, y0) = rawPoint(upLeft, upTop, width, height, orientation)
        val (x1, y1) = rawPoint(upRight, upBottom, width, height, orientation)
        return LocalArtworkRegion(
            left = min(x0, x1).coerceIn(0, width),
            top = min(y0, y1).coerceIn(0, height),
            right = max(x0, x1).coerceIn(0, width),
            bottom = max(y0, y1).coerceIn(0, height),
        )
    }

    /** Inverse of the EXIF orientation: upright pixel (u, v) to stored pixel (x, y). */
    private fun rawPoint(u: Int, v: Int, width: Int, height: Int, orientation: Int): Pair<Int, Int> =
        when (orientation) {
            2 -> width - u to v
            3 -> width - u to height - v
            4 -> u to height - v
            5 -> v to u
            6 -> v to height - u
            7 -> width - v to height - u
            8 -> width - v to u
            else -> u to v
        }
}
