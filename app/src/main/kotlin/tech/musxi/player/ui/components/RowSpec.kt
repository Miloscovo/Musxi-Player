package tech.musxi.player.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Row and group metrics for every card and every long list.
 *
 * A row is never given a height. Its height is its content plus [vertical] above and below, with
 * [minHeight] as the floor, so it grows with the font scale instead of clipping. A 48dp cover gives a
 * 64dp row, a 40dp cover 56dp, a 56dp cover 72dp. These numbers belong next to `Space` in Theme.kt;
 * they live in this file until that one is open for edits.
 */
object RowSpec {
    /** Least height of any row. Above the 44dp touch minimum, and the height of a 40dp-lead row. */
    val minHeight = 56.dp

    /** Space above and below the content of a row. */
    val vertical = 8.dp

    /** Lead sizes: list cover, compact cover (queue), feature cover (home modules), avatar. */
    val cover = 48.dp
    val coverCompact = 40.dp
    val coverFeature = 56.dp
    val avatar = 40.dp

    /** Cover of a tile in a horizontal strip. */
    val coverTile = 96.dp

    /** Round button that sits inside a row, such as the resume button, its glyph and its progress ring. */
    val action = 40.dp
    val actionGlyph = 20.dp
    val actionStroke = 2.dp

    /** Space between a tile's cover and its title. */
    val tileGap = 6.dp

    /** Gap between a lead and its text, between the text and what trails it, and before a chevron. */
    val leadGap = 12.dp
    val trailGap = 12.dp
    val chevronGap = 8.dp

    /** Distance from a group heading or footer to the card or list it belongs to. */
    val headingGap = 8.dp

    /** Divider thickness: one physical pixel on a 2x screen. */
    val hairline = 0.5.dp

    /** Where the text of a row starts, measured from the row's start edge, when the lead is [lead] wide. */
    fun textStart(lead: Dp): Dp = lead + leadGap
}
