package tech.musxi.player.ui.localaudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.musxi.player.localaudio.LocalArtworkCrop
import tech.musxi.player.localaudio.LocalArtworkCropGeometry
import tech.musxi.player.localaudio.LocalArtworkImages
import tech.musxi.player.ui.components.MusxiSheet
import tech.musxi.player.ui.components.SheetDetent
import tech.musxi.player.ui.theme.Haptic
import tech.musxi.player.ui.theme.Motion
import tech.musxi.player.ui.theme.Space
import tech.musxi.player.ui.theme.rememberHaptics
import tech.musxi.player.ui.theme.rememberReducedMotion
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt

/** The area a drag or pinch frames the picture in, so a test can find it. */
internal const val CoverCropWindowTag = "cover-crop-window"

/** The confirm button, so a test knows the frame is ready to hand back a crop. */
internal const val CoverCropConfirmTag = "cover-crop-confirm"

/** Longest edge decoded for the frame: sharp at 4× on a phone screen, at most 16 MB of pixels. */
private const val PREVIEW_EDGE = 2048

/** A double tap from the start goes this much closer. */
private const val DOUBLE_TAP_ZOOM = 2f

/** A picked picture waiting to be framed: the recording it is for, the picture, the grant's flags. */
internal class CoverCropRequest(val audioId: String, val uri: Uri, val flags: Int)

/**
 * The one way to change a recording's cover, from any place that shows it (a row's menu, a long
 * press on the mini or the player's cover): [pick] opens the system picker, the picture opens in
 * the frame, "换一张" picks again for the same recording, and [restore] drops a picked cover.
 *
 * The frame is a [MusxiSheet], and a sheet must be drawn in the shell's overlay, never in the page
 * content the glass records: a sheet inside the recording samples itself, and the render thread
 * recurses until it overflows its stack (the 11-12 crash). So callers only talk to this host and
 * the shell draws [CoverCropOverlay].
 *
 * The request holds the picture's URI in memory only: an activity re-creation drops it.
 */
@Stable
internal class CoverCropHost {
    var request by mutableStateOf<CoverCropRequest?>(null)
        private set
    var saving by mutableStateOf(false)
        private set

    // Wired by [rememberCoverCropHost].
    internal var launchPicker: (String) -> Unit = {}
    internal var save: (CoverCropRequest, LocalArtworkCrop?, () -> Unit) -> Unit = { _, _, done -> done() }
    internal var clear: (String) -> Unit = {}

    fun pick(audioId: String) { if (!saving) launchPicker(audioId) }
    /** Back to the picker for the recording being framed; a cancelled picker keeps the picture. */
    fun repick() { val current = request ?: return; if (!saving) launchPicker(current.audioId) }
    fun restore(audioId: String) = clear(audioId)

    internal fun picked(audioId: String, uri: Uri, flags: Int) {
        if (!saving) request = CoverCropRequest(audioId, uri, flags)
    }

    fun close() { if (!saving) request = null }

    fun confirm(crop: LocalArtworkCrop?) {
        val current = request ?: return
        if (saving) return
        saving = true
        save(current, crop) {
            saving = false
            if (request === current) request = null
        }
    }
}

/** The shell's [CoverCropHost]; null where no shell provides one (tests, previews). */
internal val LocalCoverCropHost = staticCompositionLocalOf<CoverCropHost?> { null }

/**
 * The shell's host: its own picker launcher (the recording id survives an activity re-creation,
 * the picture does not) and the save and restore commands with their notices.
 */
@Composable
internal fun rememberCoverCropHost(controller: LocalAudioController, onNotice: (String) -> Unit): CoverCropHost {
    val host = remember { CoverCropHost() }
    var pending by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val notice by androidx.compose.runtime.rememberUpdatedState(onNotice)
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val audioId = pending
        pending = null
        val data = result.data
        val uri = data?.data
        // A cancelled picker changes nothing, also when it was opened from the frame.
        if (audioId == null || result.resultCode != android.app.Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        host.picked(audioId, uri, data.flags)
    }
    host.launchPicker = { audioId ->
        pending = audioId
        try {
            launcher.launch(tech.musxi.player.localaudio.LocalAudioPickers.image())
        } catch (_: android.content.ActivityNotFoundException) {
            pending = null
            notice("这台手机上没有可用的图片选择器")
        }
    }
    host.save = { request, crop, done ->
        controller.setCustomArtwork(request.audioId, request.uri, request.flags, crop) { result ->
            done()
            coverSaveNotice(result)?.let(notice)
        }
    }
    host.clear = { audioId ->
        controller.clearCustomArtwork(audioId) { r ->
            r.onSuccess { notice("已恢复默认封面") }.onFailure { notice(localErrorText(r.localError())) }
        }
    }
    return host
}

/** What to tell the user after a cover was saved or refused; null when a newer action reports instead. */
internal fun coverSaveNotice(result: Result<Unit>): String? = when {
    result.isSuccess -> "已更换封面"
    else -> when (result.localError()) {
        tech.musxi.core.localaudio.LocalAudioError.PERMISSION_REQUIRED -> "没有读取这张图片的权限，请重新选择"
        tech.musxi.core.localaudio.LocalAudioError.READ_FAILED -> "这张图片读不出来或太大，请换一张"
        tech.musxi.core.localaudio.LocalAudioError.INVALID_TARGET -> "取景范围太小，请缩小一些再试"
        // A newer choice or a restore took over; it reports for itself.
        tech.musxi.core.localaudio.LocalAudioError.STALE_STATE -> null
        else -> localErrorText(result.localError())
    }
}

/** The frame for [host]'s request; call it from the shell's overlay, next to the other sheets. */
@Composable
internal fun CoverCropOverlay(host: CoverCropHost) {
    val request = host.request
    // Kept so the sheet slides out showing the picture it showed.
    val last = remember { arrayOfNulls<CoverCropRequest>(1) }
    if (request != null) last[0] = request
    CoverCropSheet(
        visible = request != null,
        uri = (request ?: last[0])?.uri,
        saving = host.saving,
        onDismiss = host::close,
        onConfirm = host::confirm,
        onRepick = host::repick,
    )
}

/**
 * The frame a user puts over a picture before it becomes a recording's cover.
 *
 * The picture starts centred with its shorter side filling the square window; one finger moves
 * it, two pinch it, a double tap goes 2× closer or back to the start. The part outside the
 * window stays visible under a veil, so the user sees what they are leaving out. "设为封面"
 * reports the window as a crop of the EXIF-upright picture, which is what the backend takes.
 */
@Composable
internal fun CoverCropSheet(
    visible: Boolean,
    uri: Uri?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (LocalArtworkCrop?) -> Unit,
    onRepick: (() -> Unit)? = null,
) {
    MusxiSheet(
        visible = visible && uri != null,
        onDismiss = { if (!saving) onDismiss() },
        initialDetent = SheetDetent.FULL,
    ) {
        // Inside the sheet's content, so the decoded picture is released once the sheet is gone.
        if (uri != null) {
            val context = LocalContext.current
            var preview by remember(uri) { mutableStateOf<CropPreview>(CropPreview.Loading) }
            LaunchedEffect(uri) { preview = withContext(Dispatchers.IO) { loadCropPreview(context, uri) } }
            CoverCropContent(preview, saving, onDismiss, onConfirm, onRepick)
        }
    }
}

/** The picture as the frame shows it: decoded small and turned upright, or why it cannot be. */
internal sealed interface CropPreview {
    data object Loading : CropPreview
    data class Failed(val text: String) : CropPreview
    /** [width] × [height] are the upright source's pixels; [image] has the same proportions. */
    class Ready(val image: ImageBitmap, val width: Int, val height: Int) : CropPreview
}

@Composable
internal fun ColumnScope.CoverCropContent(
    preview: CropPreview,
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (LocalArtworkCrop?) -> Unit,
    onRepick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val ready = preview as? CropPreview.Ready
    var fit by remember(ready) { mutableStateOf(ready?.let { CoverCropGeometry.initial(it.width, it.height) }) }
    val scope = rememberCoroutineScope()
    var settle by remember { mutableStateOf<Job?>(null) }
    val haptic = rememberHaptics()
    val reducedMotion = rememberReducedMotion()

    Header(saving)
    Spacer(Modifier.height(Space.sibling))
    BoxWithConstraints(
        Modifier.fillMaxWidth().weight(1f).clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val pagePx = with(density) { Space.page.toPx() }
        val side = min(constraints.maxWidth - 2 * pagePx, constraints.maxHeight.toFloat()).coerceAtLeast(1f)
        val originX = (constraints.maxWidth - side) / 2f
        val originY = (constraints.maxHeight - side) / 2f
        val shape = MaterialTheme.shapes.large
        val veil = cs.surface.copy(alpha = 0.78f)
        val empty = cs.surfaceContainerHighest
        val edge = cs.onSurface.copy(alpha = 0.22f)

        Canvas(
            Modifier.fillMaxSize()
                .semantics { contentDescription = "封面取景框" }
                .testTag(CoverCropWindowTag)
                .pointerInput(ready, side) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val at = fit ?: return@detectTransformGestures
                        settle?.cancel()
                        var next = at
                        if (zoom != 1f) next = next.zoomed(zoom, (centroid.x - originX) / side, (centroid.y - originY) / side)
                        if (pan != Offset.Zero) next = next.dragged(pan.x / side, pan.y / side)
                        fit = next
                    }
                }
                .pointerInput(ready, side) {
                    detectTapGestures(onDoubleTap = { tap ->
                        val from = fit ?: return@detectTapGestures
                        val to = if (from.atStart) {
                            from.zoomed(DOUBLE_TAP_ZOOM, (tap.x - originX) / side, (tap.y - originY) / side)
                        } else {
                            CoverCropGeometry.initial(from.width, from.height)
                        }
                        if (to == from) return@detectTapGestures
                        haptic(Haptic.TICK)
                        settle?.cancel()
                        if (reducedMotion) fit = to
                        else settle = scope.launch {
                            animate(0f, 1f, animationSpec = Motion.standard()) { t, _ ->
                                fit = CoverCropGeometry.between(from, to, t)
                            }
                        }
                    })
                },
        ) {
            val window = Path().apply {
                addOutline(shape.createOutline(Size(side, side), layoutDirection, this@Canvas))
                translate(Offset(originX, originY))
            }
            val shown = fit
            if (ready == null || shown == null) {
                drawPath(window, empty)
            } else {
                drawImage(
                    ready.image,
                    dstOffset = IntOffset(
                        (originX + shown.drawnLeft * side).roundToInt(),
                        (originY + shown.drawnTop * side).roundToInt(),
                    ),
                    dstSize = IntSize(
                        (shown.drawnWidth * side).roundToInt().coerceAtLeast(1),
                        (shown.drawnHeight * side).roundToInt().coerceAtLeast(1),
                    ),
                    filterQuality = FilterQuality.Medium,
                )
            }
            // What falls outside the window stays in view, faded: it is what the cover leaves out.
            clipPath(window, ClipOp.Difference) { drawRect(veil) }
            drawPath(window, edge, style = Stroke(width = 1.dp.toPx()))
        }
        val message = when (preview) {
            CropPreview.Loading -> "正在读取图片…"
            is CropPreview.Failed -> preview.text
            is CropPreview.Ready -> null
        }
        if (message != null) {
            val sideDp = with(density) { side.toDp() }
            Box(Modifier.size(sideDp).padding(Space.page), contentAlignment = Alignment.Center) {
                Text(
                    message, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    Spacer(Modifier.height(Space.sibling))
    Actions(
        saving = saving,
        ready = fit != null,
        canReset = fit?.atStart == false,
        onDismiss = onDismiss,
        onRepick = onRepick,
        onReset = {
            val from = fit ?: return@Actions
            settle?.cancel()
            fit = CoverCropGeometry.initial(from.width, from.height)
        },
        onConfirm = {
            val shown = fit ?: return@Actions
            // A picture under 64 px a side cannot be cut; it goes in whole, as before the frame.
            onConfirm(if (shown.shortSide >= LocalArtworkCropGeometry.MIN_CROP_EDGE) shown.crop() else null)
        },
    )
    Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(8.dp))
}

@Composable
private fun Header(saving: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.page).padding(top = 4.dp)) {
        Text("选取封面区域", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
        Text(
            if (saving) "正在保存封面…" else "拖动、双指缩放来取景，双击放大或还原",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun Actions(
    saving: Boolean,
    ready: Boolean,
    canReset: Boolean,
    onDismiss: () -> Unit,
    onRepick: (() -> Unit)?,
    onReset: () -> Unit,
    onConfirm: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.page - 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.heightIn(min = 44.dp)) {
            Text("取消", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        }
        // Back to the picker for another picture; a cancelled picker leaves this one in the frame.
        if (onRepick != null) {
            TextButton(onClick = onRepick, enabled = !saving, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("换一张", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            }
        }
        TextButton(onClick = onReset, enabled = canReset && !saving, modifier = Modifier.heightIn(min = 44.dp)) {
            Text(
                "还原", style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant.copy(alpha = if (canReset && !saving) 1f else 0.38f),
            )
        }
        Spacer(Modifier.weight(1f))
        // The one heavy action of the sheet, in ink: it commits the picture.
        Button(
            onClick = onConfirm,
            enabled = ready && !saving,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = cs.onSurface, contentColor = cs.surface),
            contentPadding = PaddingValues(horizontal = Space.page),
            // The row is inset 12dp less so the text buttons' own padding lines their words up
            // with the page margin; the capsule's edge takes that 12dp back.
            modifier = Modifier.padding(end = 12.dp).heightIn(min = 44.dp).testTag(CoverCropConfirmTag),
        ) {
            Text(if (saving) "正在保存…" else "设为封面", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Decodes [uri] for the frame the way the backend reads it: the same size limits, the same EXIF
 * orientation (system `ExifInterface`, unknown means as stored), sampled down to [PREVIEW_EDGE].
 * Runs off the main thread.
 */
internal fun loadCropPreview(context: Context, uri: Uri): CropPreview {
    val unreadable = CropPreview.Failed("这张图片读不出来，请换一张")
    return try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode returns null by design; only a missing stream is a failure here.
        val opened = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); true }
        if (opened != true) return unreadable
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return unreadable
        if (!LocalArtworkImages.pickedSizeAccepted(width, height)) {
            return CropPreview.Failed("图片太大，请换一张不超过 3200 万像素的")
        }
        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
        val options = BitmapFactory.Options().apply {
            inSampleSize = LocalArtworkImages.sampleSize(width, height, PREVIEW_EDGE)
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return unreadable
        val turn = uprightMatrix(orientation)
        val upright = if (turn.isIdentity) decoded else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, turn, true)
                .also { if (it !== decoded) decoded.recycle() }
        }
        val swapped = orientation in ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270
        CropPreview.Ready(upright.asImageBitmap(), if (swapped) height else width, if (swapped) width else height)
    } catch (e: SecurityException) {
        android.util.Log.w("CoverCrop", "preview: no permission (${e.javaClass.simpleName})")
        CropPreview.Failed("没有读取这张图片的权限，请重新选择")
    } catch (e: OutOfMemoryError) {
        android.util.Log.w("CoverCrop", "preview: out of memory")
        unreadable
    } catch (e: Exception) {
        // Only the exception type: the message may carry the picture's path.
        android.util.Log.w("CoverCrop", "preview failed: ${e.javaClass.name}")
        unreadable
    }
}

/** The same turn the backend applies before it cuts (`LocalArtworkImageDecoder`). */
private fun uprightMatrix(orientation: Int) = Matrix().apply {
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
    }
}
