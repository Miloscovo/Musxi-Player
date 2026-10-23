package tech.musxi.player.ui.components

import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Optical centring for a CJK label inside a small shape. A plain [Text] looks slightly low there.
 *
 * Measured on a 12sp CJK label inside a 32dp band at xhdpi: the glyph ink left a 23px gap above
 * and a 19px gap below, so the ink centre sat 1dp under the band's centre. The cause is the font's
 * own metrics — Latin letters need descender room, Han characters have none, so the ink occupies
 * the lower part of the ascent/descent box.
 *
 * Two "proper" remedies were measured and both left the 2px offset exactly unchanged:
 * `PlatformTextStyle(includeFontPadding = false)` and `LineHeightStyle(Trim.Both)`. An explicit
 * 1dp lift is therefore the honest fix, and it is kept in one place so every pill and chip gets
 * the same correction.
 *
 * Use this for short labels inside chips, pills and other capsules. Ordinary paragraphs should
 * keep plain [Text] — the offset would only matter where a shape makes centring visible.
 */
@Composable
fun OpticallyCentredText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    Text(text = text, style = style, color = color, modifier = modifier.offset(y = CJK_INK_LIFT))
}

/** Measured correction for CJK ink sitting low inside a centred line box. */
private val CJK_INK_LIFT = (-1).dp
