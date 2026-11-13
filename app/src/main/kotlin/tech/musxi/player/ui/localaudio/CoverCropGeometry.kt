package tech.musxi.player.ui.localaudio

import tech.musxi.player.localaudio.LocalArtworkCrop
import tech.musxi.player.localaudio.LocalArtworkCropGeometry
import kotlin.math.max
import kotlin.math.min

/**
 * The square window a user moves over a picture, and the crop it stands for.
 *
 * Pure geometry in the normalized coordinates of the EXIF-upright picture — the ones
 * `LocalArtworkCrop` takes — so the rules are testable without Android graphics. The screen hands
 * in touches as fractions of the window's side ("window fractions": 0..1 across the window).
 *
 * [zoom] is how many times closer than the start the user is: 1 is the start, where the window
 * spans the picture's shorter side; [maxZoom] is the closest. ([centerX], [centerY]) is the picture
 * point in the middle of the window.
 */
internal object CoverCropGeometry {
    /** How much closer than the start the user may go on a large picture. */
    const val MAX_ZOOM = 4f

    data class CoverCropFit(
        val width: Int,
        val height: Int,
        val zoom: Float,
        val centerX: Float,
        val centerY: Float,
    ) {
        /** The picture's shorter side, in pixels: the window spans this much at zoom 1. */
        val shortSide: Float get() = min(width, height).coerceAtLeast(1).toFloat()

        /**
         * The closest zoom: [MAX_ZOOM], or less on a small picture, so the window never covers
         * fewer pixels than the backend keeps (64 px a side).
         */
        val maxZoom: Float get() = (shortSide / LocalArtworkCropGeometry.MIN_CROP_EDGE).coerceIn(1f, MAX_ZOOM)

        private fun halfX(z: Float) = min(0.5f, shortSide / (2f * z) / width)
        private fun halfY(z: Float) = min(0.5f, shortSide / (2f * z) / height)

        fun crop(): LocalArtworkCrop = LocalArtworkCrop(
            left = (centerX - halfX(zoom)).coerceIn(0f, 1f),
            top = (centerY - halfY(zoom)).coerceIn(0f, 1f),
            right = (centerX + halfX(zoom)).coerceIn(0f, 1f),
            bottom = (centerY + halfY(zoom)).coerceIn(0f, 1f),
        )

        /** Holds the zoom in range and the window inside the picture. */
        fun clamped(): CoverCropFit {
            val z = zoom.coerceIn(1f, maxZoom)
            val hx = halfX(z)
            val hy = halfY(z)
            return copy(zoom = z, centerX = centerX.coerceIn(hx, 1f - hx), centerY = centerY.coerceIn(hy, 1f - hy))
        }

        /**
         * Pinch by [factor] about a point of the window ([windowX], [windowY] as window fractions):
         * the picture under that point stays under it, unless the window would leave the picture.
         */
        fun zoomed(factor: Float, windowX: Float = 0.5f, windowY: Float = 0.5f): CoverCropFit {
            val next = (zoom * factor).coerceIn(1f, maxZoom)
            if (next == zoom) return this
            val px = centerX + (windowX - 0.5f) / drawnWidth
            val py = centerY + (windowY - 0.5f) / drawnHeight
            val ratio = zoom / next
            return copy(zoom = next, centerX = px - (px - centerX) * ratio, centerY = py - (py - centerY) * ratio).clamped()
        }

        /** The picture follows a finger that moved [dx], [dy] window fractions. */
        fun dragged(dx: Float, dy: Float): CoverCropFit =
            copy(centerX = centerX - dx / drawnWidth, centerY = centerY - dy / drawnHeight).clamped()

        val atMaxZoom: Boolean get() = zoom >= maxZoom - 1e-3f

        /** The picture's drawn size, in window sides. */
        val drawnWidth: Float get() = width * zoom / shortSide
        val drawnHeight: Float get() = height * zoom / shortSide

        /** Where the picture's top-left corner sits, in window sides from the window's own corner. */
        val drawnLeft: Float get() = 0.5f - centerX * drawnWidth
        val drawnTop: Float get() = 0.5f - centerY * drawnHeight

        /** Whether this is still the start: nothing to put back. */
        val atStart: Boolean get() = this == initial(width, height)
    }

    /** The start: centred, the picture's shorter side exactly spanning the window. */
    fun initial(width: Int, height: Int): CoverCropFit =
        CoverCropFit(max(1, width), max(1, height), 1f, 0.5f, 0.5f)

    /** A step of an animation from [from] to [to]; [t] runs 0..1. */
    fun between(from: CoverCropFit, to: CoverCropFit, t: Float): CoverCropFit = from.copy(
        zoom = from.zoom + (to.zoom - from.zoom) * t,
        centerX = from.centerX + (to.centerX - from.centerX) * t,
        centerY = from.centerY + (to.centerY - from.centerY) * t,
    ).clamped()
}
