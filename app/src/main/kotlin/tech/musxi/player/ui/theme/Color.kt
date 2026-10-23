package tech.musxi.player.ui.theme

import androidx.compose.ui.graphics.Color
import tech.musxi.core.account.Platform

/**
 * Musxi keeps large surfaces neutral and spends colour on a few signals only:
 * the main play button, played progress, the current track, the selected tab,
 * the active lyric line and one primary action per page. Cover colours belong
 * to the now-playing page; platform colours are identity dots, never fills.
 */
enum class Accent(val label: String) { MIST("雾蓝"), TEAL("松石青"), AMBER("暖琥珀") }

enum class ThemeMode { LIGHT, DARK, BLACK }

/** A confirmed like keeps its own colour, independent of the artwork and platform. */
internal fun likedHeartColor(dark: Boolean): Color =
    if (dark) Color(0xFFFF7B8C) else Color(0xFFD83B52)

/** Accent roles for one brightness. Containers are soft enough to sit on neutral surfaces. */
data class AccentTones(
    val primary: Color, val onPrimary: Color,
    val container: Color, val onContainer: Color,
)

internal fun Accent.tones(dark: Boolean): AccentTones = when (this) {
    Accent.MIST -> if (!dark) AccentTones(Color(0xFF4A6A9A), Color.White, Color(0xFFDDE5F2), Color(0xFF1C3150))
        else AccentTones(Color(0xFFA9BEE0), Color(0xFF16294A), Color(0xFF33476A), Color(0xFFDDE5F2))
    Accent.TEAL -> if (!dark) AccentTones(Color(0xFF2B7A71), Color.White, Color(0xFFCFE8E3), Color(0xFF0C3A35))
        else AccentTones(Color(0xFF8DCFC5), Color(0xFF00352F), Color(0xFF1E5952), Color(0xFFCFE8E3))
    Accent.AMBER -> if (!dark) AccentTones(Color(0xFF9C6229), Color.White, Color(0xFFF4E2CF), Color(0xFF452708))
        else AccentTones(Color(0xFFE6B988), Color(0xFF452708), Color(0xFF684420), Color(0xFFF4E2CF))
}

/** Near-neutral greys with a barely visible warm cast (about 2% saturation). */
internal object Neutrals {
    val lightBackground = Color(0xFFF7F7F5)
    val lightLowest = Color(0xFFFFFFFF)
    val lightLow = Color(0xFFF1F1EE)
    val light = Color(0xFFEBEBE7)
    val lightHigh = Color(0xFFE4E4E0)
    val lightHighest = Color(0xFFDDDDD8)
    val lightOn = Color(0xFF1B1C1A)
    val lightOnVariant = Color(0xFF5E5F5A)
    val lightOutline = Color(0xFF8E8F89)
    val lightOutlineVariant = Color(0xFFD5D5CF)

    val darkBackground = Color(0xFF121212)
    val darkLowest = Color(0xFF0C0C0C)
    val darkLow = Color(0xFF1A1A1A)
    val dark = Color(0xFF1F1F1F)
    val darkHigh = Color(0xFF262626)
    val darkHighest = Color(0xFF2E2E2E)
    val darkOn = Color(0xFFE6E6E2)
    val darkOnVariant = Color(0xFFA6A6A1)
    val darkOutline = Color(0xFF75756F)
    val darkOutlineVariant = Color(0xFF3A3A37)

    // Pure-black mode only swaps the base; containers keep the normal dark ladder so cards stay
    // readable and pure black is visibly distinct from the dark grey theme.
    val blackBackground = Color(0xFF000000)
}

/** Desaturated platform identity colours, used only for small dots and labels. */
fun Platform.identityColor(dark: Boolean): Color = when (this) {
    Platform.NETEASE -> if (dark) Color(0xFFE38C86) else Color(0xFFC4504A)
    Platform.QQ -> if (dark) Color(0xFF7FC9A0) else Color(0xFF34925F)
    Platform.KUGOU -> if (dark) Color(0xFF8CB1E6) else Color(0xFF3F73BA)
}

val Platform.displayName: String get() = when (this) {
    Platform.NETEASE -> "网易云"
    Platform.QQ -> "QQ音乐"
    Platform.KUGOU -> "酷狗"
}
