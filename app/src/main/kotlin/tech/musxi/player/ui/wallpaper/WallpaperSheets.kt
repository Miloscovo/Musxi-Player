package tech.musxi.player.ui.wallpaper

import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import tech.musxi.core.wallpaper.WallpaperPreferences
import tech.musxi.player.ui.player.WallpaperRules
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tech.musxi.core.wallpaper.WallpaperFrame
import tech.musxi.player.ui.components.GlassStyle
import tech.musxi.player.ui.components.LocalGlass
import tech.musxi.player.ui.components.MusxiSheet
import tech.musxi.player.ui.components.SheetDetent
import tech.musxi.player.ui.components.glass
import tech.musxi.player.ui.player.WallpaperFraming
import tech.musxi.player.ui.player.wallpaperSource
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.rememberHaptics
import tech.musxi.player.ui.theme.rememberReducedMotion
import tech.musxi.player.ui.theme.tabular
import kotlin.math.roundToInt

internal const val WallpaperFrameWindowTag = "wallpaper-frame-window"
internal const val WallpaperFrameConfirmTag = "wallpaper-frame-confirm"
internal const val WallpaperBarTag = "wallpaper-preview-bar"

/** A double tap from the start goes this much closer. */
private const val DOUBLE_TAP_ZOOM = 2f

/** The window's shape, width over height; the frame and the player page both use it. */
@Composable
internal fun windowRatio(): Float {
    val size = LocalWindowInfo.current.containerSize
    return if (size.width > 0 && size.height > 0) size.width.toFloat() / size.height else 9f / 20f
}

/** The frame for [host]'s request; call it from the shell's overlay, next to the other sheets. */
@Composable
internal fun WallpaperFrameOverlay(host: WallpaperHost) {
    val request = host.framing
    // Kept so the sheet slides out showing what it showed.
    val last = remember { arrayOfNulls<WallpaperFramingRequest>(1) }
    if (request != null) last[0] = request
    val shown = request ?: last[0]
    MusxiSheet(
        visible = request != null,
        onDismiss = host::dismissFrame,
        initialDetent = SheetDetent.FULL,
    ) {
        if (shown != null) {
            val image = rememberWallpaperImage(host, shown.id)
            WallpaperFrameContent(
                image = image,
                start = shown.start,
                ratio = windowRatio(),
                fromBar = shown.fromBar,
                working = host.working,
                onDismiss = host::dismissFrame,
                onRepick = host::repick,
                onConfirm = host::confirmFrame,
                hint = host.kind.frameHint,
            )
        }
    }
}

/**
 * Where the wallpaper sits, framed in a window shaped like the phone's screen: one finger moves
 * the picture, two pinch it, a double tap goes 2× closer or back. The rest of the picture stays in
 * view under a veil, as in the cover frame, so the two read as one tool.
 */
@Composable
internal fun ColumnScope.WallpaperFrameContent(
    image: ImageBitmap?,
    start: WallpaperFrame,
    ratio: Float,
    fromBar: Boolean,
    working: Boolean,
    onDismiss: () -> Unit,
    onRepick: () -> Unit,
    onConfirm: (WallpaperFrame) -> Unit,
    hint: String = WallpaperKind.PLAYER.frameHint,
) {
    val cs = MaterialTheme.colorScheme
    var frame by remember(image, start) {
        mutableStateOf(image?.let { WallpaperFraming.resolved(it.width, it.height, ratio, start) } ?: start)
    }
    val scope = rememberCoroutineScope()
    var settle by remember { mutableStateOf<Job?>(null) }
    val haptic = rememberHaptics()
    val reducedMotion = rememberReducedMotion()

    Column(Modifier.fillMaxWidth().padding(horizontal = Space.page).padding(top = 4.dp)) {
        Text("选取壁纸区域", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(Space.sibling))
    BoxWithConstraints(
        Modifier.fillMaxWidth().weight(1f).clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val pagePx = with(density) { Space.page.toPx() }
        var winH = constraints.maxHeight.toFloat()
        var winW = winH * ratio
        if (winW > constraints.maxWidth - 2 * pagePx) {
            winW = (constraints.maxWidth - 2 * pagePx).coerceAtLeast(1f)
            winH = winW / ratio
        }
        val left = (constraints.maxWidth - winW) / 2f
        val top = (constraints.maxHeight - winH) / 2f
        val shape = MaterialTheme.shapes.large
        val veil = cs.surface.copy(alpha = 0.78f)
        val empty = cs.surfaceContainerHighest
        val edge = cs.onSurface.copy(alpha = 0.22f)

        Canvas(
            Modifier.fillMaxSize()
                .semantics { contentDescription = "壁纸取景框" }
                .testTag(WallpaperFrameWindowTag)
                .pointerInput(image, winW, winH) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val img = image ?: return@detectTransformGestures
                        settle?.cancel()
                        var next = frame
                        if (zoom != 1f) {
                            next = WallpaperFraming.zoomed(
                                img.width, img.height, ratio, next, zoom,
                                ((centroid.x - left) / winW).coerceIn(0f, 1f),
                                ((centroid.y - top) / winH).coerceIn(0f, 1f),
                            )
                        }
                        if (pan != Offset.Zero) {
                            next = WallpaperFraming.dragged(img.width, img.height, ratio, next, pan.x / winW, pan.y / winH)
                        }
                        frame = next
                    }
                }
                .pointerInput(image, winW, winH) {
                    detectTapGestures(onDoubleTap = { tap ->
                        val img = image ?: return@detectTapGestures
                        val from = frame
                        val to = if (from.zoom <= 1.001f) {
                            WallpaperFraming.zoomed(
                                img.width, img.height, ratio, from, DOUBLE_TAP_ZOOM,
                                ((tap.x - left) / winW).coerceIn(0f, 1f),
                                ((tap.y - top) / winH).coerceIn(0f, 1f),
                            )
                        } else {
                            WallpaperFraming.resolved(img.width, img.height, ratio, WallpaperFrame())
                        }
                        if (to == from) return@detectTapGestures
                        haptic(Haptic.TICK)
                        settle?.cancel()
                        if (reducedMotion) frame = to
                        else settle = scope.launch {
                            animate(0f, 1f, animationSpec = Motion.standard()) { t, _ ->
                                frame = WallpaperFraming.between(from, to, t)
                            }
                        }
                    })
                },
        ) {
            val window = Path().apply {
                addOutline(shape.createOutline(Size(winW, winH), layoutDirection, this@Canvas))
                translate(Offset(left, top))
            }
            if (image == null) {
                drawPath(window, empty)
            } else {
                val src = wallpaperSource(image.width, image.height, winW, winH, frame)
                val scale = winW / src.width
                drawImage(
                    image,
                    dstOffset = IntOffset((left - src.left * scale).roundToInt(), (top - src.top * scale).roundToInt()),
                    dstSize = IntSize(
                        (image.width * scale).roundToInt().coerceAtLeast(1),
                        (image.height * scale).roundToInt().coerceAtLeast(1),
                    ),
                    filterQuality = FilterQuality.Medium,
                )
            }
            clipPath(window, ClipOp.Difference) { drawRect(veil) }
            drawPath(window, edge, style = Stroke(width = 1.dp.toPx()))
        }
        if (image == null) {
            Text(
                "正在读取图片…", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
    Spacer(Modifier.height(Space.sibling))
    val atStart = WallpaperFraming.atStart(frame)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.page - 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onDismiss, enabled = !working, modifier = Modifier.heightIn(min = 44.dp)) {
            Text("取消", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = onRepick, enabled = !working, modifier = Modifier.heightIn(min = 44.dp)) {
            Text("换一张", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        }
        TextButton(
            onClick = {
                val img = image ?: return@TextButton
                settle?.cancel()
                frame = WallpaperFraming.resolved(img.width, img.height, ratio, WallpaperFrame())
            },
            enabled = !atStart && !working,
            modifier = Modifier.heightIn(min = 44.dp),
        ) {
            Text(
                "还原", style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant.copy(alpha = if (!atStart && !working) 1f else 0.38f),
            )
        }
        Spacer(Modifier.weight(1f))
        InkButton(
            label = if (fromBar) "完成" else "预览",
            enabled = image != null && !working,
            onClick = { onConfirm(frame) },
            modifier = Modifier.padding(end = 12.dp).testTag(WallpaperFrameConfirmTag),
        )
    }
    Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(8.dp))
}

/**
 * The preview bar over the real player: strength, "调整位置", and cancel / apply. Glass, so the page
 * it previews shows through it. [preferDark] adds the owner's chosen hint for a dark photo in light
 * mode instead of switching themes behind the user's back.
 */
@Composable
internal fun WallpaperPreviewBar(
    strength: Int,
    preferDark: Boolean,
    working: Boolean,
    onStrength: (Int) -> Unit,
    onStrengthDone: () -> Unit,
    onAdjust: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.extraLarge
    Column(
        modifier
            .padding(horizontal = Space.page)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .glass(LocalGlass.current, shape, GlassStyle.REGULAR)
            .padding(horizontal = Space.inner, vertical = 12.dp)
            .testTag(WallpaperBarTag),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("强度", style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
            Spacer(Modifier.width(Space.sibling))
            val top = WallpaperPreferences.MAX_STRENGTH
            val beyond = strength > WallpaperRules.RECOMMENDED
            val recommendedAt = WallpaperRules.RECOMMENDED / top.toFloat()
            val surface = cs.surface
            val onSurface = cs.onSurface
            Slider(
                value = strength.toFloat(),
                onValueChange = { onStrength(it.roundToInt()) },
                onValueChangeFinished = onStrengthDone,
                valueRange = 0f..top.toFloat(),
                enabled = !working,
                colors = SliderDefaults.colors(
                    thumbColor = cs.onSurface,
                    activeTrackColor = cs.onSurface,
                    inactiveTrackColor = cs.onSurface.copy(alpha = 0.16f),
                ),
                modifier = Modifier
                    .weight(1f)
                    // A dot on the track at 100, where "as much as stays readable" ends. The track
                    // runs between the thumb's half-widths (2dp each side for the 4dp handle).
                    .drawWithContent {
                        drawContent()
                        val inset = 2.dp.toPx()
                        val x = inset + recommendedAt * (size.width - 2 * inset)
                        val onActive = strength >= WallpaperRules.RECOMMENDED
                        drawCircle(
                            color = if (onActive) surface.copy(alpha = 0.8f) else onSurface.copy(alpha = 0.55f),
                            radius = 2.dp.toPx(),
                            center = Offset(x, size.height / 2f),
                        )
                    }
                    .semantics {
                        stateDescription = if (beyond) "强度 $strength，超出推荐" else "强度 $strength"
                    },
            )
            Spacer(Modifier.width(Space.sibling))
            Text(
                "$strength", style = MaterialTheme.typography.labelLarge.tabular,
                color = if (beyond) cs.primary else cs.onSurfaceVariant,
                textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.widthIn(min = 28.dp),
            )
        }
        if (strength > WallpaperRules.RECOMMENDED) {
            Text(
                "超过 100 后，部分文字可能不够清楚",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
        }
        if (preferDark) {
            Text(
                "这张图偏暗，在深色模式下更好看",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onCancel, enabled = !working, modifier = Modifier.heightIn(min = 44.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) { Text("取消", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge) }
            TextButton(
                onClick = onAdjust, enabled = !working, modifier = Modifier.heightIn(min = 44.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) { Text("调整位置", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge) }
            Spacer(Modifier.weight(1f))
            InkButton("应用", enabled = !working, onClick = onApply)
        }
    }
}

/** The sheet's one heavy action, in ink, as in the cover frame. */
@Composable
private fun InkButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = cs.onSurface, contentColor = cs.surface),
        contentPadding = PaddingValues(horizontal = Space.page),
        modifier = modifier.heightIn(min = 44.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
