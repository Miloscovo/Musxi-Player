package tech.musxi.player.ui.player

import kotlin.math.roundToInt

/**
 * Average cover colour used to tint the player, ported from the PC client's `vinylColor`
 * (frontend/src/composables/vinylColor.ts) so both clients pick the same tint for a cover.
 *
 * [argb] holds non-premultiplied ARGB pixels, normally a cover scaled to 32×32. Transparent,
 * near-black (brightest channel < 32) and near-white (darkest channel > 245) pixels are skipped;
 * saturated pixels weigh more. Returns an opaque ARGB colour, or null when nothing was usable.
 */
fun averageCoverColor(argb: IntArray): Int? {
    var red = 0.0
    var green = 0.0
    var blue = 0.0
    var total = 0.0
    for (pixel in argb) {
        val alpha = (pixel ushr 24) / 255.0
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val high = maxOf(r, g, b)
        val low = minOf(r, g, b)
        if (alpha == 0.0 || high < 32 || low > 245) continue
        val weight = alpha * (1 + (high - low) / 32.0)
        red += r * weight
        green += g * weight
        blue += b * weight
        total += weight
    }
    if (total == 0.0) return null
    return (0xFF shl 24) or (channel(red / total) shl 16) or (channel(green / total) shl 8) or channel(blue / total)
}

private fun channel(value: Double): Int = value.roundToInt().coerceIn(0, 255)
