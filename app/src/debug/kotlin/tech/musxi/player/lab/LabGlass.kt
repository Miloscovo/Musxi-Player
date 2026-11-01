package tech.musxi.player.lab

import android.os.Build
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/**
 * Glass styles of the interaction spec, section 3. `REGULAR` for the dock, sheets and top bars,
 * `THIN` for floating menus and notices, `CLEAR` for controls over the player's cover colour.
 */
internal enum class LabGlassStyle(val blur: Dp, val lightTint: Float, val darkTint: Float, val elevation: Dp) {
    REGULAR(24.dp, 0.55f, 0.45f, 18.dp),
    THIN(20.dp, 0.68f, 0.55f, 12.dp),
    CLEAR(16.dp, 0.30f, 0.30f, 12.dp),
}

private class LabGlassScope(val haze: HazeState, val enabled: Boolean, val dark: Boolean)

private val LocalLabGlass = compositionLocalOf<LabGlassScope?> { null }

/**
 * Hosts glass for one experiment. Page content goes in [content] and is recorded as the blur
 * source; floating layers go in [overlay] and may use [labGlass]. A layer can only blur what was
 * recorded, so anything that should show through the glass must be inside [content].
 *
 * Blur needs Android 12 (API 31); below that, and when the lab's material switch is set to solid,
 * every glass surface falls back to the solid style of the spec.
 */
@Composable
internal fun LabGlassHost(
    env: LabEnv,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val haze = remember { HazeState() }
    val scope = LabGlassScope(haze, env.glass && Build.VERSION.SDK_INT >= 31, env.dark)
    CompositionLocalProvider(LocalLabGlass provides scope) {
        Box(modifier.fillMaxSize()) {
            // The page background is recorded with the content: glass must blur an opaque layer, or
            // the sharp page underneath shows through the tint.
            Box(
                Modifier.fillMaxSize().hazeSource(haze).background(MaterialTheme.colorScheme.background),
                content = content,
            )
            overlay()
        }
    }
}

/**
 * A glass surface: shadow, clip, blur with tint and grain, a top sheen, and a 1dp highlight stroke.
 * Outside a [LabGlassHost], or with glass off, it is the solid fallback with the same shape,
 * shadow and stroke.
 */
internal fun Modifier.labGlass(shape: Shape, style: LabGlassStyle = LabGlassStyle.REGULAR): Modifier = composed {
    val scope = LocalLabGlass.current
    val dark = scope?.dark ?: false
    val cs = MaterialTheme.colorScheme
    val base = this.shadow(
        style.elevation, shape,
        ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.20f),
    ).clip(shape)
    val surface = if (scope != null && scope.enabled) {
        val tint = (if (dark) Color.Black else Color.White).copy(alpha = if (dark) style.darkTint else style.lightTint)
        base.hazeEffect(scope.haze) {
            blurRadius = style.blur
            tints = listOf(HazeTint(tint))
            noiseFactor = 0.08f
        }.drawBehind {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (dark) 0.16f else 0.35f),
                    0.35f to Color.Transparent,
                    0.75f to Color.Transparent,
                    1f to Color.Black.copy(alpha = if (dark) 0.14f else 0.06f),
                ),
            )
        }
    } else {
        base.background(if (dark) cs.surfaceContainerHigh else Color.White)
    }
    surface.border(
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
