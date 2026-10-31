package tech.musxi.player.ui.theme

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Picks dark or light status/navigation bar icons for the colour actually behind them.
 *
 * The app draws edge to edge, so the bars are transparent and the system has no idea what sits under
 * them; left alone it keeps light icons, which vanish on our light pages. Each layer that owns the
 * top of the screen calls this with its own backdrop; the last one composed wins.
 */
@Composable
fun SystemBarIcons(behind: Color) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val lightBackdrop = behind.luminance() > 0.5f
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightBackdrop
            isAppearanceLightNavigationBars = lightBackdrop
        }
    }
}
