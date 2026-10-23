package tech.musxi.player.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * One system-font scale for Chinese, Latin and mixed text. Font selection and fallback stay with
 * Android; sp values retain the user's font scale. Natural spacing avoids adding tracking to
 * every Han character or spreading English titles across a narrow phone layout.
 */
val MusxiTypography = Typography(
    displayLarge = systemText(57, 64),
    displayMedium = systemText(45, 52),
    displaySmall = systemText(36, 44, FontWeight.SemiBold),
    headlineLarge = systemText(32, 40),
    headlineMedium = systemText(28, 36, FontWeight.SemiBold),
    headlineSmall = systemText(22, 32, FontWeight.SemiBold),
    titleLarge = systemText(22, 28, FontWeight.SemiBold),
    titleMedium = systemText(16, 24, FontWeight.Medium),
    titleSmall = systemText(14, 20, FontWeight.Medium),
    bodyLarge = systemText(16, 24),
    bodyMedium = systemText(14, 20),
    bodySmall = systemText(12, 16),
    labelLarge = systemText(14, 20, FontWeight.Medium),
    labelMedium = systemText(12, 16, FontWeight.Medium),
    labelSmall = systemText(11, 16, FontWeight.Medium),
)

private fun systemText(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = size.sp,
    lineHeight = height.sp,
    fontWeight = weight,
    letterSpacing = 0.sp,
)

/** Tabular figures keep times and counts from jittering while they update. */
val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")
