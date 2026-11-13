package tech.musxi.player.ui.components

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import tech.musxi.player.ui.theme.isDarkSurface

/**
 * Glass styles of docs/design/interaction-spec.md, section 3. REGULAR: dock, sheets, top bars.
 * THIN: floating menus and notices. CLEAR: controls over the player's cover colour, icons only.
 */
enum class GlassStyle(val blur: Dp, val lightTint: Float, val darkTint: Float, val elevation: Dp) {
    REGULAR(24.dp, 0.55f, 0.45f, 18.dp),
    THIN(20.dp, 0.68f, 0.55f, 12.dp),
    CLEAR(16.dp, 0.30f, 0.30f, 12.dp),
}

/**
 * What a glass surface needs from its host. [blurs] is false where the solid fallback is drawn.
 * [backdrop] is what the surface samples, set only for the refraction renderer: the host's one
 * recording of the page, or inside a [GlassLayer] that page with the layer drawn over it.
 * [depth] is 0 at the host and one more per [GlassLayer]; a surface samples recordings up to its
 * own depth, so the dock never blurs a copy of a layer rising above it.
 */
class GlassScope internal constructor(
    internal val haze: HazeState,
    val blurs: Boolean,
    internal val dark: Boolean,
    internal val solid: Color,
    internal val backdrop: Backdrop? = null,
    internal val depth: Int = 0,
) {
    internal val renderer: GlassRenderer = when {
        !blurs -> GlassRenderer.SOLID
        backdrop != null -> GlassRenderer.REFRACTION
        else -> GlassRenderer.FROSTED
    }
}

/** The nearest [GlassHost]; null outside one, where glass draws as solid. */
val LocalGlass = compositionLocalOf<GlassScope?> { null }

/**
 * Records [content] as the glass source and draws [overlay] above it. Only what [content] draws can
 * show through the glass, so floating layers belong in [overlay]. The page background is recorded
 * with the content: glass must blur an opaque layer.
 *
 * The renderer is chosen here, once, by [chooseGlassRenderer]: refraction on Android 13+, Haze blur
 * on Android 12, solid below that, in battery saver, and when [enabled] is false (the "透明效果"
 * setting, once settings exist). The page is recorded once, for that renderer only, and every glass
 * surface in the window samples that one recording.
 */
@Composable
fun GlassHost(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val saver = remember(context) { (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true }
    val renderer = chooseGlassRenderer(Build.VERSION.SDK_INT, saver, enabled)
    val haze = remember { HazeState() }
    // Only the refraction renderer needs the GraphicsLayer, so the others never allocate it.
    val backdrop = if (renderer == GlassRenderer.REFRACTION) rememberLayerBackdrop() else null
    val dark = cs.background.isDarkSurface()
    val solid = if (dark) cs.surfaceContainerHigh else Color.White
    val scope = remember(haze, backdrop, renderer, dark, solid) {
        GlassScope(haze, blurs = renderer != GlassRenderer.SOLID, dark = dark, solid = solid, backdrop = backdrop)
    }
    // `layerBackdrop` and `hazeSource` sit before `background`, so the page colour is in the recording.
    val source = when (renderer) {
        GlassRenderer.REFRACTION -> if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier
        GlassRenderer.FROSTED -> Modifier.hazeSource(haze)
        GlassRenderer.SOLID -> Modifier
    }
    CompositionLocalProvider(LocalGlass provides scope) {
        Box(modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().then(source).pageBackground(cs.background), content = content)
            overlay()
        }
    }
}

/**
 * A second recording inside a [GlassHost]'s overlay, for a layer that grows over the page (the
 * player). Glass in [overlay] samples the host's page with [content] drawn over it, so a sheet over
 * the full player shows the player, and one over a half-grown player shows both. Glass elsewhere in
 * the host (the dock) keeps sampling the page alone. [content] has no fill of its own: where it is
 * transparent the page shows through, in the picture and on screen. Glass inside [content] samples
 * the host; a layer cannot sample its own recording.
 *
 * Records only while [active]; otherwise, and where the host draws solid or there is no host,
 * [overlay] gets the host's scope and nothing is recorded. Switching [active] keeps [content]'s
 * state. Cost while active: one more recording of [content], and each glass surface in [overlay]
 * draws two recordings instead of one.
 */
@Composable
fun GlassLayer(
    active: Boolean,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val host = LocalGlass.current
    val renderer = host?.renderer ?: GlassRenderer.SOLID
    val depth = (host?.depth ?: 0) + 1
    // The refraction layer is kept while inactive, so a gesture that starts the layer does not
    // allocate in its first frame. Haze's own layer comes and goes with its source.
    val layer = if (renderer == GlassRenderer.REFRACTION) rememberLayerBackdrop() else null
    val hostBackdrop = host?.backdrop
    val combined = if (layer != null && hostBackdrop != null) rememberCombinedBackdrop(hostBackdrop, layer) else null
    val records = active && host != null && renderer != GlassRenderer.SOLID
    val inner = remember(host, combined, depth) {
        host?.let { GlassScope(it.haze, it.blurs, it.dark, it.solid, backdrop = combined, depth = depth) }
    }
    val source = when {
        !records -> Modifier
        layer != null -> Modifier.layerBackdrop(layer)
        renderer == GlassRenderer.FROSTED -> Modifier.hazeSource(host.haze, zIndex = depth.toFloat())
        else -> Modifier
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(source), content = content)
        CompositionLocalProvider(LocalGlass provides if (records) inner else host) { overlay() }
    }
}

/** Haze recordings a surface at [depth] may sample: the page (0) and layers up to its own depth. */
internal fun samplesArea(zIndex: Float, depth: Int): Boolean = zIndex <= depth

/**
 * A glass surface: soft shadow, clip, blur with tint, a top sheen, a 1dp highlight stroke. On
 * Android 12 the blur has grain; on Android 13+ the rim also bends the page like a lens. With no
 * [scope] (outside a host) or when the scope does not blur, it is the solid fallback. All three
 * renderers share the shape, shadow and stroke. Pass `LocalGlass.current` as [scope].
 *
 * Use it in the `overlay` of a [GlassHost] or [GlassLayer], never inside recorded content: there
 * the Android 12 blur would sample the recording it is part of, which Haze rejects with an
 * exception once `canDrawArea` is set (as it is here, for [GlassScope.depth]).
 */
@OptIn(ExperimentalHazeApi::class)
fun Modifier.glass(scope: GlassScope?, shape: Shape, style: GlassStyle = GlassStyle.REGULAR): Modifier {
    val dark = scope?.dark ?: false
    val base = shadow(
        style.elevation, shape,
        ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.20f),
    ).clip(shape)
    val backdrop = scope?.backdrop
    val surface = when {
        scope == null || scope.renderer == GlassRenderer.SOLID -> base.background(scope?.solid ?: Color.White)
        backdrop != null && scope.renderer == GlassRenderer.REFRACTION ->
            base.refractedSurface(backdrop, shape, style, dark)
        else -> base.hazeEffect(scope.haze) {
            blurRadius = style.blur
            tints = listOf(HazeTint(glassTint(style, dark)))
            noiseFactor = 0.08f
            // Without it every effect samples every source, layers included (experimental in Haze 1.6).
            val depth = scope.depth
            canDrawArea = { area -> samplesArea(area.zIndex, depth) }
        }.drawBehind { drawSheen(dark) }
    }
    return surface.border(
        1.dp,
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = if (dark) 0.50f else 0.85f),
                Color.White.copy(alpha = if (dark) 0.08f else 0.15f),
                Color.White.copy(alpha = if (dark) 0.28f else 0.45f),
            ),
        ),
        shape,
    )
}

/**
 * Glass for a bar along a window edge (the shell's top bar): the same material as [glass] without
 * the shadow, rim stroke or lens, which belong to a floating shape and would only draw a frame
 * around the screen. The bar draws its own hairline.
 */
@OptIn(ExperimentalHazeApi::class)
fun Modifier.glassBar(scope: GlassScope?, style: GlassStyle = GlassStyle.REGULAR): Modifier {
    val dark = scope?.dark ?: false
    val backdrop = scope?.backdrop
    return when {
        scope == null || scope.renderer == GlassRenderer.SOLID -> background(scope?.solid ?: Color.White)
        backdrop != null && scope.renderer == GlassRenderer.REFRACTION ->
            refractedSurface(backdrop, androidx.compose.ui.graphics.RectangleShape, style, dark)
        else -> hazeEffect(scope.haze) {
            blurRadius = style.blur
            tints = listOf(HazeTint(glassTint(style, dark)))
            noiseFactor = 0.08f
            val depth = scope.depth
            canDrawArea = { area -> samplesArea(area.zIndex, depth) }
        }
    }
}

/** Top sheen and bottom shade of the blurring renderers. Drawn behind the content, so text stays crisp. */
internal fun DrawScope.drawSheen(dark: Boolean) {
    drawRect(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (dark) 0.16f else 0.35f),
            0.35f to Color.Transparent,
            0.75f to Color.Transparent,
            1f to Color.Black.copy(alpha = if (dark) 0.14f else 0.06f),
        ),
    )
}
