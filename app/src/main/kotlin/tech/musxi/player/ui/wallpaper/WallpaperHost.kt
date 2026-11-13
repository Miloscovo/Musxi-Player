package tech.musxi.player.ui.wallpaper

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.musxi.core.wallpaper.WallpaperError
import tech.musxi.core.wallpaper.WallpaperFrame
import tech.musxi.player.wallpaper.AppWallpaperRepository
import java.io.File

/** The picture being framed, where the frame starts, and whether the preview bar is waiting under it. */
internal data class WallpaperFramingRequest(val id: String, val start: WallpaperFrame, val fromBar: Boolean)

/**
 * The two wallpapers the owner asked for (11-12): one behind the player and its lyrics, one behind
 * every other page. Each has its own picture, switch, strength and frame; neither stands in for the
 * other.
 */
internal enum class WallpaperKind(
    val title: String,
    /** Where it shows, in the words of the row's menu header. */
    val scope: String,
    val frameHint: String,
    val appliedNotice: String,
    /** The other kind's picture, offered in this one's menu. */
    val useOtherLabel: String,
) {
    INTERFACE(
        title = "界面壁纸",
        scope = "曲库、搜索和我的页面背后",
        frameHint = "框内就是曲库、搜索和我的页面背后显示的部分。拖动、双指缩放来取景，双击放大或还原",
        appliedNotice = "已设为界面壁纸",
        useOtherLabel = "用播放页那张",
    ),
    PLAYER(
        title = "播放页壁纸",
        scope = "只用在播放页和歌词页",
        frameHint = "框内就是播放页上显示的部分。拖动、双指缩放来取景，双击放大或还原",
        appliedNotice = "已设为播放页壁纸",
        useOtherLabel = "用界面那张",
    ),
}

/**
 * The player wallpaper's one flow, from any entry (the 外观 row, its long-press menu, the preview
 * bar): pick → frame → preview on the real player → apply or cancel.
 *
 * Like the cover frame, the frame sheet and the preview bar are drawn in the shell's overlay (a
 * sheet inside the glass recording samples itself and crashes the render thread), so entries talk
 * to this host and the shell draws [WallpaperFrameOverlay] and the bar.
 *
 * The order of backend calls follows the contract: a new picture is `choose → preview(id) →
 * setPreferences(effective.copy(frame))`; an applied one is `preview(saved id)` first. Nothing here
 * keeps a URI beyond the one choose call.
 */
@Stable
internal class WallpaperHost(
    val kind: WallpaperKind,
    val repo: AppWallpaperRepository,
    private val scope: CoroutineScope,
) {
    // Wired by [rememberWallpaperHost].
    internal var notice: (String) -> Unit = {}
    internal var launchPicker: () -> Unit = {}
    /** Shows the page the preview is judged on: the player, or the library home for the interface. */
    internal var openPreview: () -> Unit = {}
    /** Leaves that page again after apply or cancel. */
    internal var closePreview: () -> Unit = {}
    /** The other kind's host: its saved picture can be reused, and its decoded images shared. */
    internal var other: WallpaperHost? = null

    var framing by mutableStateOf<WallpaperFramingRequest?>(null)
        private set

    /** The preview bar is on the open player. */
    var bar by mutableStateOf(false)
        private set

    /** The slider's value while a finger is on it; saved to the draft only when it lets go. */
    var liveStrength by mutableStateOf<Int?>(null)

    /** A backend command is running; the entries wait instead of queueing a second one. */
    var working by mutableStateOf(false)
        private set

    internal val images = mutableStateMapOf<String, ImageBitmap>()
    /** Small copies for reading a picture's tones without scanning 4 million pixels. */
    internal val probes = HashMap<String, ImageBitmap>()
    private val loading = HashSet<String>()
    private var pickFromBar = false

    private val state get() = repo.state.value

    // --- entries ---

    /** A new picture from the 外观 row. */
    fun pickNew() {
        if (working) return
        pickFromBar = false
        launchPicker()
    }

    /** "换一张" in the frame: a cancelled picker leaves the picture that is there. */
    fun repick() {
        val current = framing ?: return
        if (working) return
        pickFromBar = current.fromBar
        launchPicker()
    }

    internal fun picked(uri: Uri) {
        val fromBar = pickFromBar
        command({ repo.choose(uri, it) }) {
            val candidate = state.candidate ?: return@command
            framing = WallpaperFramingRequest(candidate.id, WallpaperFrame(), fromBar)
        }
    }

    /** "用播放页那张" / "用界面那张": the other kind's saved picture becomes this one's candidate. */
    fun useOther() {
        val source = other ?: return
        if (working) return
        command({ repo.chooseSaved(source.repo, it) }) {
            val candidate = state.candidate ?: return@command
            framing = WallpaperFramingRequest(candidate.id, WallpaperFrame(), fromBar = false)
        }
    }

    /** "调整位置" from the row: the applied picture, from where it is now. */
    fun adjust() {
        val id = state.saved.asset?.id ?: return pickNew()
        if (working) return
        command({ repo.preview(id, it) }) {
            framing = WallpaperFramingRequest(id, state.effectivePreferences.frame, fromBar = false)
        }
    }

    /** "调整强度" from the row: straight to the preview bar. */
    fun adjustStrength() {
        val id = state.saved.asset?.id ?: return pickNew()
        if (working) return
        command({ repo.preview(id, it) }) { openBar() }
    }

    /** "调整位置" on the preview bar: the frame opens over it and closes back to it. */
    fun adjustFromBar() {
        val id = currentId() ?: return
        if (working) return
        framing = WallpaperFramingRequest(id, state.effectivePreferences.frame, fromBar = true)
    }

    fun confirmFrame(frame: WallpaperFrame) {
        val request = framing ?: return
        if (working) return
        val save = {
            command({ repo.setPreferences(state.effectivePreferences.copy(frame = frame), it) }) {
                framing = null
                openBar()
            }
        }
        if (state.previewing) save() else command({ repo.preview(request.id, it) }) { save() }
    }

    fun dismissFrame() {
        val request = framing ?: return
        if (working) return
        framing = null
        // From the 外观 row the frame is the whole flow so far: closing it drops the candidate or
        // the preview. Over the bar it is one step back to the bar.
        if (!request.fromBar) repo.cancel()
    }

    fun commitStrength() {
        val value = liveStrength ?: return
        repo.setPreferences(state.effectivePreferences.copy(strength = value)) { error ->
            liveStrength = null
            error?.let(::wallpaperErrorText)?.let(notice)
        }
    }

    fun apply() {
        val id = currentId() ?: return
        if (working) return
        command({ repo.apply(id, it) }) {
            bar = false
            liveStrength = null
            notice(kind.appliedNotice)
            closePreview()
        }
    }

    fun cancelBar() {
        if (!bar) return
        bar = false
        liveStrength = null
        repo.cancel()
        closePreview()
    }

    /**
     * The preview page was left some other way (the player pulled down): an unconfirmed preview
     * goes. The interface preview has no such exit; it stays across tabs until cancel or apply.
     */
    fun previewLeft() {
        if (!bar) return
        bar = false
        liveStrength = null
        repo.cancel()
    }

    fun setEnabled(on: Boolean) {
        if (state.saved.asset == null) {
            if (on) pickNew()
            return
        }
        if (working || state.previewing) return
        command({ repo.setPreferences(state.saved.preferences.copy(enabled = on), it) }) {}
    }

    fun restore() {
        if (working) return
        command({ repo.reset(it) }) { notice("已恢复默认背景") }
    }

    // --- pictures ---

    /**
     * Decodes [id]'s managed picture once. It is already upright and at most 2560 px a side, so it
     * is read whole; the page needs the pixels (framing, the readability floor), not a Coil painter.
     */
    internal fun load(id: String) {
        if (id in images) return
        // The same picture in both kinds is one file; decode it once and share the bitmap.
        other?.images?.get(id)?.let { shared ->
            probes[id] = other?.probes?.get(id) ?: shared
            images[id] = shared
            return
        }
        if (!loading.add(id)) return
        repo.imageFile(id) { result ->
            val file = result.getOrNull()
            if (file == null) {
                loading.remove(id)
                repo.imageFailed(id)
                return@imageFile
            }
            scope.launch {
                val decoded = withContext(Dispatchers.IO) { decodeWallpaper(file) }
                loading.remove(id)
                val image = decoded?.first
                if (decoded == null || image == null) {
                    repo.imageFailed(id)
                    return@launch
                }
                // Keep only what can still be shown: the applied picture, the candidate, the frame's.
                val keep = setOfNotNull(state.saved.asset?.id, state.candidate?.id, framing?.id, id)
                images.keys.filter { it !in keep }.forEach { images.remove(it); probes.remove(it) }
                probes[id] = decoded.second
                images[id] = image
            }
        }
    }

    private fun currentId(): String? = state.candidate?.id ?: state.saved.asset?.id

    private fun openBar() {
        bar = true
        openPreview()
    }

    private fun command(start: ((WallpaperError?) -> Unit) -> Job, done: () -> Unit) {
        working = true
        start { error ->
            working = false
            if (error == null) done() else wallpaperErrorText(error)?.let(notice)
        }
    }
}

/** The managed picture whole (already upright, ≤2560 px a side) and a ≤160 px copy for its tones. */
private fun decodeWallpaper(file: File): Pair<ImageBitmap, ImageBitmap>? = try {
    BitmapFactory.decodeFile(file.path)?.let { full ->
        val scale = 160f / maxOf(full.width, full.height)
        val small = if (scale >= 1f) full else android.graphics.Bitmap.createScaledBitmap(
            full, (full.width * scale).toInt().coerceAtLeast(1), (full.height * scale).toInt().coerceAtLeast(1), true,
        )
        full.asImageBitmap() to small.asImageBitmap()
    }
} catch (_: OutOfMemoryError) {
    null
}

/** The shell's two hosts. */
internal class WallpaperHosts(val interfaceHost: WallpaperHost, val player: WallpaperHost) {
    operator fun get(kind: WallpaperKind) = when (kind) {
        WallpaperKind.INTERFACE -> interfaceHost
        WallpaperKind.PLAYER -> player
    }
}

/** The shell's [WallpaperHosts]; null where no shell provides them (design sheets, tests). */
internal val LocalWallpaperHosts = staticCompositionLocalOf<WallpaperHosts?> { null }

@Composable
internal fun rememberWallpaperHost(
    kind: WallpaperKind,
    repo: AppWallpaperRepository,
    onNotice: (String) -> Unit,
    onOpenPreview: () -> Unit,
    onClosePreview: () -> Unit,
): WallpaperHost {
    val scope = rememberCoroutineScope()
    val host = remember(kind, repo) { WallpaperHost(kind, repo, scope) }
    // The system photo picker: no broad media permission, one picture, and a cancel changes nothing.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) host.picked(uri)
    }
    host.notice = onNotice
    host.openPreview = onOpenPreview
    host.closePreview = onClosePreview
    host.launchPicker = {
        try {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (_: android.content.ActivityNotFoundException) {
            host.notice("这台手机上没有可用的图片选择器")
        }
    }
    return host
}

/** [id]'s picture once decoded; null while it loads or with no id. */
@Composable
internal fun rememberWallpaperImage(host: WallpaperHost, id: String?): ImageBitmap? {
    LaunchedEffect(id) { if (id != null) host.load(id) }
    return id?.let { host.images[it] }
}

/**
 * What the player page draws right now: the effective picture (the candidate while previewing),
 * the effective frame, and the slider's live value while a finger is on it. Null without one.
 */
@Composable
internal fun rememberWallpaperLook(host: WallpaperHost, state: tech.musxi.core.wallpaper.WallpaperState): tech.musxi.player.ui.player.WallpaperLook? {
    val id = state.effectiveAsset?.id
    val image = rememberWallpaperImage(host, id) ?: return null
    val prefs = state.effectivePreferences
    val strength = (if (state.previewing) host.liveStrength else null) ?: prefs.strength
    return remember(image, prefs.frame, strength) {
        tech.musxi.player.ui.player.WallpaperLook(image, prefs.frame, strength, host.probes[id] ?: image)
    }
}

/**
 * The owner's choice for a dark photo in light mode (11-12): say it looks better in dark mode,
 * do not switch the theme. Read on the part of the picture the screen shows.
 */
@Composable
internal fun rememberPrefersDark(
    look: tech.musxi.player.ui.player.WallpaperLook?,
    ratio: Float,
    kind: WallpaperKind = WallpaperKind.PLAYER,
): Boolean =
    remember(look?.probe, look?.frame, ratio, kind) {
        if (look == null) return@remember false
        val source = tech.musxi.player.ui.player.wallpaperSource(look.image.width, look.image.height, ratio, 1f, look.frame)
        if (source.isEmpty) return@remember false
        val tone = tech.musxi.player.ui.player.wallpaperTone(look.image, look.probe, source)
        val lightBase = tech.musxi.player.ui.player.lumaOf(androidx.compose.ui.graphics.Color(0xFFFAFAFA))
        when (kind) {
            WallpaperKind.PLAYER -> tech.musxi.player.ui.player.wallpaperPrefersDark(tone, lightBase)
            WallpaperKind.INTERFACE -> tech.musxi.player.ui.player.interfaceWallpaperPrefersDark(tone, lightBase)
        }
    }

/** One sentence per backend refusal; null when a newer action reports for itself. */
internal fun wallpaperErrorText(error: WallpaperError): String? = when (error) {
    WallpaperError.UNSUPPORTED_FORMAT -> "不支持这种图片格式，请换一张 JPEG、PNG 或 WebP"
    WallpaperError.ANIMATED_IMAGE -> "不支持动图，请换一张静态图片"
    WallpaperError.INVALID_IMAGE -> "这张图片读不出来，请换一张"
    WallpaperError.IMAGE_TOO_LARGE -> "图片太大，请换一张"
    WallpaperError.READ_FAILED -> "没能读取这张图片，请重新选择"
    WallpaperError.STORAGE_FAILED -> "壁纸没能保存，请稍后重试"
    WallpaperError.SAVED_IMAGE_UNAVAILABLE -> "壁纸图片已不可用，请重新选择"
    WallpaperError.NO_IMAGE -> "还没有选择图片"
    WallpaperError.INVALID_ARGUMENT -> "取景位置无效，请重新调整"
    WallpaperError.STALE_CANDIDATE -> null
}
