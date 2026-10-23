package tech.musxi.player.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/** Spacing rhythm: 20dp page margin and section gap, 12dp between sibling cards. */
object Space {
    val page = 20.dp
    val section = 20.dp
    val sibling = 12.dp
    val inner = 16.dp
    /** Height reserved above content for mini player + navigation bar. */
    val bottomChrome = 152.dp
}

val MusxiShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Immutable
data class MusxiExtras(val dark: Boolean, val mode: ThemeMode, val accent: Accent)

val LocalMusxi = staticCompositionLocalOf { MusxiExtras(false, ThemeMode.LIGHT, Accent.MIST) }
/**
 * The accent in force right now.
 *
 * When something is playing, this is derived from that track's cover, so the tab bar, the mini player
 * and the player page all agree on one colour and the whole app changes temperature with the song.
 * When nothing is playing it is null and callers fall back to the theme accent — which is why the
 * three accent choices still matter in an empty app.
 */
val LocalNowPlayingAccent = staticCompositionLocalOf<Color?> { null }

@Composable
fun nowPlayingAccent(): Color = LocalNowPlayingAccent.current ?: MaterialTheme.colorScheme.primary

fun musxiColorScheme(accent: Accent, mode: ThemeMode): ColorScheme {
    val dark = mode != ThemeMode.LIGHT
    val a = accent.tones(dark)
    val n = Neutrals
    return if (!dark) lightColorScheme(
        primary = a.primary, onPrimary = a.onPrimary,
        primaryContainer = a.container, onPrimaryContainer = a.onContainer,
        secondary = n.lightOnVariant, onSecondary = Color.White,
        secondaryContainer = n.lightHigh, onSecondaryContainer = n.lightOn,
        tertiary = a.primary, onTertiary = a.onPrimary,
        tertiaryContainer = a.container, onTertiaryContainer = a.onContainer,
        background = n.lightBackground, onBackground = n.lightOn,
        surface = n.lightBackground, onSurface = n.lightOn,
        surfaceVariant = n.lightHigh, onSurfaceVariant = n.lightOnVariant,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = n.lightLowest, surfaceContainerLow = n.lightLow,
        surfaceContainer = n.light, surfaceContainerHigh = n.lightHigh,
        surfaceContainerHighest = n.lightHighest,
        surfaceBright = n.lightLowest, surfaceDim = n.lightHighest,
        outline = n.lightOutline, outlineVariant = n.lightOutlineVariant,
        inverseSurface = Color(0xFF2F302D), inverseOnSurface = Color(0xFFF1F1EE),
        inversePrimary = accent.tones(true).primary,
    ) else {
        // Pure black only swaps the base surface. Container steps stay on the normal dark ladder
        // so cards keep their separation and the difference from DARK is visible.
        val black = mode == ThemeMode.BLACK
        darkColorScheme(
            primary = a.primary, onPrimary = a.onPrimary,
            primaryContainer = a.container, onPrimaryContainer = a.onContainer,
            secondary = n.darkOnVariant, onSecondary = Color.Black,
            secondaryContainer = n.darkHigh, onSecondaryContainer = n.darkOn,
            tertiary = a.primary, onTertiary = a.onPrimary,
            tertiaryContainer = a.container, onTertiaryContainer = a.onContainer,
            background = if (black) n.blackBackground else n.darkBackground, onBackground = n.darkOn,
            surface = if (black) n.blackBackground else n.darkBackground, onSurface = n.darkOn,
            surfaceVariant = n.darkHigh, onSurfaceVariant = n.darkOnVariant,
            surfaceTint = Color.Transparent,
            surfaceContainerLowest = if (black) n.blackBackground else n.darkLowest,
            surfaceContainerLow = n.darkLow,
            surfaceContainer = n.dark,
            surfaceContainerHigh = n.darkHigh,
            surfaceContainerHighest = n.darkHighest,
            surfaceBright = n.darkHighest, surfaceDim = if (black) n.blackBackground else n.darkBackground,
            outline = n.darkOutline, outlineVariant = n.darkOutlineVariant,
            inverseSurface = Color(0xFFE6E6E2), inverseOnSurface = Color(0xFF2F302D),
            inversePrimary = accent.tones(false).primary,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MusxiTheme(accent: Accent = Accent.MIST, mode: ThemeMode = ThemeMode.LIGHT, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMusxi provides MusxiExtras(mode != ThemeMode.LIGHT, mode, accent)) {
        MaterialExpressiveTheme(
            colorScheme = musxiColorScheme(accent, mode),
            typography = MusxiTypography,
            shapes = MusxiShapes,
            content = content,
        )
    }
}

/** True when the surface is dark; used to pick the desaturated platform dot tone. */
fun Color.isDarkSurface(): Boolean =
    (0.2126f * red + 0.7152f * green + 0.0722f * blue) < 0.5f

/**
 * Colour for the bottom bar, one step away from the page background so the chrome reads as its own
 * layer without a shadow.
 *
 * The direction flips with brightness. Going *darker* in dark mode was invisible: the bar landed on
 * `#131313` against a `#121212` page — measured contrast 1.01:1, i.e. no edge at all. On dark
 * surfaces the bar has to go lighter.
 */
@Composable
fun ColorScheme.chromeBarColor(): Color =
    if (background.isDarkSurface()) lerp(surfaceContainer, surfaceContainerHigh, 0.35f)
    else lerp(surfaceContainer, onSurface, 0.06f)
