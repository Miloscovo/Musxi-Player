package tech.musxi.player.ui.player

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import tech.musxi.core.wallpaper.WallpaperFrame
import tech.musxi.core.wallpaper.WallpaperPreferences
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The player-page wallpaper as the page draws it: an already-upright picture, where it sits, and
 * how much of it shows. The picture replaces the cover-colour gradient; the cover still owns the
 * accent (the owner chose not to tint from the wallpaper).
 */
@Immutable
data class WallpaperLook(
    val image: ImageBitmap,
    val frame: WallpaperFrame = WallpaperFrame(),
    /** 0..100, picture visibility. Never strong enough to undo the text floor below. */
    val strength: Int = 35,
    /** A small copy of [image] for reading its tones; the full picture would cost a frame to scan. */
    val probe: ImageBitmap = image,
)

/** The picture's darkest and brightest typical values (sRGB luma, 0..1) in the part on screen. */
@Immutable
data class WallpaperTone(val deep: Float, val bright: Float)

internal object WallpaperRules {
    /** How much of the picture strength 100 lets through on the cover page and on the lyrics page. */
    const val COVER_VISIBLE = 0.65f
    const val LYRICS_VISIBLE = 0.45f

    /**
     * Where the backdrop must end up, in sRGB luma, for the theme's text to stay readable over the
     * picture's worst typical pixel. Light: secondary grey text (#5E5F5A) keeps about 3:1 at 0.70.
     * Dark: secondary text (#A6A6A1) keeps about 3:1 at 0.34. Lyrics read for minutes, so they ask
     * for more.
     */
    const val LIGHT_COVER = 0.70f
    const val LIGHT_LYRICS = 0.80f
    const val DARK_COVER = 0.34f
    const val DARK_LYRICS = 0.26f

    /**
     * The 界面壁纸 behind the library, search and 我的: dense lists of small grey text that are
     * read all day, so far less of the picture shows than on the player, and the floor is stricter.
     */
    const val INTERFACE_LIGHT_VISIBLE = 0.30f
    const val INTERFACE_DARK_VISIBLE = 0.40f
    const val LIGHT_INTERFACE = 0.78f
    const val DARK_INTERFACE = 0.28f

    /** Where the slider stops recommending: up to here every step stays readable. */
    const val RECOMMENDED = 100

    /**
     * Strength 150, the end of the "beyond recommended" stretch the owner asked for (11-12): every
     * cap opens a little and every text floor relaxes by about a tenth of luma. Neither goes away,
     * so even 150 keeps a layer of the theme over a picture that would swallow the text.
     */
    const val COVER_VISIBLE_MAX = 0.85f
    const val LYRICS_VISIBLE_MAX = 0.60f
    const val LIGHT_COVER_MAX = 0.60f
    const val LIGHT_LYRICS_MAX = 0.70f
    const val DARK_COVER_MAX = 0.44f
    const val DARK_LYRICS_MAX = 0.34f
    const val INTERFACE_LIGHT_VISIBLE_MAX = 0.45f
    const val INTERFACE_DARK_VISIBLE_MAX = 0.55f
    const val LIGHT_INTERFACE_MAX = 0.70f
    const val DARK_INTERFACE_MAX = 0.36f
}

/**
 * The part of an [imageW]×[imageH] picture that a [windowW]×[windowH] window shows for [frame].
 *
 * zoom 1 is the largest rectangle of the window's shape that fits in the picture; larger zooms
 * shrink it. The centre is kept as close to the frame's as the picture allows, so one saved frame
 * gives a legal result at any window shape (portrait, split screen, landscape).
 */
fun wallpaperSource(imageW: Int, imageH: Int, windowW: Float, windowH: Float, frame: WallpaperFrame): Rect {
    if (imageW <= 0 || imageH <= 0 || windowW <= 0f || windowH <= 0f) return Rect.Zero
    val ratio = windowW / windowH
    val zoom = frame.zoom.takeIf { it.isFinite() }?.coerceIn(1f, 4f) ?: 1f
    val fitW: Float
    val fitH: Float
    if (imageW.toFloat() / imageH > ratio) {
        fitH = imageH.toFloat()
        fitW = fitH * ratio
    } else {
        fitW = imageW.toFloat()
        fitH = fitW / ratio
    }
    val w = fitW / zoom
    val h = fitH / zoom
    val cx = (frame.centerX.takeIf { it.isFinite() } ?: 0.5f) * imageW
    val cy = (frame.centerY.takeIf { it.isFinite() } ?: 0.5f) * imageH
    val left = (cx - w / 2f).coerceIn(0f, imageW - w)
    val top = (cy - h / 2f).coerceIn(0f, imageH - h)
    return Rect(left, top, left + w, top + h)
}

/**
 * How opaque the theme layer over the picture is (0 = only picture, 1 = only theme).
 *
 * First the most this picture may show: the page's cap, lowered further if the picture's worst
 * typical pixel would leave the theme's text unreadable. Strength then walks linearly from nothing
 * (0) to that most (100), so every step of the slider changes the picture and 100 means "as much
 * as stays readable". A bright snowfield in dark mode therefore has a short walk, by design.
 *
 * Past 100 the walk goes on, linearly, to the same measure taken with the looser 150 cap and floor
 * ([WallpaperRules.COVER_VISIBLE_MAX] and friends): more picture, some text less comfortable, never
 * the theme gone.
 */
fun wallpaperScrim(strength: Int, dark: Boolean, lyrics: Boolean, tone: WallpaperTone, baseLuma: Float): Float =
    scrimOf(
        strength,
        wallpaperMost(dark, lyrics, tone, baseLuma),
        wallpaperMost(
            cap = if (lyrics) WallpaperRules.LYRICS_VISIBLE_MAX else WallpaperRules.COVER_VISIBLE_MAX,
            target = when {
                dark && lyrics -> WallpaperRules.DARK_LYRICS_MAX
                dark -> WallpaperRules.DARK_COVER_MAX
                lyrics -> WallpaperRules.LIGHT_LYRICS_MAX
                else -> WallpaperRules.LIGHT_COVER_MAX
            },
            dark = dark, tone = tone, baseLuma = baseLuma,
        ),
    )

/** The most of this picture the page may show (0..1) at strength 100: the cap, or less if the text needs it. */
fun wallpaperMost(dark: Boolean, lyrics: Boolean, tone: WallpaperTone, baseLuma: Float): Float = wallpaperMost(
    cap = if (lyrics) WallpaperRules.LYRICS_VISIBLE else WallpaperRules.COVER_VISIBLE,
    target = when {
        dark && lyrics -> WallpaperRules.DARK_LYRICS
        dark -> WallpaperRules.DARK_COVER
        lyrics -> WallpaperRules.LIGHT_LYRICS
        else -> WallpaperRules.LIGHT_COVER
    },
    dark = dark, tone = tone, baseLuma = baseLuma,
)

/** The 界面壁纸's scrim: the same walk as the player's, with the interface's caps and floors. */
fun interfaceWallpaperScrim(strength: Int, dark: Boolean, tone: WallpaperTone, baseLuma: Float): Float = scrimOf(
    strength,
    wallpaperMost(
        cap = if (dark) WallpaperRules.INTERFACE_DARK_VISIBLE else WallpaperRules.INTERFACE_LIGHT_VISIBLE,
        target = if (dark) WallpaperRules.DARK_INTERFACE else WallpaperRules.LIGHT_INTERFACE,
        dark = dark, tone = tone, baseLuma = baseLuma,
    ),
    wallpaperMost(
        cap = if (dark) WallpaperRules.INTERFACE_DARK_VISIBLE_MAX else WallpaperRules.INTERFACE_LIGHT_VISIBLE_MAX,
        target = if (dark) WallpaperRules.DARK_INTERFACE_MAX else WallpaperRules.LIGHT_INTERFACE_MAX,
        dark = dark, tone = tone, baseLuma = baseLuma,
    ),
)

/**
 * 0..100 walks from nothing to [most]; 100..[WallpaperPreferences.MAX_STRENGTH] walks on from [most]
 * to [mostBeyond]. The looser measure can only allow more, but it is kept at least [most] anyway so
 * the slider never turns back.
 */
private fun scrimOf(strength: Int, most: Float, mostBeyond: Float): Float {
    val top = WallpaperPreferences.MAX_STRENGTH
    val s = strength.coerceIn(0, top)
    val recommended = WallpaperRules.RECOMMENDED
    val visible = if (s <= recommended) {
        s / recommended.toFloat() * most
    } else {
        most + (s - recommended) / (top - recommended).toFloat() * (max(mostBeyond, most) - most)
    }
    return (1f - visible).coerceIn(0f, 1f)
}

/** The most a page with [cap] may show, lowered until its backdrop's worst pixel reaches [target]. */
private fun wallpaperMost(cap: Float, target: Float, dark: Boolean, tone: WallpaperTone, baseLuma: Float): Float {
    val floor = if (dark) {
        val p = tone.bright
        if (p <= target || p <= baseLuma) 0f else (p - target) / (p - baseLuma)
    } else {
        val p = tone.deep
        if (p >= target || p >= baseLuma) 0f else (target - p) / (baseLuma - p)
    }
    return (1f - max(floor, 1f - cap)).coerceIn(0f, 1f)
}

/**
 * True when light mode has to hide most of this picture to keep the text readable (a dark photo):
 * the preview then says it looks better in dark mode, which the owner chose over switching themes.
 */
fun wallpaperPrefersDark(tone: WallpaperTone, lightBaseLuma: Float): Boolean =
    wallpaperMost(dark = false, lyrics = false, tone = tone, baseLuma = lightBaseLuma) < WallpaperRules.COVER_VISIBLE * 0.5f

/** The same hint for the 界面壁纸, measured against the interface's own cap and floor. */
fun interfaceWallpaperPrefersDark(tone: WallpaperTone, lightBaseLuma: Float): Boolean =
    wallpaperMost(
        cap = WallpaperRules.INTERFACE_LIGHT_VISIBLE, target = WallpaperRules.LIGHT_INTERFACE,
        dark = false, tone = tone, baseLuma = lightBaseLuma,
    ) < WallpaperRules.INTERFACE_LIGHT_VISIBLE * 0.5f

/** sRGB luma of a colour, the same measure [WallpaperTone] uses. */
internal fun lumaOf(color: Color): Float = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue

/**
 * The 10th and 90th percentile luma of the part [source] (in [image] pixels) shows, read from the
 * small [probe] copy on a coarse grid: enough for a floor, cheap enough to run on a frame change.
 */
fun wallpaperTone(image: ImageBitmap, probe: ImageBitmap, source: Rect): WallpaperTone {
    val sx = probe.width.toFloat() / image.width
    val sy = probe.height.toFloat() / image.height
    val left = (source.left * sx).roundToInt().coerceIn(0, probe.width - 1)
    val top = (source.top * sy).roundToInt().coerceIn(0, probe.height - 1)
    val width = (source.width * sx).roundToInt().coerceIn(1, probe.width - left)
    val height = (source.height * sy).roundToInt().coerceIn(1, probe.height - top)
    val pixels = probe.toPixelMap(left, top, width, height)
    val columns = min(40, width)
    val rows = min(72, height)
    val samples = FloatArray(columns * rows)
    for (r in 0 until rows) for (c in 0 until columns) {
        val x = ((c + 0.5f) * width / columns).toInt().coerceIn(0, width - 1)
        val y = ((r + 0.5f) * height / rows).toInt().coerceIn(0, height - 1)
        samples[r * columns + c] = lumaOf(pixels[x, y])
    }
    samples.sort()
    return WallpaperTone(deep = samples[samples.size / 10], bright = samples[samples.size * 9 / 10])
}

/** Draws the framed picture across the whole draw area with the theme layer [scrim] over it. */
internal fun DrawScope.drawWallpaper(
    look: WallpaperLook,
    source: Rect,
    base: Color,
    scrim: Float,
    alpha: Float,
    /** Where the picture's area starts and how big it is; the whole draw area by default. */
    topLeft: Offset = Offset.Zero,
    area: Size = size,
) {
    if (source.isEmpty) return
    // A transparent PNG shows the theme's own colour through, not the dock under the surface.
    drawRect(base, topLeft, area, alpha = alpha)
    drawImage(
        image = look.image,
        srcOffset = IntOffset(source.left.roundToInt(), source.top.roundToInt()),
        srcSize = IntSize(source.width.roundToInt().coerceAtLeast(1), source.height.roundToInt().coerceAtLeast(1)),
        dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
        dstSize = IntSize(area.width.roundToInt(), area.height.roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Medium,
    )
    drawRect(base, topLeft, area, alpha = scrim * alpha)
}

/**
 * Framing gestures in the window the picture is shown in, whose shape is [ratio] (width / height).
 * Every result is resolved through [wallpaperSource], so the stored centre is the one actually
 * shown: a drag past the edge does not bank an invisible offset that the next drag must undo.
 */
object WallpaperFraming {
    const val MAX_ZOOM = 4f

    fun resolved(w: Int, h: Int, ratio: Float, frame: WallpaperFrame): WallpaperFrame {
        val r = wallpaperSource(w, h, ratio, 1f, frame)
        if (r.isEmpty) return WallpaperFrame()
        return WallpaperFrame(
            (r.center.x / w).coerceIn(0f, 1f),
            (r.center.y / h).coerceIn(0f, 1f),
            frame.zoom.coerceIn(1f, MAX_ZOOM),
        )
    }

    /** One finger moved the picture by ([dx], [dy]) window widths / heights; the picture follows it. */
    fun dragged(w: Int, h: Int, ratio: Float, frame: WallpaperFrame, dx: Float, dy: Float): WallpaperFrame {
        val r = wallpaperSource(w, h, ratio, 1f, frame)
        return resolved(w, h, ratio, frame.copy(centerX = (r.center.x - dx * r.width) / w, centerY = (r.center.y - dy * r.height) / h))
    }

    /** Pinched by [factor] around a window point ([wx], [wy], 0..1); that point stays under the fingers. */
    fun zoomed(w: Int, h: Int, ratio: Float, frame: WallpaperFrame, factor: Float, wx: Float = 0.5f, wy: Float = 0.5f): WallpaperFrame {
        val r = wallpaperSource(w, h, ratio, 1f, frame)
        val zoom = frame.zoom.coerceIn(1f, MAX_ZOOM)
        val next = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        val px = r.left + wx * r.width
        val py = r.top + wy * r.height
        val nw = r.width * zoom / next
        val nh = r.height * zoom / next
        return resolved(w, h, ratio, WallpaperFrame((px - wx * nw + nw / 2f) / w, (py - wy * nh + nh / 2f) / h, next))
    }

    fun between(from: WallpaperFrame, to: WallpaperFrame, t: Float) = WallpaperFrame(
        from.centerX + (to.centerX - from.centerX) * t,
        from.centerY + (to.centerY - from.centerY) * t,
        from.zoom + (to.zoom - from.zoom) * t,
    )

    fun atStart(frame: WallpaperFrame): Boolean =
        frame.zoom <= 1.001f && kotlin.math.abs(frame.centerX - 0.5f) < 0.001f && kotlin.math.abs(frame.centerY - 0.5f) < 0.001f
}
