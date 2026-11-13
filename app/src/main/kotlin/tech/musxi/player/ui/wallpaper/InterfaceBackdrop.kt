package tech.musxi.player.ui.wallpaper

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import tech.musxi.core.wallpaper.WallpaperState
import tech.musxi.player.ui.components.PageBackdrop
import tech.musxi.player.ui.player.drawWallpaper
import tech.musxi.player.ui.player.interfaceWallpaperScrim
import tech.musxi.player.ui.player.lumaOf
import tech.musxi.player.ui.player.wallpaperSource
import tech.musxi.player.ui.player.wallpaperTone
import tech.musxi.player.ui.theme.LocalMusxi
import tech.musxi.player.ui.theme.Motion

/**
 * The 界面壁纸 as the pages draw it, or null without one. The picture is framed for the window,
 * the theme layer over it follows the interface's stricter cap and floor (dense grey text, read all
 * day), and a strength change eases in instead of jumping. The player never draws this: its page is
 * opaque and has a wallpaper of its own.
 */
@Composable
internal fun rememberInterfaceBackdrop(host: WallpaperHost, state: WallpaperState): PageBackdrop? {
    val look = rememberWallpaperLook(host, state) ?: return null
    val ratio = windowRatio()
    val dark = LocalMusxi.current.dark
    val base = MaterialTheme.colorScheme.background
    val tone = remember(look.image, look.probe, look.frame, ratio) {
        val source = wallpaperSource(look.image.width, look.image.height, ratio, 1f, look.frame)
        if (source.isEmpty) null else wallpaperTone(look.image, look.probe, source)
    } ?: return null
    val scrim = animateFloatAsState(
        interfaceWallpaperScrim(look.strength, dark, tone, lumaOf(base)),
        Motion.standard(), label = "interfaceWallpaperScrim",
    )
    // Stable while only the strength moves: the scrim is read at draw time, so a drag on the slider
    // redraws the pages without recomposing them.
    return remember(look.image, look.frame, base) {
        PageBackdrop { topLeft, window ->
            val source = wallpaperSource(look.image.width, look.image.height, window.width, window.height, look.frame)
            drawWallpaper(look, source, base, scrim.value, alpha = 1f, topLeft = topLeft, area = window)
        }
    }
}
